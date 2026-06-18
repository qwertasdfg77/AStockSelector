package com.codex.astockselector.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.codex.astockselector.model.DailyBar
import com.codex.astockselector.model.MarketSegment
import com.codex.astockselector.model.RuleCheck
import com.codex.astockselector.model.SignalLevel
import com.codex.astockselector.model.StockProfile
import com.codex.astockselector.model.StrategyConfig
import com.codex.astockselector.model.StrategySignal
import com.codex.astockselector.model.strategyRuleKey
import com.codex.astockselector.strategy.StrategyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    val failedStockCount: Int = 0,
    val retryableFailedStockCount: Int = 0,
    val latestDateCoveragePct: Double = 0.0,
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

private data class CachedStockState(
    val latestTradeDate: String,
    val barCount: Int,
)

private data class CacheFailureState(
    val tsCode: String,
    val retryDate: String,
    val retryCount: Int,
) {
    fun canRetry(today: String): Boolean =
        retryDate != today || retryCount < CacheMarketRepository.MAX_DAILY_FAILURE_RETRIES
}

object CacheMarketRepository {
    private const val DB_NAME = "market_cache.db"
    private const val CACHE_KEEP_TRADE_DAYS = 270
    private const val SIGNAL_LOOKBACK_TRADE_DAYS = 270
    private const val INCREMENTAL_UPDATE_DAYS = 40
    private const val EVALUATION_BATCH_SIZE = 64
    private const val SQLITE_MAX_VARIABLES = 900
    const val MAX_DAILY_FAILURE_RETRIES = 2
    private const val MIN_EXPECTED_DATE_COVERAGE = 0.85
    private val dateFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val chinaZone = ZoneId.of("Asia/Shanghai")

    suspend fun loadSmartSignals(
        context: Context,
        config: StrategyConfig,
        onProgress: suspend (String) -> Unit = {},
    ): List<StrategySignal> = withContext(Dispatchers.IO) {
        val expectedDate = expectedLatestClosedTradeDate()
        var info = cacheInfo(context)
        val cacheDateIsLatest = info.exists && info.dateEnd >= expectedDate

        if (cacheDateIsLatest && info.retryableFailedStockCount <= 0) {
            emit(onProgress, "缓存已是收盘最新数据（${info.dateEnd}），直接筛选。")
            return@withContext loadSignals(context, config, onProgress)
        }

        if (cacheDateIsLatest) {
            emit(onProgress, "缓存已是收盘最新数据，但有 ${info.retryableFailedStockCount} 只失败股票需要补读。")
        } else {
            val currentDate = if (info.exists) info.dateEnd.ifBlank { "无" } else "无缓存"
            emit(onProgress, "阶段1/5：缓存最新日期 $currentDate，目标收盘日期 $expectedDate，开始读取股票列表并规划缺口更新...")
        }
        val updatedCodes = updateCache(context, config, info, expectedDate, forceRebuild = false, onProgress)
        info = cacheInfo(context)
        if (!info.exists || info.dateEnd < expectedDate) {
            val actualDate = info.dateEnd.ifBlank { "无" }
            error("缓存更新后仍未达到目标收盘日期：目标 $expectedDate，当前 $actualDate。请稍后重试或检查数据源。")
        }
        if (info.retryableFailedStockCount > 0) {
            emit(onProgress, "仍有 ${info.retryableFailedStockCount} 只股票未补齐，已记录失败队列，稍后可再次重试。")
        }
        emit(onProgress, "阶段5/5：缓存已更新到 ${info.dateEnd}，开始增量筛选...")
        loadSignals(context, config, onProgress, updatedCodes = updatedCodes)
    }

    suspend fun rebuildCacheAndLoadSignals(
        context: Context,
        config: StrategyConfig,
        onProgress: suspend (String) -> Unit = {},
    ): List<StrategySignal> = withContext(Dispatchers.IO) {
        val expectedDate = expectedLatestClosedTradeDate()
        emit(onProgress, "阶段1/5：开始重建缓存，将删除旧K线缓存，但保留App设置和已选战法。")
        val deletedCount = deleteCacheFiles(context)
        emit(onProgress, "阶段2/5：已删除旧缓存文件 $deletedCount 个，开始读取股票列表并规划重建。")
        val updatedCodes = updateCache(
            context = context,
            config = config,
            currentInfo = MarketCacheInfo(),
            expectedDate = expectedDate,
            forceRebuild = true,
            onProgress = onProgress,
        )
        val info = cacheInfo(context)
        if (!info.exists || info.dateEnd < expectedDate) {
            val actualDate = info.dateEnd.ifBlank { "无" }
            error("重建缓存后仍未达到目标收盘日期：目标 $expectedDate，当前 $actualDate。请稍后重试或检查数据源。")
        }
        emit(onProgress, "阶段5/5：缓存重建完成：${info.stockCount} 只股票，${info.dailyBarCount} 行K线，开始增量筛选...")
        loadSignals(context, config, onProgress, updatedCodes = updatedCodes)
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
                val failedStockCount = if (db.tableExists("cache_update_failures")) {
                    db.count("cache_update_failures")
                } else {
                    0
                }
                val retryableFailedStockCount = if (db.tableExists("cache_update_failures")) {
                    db.retryableFailureCount(todayRetryDate())
                } else {
                    0
                }
                val latestCoverage = if (dateRange.second.isNotBlank()) {
                    db.dateCoverageRatio(dateRange.second)
                } else {
                    0.0
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
                    failedStockCount = failedStockCount,
                    retryableFailedStockCount = retryableFailedStockCount,
                    latestDateCoveragePct = latestCoverage * 100.0,
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
            isLatest = info.exists && info.dateEnd >= expectedDate && info.retryableFailedStockCount <= 0,
        )
    }

    suspend fun markNewSignalCodes(
        context: Context,
        ruleKey: String,
        tradeDate: String,
        newSignalCodes: Set<String>,
    ) = withContext(Dispatchers.IO) {
        if (ruleKey.isBlank() || tradeDate.isBlank()) return@withContext
        val file = cacheFile(context)?.takeIf { it.canOpenForWrite() } ?: return@withContext
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            initSchema(db)
            db.execSQL(
                "UPDATE strategy_signal_results SET is_new = 0 WHERE rule_key = ? AND trade_date = ?",
                arrayOf(ruleKey, tradeDate),
            )
            newSignalCodes.chunked(SQLITE_MAX_VARIABLES).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val args = arrayOf(ruleKey, tradeDate) + chunk.toTypedArray()
                db.execSQL(
                    "UPDATE strategy_signal_results SET is_new = 1 WHERE rule_key = ? AND trade_date = ? AND ts_code IN ($placeholders)",
                    args,
                )
            }
        }
    }

    suspend fun loadSignals(
        context: Context,
        config: StrategyConfig,
        onProgress: suspend (String) -> Unit = {},
        updatedCodes: Set<String> = emptySet(),
    ): List<StrategySignal> = withContext(Dispatchers.IO) {
        val existingFile = cacheFile(context) ?: error("没有找到本地缓存 market_cache.db")
        val file = if (existingFile.canOpenForWrite()) {
            existingFile
        } else {
            writableCacheFile(context, onProgress)
        }
        val startedAt = System.currentTimeMillis()
        emit(onProgress, "阶段5/5：正在打开本地缓存，准备增量筛选...")

        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            initSchema(db)
            val stocks = loadStocks(db)
            val cutoffTradeDate = recentTradeDateCutoff(db, SIGNAL_LOOKBACK_TRADE_DAYS)
            val cacheTradeDate = latestTradeDate(db)
            if (cacheTradeDate.isBlank()) {
                error("本地缓存没有可筛选的K线数据")
            }
            val ruleKey = config.strategyRuleKey()
            val currentCodes = stocks.keys

            cleanObsoleteSignalResults(db, cacheTradeDate, currentCodes)
            if (updatedCodes.isNotEmpty()) {
                clearSignalResultsForCodes(db, updatedCodes)
                emit(onProgress, "阶段5/5：已标记 ${updatedCodes.size} 只更新成功股票需要重新筛选。")
            }

            val evaluableCodes = loadEvaluableCodes(db, cutoffTradeDate).filter { it in stocks }.toSet()
            val evaluatedCodes = loadEvaluatedCodes(db, ruleKey, cacheTradeDate)
            val codesToEvaluate = (evaluableCodes - evaluatedCodes).sorted()
            val cachedSignals = loadStoredSignalResults(db, ruleKey, cacheTradeDate)

            if (codesToEvaluate.isEmpty()) {
                val sorted = cachedSignals.sortSignals()
                emit(onProgress, "阶段5/5：增量结果表已是最新，直接读取 ${sorted.size} 条命中结果。")
                return@use sorted
            }

            emit(
                onProgress,
                "阶段5/5：正在增量筛选 ${codesToEvaluate.size}/${evaluableCodes.size} 只股票，已复用 ${cachedSignals.size} 条历史命中结果...",
            )

            val signals = mutableListOf<StrategySignal>()
            val bars = mutableListOf<DailyBar>()
            val pendingEvaluations = mutableListOf<CandidateBars>()
            var currentTsCode = ""
            var completed = 0

            suspend fun flushPendingEvaluations() {
                if (pendingEvaluations.isEmpty()) return
                val pending = pendingEvaluations.toList()
                val evaluated = evaluateCandidateBatch(pending, config)
                signals += evaluated
                val nowText = LocalDateTime.now(chinaZone).toString()
                pending.forEach { candidate ->
                    val stockSignals = evaluated.filter { it.stock.tsCode == candidate.stock.tsCode }
                    storeSignalEvaluation(
                        db = db,
                        ruleKey = ruleKey,
                        tradeDate = cacheTradeDate,
                        tsCode = candidate.stock.tsCode,
                        signals = stockSignals,
                        nowText = nowText,
                    )
                }
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
                } else if (stock != null) {
                    storeSignalEvaluation(
                        db = db,
                        ruleKey = ruleKey,
                        tradeDate = cacheTradeDate,
                        tsCode = stock.tsCode,
                        signals = emptyList(),
                        nowText = LocalDateTime.now(chinaZone).toString(),
                    )
                }
                completed += 1
            }

            codesToEvaluate.chunked(SQLITE_MAX_VARIABLES).forEach { codeChunk ->
                val placeholders = codeChunk.joinToString(",") { "?" }
                val args = arrayOf(cutoffTradeDate) + codeChunk.toTypedArray()
                db.rawQuery(
                    """
                    SELECT ts_code, trade_date, open, high, low, close, pre_close, pct_chg, volume, amount
                    FROM daily_bars
                    WHERE trade_date >= ? AND ts_code IN ($placeholders)
                    ORDER BY ts_code, trade_date
                    """.trimIndent(),
                    args,
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
                                    "阶段5/5：正在筛选 $completed/${codesToEvaluate.size}，本次新命中 ${signals.size} 条，剩余约 ${
                                        estimateRemaining(completed, codesToEvaluate.size, startedAt)
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
            }
            evaluateCurrent()
            flushPendingEvaluations()

            val missingBarCodes = codesToEvaluate.toSet() - loadEvaluatedCodes(db, ruleKey, cacheTradeDate)
            if (missingBarCodes.isNotEmpty()) {
                val nowText = LocalDateTime.now(chinaZone).toString()
                missingBarCodes.forEach { tsCode ->
                    storeSignalEvaluation(db, ruleKey, cacheTradeDate, tsCode, emptyList(), nowText)
                }
            }

            val sorted = (cachedSignals + signals).sortSignals()
            emit(onProgress, "阶段5/5：本地增量筛选完成，复用 ${cachedSignals.size} 条，新增 ${signals.size} 条，合计命中 ${sorted.size} 条信号。")
            sorted
        }
    }

    private suspend fun updateCache(
        context: Context,
        config: StrategyConfig,
        currentInfo: MarketCacheInfo,
        expectedDate: String,
        forceRebuild: Boolean,
        onProgress: suspend (String) -> Unit,
    ): Set<String> {
        val file = writableCacheFile(context, onProgress)
        ensureSchema(file)

        val candidates = DirectMarketRepository.loadCacheStockCandidates(config, onProgress)
        if (candidates.isEmpty()) {
            error("没有读取到可更新的股票列表")
        }

        val targets = buildUpdateTargets(
            file = file,
            candidates = candidates,
            currentInfo = currentInfo,
            expectedDate = expectedDate,
            forceRebuild = forceRebuild,
            onProgress = onProgress,
        )
        if (targets.isEmpty()) {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                initSchema(db)
                val nowText = LocalDateTime.now(chinaZone).toString()
                db.beginTransaction()
                try {
                    syncStockMaster(db, candidates, nowText)
                    deleteObsoleteFailures(db, candidates.map { it.stock.tsCode }.toSet())
                    updateMetadata(db, nowText, expectedDate)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            emit(onProgress, "阶段2/5：没有需要联网补读的股票，已刷新股票列表和缓存状态。")
            return emptySet()
        }

        val updateResult = DirectMarketRepository.loadCacheUpdates(
            targets = targets,
            onProgress = onProgress,
        )
        val allTargetsFailed = updateResult.updates.isEmpty() && updateResult.failures.isNotEmpty()
        val successCodes = updateResult.updates.map { it.stock.tsCode }.toSet()

        emit(onProgress, "阶段4/5：正在保存K线到本地缓存数据库...")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            initSchema(db)
            val nowText = LocalDateTime.now(chinaZone).toString()
            val targetCodes = targets.map { it.candidate.stock.tsCode }.toSet()
            val failedTargetCodes = targetCodes - successCodes

            db.beginTransaction()
            try {
                val barSql = """
                    INSERT OR REPLACE INTO daily_bars (
                        ts_code, trade_date, open, high, low, close, pre_close, pct_chg, volume, amount, source
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()

                syncStockMaster(db, candidates, nowText)
                updateResult.updates.forEachIndexed { index, item ->
                    upsertStock(db, item.stock, nowText)
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
                        emit(onProgress, "阶段4/5：正在保存缓存 ${index + 1}/${updateResult.updates.size} 只...")
                    }
                }

                clearSuccessfulFailures(db, successCodes)
                recordFailures(
                    db = db,
                    failures = updateResult.failures.filter { it.candidate.stock.tsCode in failedTargetCodes },
                    nowText = nowText,
                )
                deleteObsoleteFailures(db, candidates.map { it.stock.tsCode }.toSet())
                cleanOldBars(db)
                updateMetadata(db, nowText, expectedDate)
                val coverage = db.dateCoverageRatio(expectedDate)
                if (coverage < MIN_EXPECTED_DATE_COVERAGE) {
                    emit(
                        onProgress,
                        "阶段4/5：目标交易日覆盖率 ${String.format("%.1f", coverage * 100)}%，低于 ${String.format("%.0f", MIN_EXPECTED_DATE_COVERAGE * 100)}%，失败股票已保留到下次补读。",
                    )
                } else {
                    emit(onProgress, "阶段4/5：目标交易日覆盖率 ${String.format("%.1f", coverage * 100)}%。")
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        if (allTargetsFailed) {
            error("本次目标股票K线全部读取失败，已记录失败队列。")
        }
        return successCodes
    }

    private fun cacheFile(context: Context): File? {
        val candidates = listOfNotNull(
            File(context.filesDir, DB_NAME),
            context.getExternalFilesDir(null)?.let { File(it, DB_NAME) },
        ).filter { it.exists() && it.length() > 0L }
        return candidates.maxByOrNull { it.lastModified() }
    }

    private fun deleteCacheFiles(context: Context): Int {
        val candidates = listOfNotNull(
            File(context.filesDir, DB_NAME),
            context.getExternalFilesDir(null)?.let { File(it, DB_NAME) },
        ).distinctBy { it.absolutePath }

        return candidates.count { file ->
            file.exists() && runCatching { file.delete() }.getOrDefault(false)
        }
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

    private fun ensureSchema(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            initSchema(db)
        }
    }

    private suspend fun buildUpdateTargets(
        file: File,
        candidates: List<MarketCacheStockCandidate>,
        currentInfo: MarketCacheInfo,
        expectedDate: String,
        forceRebuild: Boolean,
        onProgress: suspend (String) -> Unit,
    ): List<MarketCacheUpdateTarget> {
        val today = todayRetryDate()
        val currentCodes = candidates.map { it.stock.tsCode }.toSet()

        val targets = SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            initSchema(db)
            deleteObsoleteFailures(db, currentCodes)
            val cachedStates = loadCachedStockStates(db)
            val failureStates = loadFailureStates(db)
            val cacheDateIsLatest = currentInfo.exists && currentInfo.dateEnd >= expectedDate
            var skippedByRetryLimit = 0
            var newCount = 0
            var missingDateCount = 0
            var failureCount = 0

            val planned = candidates.mapNotNull { candidate ->
                val tsCode = candidate.stock.tsCode
                val cached = cachedStates[tsCode]
                val failure = failureStates[tsCode]
                val retryableFailure = failure != null && failure.canRetry(today)
                if (failure != null && !retryableFailure) {
                    skippedByRetryLimit += 1
                }

                val isNewOrIncomplete = cached == null || cached.barCount < SIGNAL_LOOKBACK_TRADE_DAYS
                val missingExpectedDate = cached?.latestTradeDate.orEmpty() < expectedDate
                val shouldUpdate = when {
                    forceRebuild -> true
                    cacheDateIsLatest -> retryableFailure
                    !failure.canRetryOrTrue(today) -> false
                    else -> isNewOrIncomplete || missingExpectedDate || retryableFailure
                }

                if (!shouldUpdate) return@mapNotNull null

                when {
                    isNewOrIncomplete || forceRebuild -> newCount += 1
                    retryableFailure -> failureCount += 1
                    missingExpectedDate -> missingDateCount += 1
                }

                MarketCacheUpdateTarget(
                    candidate = candidate,
                    days = if (forceRebuild || isNewOrIncomplete) {
                        CACHE_KEEP_TRADE_DAYS
                    } else {
                        INCREMENTAL_UPDATE_DAYS
                    },
                )
            }

            if (skippedByRetryLimit > 0) {
                emit(onProgress, "阶段2/5：有 $skippedByRetryLimit 只股票今天已达到失败重试上限，暂不重复请求。")
            }
            if (planned.isNotEmpty()) {
                val action = if (forceRebuild) {
                    "重建缓存"
                } else {
                    "增量更新"
                }
                emit(
                    onProgress,
                    "阶段2/5：$action 计划：共 ${planned.size} 只；新增/历史不足 $newCount 只，缺目标交易日 $missingDateCount 只，失败补读 $failureCount 只。",
                )
            }
            planned
        }

        return targets
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
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cache_update_failures (
                ts_code TEXT PRIMARY KEY,
                symbol TEXT NOT NULL,
                name TEXT NOT NULL,
                retry_date TEXT NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                last_failed_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cache_update_failures_retry ON cache_update_failures(retry_date, retry_count)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS strategy_evaluations (
                rule_key TEXT NOT NULL,
                ts_code TEXT NOT NULL,
                trade_date TEXT NOT NULL,
                signal_count INTEGER NOT NULL DEFAULT 0,
                evaluated_at TEXT NOT NULL,
                PRIMARY KEY (rule_key, ts_code, trade_date)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_strategy_evaluations_date ON strategy_evaluations(rule_key, trade_date)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS strategy_signal_results (
                rule_key TEXT NOT NULL,
                ts_code TEXT NOT NULL,
                trade_date TEXT NOT NULL,
                strategy TEXT NOT NULL,
                score INTEGER NOT NULL,
                level TEXT NOT NULL,
                reasons_json TEXT NOT NULL,
                metrics_json TEXT NOT NULL,
                buy_trigger TEXT NOT NULL,
                stop_loss TEXT NOT NULL,
                rule_checks_json TEXT NOT NULL,
                is_new INTEGER NOT NULL DEFAULT 0,
                evaluated_at TEXT NOT NULL,
                PRIMARY KEY (rule_key, ts_code, trade_date, strategy)
            )
            """.trimIndent(),
        )
        db.addColumnIfMissing("strategy_signal_results", "is_new", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_strategy_signal_results_date ON strategy_signal_results(rule_key, trade_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_strategy_signal_results_stock ON strategy_signal_results(rule_key, ts_code)")
    }

    private fun syncStockMaster(
        db: SQLiteDatabase,
        candidates: List<MarketCacheStockCandidate>,
        nowText: String,
    ) {
        candidates.forEach { candidate ->
            upsertStock(db, candidate.stock, nowText)
        }
    }

    private fun upsertStock(
        db: SQLiteDatabase,
        stock: MarketCacheStockRecord,
        nowText: String,
    ) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO stocks (
                symbol, code, ts_code, name, market, is_st, current_price, current_amount, source, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                stock.symbol,
                stock.code,
                stock.tsCode,
                stock.name,
                stock.market.name,
                if (stock.isSt) 1 else 0,
                stock.currentPrice,
                stock.currentAmount,
                "sina",
                nowText,
            ),
        )
        db.execSQL(
            """
            UPDATE stocks
            SET code = ?,
                ts_code = ?,
                name = ?,
                market = ?,
                is_st = ?,
                current_price = CASE WHEN ? > 0 THEN ? ELSE current_price END,
                current_amount = ?,
                source = ?,
                updated_at = ?
            WHERE symbol = ?
            """.trimIndent(),
            arrayOf(
                stock.code,
                stock.tsCode,
                stock.name,
                stock.market.name,
                if (stock.isSt) 1 else 0,
                stock.currentPrice,
                stock.currentPrice,
                stock.currentAmount,
                "sina",
                nowText,
                stock.symbol,
            ),
        )
    }

    private fun loadCachedStockStates(db: SQLiteDatabase): Map<String, CachedStockState> {
        val result = mutableMapOf<String, CachedStockState>()
        db.rawQuery(
            """
            SELECT ts_code, MAX(trade_date) AS latest_trade_date, COUNT(*) AS bar_count
            FROM daily_bars
            GROUP BY ts_code
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] = CachedStockState(
                    latestTradeDate = cursor.getStringOrEmpty(1),
                    barCount = cursor.getInt(2),
                )
            }
        }
        return result
    }

    private fun loadFailureStates(db: SQLiteDatabase): Map<String, CacheFailureState> {
        if (!db.tableExists("cache_update_failures")) return emptyMap()
        val result = mutableMapOf<String, CacheFailureState>()
        db.rawQuery(
            "SELECT ts_code, retry_date, retry_count FROM cache_update_failures",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val tsCode = cursor.getString(0)
                result[tsCode] = CacheFailureState(
                    tsCode = tsCode,
                    retryDate = cursor.getStringOrEmpty(1),
                    retryCount = cursor.getInt(2),
                )
            }
        }
        return result
    }

    private fun clearSuccessfulFailures(db: SQLiteDatabase, successCodes: Set<String>) {
        successCodes.forEach { tsCode ->
            db.execSQL("DELETE FROM cache_update_failures WHERE ts_code = ?", arrayOf(tsCode))
        }
    }

    private fun recordFailures(
        db: SQLiteDatabase,
        failures: List<MarketCacheUpdateFailure>,
        nowText: String,
    ) {
        if (failures.isEmpty()) return
        val today = todayRetryDate()
        val existing = loadFailureStates(db)
        failures.forEach { failure ->
            val stock = failure.candidate.stock
            val previous = existing[stock.tsCode]
            val retryCount = if (previous?.retryDate == today) {
                previous.retryCount + 1
            } else {
                1
            }
            db.execSQL(
                """
                INSERT OR REPLACE INTO cache_update_failures (
                    ts_code, symbol, name, retry_date, retry_count, last_error, last_failed_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    stock.tsCode,
                    failure.candidate.symbol,
                    stock.name,
                    today,
                    retryCount,
                    failure.errorMessage.take(300),
                    nowText,
                    nowText,
                ),
            )
        }
    }

    private fun deleteObsoleteFailures(db: SQLiteDatabase, currentCodes: Set<String>) {
        if (!db.tableExists("cache_update_failures") || currentCodes.isEmpty()) return
        val obsoleteCodes = mutableListOf<String>()
        db.rawQuery("SELECT ts_code FROM cache_update_failures", null).use { cursor ->
            while (cursor.moveToNext()) {
                val tsCode = cursor.getString(0)
                if (tsCode !in currentCodes) obsoleteCodes += tsCode
            }
        }
        obsoleteCodes.forEach { tsCode ->
            db.execSQL("DELETE FROM cache_update_failures WHERE ts_code = ?", arrayOf(tsCode))
        }
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
            "kline_source" to "sina_primary_tencent_fallback_gap_incremental",
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

    private fun latestTradeDate(db: SQLiteDatabase): String =
        db.rawQuery("SELECT MAX(trade_date) FROM daily_bars", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getStringOrEmpty(0) else ""
        }

    private fun loadEvaluableCodes(db: SQLiteDatabase, cutoffTradeDate: String): List<String> {
        val codes = mutableListOf<String>()
        db.rawQuery(
            "SELECT DISTINCT ts_code FROM daily_bars WHERE trade_date >= ? ORDER BY ts_code",
            arrayOf(cutoffTradeDate),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                codes += cursor.getString(0)
            }
        }
        return codes
    }

    private fun loadEvaluatedCodes(
        db: SQLiteDatabase,
        ruleKey: String,
        tradeDate: String,
    ): Set<String> {
        val codes = mutableSetOf<String>()
        db.rawQuery(
            "SELECT ts_code FROM strategy_evaluations WHERE rule_key = ? AND trade_date = ?",
            arrayOf(ruleKey, tradeDate),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                codes += cursor.getString(0)
            }
        }
        return codes
    }

    private fun loadStoredSignalResults(
        db: SQLiteDatabase,
        ruleKey: String,
        tradeDate: String,
    ): List<StrategySignal> {
        val signals = mutableListOf<StrategySignal>()
        db.rawQuery(
            """
            SELECT r.trade_date,
                   r.ts_code,
                   s.name,
                   s.market,
                   s.is_st,
                   r.strategy,
                   r.score,
                   r.level,
                   r.reasons_json,
                   r.metrics_json,
                   r.buy_trigger,
                   r.stop_loss,
                   r.rule_checks_json
            FROM strategy_signal_results r
            JOIN stocks s ON s.ts_code = r.ts_code
            WHERE r.rule_key = ? AND r.trade_date = ?
            """.trimIndent(),
            arrayOf(ruleKey, tradeDate),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val stock = StockProfile(
                    tsCode = cursor.getString(1),
                    name = cursor.getString(2),
                    market = marketFromValue(cursor.getString(3)),
                    listDate = "",
                    isSt = cursor.getInt(4) != 0,
                )
                signals += StrategySignal(
                    tradeDate = cursor.getString(0),
                    stock = stock,
                    strategy = cursor.getString(5),
                    score = cursor.getInt(6),
                    level = cursor.getString(7).toSignalLevel(),
                    reasons = cursor.getString(8).toStringList(),
                    metrics = cursor.getString(9).toPairList(),
                    buyTrigger = cursor.getString(10),
                    stopLoss = cursor.getString(11),
                    ruleChecks = cursor.getString(12).toRuleCheckList(),
                )
            }
        }
        return signals
    }

    private fun storeSignalEvaluation(
        db: SQLiteDatabase,
        ruleKey: String,
        tradeDate: String,
        tsCode: String,
        signals: List<StrategySignal>,
        nowText: String,
    ) {
        db.execSQL(
            "DELETE FROM strategy_signal_results WHERE rule_key = ? AND ts_code = ? AND trade_date = ?",
            arrayOf(ruleKey, tsCode, tradeDate),
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO strategy_evaluations (
                rule_key, ts_code, trade_date, signal_count, evaluated_at
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(ruleKey, tsCode, tradeDate, signals.size, nowText),
        )

        signals.forEach { signal ->
            db.execSQL(
                """
                INSERT OR REPLACE INTO strategy_signal_results (
                    rule_key, ts_code, trade_date, strategy, score, level, reasons_json, metrics_json,
                    buy_trigger, stop_loss, rule_checks_json, evaluated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    ruleKey,
                    tsCode,
                    tradeDate,
                    signal.strategy,
                    signal.score,
                    signal.level.name,
                    signal.reasons.toStringJsonArray().toString(),
                    signal.metrics.toPairJsonArray().toString(),
                    signal.buyTrigger,
                    signal.stopLoss,
                    signal.ruleChecks.toRuleCheckJsonArray().toString(),
                    nowText,
                ),
            )
        }
    }

    private fun clearSignalResultsForCodes(
        db: SQLiteDatabase,
        codes: Set<String>,
    ) {
        if (codes.isEmpty()) return
        codes.chunked(SQLITE_MAX_VARIABLES).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.execSQL(
                "DELETE FROM strategy_signal_results WHERE ts_code IN ($placeholders)",
                chunk.toTypedArray(),
            )
            db.execSQL(
                "DELETE FROM strategy_evaluations WHERE ts_code IN ($placeholders)",
                chunk.toTypedArray(),
            )
        }
    }

    private fun cleanObsoleteSignalResults(
        db: SQLiteDatabase,
        currentTradeDate: String,
        currentCodes: Set<String>,
    ) {
        db.execSQL("DELETE FROM strategy_signal_results WHERE trade_date != ?", arrayOf(currentTradeDate))
        db.execSQL("DELETE FROM strategy_evaluations WHERE trade_date != ?", arrayOf(currentTradeDate))
        if (currentCodes.isEmpty()) return

        val obsoleteCodes = mutableListOf<String>()
        db.rawQuery("SELECT DISTINCT ts_code FROM strategy_evaluations", null).use { cursor ->
            while (cursor.moveToNext()) {
                val tsCode = cursor.getString(0)
                if (tsCode !in currentCodes) obsoleteCodes += tsCode
            }
        }
        obsoleteCodes.chunked(SQLITE_MAX_VARIABLES).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.execSQL(
                "DELETE FROM strategy_signal_results WHERE ts_code IN ($placeholders)",
                chunk.toTypedArray(),
            )
            db.execSQL(
                "DELETE FROM strategy_evaluations WHERE ts_code IN ($placeholders)",
                chunk.toTypedArray(),
            )
        }
    }

    private fun List<StrategySignal>.sortSignals(): List<StrategySignal> =
        sortedWith(compareByDescending<StrategySignal> { it.score }.thenBy { it.stock.tsCode })

    private fun List<String>.toStringJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { array.put(it) }
        return array
    }

    private fun List<Pair<String, String>>.toPairJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { (label, value) ->
            array.put(
                JSONObject()
                    .put("label", label)
                    .put("value", value),
            )
        }
        return array
    }

    private fun List<RuleCheck>.toRuleCheckJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { check ->
            array.put(
                JSONObject()
                    .put("label", check.label)
                    .put("passed", check.passed),
            )
        }
        return array
    }

    private fun String.toStringList(): List<String> {
        val array = runCatching { JSONArray(this) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { index -> array.optString(index) }
    }

    private fun String.toPairList(): List<Pair<String, String>> {
        val array = runCatching { JSONArray(this) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            item.optString("label") to item.optString("value")
        }
    }

    private fun String.toRuleCheckList(): List<RuleCheck> {
        val array = runCatching { JSONArray(this) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            RuleCheck(
                label = item.optString("label"),
                passed = item.optBoolean("passed"),
            )
        }
    }

    private fun String.toSignalLevel(): SignalLevel =
        runCatching { SignalLevel.valueOf(this) }.getOrDefault(SignalLevel.NORMAL)

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

    private fun SQLiteDatabase.tableExists(table: String): Boolean =
        rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { cursor ->
            cursor.moveToFirst()
        }

    private fun SQLiteDatabase.addColumnIfMissing(table: String, column: String, definition: String) {
        val exists = rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            var found = false
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext() && !found) {
                found = cursor.getString(nameIndex) == column
            }
            found
        }
        if (!exists) {
            execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    private fun SQLiteDatabase.retryableFailureCount(today: String): Int =
        rawQuery(
            """
            SELECT COUNT(*)
            FROM cache_update_failures
            WHERE retry_date != ? OR retry_count < ?
            """.trimIndent(),
            arrayOf(today, MAX_DAILY_FAILURE_RETRIES.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun SQLiteDatabase.dateCoverageRatio(tradeDate: String): Double {
        if (tradeDate.isBlank() || !tableExists("stocks") || !tableExists("daily_bars")) return 0.0
        val stockTotal = count("stocks")
        if (stockTotal <= 0) return 0.0
        val covered = rawQuery(
            "SELECT COUNT(DISTINCT ts_code) FROM daily_bars WHERE trade_date = ?",
            arrayOf(tradeDate),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        return covered.toDouble() / stockTotal.toDouble()
    }

    private fun CacheFailureState?.canRetryOrTrue(today: String): Boolean =
        this?.canRetry(today) ?: true

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

    private fun todayRetryDate(): String =
        LocalDate.now(chinaZone).format(dateFormatter)

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
