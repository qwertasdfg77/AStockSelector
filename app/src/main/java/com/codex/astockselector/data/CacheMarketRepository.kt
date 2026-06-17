package com.codex.astockselector.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.codex.astockselector.model.CustomFilterConfig
import com.codex.astockselector.model.DailyBar
import com.codex.astockselector.model.MarketSegment
import com.codex.astockselector.model.StockProfile
import com.codex.astockselector.model.StrategyConfig
import com.codex.astockselector.model.StrategySignal
import com.codex.astockselector.strategy.CustomFilterEngine
import com.codex.astockselector.strategy.StrategyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil

data class MarketCacheInfo(
    val exists: Boolean = false,
    val path: String = "",
    val sizeMb: Double = 0.0,
    val generatedAt: String = "",
    val stockCount: Int = 0,
    val dailyBarCount: Int = 0,
    val dateStart: String = "",
    val dateEnd: String = "",
    val klineSource: String = "",
)

data class MarketCacheFreshness(
    val info: MarketCacheInfo = MarketCacheInfo(),
    val expectedDate: String = "",
    val isLatest: Boolean = false,
)

private data class CandidateBars(
    val stock: StockProfile,
    val bars: List<DailyBar>,
)

object CacheMarketRepository {
    private const val DB_NAME = "market_cache.db"
    private const val CACHE_KEEP_TRADE_DAYS = 320
    private const val SIGNAL_LOOKBACK_TRADE_DAYS = 270
    private const val INCREMENTAL_UPDATE_DAYS = 40
    private const val EVALUATION_BATCH_SIZE = 64
    private val dateFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val chinaZone = ZoneId.of("Asia/Shanghai")

    suspend fun loadSmartSignals(
        context: Context,
        config: StrategyConfig,
        onProgress: suspend (String) -> Unit = {},
    ): List<StrategySignal> = withContext(Dispatchers.IO) {
        val expectedDate = expectedLatestClosedTradeDate()
        var info = cacheInfo(context)

        if (info.exists && info.dateEnd >= expectedDate) {
            emit(onProgress, "缓存已是收盘最新数据（${info.dateEnd}），直接筛选。")
            return@withContext loadSignals(context, config, onProgress)
        }

        val currentDate = if (info.exists) info.dateEnd.ifBlank { "无" } else "无缓存"
        emit(onProgress, "缓存最新日期 $currentDate，目标收盘日期 $expectedDate，开始联网更新并合并缓存...")
        updateCache(context, config, info, expectedDate, onProgress)
        info = cacheInfo(context)
        if (!info.exists || info.dateEnd < expectedDate) {
            val actualDate = info.dateEnd.ifBlank { "无" }
            error("缓存更新后仍未达到目标收盘日期：目标 $expectedDate，当前 $actualDate。请稍后重试或检查数据源。")
        }
        emit(onProgress, "缓存已更新到 ${info.dateEnd}，开始筛选...")
        loadSignals(context, config, onProgress)
    }

    suspend fun cacheInfo(context: Context): MarketCacheInfo = withContext(Dispatchers.IO) {
        val file = cacheFile(context) ?: return@withContext MarketCacheInfo()
        runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val metadata = loadMetadata(db)
                val dateRange = db.rawQuery("SELECT MIN(trade_date), MAX(trade_date) FROM daily_bars", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getStringOrEmpty(0) to cursor.getStringOrEmpty(1)
                    } else {
                        "" to ""
                    }
                }
                MarketCacheInfo(
                    exists = true,
                    path = file.absolutePath,
                    sizeMb = file.length().toDouble() / 1024.0 / 1024.0,
                    generatedAt = metadata["generated_at"].orEmpty(),
                    stockCount = metadata["stock_count"]?.toIntOrNull()
                        ?: db.count("stocks"),
                    dailyBarCount = metadata["daily_bar_count"]?.toIntOrNull()
                        ?: db.count("daily_bars"),
                    dateStart = dateRange.first,
                    dateEnd = dateRange.second,
                    klineSource = metadata["kline_source"].orEmpty(),
                )
            }
        }.getOrElse {
            MarketCacheInfo(
                exists = false,
                path = file.absolutePath,
            )
        }
    }

    suspend fun cacheFreshness(context: Context): MarketCacheFreshness = withContext(Dispatchers.IO) {
        val info = cacheInfo(context)
        val expectedDate = expectedLatestClosedTradeDate()
        MarketCacheFreshness(
            info = info,
            expectedDate = expectedDate,
            isLatest = info.exists && info.dateEnd >= expectedDate,
        )
    }

    suspend fun loadSignals(
        context: Context,
        config: StrategyConfig,
        onProgress: suspend (String) -> Unit = {},
    ): List<StrategySignal> = withContext(Dispatchers.IO) {
        val file = cacheFile(context) ?: error("没有找到本地缓存 market_cache.db")
        val startedAt = System.currentTimeMillis()
        emit(onProgress, "正在打开本地缓存...")

        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val stocks = loadStocks(db)
            val cutoffTradeDate = recentTradeDateCutoff(db, SIGNAL_LOOKBACK_TRADE_DAYS)
            val total = db.countDistinctSince("daily_bars", "ts_code", cutoffTradeDate).coerceAtLeast(stocks.size)
            emit(onProgress, "本地缓存已打开：${stocks.size} 只股票，开始筛选...")

            val signals = mutableListOf<StrategySignal>()
            val bars = mutableListOf<DailyBar>()
            val pendingEvaluations = mutableListOf<CandidateBars>()
            var currentTsCode = ""
            var completed = 0

            suspend fun flushPendingEvaluations() {
                if (pendingEvaluations.isEmpty()) return
                signals += evaluateCandidateBatch(pendingEvaluations.toList(), config)
                pendingEvaluations.clear()
            }

            suspend fun evaluateCurrent() {
                if (currentTsCode.isBlank() || bars.isEmpty()) return
                val stock = stocks[currentTsCode]
                if (stock != null && isCoarseCandidate(stock, bars, config)) {
                    pendingEvaluations += CandidateBars(stock, bars.toList())
                    if (pendingEvaluations.size >= EVALUATION_BATCH_SIZE) {
                        flushPendingEvaluations()
                    }
                }
                completed += 1
            }

            db.rawQuery(
                """
                SELECT ts_code, trade_date, open, high, low, close, pre_close, pct_chg, volume, amount
                FROM daily_bars
                WHERE trade_date >= ?
                ORDER BY ts_code, trade_date
                """.trimIndent(),
                arrayOf(cutoffTradeDate),
            ).use { cursor ->
                val tsIndex = cursor.getColumnIndexOrThrow("ts_code")
                val dateIndex = cursor.getColumnIndexOrThrow("trade_date")
                val openIndex = cursor.getColumnIndexOrThrow("open")
                val highIndex = cursor.getColumnIndexOrThrow("high")
                val lowIndex = cursor.getColumnIndexOrThrow("low")
                val closeIndex = cursor.getColumnIndexOrThrow("close")
                val preCloseIndex = cursor.getColumnIndexOrThrow("pre_close")
                val pctIndex = cursor.getColumnIndexOrThrow("pct_chg")
                val volumeIndex = cursor.getColumnIndexOrThrow("volume")
                val amountIndex = cursor.getColumnIndexOrThrow("amount")

                while (cursor.moveToNext()) {
                    val tsCode = cursor.getString(tsIndex)
                    if (currentTsCode.isBlank()) {
                        currentTsCode = tsCode
                    } else if (tsCode != currentTsCode) {
                        evaluateCurrent()
                        if (completed % 50 == 0) {
                            flushPendingEvaluations()
                            emit(
                                onProgress,
                                "缓存筛选 $completed/$total，已命中 ${signals.size} 条，剩余约 ${
                                    estimateRemaining(completed, total, startedAt)
                                }。",
                            )
                        }
                        bars.clear()
                        currentTsCode = tsCode
                    }

                    bars += DailyBar(
                        tsCode = tsCode,
                        tradeDate = cursor.getString(dateIndex),
                        open = cursor.getDouble(openIndex),
                        high = cursor.getDouble(highIndex),
                        low = cursor.getDouble(lowIndex),
                        close = cursor.getDouble(closeIndex),
                        preClose = cursor.getDouble(preCloseIndex),
                        pctChg = cursor.getDouble(pctIndex),
                        volume = cursor.getDouble(volumeIndex),
                        amount = cursor.getDouble(amountIndex),
                    )
                }
            }
            evaluateCurrent()
            flushPendingEvaluations()

            val sorted = signals.sortedWith(
                compareByDescending<StrategySignal> { it.score }.thenBy { it.stock.tsCode },
            )
            emit(onProgress, "本地缓存筛选完成：命中 ${sorted.size} 条信号。")
            sorted
        }
    }

    suspend fun loadCustomSignals(
        context: Context,
        config: CustomFilterConfig,
        onProgress: suspend (String) -> Unit = {},
    ): List<StrategySignal> = withContext(Dispatchers.IO) {
        val file = cacheFile(context) ?: error("没有找到本地缓存 market_cache.db")
        val startedAt = System.currentTimeMillis()
        emit(onProgress, "正在打开本地缓存...")

        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val stocks = loadStocks(db)
            val cutoffTradeDate = recentTradeDateCutoff(db, SIGNAL_LOOKBACK_TRADE_DAYS)
            val total = db.countDistinctSince("daily_bars", "ts_code", cutoffTradeDate).coerceAtLeast(stocks.size)
            emit(onProgress, "本地缓存已打开：${stocks.size} 只股票，开始自定义筛选...")

            val signals = mutableListOf<StrategySignal>()
            val bars = mutableListOf<DailyBar>()
            val pendingEvaluations = mutableListOf<CandidateBars>()
            var currentTsCode = ""
            var completed = 0

            suspend fun flushPendingEvaluations() {
                if (pendingEvaluations.isEmpty()) return
                signals += evaluateCustomCandidateBatch(pendingEvaluations.toList(), config)
                pendingEvaluations.clear()
            }

            suspend fun evaluateCurrent() {
                if (currentTsCode.isBlank() || bars.isEmpty()) return
                val stock = stocks[currentTsCode]
                if (stock != null) {
                    pendingEvaluations += CandidateBars(stock, bars.toList())
                    if (pendingEvaluations.size >= EVALUATION_BATCH_SIZE) {
                        flushPendingEvaluations()
                    }
                }
                completed += 1
            }

            db.rawQuery(
                """
                SELECT ts_code, trade_date, open, high, low, close, pre_close, pct_chg, volume, amount
                FROM daily_bars
                WHERE trade_date >= ?
                ORDER BY ts_code, trade_date
                """.trimIndent(),
                arrayOf(cutoffTradeDate),
            ).use { cursor ->
                val tsIndex = cursor.getColumnIndexOrThrow("ts_code")
                val dateIndex = cursor.getColumnIndexOrThrow("trade_date")
                val openIndex = cursor.getColumnIndexOrThrow("open")
                val highIndex = cursor.getColumnIndexOrThrow("high")
                val lowIndex = cursor.getColumnIndexOrThrow("low")
                val closeIndex = cursor.getColumnIndexOrThrow("close")
                val preCloseIndex = cursor.getColumnIndexOrThrow("pre_close")
                val pctIndex = cursor.getColumnIndexOrThrow("pct_chg")
                val volumeIndex = cursor.getColumnIndexOrThrow("volume")
                val amountIndex = cursor.getColumnIndexOrThrow("amount")

                while (cursor.moveToNext()) {
                    val tsCode = cursor.getString(tsIndex)
                    if (currentTsCode.isBlank()) {
                        currentTsCode = tsCode
                    } else if (tsCode != currentTsCode) {
                        evaluateCurrent()
                        if (completed % 50 == 0) {
                            flushPendingEvaluations()
                            emit(
                                onProgress,
                                "自定义筛选 $completed/$total，已命中 ${signals.size} 条，剩余约 ${
                                    estimateRemaining(completed, total, startedAt)
                                }。",
                            )
                        }
                        bars.clear()
                        currentTsCode = tsCode
                    }

                    bars += DailyBar(
                        tsCode = tsCode,
                        tradeDate = cursor.getString(dateIndex),
                        open = cursor.getDouble(openIndex),
                        high = cursor.getDouble(highIndex),
                        low = cursor.getDouble(lowIndex),
                        close = cursor.getDouble(closeIndex),
                        preClose = cursor.getDouble(preCloseIndex),
                        pctChg = cursor.getDouble(pctIndex),
                        volume = cursor.getDouble(volumeIndex),
                        amount = cursor.getDouble(amountIndex),
                    )
                }
            }
            evaluateCurrent()
            flushPendingEvaluations()

            val sorted = signals.sortedWith(
                compareByDescending<StrategySignal> { it.score }.thenBy { it.stock.tsCode },
            )
            emit(onProgress, "自定义筛选完成：命中 ${sorted.size} 只股票。")
            sorted
        }
    }

    private suspend fun updateCache(
        context: Context,
        config: StrategyConfig,
        currentInfo: MarketCacheInfo,
        expectedDate: String,
        onProgress: suspend (String) -> Unit,
    ) {
        val file = writableCacheFile(context, onProgress)
        val updateDays = if (!currentInfo.exists || currentInfo.dateEnd.isBlank()) {
            CACHE_KEEP_TRADE_DAYS
        } else {
            INCREMENTAL_UPDATE_DAYS
        }
        val updates = DirectMarketRepository.loadCacheUpdates(
            config = config,
            days = updateDays,
            onProgress = onProgress,
        )
        if (updates.isEmpty()) {
            error("联网更新未读取到可写入缓存的数据")
        }

        emit(onProgress, "正在写入缓存数据库...")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            initSchema(db)
            val nowText = LocalDateTime.now(chinaZone).toString()

            db.beginTransaction()
            try {
                val stockSql = """
                    INSERT OR REPLACE INTO stocks (
                        symbol, code, ts_code, name, market, is_st, current_price, current_amount, source, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                val barSql = """
                    INSERT OR REPLACE INTO daily_bars (
                        ts_code, trade_date, open, high, low, close, pre_close, pct_chg, volume, amount, source
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()

                updates.forEachIndexed { index, item ->
                    db.execSQL(
                        stockSql,
                        arrayOf(
                            item.stock.symbol,
                            item.stock.code,
                            item.stock.tsCode,
                            item.stock.name,
                            item.stock.market.name,
                            if (item.stock.isSt) 1 else 0,
                            item.stock.currentPrice,
                            item.stock.currentAmount,
                            "sina",
                            nowText,
                        ),
                    )
                    item.bars.forEach { bar ->
                        db.execSQL(
                            barSql,
                            arrayOf(
                                bar.tsCode,
                                bar.tradeDate,
                                bar.open,
                                bar.high,
                                bar.low,
                                bar.close,
                                bar.preClose,
                                bar.pctChg,
                                bar.volume,
                                bar.amount,
                                item.source,
                            ),
                        )
                    }
                    if ((index + 1) % 200 == 0) {
                        emit(onProgress, "缓存写入 ${index + 1}/${updates.size} 只...")
                    }
                }

                cleanOldBars(db)
                updateMetadata(db, nowText, expectedDate)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun cacheFile(context: Context): File? {
        val candidates = listOfNotNull(
            File(context.filesDir, DB_NAME),
            context.getExternalFilesDir(null)?.let { File(it, DB_NAME) },
        ).filter { it.exists() && it.length() > 0L }
        return candidates.maxByOrNull { it.lastModified() }
    }

    private suspend fun writableCacheFile(
        context: Context,
        onProgress: suspend (String) -> Unit,
    ): File {
        val existing = cacheFile(context)
        if (existing != null && existing.canOpenForWrite()) return existing

        val internalFile = File(context.filesDir, DB_NAME)
        if (existing != null && existing.absolutePath != internalFile.absolutePath) {
            emit(onProgress, "检测到当前缓存文件不可写，正在迁移到应用内部缓存...")
            if (!context.filesDir.exists()) {
                context.filesDir.mkdirs()
            }
            existing.copyTo(internalFile, overwrite = true)
            internalFile.setWritable(true, true)
            if (!internalFile.canOpenForWrite()) {
                error("缓存已迁移但内部缓存文件仍不可写：${internalFile.absolutePath}")
            }
            emit(onProgress, "缓存迁移完成，后续更新将写入可写缓存。")
            return internalFile
        }

        if (existing != null) {
            error("当前缓存文件不可写：${existing.absolutePath}")
        }
        if (!context.filesDir.exists()) {
            context.filesDir.mkdirs()
        }
        return internalFile
    }

    private fun File.canOpenForWrite(): Boolean =
        runCatching {
            java.io.RandomAccessFile(this, "rw").use { }
            true
        }.getOrDefault(false)

    private fun initSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stocks (
                symbol TEXT PRIMARY KEY,
                code TEXT NOT NULL,
                ts_code TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                market TEXT NOT NULL,
                is_st INTEGER NOT NULL DEFAULT 0,
                current_price REAL NOT NULL DEFAULT 0,
                current_amount REAL NOT NULL DEFAULT 0,
                source TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_bars (
                ts_code TEXT NOT NULL,
                trade_date TEXT NOT NULL,
                open REAL NOT NULL,
                high REAL NOT NULL,
                low REAL NOT NULL,
                close REAL NOT NULL,
                pre_close REAL NOT NULL,
                pct_chg REAL NOT NULL,
                volume REAL NOT NULL,
                amount REAL NOT NULL,
                source TEXT NOT NULL,
                PRIMARY KEY (ts_code, trade_date)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_daily_bars_trade_date ON daily_bars(trade_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_daily_bars_ts_code ON daily_bars(ts_code)")
    }

    private fun cleanOldBars(db: SQLiteDatabase) {
        val cutoff = db.rawQuery(
            """
            SELECT MIN(trade_date)
            FROM (
                SELECT DISTINCT trade_date
                FROM daily_bars
                ORDER BY trade_date DESC
                LIMIT $CACHE_KEEP_TRADE_DAYS
            )
            """.trimIndent(),
            null,
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else ""
        }
        if (cutoff.isNotBlank()) {
            db.execSQL("DELETE FROM daily_bars WHERE trade_date < ?", arrayOf(cutoff))
        }
    }

    private fun updateMetadata(db: SQLiteDatabase, nowText: String, expectedDate: String) {
        val values = mapOf(
            "schema_version" to "1",
            "generated_at" to nowText,
            "stock_source" to "sina",
            "kline_source" to "sina_primary_tencent_fallback_incremental",
            "cache_days" to CACHE_KEEP_TRADE_DAYS.toString(),
            "last_expected_trade_date" to expectedDate,
            "stock_count" to db.count("stocks").toString(),
            "daily_bar_count" to db.count("daily_bars").toString(),
        )
        values.forEach { (key, value) ->
            db.execSQL(
                "INSERT OR REPLACE INTO metadata(key, value) VALUES (?, ?)",
                arrayOf(key, value),
            )
        }
    }

    private fun isCoarseCandidate(
        stock: StockProfile,
        bars: List<DailyBar>,
        config: StrategyConfig,
    ): Boolean {
        if (stock.isSt || bars.size < 260) return false

        val today = bars.last()
        val firstBoardCandidate = today.pctChg >= stock.market.limitUpPct * config.nearLimitRatio
        if (firstBoardCandidate) return true

        val yesterday = bars[bars.lastIndex - 1]
        val denominator = yesterday.open - yesterday.close
        val gameKLineCandidate = denominator > 0.0 &&
            today.close > today.open &&
            (today.close - yesterday.close) / denominator >= config.reboundRatioThreshold &&
            today.close >= yesterday.open
        if (gameKLineCandidate) return true

        val last9 = bars.takeLast(9)
        val yangCount = last9.count { it.close > it.open }
        val totalRisePct = (last9.last().close - last9.first().open) / last9.first().open * 100.0
        val nineYangCandidate = yangCount >= config.minNineYangYangCount && totalRisePct <= config.maxNineYangRisePct
        if (nineYangCandidate) return true

        val last120 = bars.takeLast(120)
        val last30 = bars.takeLast(30)
        val last20 = bars.takeLast(20)
        val previous20 = bars.dropLast(20).takeLast(20)
        val previous5 = bars.dropLast(1).takeLast(5)
        if (last120.size < 120 || previous20.size < 20 || previous5.size < 5) return false

        val low120 = last120.minOf { it.low }
        val high30 = last30.maxOf { it.high }
        val low30 = last30.minOf { it.low }
        val high20 = last20.maxOf { it.high }
        val low20 = last20.minOf { it.low }
        val previous20Low = previous20.minOf { it.low }
        val avgVolume5 = previous5.map { it.volume }.average()
        val ma20 = last20.map { it.close }.average()
        val ma20ThreeDaysAgo = bars.dropLast(3).takeLast(20).map { it.close }.average()
        val ma60 = bars.takeLast(60).map { it.close }.average()
        val closeRange = today.high - today.low

        val low120DistancePct = if (low120 > 0.0) (today.close / low120 - 1.0) * 100.0 else Double.MAX_VALUE
        val low20VsPrevious20Pct = if (previous20Low > 0.0) (low20 / previous20Low - 1.0) * 100.0 else -Double.MAX_VALUE
        val amplitude30Pct = if (low30 > 0.0) (high30 / low30 - 1.0) * 100.0 else Double.MAX_VALUE
        val volumeRatio5 = if (avgVolume5 > 0.0) today.volume / avgVolume5 else 0.0
        val closeStrength = if (closeRange > 0.0) (today.close - today.low) / closeRange else 0.0
        val nearHigh20Pct = if (high20 > 0.0) (today.close / high20 - 1.0) * 100.0 else -Double.MAX_VALUE
        val notOneWord = today.preClose <= 0.0 || abs(today.high - today.low) / today.preClose > 0.002

        return today.amount >= config.minAmount &&
            notOneWord &&
            low120DistancePct in 0.0..25.0 &&
            low20VsPrevious20Pct >= -3.0 &&
            amplitude30Pct <= 35.0 &&
            today.close >= ma20 &&
            ma20 >= ma20ThreeDaysAgo * 0.995 &&
            today.close >= ma60 * 0.97 &&
            volumeRatio5 >= 1.2 &&
            today.close > today.open &&
            closeStrength >= 0.60 &&
            nearHigh20Pct >= -3.0 &&
            today.pctChg in 1.0..8.0
    }

    private suspend fun evaluateCandidateBatch(
        candidates: List<CandidateBars>,
        config: StrategyConfig,
    ): List<StrategySignal> = coroutineScope {
        candidates
            .map { candidate ->
                async(Dispatchers.Default) {
                    StrategyEngine.evaluate(candidate.stock, candidate.bars, config)
                }
            }
            .awaitAll()
            .flatten()
    }

    private suspend fun evaluateCustomCandidateBatch(
        candidates: List<CandidateBars>,
        config: CustomFilterConfig,
    ): List<StrategySignal> = coroutineScope {
        candidates
            .map { candidate ->
                async(Dispatchers.Default) {
                    CustomFilterEngine.evaluate(candidate.stock, candidate.bars, config)
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    private fun loadStocks(db: SQLiteDatabase): Map<String, StockProfile> {
        val result = mutableMapOf<String, StockProfile>()
        db.rawQuery(
            """
            SELECT ts_code, name, market, is_st
            FROM stocks
            """.trimIndent(),
            null,
        ).use { cursor ->
            val tsIndex = cursor.getColumnIndexOrThrow("ts_code")
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val marketIndex = cursor.getColumnIndexOrThrow("market")
            val stIndex = cursor.getColumnIndexOrThrow("is_st")
            while (cursor.moveToNext()) {
                val tsCode = cursor.getString(tsIndex)
                result[tsCode] = StockProfile(
                    tsCode = tsCode,
                    name = cursor.getString(nameIndex),
                    market = marketFromValue(cursor.getString(marketIndex)),
                    listDate = "",
                    isSt = cursor.getInt(stIndex) != 0,
                )
            }
        }
        return result
    }

    private fun loadMetadata(db: SQLiteDatabase): Map<String, String> {
        val result = mutableMapOf<String, String>()
        db.rawQuery("SELECT key, value FROM metadata", null).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return result
    }

    private fun SQLiteDatabase.count(table: String): Int =
        rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun SQLiteDatabase.countDistinct(table: String, column: String): Int =
        rawQuery("SELECT COUNT(DISTINCT $column) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun SQLiteDatabase.countDistinctSince(table: String, column: String, tradeDate: String): Int =
        rawQuery("SELECT COUNT(DISTINCT $column) FROM $table WHERE trade_date >= ?", arrayOf(tradeDate)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun recentTradeDateCutoff(db: SQLiteDatabase, limit: Int): String =
        db.rawQuery(
            """
            SELECT MIN(trade_date)
            FROM (
                SELECT DISTINCT trade_date
                FROM daily_bars
                ORDER BY trade_date DESC
                LIMIT ?
            )
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else ""
        }

    private fun android.database.Cursor.getStringOrEmpty(index: Int): String =
        if (isNull(index)) "" else getString(index)

    private fun marketFromValue(value: String): MarketSegment =
        runCatching { MarketSegment.valueOf(value) }.getOrDefault(MarketSegment.UNKNOWN)

    private fun expectedLatestClosedTradeDate(): String {
        val now = LocalDateTime.now(chinaZone)
        var date = now.toLocalDate()
        if (date.isTradingWeekday() && now.toLocalTime() < LocalTime.of(15, 30)) {
            date = date.minusDays(1)
        }
        while (!date.isTradingWeekday()) {
            date = date.minusDays(1)
        }
        return date.format(dateFormatter)
    }

    private fun LocalDate.isTradingWeekday(): Boolean =
        dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY

    private fun estimateRemaining(completed: Int, total: Int, startedAt: Long): String {
        if (completed <= 0 || total <= completed) return "0秒"
        val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        val remainingMs = ceil(elapsedMs.toDouble() / completed * (total - completed)).toLong()
        return formatDuration(remainingMs)
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
    }

    private suspend fun emit(onProgress: suspend (String) -> Unit, message: String) {
        withContext(Dispatchers.Main.immediate) {
            onProgress(message)
        }
    }
}
