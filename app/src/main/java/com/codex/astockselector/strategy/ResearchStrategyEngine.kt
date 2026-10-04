package com.codex.astockselector.strategy

import com.codex.astockselector.model.DailyBar
import com.codex.astockselector.model.RuleCheck
import com.codex.astockselector.model.SignalLevel
import com.codex.astockselector.model.StockProfile
import com.codex.astockselector.model.StrategyConfig
import com.codex.astockselector.model.StrategySignal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/** Fixed research predicates; scores indicate passed conditions, not win probability. */
object ResearchStrategyEngine {
    const val MIN_HISTORY = 120
    private const val PRICE_EVENT_LOOKBACK = 71

    fun isCoarseCandidate(bars: List<DailyBar>): Boolean {
        if (bars.size < MIN_HISTORY) return false
        val today = bars.last()
        return today.close > today.open &&
            today.close > bars[bars.lastIndex - 1].high &&
            strength(today) >= 0.60
    }

    fun evaluate(stock: StockProfile, bars: List<DailyBar>, config: StrategyConfig): List<StrategySignal> {
        if (stock.isSt || bars.size < MIN_HISTORY) return emptyList()
        if (!bars.last().amount.isFinite() || bars.last().amount < config.minAmount) return emptyList()
        if (!validHistory(stock, bars) || !isCoarseCandidate(bars)) return emptyList()
        val features = Features(bars)
        return listOfNotNull(
            panicRepair(stock, features),
            trendEngulf(stock, features),
            pullbackRestart(stock, features),
        )
    }

    private fun validHistory(stock: StockProfile, bars: List<DailyBar>): Boolean {
        val history = bars.takeLast(MIN_HISTORY)
        if (!history.all { bar ->
                val numbers = listOf(bar.open, bar.high, bar.low, bar.close, bar.volume)
                val date = runCatching { LocalDate.parse(bar.tradeDate, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
                numbers.all { it.isFinite() } &&
                    minOf(bar.open, bar.high, bar.low, bar.close, bar.volume) > 0.0 &&
                    bar.high + 1e-8 >= maxOf(bar.open, bar.close) &&
                    bar.low - 1e-8 <= minOf(bar.open, bar.close) &&
                    bar.high >= bar.low && date != null &&
                    date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
            }) return false
        if (!history.zipWithNext().all { (previous, current) -> previous.tradeDate < current.tradeDate }) return false
        val rate = stock.market.limitUpPct / 100.0
        val start = maxOf(1, bars.size - PRICE_EVENT_LOOKBACK)
        return (start..bars.lastIndex).none { index ->
            val previous = bars[index - 1].close
            val bar = bars[index]
            previous <= 0.0 ||
                bar.high > previous * (1.0 + rate + 0.01) ||
                bar.low < previous * (1.0 - rate - 0.01)
        }
    }

    private fun panicRepair(stock: StockProfile, f: Features): StrategySignal? {
        val t = f.bars.lastIndex
        val today = f.bars[t]
        val ratio = today.volume / f.previousVolume(t)
        if (!(today.close > today.open && today.close > f.bars[t - 1].high &&
                ratio in 0.8..3.0 && strength(today) >= 0.65)) return null
        for (anchor in t - 7..t - 2) {
            val bar = f.bars[anchor]
            val atr = f.atrBefore[anchor]
            val fall = f.bars[anchor - 1].close - bar.close
            if (!atr.isFinite() || atr <= 0.0 || bar.close >= bar.open || fall < 1.5 * atr) continue
            if (bar.low > f.bars.subList(anchor - 19, anchor + 1).minOf { it.low }) continue
            if (f.bars.subList(anchor + 1, t + 1).minOf { it.low } < bar.low - 0.1 * atr) continue
            if (today.close < bar.close + 0.60 * fall || today.close - bar.low > 3.0 * atr) continue
            return signal(
                stock, today, "恐慌修复",
                listOf(
                    "前2至7个交易日出现恐慌阴线，收盘下跌至少1.5个起点ATR",
                    "恐慌低点创当时20日新低，后续低点未明显失守",
                    "收盘修复恐慌跌幅至少60%，距恐慌低点不超过3个起点ATR",
                    "阳线突破昨日最高价，收盘强度至少65%，量比0.8至3倍",
                ),
                listOf("恐慌日" to bar.tradeDate, "修复比例" to percent((today.close - bar.close) / fall),
                    "距恐慌低点" to "${number((today.close - bar.low) / atr)} ATR", "量比" to "${number(ratio)}x"),
                "形态确认参考：收盘修复恐慌跌幅并突破前一日高点。",
                "形态失效参考：跌破恐慌阴线低点。",
            )
        }
        return null
    }

    private fun trendEngulf(stock: StockProfile, f: Features): StrategySignal? {
        val t = f.bars.lastIndex
        val today = f.bars[t]
        val ratio = today.volume / f.previousVolume(t)
        // The recommended ablation keeps anchor volume, not a signal-day volume floor.
        if (!(today.close > today.open && today.close > f.bars[t - 1].high &&
                strength(today) >= 0.65 && ratio <= 3.0)) return null
        for (anchor in t - 5 until t) {
            val bar = f.bars[anchor]
            val atr = f.atrBefore[anchor]
            if (!atr.isFinite() || atr <= 0.0 || !f.trend(anchor - 1)) continue
            if (!(f.bars[anchor - 1].close >= f.ma20[anchor - 1] &&
                    bar.open - bar.close >= 0.8 * atr &&
                    bar.volume >= 1.2 * f.previousVolume(anchor) && today.close >= bar.open)) continue
            if (f.bars.subList(anchor, t + 1).minOf { it.low } < f.ma60[anchor - 1] - 0.5 * atr) continue
            if (today.close - f.ma20[t] > 1.5 * atr) continue
            return signal(
                stock, today, "趋势反包",
                listOf(
                    "回调前MA20高于MA60，两条均线均不低于5日前",
                    "前1至5日出现实体至少0.8个起点ATR、量比至少1.2倍的阴线",
                    "阳线收盘覆盖回调阴线开盘价，并突破昨日最高价",
                    "结构低点守住起点MA60下方0.5个ATR，收盘最多高于MA20 1.5个ATR",
                    "收盘强度至少65%，当日量比不超过3倍，不要求放量下限",
                ),
                listOf("回调日" to bar.tradeDate, "距MA20" to "${number((today.close - f.ma20[t]) / atr)} ATR",
                    "收盘强度" to percent(strength(today)), "量比" to "${number(ratio)}x"),
                "形态确认参考：趋势中阳线收盘覆盖回调阴线开盘价。",
                "形态失效参考：结构低点失守或趋势支撑明显下移。",
            )
        }
        return null
    }

    private fun pullbackRestart(stock: StockProfile, f: Features): StrategySignal? {
        val t = f.bars.lastIndex
        val today = f.bars[t]
        val ratio = today.volume / f.previousVolume(t)
        if (!(f.trend(t) && today.close > today.open && today.close > f.ma20[t] &&
                today.close > maxOf(f.bars[t - 2].high, f.bars[t - 1].high) &&
                strength(today) >= 0.60 && ratio in 1.1..2.5)) return null
        for (length in 3..12) {
            val anchor = t - length - 1
            val bar = f.bars[anchor]
            val atr = f.atrBefore[anchor]
            if (!atr.isFinite() || atr <= 0.0 || !f.trend(anchor)) continue
            if (bar.close < f.bars.subList(anchor - 19, anchor + 1).maxOf { it.close }) continue
            val correction = f.bars.subList(anchor + 1, t)
            val depth = bar.close - correction.minOf { it.low }
            if (depth !in 0.5 * atr..2.0 * atr) continue
            if (correction.sumOf { it.volume } / length > 0.85 * f.volume5[anchor]) continue
            if ((anchor + 1 until t).any { index -> f.bars[index].low < f.ma20[index] - 0.5 * atr }) continue
            if (today.close - f.ma20[t] > 1.0 * atr) continue
            return signal(
                stock, today, "缩量再起",
                listOf(
                    "当前及起点均为MA20高于MA60、两条均线不下降的趋势",
                    "起点收盘创当时20日新高，随后回调3至12个交易日",
                    "回调深度0.5至2个起点ATR，回调均量不超过起点5日均量85%",
                    "回调低点守住各日MA20下方0.5个起点ATR",
                    "阳线站上MA20并突破前两日高点，收盘最多高于MA20 1个起点ATR",
                    "收盘强度至少60%，当日量比1.1至2.5倍",
                ),
                listOf("回调天数" to "$length", "回调深度" to "${number(depth / atr)} ATR",
                    "距MA20" to "${number((today.close - f.ma20[t]) / atr)} ATR", "量比" to "${number(ratio)}x"),
                "形态确认参考：缩量回调后阳线重新突破前两日高点。",
                "形态失效参考：回调低点失守或MA20支撑明显下移。",
            )
        }
        return null
    }

    private class Features(val bars: List<DailyBar>) {
        val ma20 = average(bars.map { it.close }, 20)
        val ma60 = average(bars.map { it.close }, 60)
        val volume5 = average(bars.map { it.volume }, 5)
        val atrBefore: DoubleArray

        init {
            val ranges = bars.mapIndexed { index, bar ->
                if (index == 0) bar.high - bar.low else max(
                    bar.high - bar.low,
                    max(abs(bar.high - bars[index - 1].close), abs(bar.low - bars[index - 1].close)),
                )
            }
            val atr = average(ranges, 20)
            atrBefore = DoubleArray(bars.size) { index -> if (index == 0) Double.NaN else atr[index - 1] }
        }

        fun previousVolume(index: Int): Double = volume5[index - 1]

        fun trend(index: Int): Boolean = index >= 65 && ma20[index] > ma60[index] &&
            ma20[index] >= ma20[index - 5] && ma60[index] >= ma60[index - 5]
    }

    private fun average(values: List<Double>, period: Int): DoubleArray {
        var running = 0.0
        var invalid = 0
        return DoubleArray(values.size) { index ->
            if (values[index].isFinite()) running += values[index] else invalid++
            if (index >= period) {
                val outgoing = values[index - period]
                if (outgoing.isFinite()) running -= outgoing else invalid--
            }
            if (index + 1 >= period && invalid == 0) running / period else Double.NaN
        }
    }

    private fun strength(bar: DailyBar): Double =
        if (bar.high > bar.low) round((bar.close - bar.low) / (bar.high - bar.low) * 1e12) / 1e12 else 0.0

    private fun signal(
        stock: StockProfile,
        today: DailyBar,
        name: String,
        reasons: List<String>,
        metrics: List<Pair<String, String>>,
        trigger: String,
        risk: String,
    ): StrategySignal = StrategySignal(
        tradeDate = today.tradeDate, stock = stock, strategy = name, score = 100,
        level = SignalLevel.NORMAL, reasons = reasons, metrics = metrics,
        buyTrigger = trigger, stopLoss = risk, ruleChecks = reasons.map { RuleCheck(it, true) },
    )

    private fun number(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun percent(value: Double): String = "${number(value * 100.0)}%"
}
