package com.codex.astockselector.data

import com.codex.astockselector.model.DailyBar
import com.codex.astockselector.model.MarketSegment
import com.codex.astockselector.model.StockProfile
import com.codex.astockselector.model.StrategyConfig
import com.codex.astockselector.model.StrategySignal
import com.codex.astockselector.strategy.StrategyEngine
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

data class MarketCacheStockRecord(
    val symbol: String,
    val code: String,
    val tsCode: String,
    val name: String,
    val market: MarketSegment,
    val isSt: Boolean,
    val currentPrice: Double,
    val currentAmount: Double,
)

data class MarketCacheStockBars(
    val stock: MarketCacheStockRecord,
    val bars: List<DailyBar>,
    val source: String,
)

data class MarketCacheStockCandidate(
    val symbol: String,
    val stock: MarketCacheStockRecord,
)

data class MarketCacheUpdateTarget(
    val candidate: MarketCacheStockCandidate,
    val days: Int,
)

data class MarketCacheUpdateFailure(
    val candidate: MarketCacheStockCandidate,
    val errorMessage: String,
)

data class MarketCacheUpdateResult(
    val updates: List<MarketCacheStockBars>,
    val failures: List<MarketCacheUpdateFailure>,
)

object DirectMarketRepository {
    private const val LIST_COUNT_URL =
        "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeStockCount"
    private const val LIST_URL =
        "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData"
    private const val SINA_KLINE_URL =
        "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData"
    private const val TENCENT_KLINE_URL =
        "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
    private const val TENCENT_QUOTE_URL = "https://qt.gtimg.cn/q="

    private const val LIST_PAGE_SIZE = 100
    private const val KLINE_CONCURRENCY = 3
    private const val CACHE_UPDATE_CONCURRENCY = 8
    private const val PER_STOCK_DELAY_MS = 650L
    private const val CACHE_UPDATE_DELAY_MS = 120L
    private const val CACHE_EARLY_FAILURE_CHECK_COUNT = 100
    private const val RETRY_COUNT = 3

    private val gson = Gson()
    private val requestGate = Mutex()
    private var lastStockRequestAt = 0L
    private var lastCacheUpdateRequestAt = 0L
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun loadRealSignals(
        config: StrategyConfig,
        onProgress: suspend (String) -> Unit = {},
    ): List<StrategySignal> {
        return withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            emit(onProgress, "正在读取沪深A股列表...")
            val stocks = loadStocks(config, onProgress)
            if (stocks.isEmpty()) {
                error("没有读取到股票列表，请检查手机网络后重试")
            }

            emit(
                onProgress,
                "通过流动性过滤后 ${stocks.size} 只，开始逐只读取日K。已启用每只股票 ${PER_STOCK_DELAY_MS}ms 间隔。",
            )

            val semaphore = Semaphore(KLINE_CONCURRENCY)
            val completed = AtomicInteger(0)
            val success = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val fallback = AtomicInteger(0)
            val total = stocks.size

            val signals = coroutineScope {
                stocks.map { stock ->
                    async {
                        semaphore.withPermit {
                            try {
                                val result = loadBarsWithFallback(stock)
                                if (result.source == MarketSource.TENCENT) {
                                    fallback.incrementAndGet()
                                }
                                if (result.bars.size < 260) {
                                    failed.incrementAndGet()
                                    emptyList()
                                } else {
                                    success.incrementAndGet()
                                    StrategyEngine.evaluate(stock.profile, result.bars, config)
                                }
                            } catch (_: Exception) {
                                failed.incrementAndGet()
                                emptyList()
                            } finally {
                                val done = completed.incrementAndGet()
                                if (done == total || done % 10 == 0) {
                                    emit(
                                        onProgress,
                                        buildProgressText(
                                            completed = done,
                                            total = total,
                                            success = success.get(),
                                            failed = failed.get(),
                                            fallback = fallback.get(),
                                            startedAt = startedAt,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }.awaitAll().flatten()
            }

            val sorted = signals.sortedWith(
                compareByDescending<StrategySignal> { it.score }.thenBy { it.stock.tsCode },
            )
            emit(
                onProgress,
                "手机直接读取完成：命中 ${sorted.size} 条信号；K线成功 ${success.get()} 只，失败 ${failed.get()} 只，备用腾讯 ${fallback.get()} 只。",
            )
            sorted
        }
    }

    suspend fun loadCacheUpdates(
        config: StrategyConfig,
        days: Int,
        onProgress: suspend (String) -> Unit = {},
    ): MarketCacheUpdateResult {
        val candidates = loadCacheStockCandidates(config, onProgress)
        return loadCacheUpdates(
            targets = candidates.map { MarketCacheUpdateTarget(it, days) },
            onProgress = onProgress,
        )
    }

    suspend fun loadCacheStockCandidates(
        config: StrategyConfig,
        onProgress: suspend (String) -> Unit = {},
    ): List<MarketCacheStockCandidate> {
        return withContext(Dispatchers.IO) {
            emit(onProgress, "正在读取股票列表，用于更新缓存...")
            val stocks = loadStocks(config, onProgress)
            if (stocks.isEmpty()) {
                error("没有读取到股票列表，请检查手机网络后重试")
            }
            stocks.map { stock ->
                MarketCacheStockCandidate(
                    symbol = stock.symbol,
                    stock = stock.toCacheStockRecord(currentPrice = 0.0),
                )
            }
        }
    }

    suspend fun loadCacheUpdates(
        targets: List<MarketCacheUpdateTarget>,
        onProgress: suspend (String) -> Unit = {},
    ): MarketCacheUpdateResult {
        return withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            if (targets.isEmpty()) {
                emit(onProgress, "没有需要联网更新的股票。")
                return@withContext MarketCacheUpdateResult(emptyList(), emptyList())
            }

            val maxDays = targets.maxOf { it.days }
            emit(onProgress, "准备更新 ${targets.size} 只股票，最多读取最近 ${maxDays} 天K线并合并到缓存...")
            val completed = AtomicInteger(0)
            val success = AtomicInteger(0)
            val sinaSuccess = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val fallback = AtomicInteger(0)
            val abortEarly = AtomicBoolean(false)
            val lastError = AtomicReference("")
            val failures = mutableListOf<MarketCacheUpdateFailure>()
            val total = targets.size
            val nextIndex = AtomicInteger(0)

            val result = coroutineScope {
                List(minOf(CACHE_UPDATE_CONCURRENCY, total)) {
                    async {
                        val workerResults = mutableListOf<MarketCacheStockBars>()
                        while (!abortEarly.get()) {
                            val index = nextIndex.getAndIncrement()
                            if (index >= total) break
                            val target = targets[index]
                            val stock = target.candidate.toDirectStock()
                            try {
                                val barResult = loadCacheBarsWithFallback(stock, target.days)
                                val bars = barResult.bars
                                if (bars.isEmpty()) {
                                    failed.incrementAndGet()
                                    synchronized(failures) {
                                        failures += MarketCacheUpdateFailure(
                                            candidate = target.candidate,
                                            errorMessage = "K线返回空数据",
                                        )
                                    }
                                } else {
                                    success.incrementAndGet()
                                    if (barResult.source == MarketSource.SINA) {
                                        sinaSuccess.incrementAndGet()
                                    } else {
                                        fallback.incrementAndGet()
                                    }
                                    workerResults += MarketCacheStockBars(
                                        stock = stock.toCacheStockRecord(currentPrice = bars.lastOrNull()?.close ?: 0.0),
                                        bars = bars,
                                        source = barResult.source.name.lowercase(),
                                    )
                                }
                            } catch (error: Exception) {
                                val message = error.message ?: error::class.java.simpleName
                                lastError.compareAndSet("", message)
                                failed.incrementAndGet()
                                synchronized(failures) {
                                    failures += MarketCacheUpdateFailure(
                                        candidate = target.candidate,
                                        errorMessage = message,
                                    )
                                }
                            } finally {
                                val done = completed.incrementAndGet()
                                if (done >= CACHE_EARLY_FAILURE_CHECK_COUNT && success.get() == 0) {
                                    abortEarly.set(true)
                                }
                                if (done == total || done % 50 == 0) {
                                    emit(
                                        onProgress,
                                        "缓存更新K线 $done/$total，成功 ${success.get()}（新浪 ${sinaSuccess.get()}，备用腾讯 ${fallback.get()}），失败 ${failed.get()}，剩余约 ${
                                            estimateRemaining(done, total, startedAt)
                                        }。",
                                    )
                                }
                            }
                        }
                        workerResults
                    }
                }.awaitAll().flatten()
            }

            if (result.isEmpty()) {
                val detail = lastError.get().ifBlank { "没有任何K线源返回有效数据" }
                error("K线读取全部失败，已提前停止。新浪和备用腾讯都不可用。最近错误：$detail")
            }

            emit(
                onProgress,
                "缓存更新K线读取完成：成功 ${success.get()} 只（新浪 ${sinaSuccess.get()}，备用腾讯 ${fallback.get()}），失败 ${failed.get()} 只。",
            )
            MarketCacheUpdateResult(
                updates = result,
                failures = failures,
            )
        }
    }

    private suspend fun loadStocks(
        config: StrategyConfig,
        onProgress: suspend (String) -> Unit,
    ): List<DirectStock> {
        return try {
            loadSinaStocks(config, onProgress)
        } catch (error: Exception) {
            emit(onProgress, "新浪列表读取失败：${error.message ?: "未知错误"}。正在尝试腾讯报价备用列表...")
            loadTencentQuoteStocks(config, onProgress)
        }
    }

    private suspend fun loadSinaStocks(
        config: StrategyConfig,
        onProgress: suspend (String) -> Unit,
    ): List<DirectStock> {
        val total = fetchText(
            url = LIST_COUNT_URL,
            params = mapOf("node" to "hs_a"),
            referer = "https://finance.sina.com.cn/",
            sourceName = "新浪列表数量",
        ).trim().trim('"').toIntOrNull() ?: 0
        val pages = (total + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE
        val result = mutableListOf<DirectStock>()

        for (page in 1..pages) {
            val json = fetchText(
                url = LIST_URL,
                params = mapOf(
                    "page" to page.toString(),
                    "num" to LIST_PAGE_SIZE.toString(),
                    "sort" to "symbol",
                    "asc" to "1",
                    "node" to "hs_a",
                    "symbol" to "",
                    "_s_r_a" to "page",
                ),
                referer = "https://finance.sina.com.cn/",
                sourceName = "新浪股票列表第 $page 页",
            )
            val type = object : TypeToken<List<SinaStockItem>>() {}.type
            val items: List<SinaStockItem> = gson.fromJson(json, type) ?: emptyList()
            items.mapNotNullTo(result) { item ->
                val symbol = item.symbol ?: return@mapNotNullTo null
                val code = item.code ?: return@mapNotNullTo null
                val name = item.name ?: code
                val amount = item.amount ?: 0.0
                val trade = item.trade?.toDoubleOrNull() ?: 0.0

                if (!symbol.startsWith("sh") && !symbol.startsWith("sz")) return@mapNotNullTo null
                if (code.length != 6) return@mapNotNullTo null
                if (name.contains("ST", ignoreCase = true)) return@mapNotNullTo null
                if (trade <= 0.0 || amount < config.minAmount) return@mapNotNullTo null

                DirectStock(
                    symbol = symbol,
                    currentAmount = amount,
                    profile = StockProfile(
                        tsCode = "${code}.${if (symbol.startsWith("sh")) "SH" else "SZ"}",
                        name = name,
                        market = marketFromCode(code, symbol),
                        listDate = "",
                        isSt = false,
                    ),
                )
            }

            if (page == pages || page % 10 == 0) {
                emit(onProgress, "新浪列表读取 $page/$pages 页，保留 ${result.size} 只...")
            }
            delay(120L)
        }

        return result.filter { it.profile.market != MarketSegment.UNKNOWN }
    }

    private suspend fun loadTencentQuoteStocks(
        config: StrategyConfig,
        onProgress: suspend (String) -> Unit,
    ): List<DirectStock> {
        val symbols = generatedTencentSymbols()
        val chunks = symbols.chunked(80)
        val result = mutableListOf<DirectStock>()

        chunks.forEachIndexed { index, chunk ->
            val text = fetchRawText(
                fullUrl = TENCENT_QUOTE_URL + chunk.joinToString(","),
                referer = "https://gu.qq.com/",
                sourceName = "腾讯报价备用列表第 ${index + 1} 批",
            )
            parseTencentQuoteText(text, config, result)
            if (index + 1 == chunks.size || (index + 1) % 10 == 0) {
                emit(
                    onProgress,
                    "腾讯备用列表读取 ${index + 1}/${chunks.size} 批，保留 ${result.size} 只...",
                )
            }
            delay(180L)
        }

        return result.distinctBy { it.symbol }.filter { it.profile.market != MarketSegment.UNKNOWN }
    }

    private suspend fun loadBarsWithFallback(stock: DirectStock): BarLoadResult {
        val sinaResult = runCatching {
            waitBeforeStockRequest()
            BarLoadResult(loadSinaBars(stock), MarketSource.SINA)
        }.getOrNull()
        if (sinaResult != null && sinaResult.bars.size >= 260) {
            return sinaResult
        }

        waitBeforeStockRequest()
        val tencentBars = loadTencentBars(stock)
        return BarLoadResult(tencentBars, MarketSource.TENCENT)
    }

    private suspend fun loadCacheBarsWithFallback(stock: DirectStock, days: Int): BarLoadResult {
        var sinaError: Throwable? = null
        val sinaResult = runCatching {
            waitBeforeCacheUpdateRequest()
            BarLoadResult(loadSinaBars(stock, days), MarketSource.SINA)
        }.onFailure { error ->
            sinaError = error
        }.getOrNull()
        if (sinaResult != null && sinaResult.bars.isNotEmpty()) {
            return sinaResult
        }

        val tencentResult = runCatching {
            waitBeforeCacheUpdateRequest()
            BarLoadResult(loadTencentBars(stock, days), MarketSource.TENCENT)
        }.getOrElse { tencentError ->
            throw IllegalStateException(
                "新浪K线${sinaError?.message ?: "返回空数据"}；腾讯K线${tencentError.message ?: "请求失败"}",
            )
        }
        if (tencentResult.bars.isEmpty()) {
            throw IllegalStateException("新浪K线${sinaError?.message ?: "返回空数据"}；腾讯K线返回空数据")
        }
        return tencentResult
    }

    private suspend fun loadSinaBars(stock: DirectStock, days: Int = 280): List<DailyBar> {
        val json = fetchText(
            url = SINA_KLINE_URL,
            params = mapOf(
                "symbol" to stock.symbol,
                "scale" to "240",
                "ma" to "no",
                "datalen" to days.toString(),
            ),
            referer = "https://finance.sina.com.cn/",
            sourceName = "新浪K线 ${stock.symbol}",
        )
        val type = object : TypeToken<List<SinaKLineItem>>() {}.type
        val items: List<SinaKLineItem> = gson.fromJson(json, type) ?: emptyList()

        return buildDailyBars(
            stock = stock,
            rows = items.mapNotNull { item ->
                val day = item.day ?: return@mapNotNull null
                KLineRow(
                    day = day,
                    open = item.open,
                    close = item.close,
                    high = item.high,
                    low = item.low,
                    volume = item.volume,
                )
            },
            limit = days,
        )
    }

    private suspend fun loadTencentBars(stock: DirectStock, days: Int = 280): List<DailyBar> {
        val json = fetchText(
            url = TENCENT_KLINE_URL,
            params = mapOf("param" to "${stock.symbol},day,,,$days,qfq"),
            referer = "https://gu.qq.com/",
            sourceName = "腾讯K线 ${stock.symbol}",
        )
        val root = gson.fromJson(json, JsonObject::class.java)
        val data = root.getAsJsonObject("data")?.getAsJsonObject(stock.symbol) ?: return emptyList()
        val rows = data.getAsJsonArray("qfqday")
            ?: data.getAsJsonArray("day")
            ?: JsonArray()

        return buildDailyBars(
            stock = stock,
            rows = rows.mapNotNull { element ->
                val row = element.asJsonArray
                if (row.size() < 6) return@mapNotNull null
                KLineRow(
                    day = row[0].asString,
                    open = row[1].asString,
                    close = row[2].asString,
                    high = row[3].asString,
                    low = row[4].asString,
                    volume = row[5].asString,
                )
            },
            limit = days,
        )
    }

    private fun buildDailyBars(stock: DirectStock, rows: List<KLineRow>, limit: Int): List<DailyBar> {
        var previousClose: Double? = null
        return rows.mapNotNull { row ->
            val tradeDate = row.day.replace("-", "")
            val open = row.open?.toDoubleOrNull() ?: return@mapNotNull null
            val high = row.high?.toDoubleOrNull() ?: return@mapNotNull null
            val low = row.low?.toDoubleOrNull() ?: return@mapNotNull null
            val close = row.close?.toDoubleOrNull() ?: return@mapNotNull null
            val volume = row.volume?.toDoubleOrNull() ?: 0.0
            if (open <= 0.0 || close <= 0.0) return@mapNotNull null

            val preClose = previousClose ?: open
            previousClose = close
            DailyBar(
                tsCode = stock.profile.tsCode,
                tradeDate = tradeDate,
                open = open,
                high = high,
                low = low,
                close = close,
                preClose = preClose,
                pctChg = if (preClose > 0.0) (close - preClose) / preClose * 100.0 else 0.0,
                volume = volume,
                amount = stock.currentAmount,
            )
        }.takeLast(limit)
    }

    private suspend fun fetchText(
        url: String,
        params: Map<String, String>,
        referer: String,
        sourceName: String,
    ): String {
        val fullUrl = buildString {
            append(url)
            append('?')
            append(
                params.entries.joinToString("&") { (key, value) ->
                    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
                },
            )
        }
        return fetchRawText(fullUrl, referer, sourceName)
    }

    private suspend fun fetchRawText(fullUrl: String, referer: String, sourceName: String): String {
        var lastError: Throwable? = null
        repeat(RETRY_COUNT) { attempt ->
            try {
                val request = Request.Builder()
                    .url(fullUrl)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
                    )
                    .header("Referer", referer)
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return response.body?.string().orEmpty()
                    }
                    lastError = IllegalStateException("HTTP ${response.code}")
                }
            } catch (error: Exception) {
                lastError = error
            }
            if (attempt < RETRY_COUNT - 1) {
                delay((attempt + 1) * 900L)
            }
        }
        error("$sourceName 请求失败：${lastError?.message ?: "未知错误"}")
    }

    private suspend fun waitBeforeStockRequest() {
        requestGate.withLock {
            val now = System.currentTimeMillis()
            val waitMs = PER_STOCK_DELAY_MS - (now - lastStockRequestAt)
            if (waitMs > 0) {
                delay(waitMs)
            }
            lastStockRequestAt = System.currentTimeMillis()
        }
    }

    private suspend fun waitBeforeCacheUpdateRequest() {
        requestGate.withLock {
            val now = System.currentTimeMillis()
            val waitMs = CACHE_UPDATE_DELAY_MS - (now - lastCacheUpdateRequestAt)
            if (waitMs > 0) {
                delay(waitMs)
            }
            lastCacheUpdateRequestAt = System.currentTimeMillis()
        }
    }

    private fun parseTencentQuoteText(
        text: String,
        config: StrategyConfig,
        result: MutableList<DirectStock>,
    ) {
        val regex = Regex("""v_(sh|sz)(\d{6})="([^"]*)";""")
        regex.findAll(text).forEach { match ->
            val symbol = match.groupValues[1] + match.groupValues[2]
            val code = match.groupValues[2]
            val parts = match.groupValues[3].split("~")
            val name = parts.getOrNull(1)?.trim().orEmpty()
            val trade = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
            val volume = parts.getOrNull(6)?.toDoubleOrNull() ?: 0.0
            val amount = (parts.getOrNull(37)?.toDoubleOrNull() ?: 0.0) * 10_000.0

            if (name.isBlank()) return@forEach
            if (name.contains("ST", ignoreCase = true) || name.contains("退") || name.startsWith("PT")) return@forEach
            if (trade <= 0.0 || volume <= 0.0 || amount < config.minAmount) return@forEach

            result += DirectStock(
                symbol = symbol,
                currentAmount = amount,
                profile = StockProfile(
                    tsCode = "${code}.${if (symbol.startsWith("sh")) "SH" else "SZ"}",
                    name = name,
                    market = marketFromCode(code, symbol),
                    listDate = "",
                    isSt = false,
                ),
            )
        }
    }

    private fun generatedTencentSymbols(): List<String> = buildList {
        fun addRange(prefix: String, start: Int, end: Int) {
            for (code in start..end) {
                add(prefix + code.toString().padStart(6, '0'))
            }
        }

        addRange("sh", 600000, 605999)
        addRange("sh", 688000, 689999)
        addRange("sz", 0, 3999)
        addRange("sz", 300000, 302999)
    }

    private fun buildProgressText(
        completed: Int,
        total: Int,
        success: Int,
        failed: Int,
        fallback: Int,
        startedAt: Long,
    ): String {
        val eta = estimateRemaining(completed, total, startedAt)
        return "K线读取 $completed/$total，成功 $success，失败 $failed，备用腾讯 $fallback，剩余约 $eta。"
    }

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

    private fun marketFromCode(code: String, symbol: String): MarketSegment =
        when {
            code.startsWith("300") || code.startsWith("301") || code.startsWith("302") -> MarketSegment.CHINEXT
            code.startsWith("688") || code.startsWith("689") -> MarketSegment.STAR
            symbol.startsWith("sh") || symbol.startsWith("sz") -> MarketSegment.MAIN
            else -> MarketSegment.UNKNOWN
        }

    private fun DirectStock.toCacheStockRecord(currentPrice: Double): MarketCacheStockRecord =
        MarketCacheStockRecord(
            symbol = symbol,
            code = profile.tsCode.substringBefore('.'),
            tsCode = profile.tsCode,
            name = profile.name,
            market = profile.market,
            isSt = profile.isSt,
            currentPrice = currentPrice,
            currentAmount = currentAmount,
        )

    private fun MarketCacheStockCandidate.toDirectStock(): DirectStock =
        DirectStock(
            symbol = symbol,
            currentAmount = stock.currentAmount,
            profile = StockProfile(
                tsCode = stock.tsCode,
                name = stock.name,
                market = stock.market,
                listDate = "",
                isSt = stock.isSt,
            ),
        )

    private enum class MarketSource {
        SINA,
        TENCENT,
    }

    private data class BarLoadResult(
        val bars: List<DailyBar>,
        val source: MarketSource,
    )

    private data class DirectStock(
        val symbol: String,
        val currentAmount: Double,
        val profile: StockProfile,
    )

    private data class KLineRow(
        val day: String,
        val open: String?,
        val close: String?,
        val high: String?,
        val low: String?,
        val volume: String?,
    )

    private data class SinaStockItem(
        val symbol: String?,
        val code: String?,
        val name: String?,
        val trade: String?,
        val amount: Double?,
    )

    private data class SinaKLineItem(
        val day: String?,
        val open: String?,
        val high: String?,
        val low: String?,
        val close: String?,
        @SerializedName("volume") val volume: String?,
    )
}
