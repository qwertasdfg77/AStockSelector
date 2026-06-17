package com.codex.astockselector.data

import com.codex.astockselector.model.DailyBar
import com.codex.astockselector.model.MarketSegment
import com.codex.astockselector.model.StockProfile
import com.codex.astockselector.model.StrategyConfig
import com.codex.astockselector.model.StrategySignal
import com.codex.astockselector.strategy.StrategyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TushareRepository {
    private const val DAILY_FIELDS = "ts_code,trade_date,open,high,low,close,pre_close,pct_chg,vol,amount"
    private const val STOCK_FIELDS = "ts_code,name,list_date"
    private const val CALENDAR_FIELDS = "cal_date,is_open"
    private val dateFormatter = DateTimeFormatter.BASIC_ISO_DATE

    suspend fun loadRealSignals(
        token: String,
        config: StrategyConfig,
        onProgress: suspend (String) -> Unit = {},
    ): List<StrategySignal> {
        val cleanToken = token.trim()
        require(cleanToken.isNotBlank()) { "请先填写 Tushare Token" }

        return withContext(Dispatchers.IO) {
            val api = TushareClient.create()
            emit(onProgress, "正在读取A股列表...")
            val stocks = loadStocks(api, cleanToken)
            if (stocks.isEmpty()) {
                error("Tushare 未返回股票列表，请检查 Token 权限")
            }

            emit(onProgress, "正在读取最近交易日...")
            val tradeDates = loadTradeDates(api, cleanToken, minTradingDays = 280)
            if (tradeDates.size < 260) {
                error("最近交易日不足 260 天，无法计算 MA250")
            }

            emit(onProgress, "开始拉取日线：约 ${tradeDates.size} 个交易日，首次可能需要几分钟")
            val barsByCode = mutableMapOf<String, MutableList<DailyBar>>()
            tradeDates.forEachIndexed { index, tradeDate ->
                val dailyBars = loadDailyBars(api, cleanToken, tradeDate)
                dailyBars.forEach { bar ->
                    barsByCode.getOrPut(bar.tsCode) { mutableListOf() }.add(bar)
                }
                emit(onProgress, "日线下载 ${index + 1}/${tradeDates.size}：$tradeDate")
                delay(120)
            }

            emit(onProgress, "正在计算选股信号...")
            val signals = stocks.values
                .asSequence()
                .flatMap { stock ->
                    StrategyEngine.evaluate(
                        stock = stock,
                        bars = barsByCode[stock.tsCode].orEmpty(),
                        config = config,
                    ).asSequence()
                }
                .sortedWith(compareByDescending<StrategySignal> { it.score }.thenBy { it.stock.tsCode })
                .toList()

            emit(onProgress, "真实数据更新完成：${signals.size} 条信号")
            signals
        }
    }

    private suspend fun loadStocks(api: TushareApi, token: String): Map<String, StockProfile> {
        val response = api.query(
            TushareRequest(
                api_name = "stock_basic",
                token = token,
                params = mapOf("list_status" to "L"),
                fields = STOCK_FIELDS,
            ),
        ).requireOk("stock_basic")

        return response.rows().mapNotNull { row ->
            val tsCode = row.text("ts_code") ?: return@mapNotNull null
            val name = row.text("name") ?: tsCode
            tsCode to StockProfile(
                tsCode = tsCode,
                name = name,
                market = marketFromTsCode(tsCode),
                listDate = row.text("list_date").orEmpty(),
                isSt = name.contains("ST", ignoreCase = true),
            )
        }.toMap()
    }

    private suspend fun loadTradeDates(
        api: TushareApi,
        token: String,
        minTradingDays: Int,
    ): List<String> {
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val start = today.minusDays(560)
        val response = api.query(
            TushareRequest(
                api_name = "trade_cal",
                token = token,
                params = mapOf(
                    "exchange" to "",
                    "start_date" to start.format(dateFormatter),
                    "end_date" to today.format(dateFormatter),
                ),
                fields = CALENDAR_FIELDS,
            ),
        ).requireOk("trade_cal")

        return response.rows()
            .filter { it.number("is_open")?.toInt() == 1 || it.text("is_open") == "1" }
            .mapNotNull { it.text("cal_date") }
            .sorted()
            .takeLast(minTradingDays)
    }

    private suspend fun loadDailyBars(
        api: TushareApi,
        token: String,
        tradeDate: String,
    ): List<DailyBar> {
        val response = api.query(
            TushareRequest(
                api_name = "daily",
                token = token,
                params = mapOf("trade_date" to tradeDate),
                fields = DAILY_FIELDS,
            ),
        ).requireOk("daily $tradeDate")

        return response.rows().mapNotNull { row ->
            val tsCode = row.text("ts_code") ?: return@mapNotNull null
            val amountInThousandYuan = row.number("amount") ?: return@mapNotNull null
            DailyBar(
                tsCode = tsCode,
                tradeDate = row.text("trade_date") ?: tradeDate,
                open = row.number("open") ?: return@mapNotNull null,
                high = row.number("high") ?: return@mapNotNull null,
                low = row.number("low") ?: return@mapNotNull null,
                close = row.number("close") ?: return@mapNotNull null,
                preClose = row.number("pre_close") ?: return@mapNotNull null,
                pctChg = row.number("pct_chg") ?: return@mapNotNull null,
                volume = row.number("vol") ?: 0.0,
                amount = amountInThousandYuan * 1000.0,
            )
        }
    }

    private suspend fun emit(onProgress: suspend (String) -> Unit, message: String) {
        withContext(Dispatchers.Main.immediate) {
            onProgress(message)
        }
    }

    private fun TushareResponse.requireOk(label: String): TushareTable {
        if (code != 0) {
            error("$label 调用失败：${msg ?: "未知错误"}")
        }
        return data ?: error("$label 未返回数据")
    }

    private fun TushareTable.rows(): List<TushareRow> =
        items.map { item -> TushareRow(fields.zip(item).toMap()) }

    private fun marketFromTsCode(tsCode: String): MarketSegment =
        when {
            tsCode.endsWith(".BJ") -> MarketSegment.BSE
            tsCode.startsWith("300") || tsCode.startsWith("301") -> MarketSegment.CHINEXT
            tsCode.startsWith("688") || tsCode.startsWith("689") -> MarketSegment.STAR
            tsCode.endsWith(".SH") || tsCode.endsWith(".SZ") -> MarketSegment.MAIN
            else -> MarketSegment.UNKNOWN
        }

    private data class TushareRow(private val values: Map<String, Any?>) {
        fun text(name: String): String? =
            values[name]?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" }

        fun number(name: String): Double? =
            when (val value = values[name]) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
    }
}
