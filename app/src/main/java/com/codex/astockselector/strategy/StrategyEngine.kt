package com.codex.astockselector.strategy

import com.codex.astockselector.model.DailyBar
import com.codex.astockselector.model.EnrichedBar
import com.codex.astockselector.model.RuleCheck
import com.codex.astockselector.model.SignalLevel
import com.codex.astockselector.model.StockProfile
import com.codex.astockselector.model.StrategyConfig
import com.codex.astockselector.model.StrategySignal
import kotlin.math.abs

object StrategyEngine {
    fun evaluate(
        stock: StockProfile,
        bars: List<DailyBar>,
        config: StrategyConfig = StrategyConfig(),
    ): List<StrategySignal> {
        if (stock.isSt || bars.size < 260) return emptyList()

        val sorted = bars.sortedBy { it.tradeDate }
        val enriched = enrich(sorted)
        return listOfNotNull(
            evaluateFirstBoard(stock, enriched, config),
            evaluateNineYang(stock, enriched, config),
            evaluateGameKLine(stock, enriched, config),
            evaluateLowLevelStart(stock, sorted, enriched, config),
        ).sortedWith(compareByDescending<StrategySignal> { it.level.ordinal * -1 }.thenByDescending { it.score })
    }

    private fun enrich(bars: List<DailyBar>): List<EnrichedBar> {
        val closeSums = bars.runningCloseSums()
        return bars.mapIndexed { index, bar ->
            EnrichedBar(
                bar = bar,
                ma5 = closeSums.movingAverage(index, 5),
                ma10 = closeSums.movingAverage(index, 10),
                ma20 = closeSums.movingAverage(index, 20),
                ma60 = closeSums.movingAverage(index, 60),
                ma250 = closeSums.movingAverage(index, 250),
            )
        }
    }

    private fun evaluateFirstBoard(
        stock: StockProfile,
        bars: List<EnrichedBar>,
        config: StrategyConfig,
    ): StrategySignal? {
        val today = bars.last()
        val yesterday = bars[bars.lastIndex - 1]
        val ma250 = today.ma250 ?: return null
        val yMa250 = yesterday.ma250 ?: return null
        val threshold = stock.market.limitUpPct * config.nearLimitRatio
        val recent = bars.dropLast(1).takeLast(config.firstBoardLookback)
        val avgVolume5 = bars.dropLast(1).takeLast(5).map { it.bar.volume }.average()
        val limitPrice = today.bar.preClose * (1.0 + stock.market.limitUpPct / 100.0)
        val maDistance = abs(today.bar.close - ma250) / ma250
        val crossMa250 = today.bar.close > ma250 && yesterday.bar.close <= yMa250
        val pctNearLimit = today.bar.pctChg >= threshold
        if (!pctNearLimit) return null

        val noRecentNearLimit = recent.none { it.bar.pctChg >= threshold }
        val volumeGtRequired = today.bar.volume > avgVolume5 * config.volumeMultiplier
        val amountOk = today.bar.amount >= config.minAmount
        val notOneWord = today.bar.open < limitPrice * 0.995
        val maDistanceOk = maDistance <= config.maxFirstBoardMaDistancePct
        val crossOrNearMa250 = crossMa250 || maDistanceOk

        var score = 0
        val reasons = mutableListOf<String>()

        fun mark(condition: Boolean, points: Int, reason: String) {
            if (condition) {
                score += points
                reasons += reason
            }
        }

        mark(crossOrNearMa250, 30, "当天上穿MA250或收盘靠近年线")
        mark(pctNearLimit, 25, "涨幅接近${stock.market.displayName}涨停")
        mark(noRecentNearLimit, 20, "过去${config.firstBoardLookback}日无近涨停")
        mark(volumeGtRequired, 10, "成交量大于近5日均量${config.volumeMultiplier}倍")
        mark(amountOk, 5, "成交额达到流动性门槛")
        mark(notOneWord, 5, "非一字板")
        mark(maDistanceOk, 5, "收盘未远离年线")

        if (score < 80) return null

        return StrategySignal(
            tradeDate = today.bar.tradeDate,
            stock = stock,
            strategy = "年线首板",
            score = score.coerceAtMost(100),
            level = if (score >= 90) SignalLevel.STRONG else SignalLevel.NORMAL,
            reasons = reasons,
            metrics = listOf(
                "涨幅" to pct(today.bar.pctChg),
                "距MA250" to pct(maDistance * 100.0),
                "成交额" to amount(today.bar.amount),
                "量能" to "${format(today.bar.volume / avgVolume5)}x",
            ),
            buyTrigger = "次日强势高开、回踩首板实体中位不破，或再次封板确认。",
            stopLoss = "跌破首板K线最低价，或收盘跌回MA250下方。",
            ruleChecks = listOf(
                RuleCheck("涨幅达到近涨停阈值", pctNearLimit),
                RuleCheck("上穿MA250或距MA250不超${pct(config.maxFirstBoardMaDistancePct * 100.0)}", crossOrNearMa250),
                RuleCheck("过去${config.firstBoardLookback}日无近涨停", noRecentNearLimit),
                RuleCheck("成交量大于近5日均量${config.volumeMultiplier}倍", volumeGtRequired),
                RuleCheck("成交额达到门槛", amountOk),
                RuleCheck("非一字板", notOneWord),
                RuleCheck("收盘未远离年线", maDistanceOk),
            ),
        )
    }

    private fun evaluateNineYang(
        stock: StockProfile,
        bars: List<EnrichedBar>,
        config: StrategyConfig,
    ): StrategySignal? {
        val today = bars.last()
        val ma250 = today.ma250 ?: return null
        val last9 = bars.takeLast(9)
        val ma250FiveDaysAgo = bars[bars.lastIndex - 5].ma250 ?: return null
        val yangCount = last9.count { it.bar.close > it.bar.open }
        val multiYang = yangCount >= config.minNineYangYangCount
        val totalRisePct = (last9.last().bar.close - last9.first().bar.open) / last9.first().bar.open * 100.0
        val nearMaCount = last9.count { enriched ->
            val itemMa250 = enriched.ma250 ?: return@count false
            abs(enriched.bar.close - itemMa250) / itemMa250 <= config.nearMaPct
        }
        val riseNotHot = totalRisePct <= config.maxNineYangRisePct
        val nearMaCountOk = nearMaCount >= config.minNineYangNearMaCount
        val ma250FlatUp = ma250 >= ma250FiveDaysAgo
        val closeAboveMa250 = today.bar.close >= ma250

        var score = 0
        val reasons = mutableListOf<String>()

        fun mark(condition: Boolean, points: Int, reason: String) {
            if (condition) {
                score += points
                reasons += reason
            }
        }

        mark(multiYang, 30, "9日内至少${config.minNineYangYangCount}根阳线")
        mark(riseNotHot, 20, "9日累计涨幅未过热")
        mark(nearMaCountOk, 20, "多数K线在MA250附近")
        mark(ma250FlatUp, 15, "MA250走平或向上")
        mark(closeAboveMa250, 15, "当前仍在年线之上")

        if (score < config.nineYangMinScore) return null

        return StrategySignal(
            tradeDate = today.bar.tradeDate,
            stock = stock,
            strategy = "九阳蓄势",
            score = score.coerceAtMost(100),
            level = SignalLevel.NORMAL,
            reasons = reasons,
            metrics = listOf(
                "阳线天数" to "$yangCount/9",
                "9日涨幅" to pct(totalRisePct),
                "近年线天数" to "$nearMaCount/9",
                "距MA250" to pct(abs(today.bar.close - ma250) / ma250 * 100.0),
            ),
            buyTrigger = "不追连续多阳，等待回调到MA5、MA10或MA250附近不破，再出阳线。",
            stopLoss = "跌破MA250且收不回，或跌破9阳启动点。",
            ruleChecks = listOf(
                RuleCheck("9日内至少${config.minNineYangYangCount}根阳线", multiYang),
                RuleCheck("9日累计涨幅不过热", riseNotHot),
                RuleCheck("至少${config.minNineYangNearMaCount}根靠近MA250", nearMaCountOk),
                RuleCheck("MA250走平或向上", ma250FlatUp),
                RuleCheck("当前仍在年线之上", closeAboveMa250),
            ),
        )
    }

    private fun evaluateGameKLine(
        stock: StockProfile,
        bars: List<EnrichedBar>,
        config: StrategyConfig,
    ): StrategySignal? {
        val today = bars.last()
        val yesterday = bars[bars.lastIndex - 1]
        val denominator = yesterday.bar.open - yesterday.bar.close
        if (denominator <= 0.0) return null

        val previousBear = yesterday.bar.close < yesterday.bar.open
        val todayBull = today.bar.close > today.bar.open
        val reboundRatio = (today.bar.close - yesterday.bar.close) / denominator
        val yangBaoYin = today.bar.close >= yesterday.bar.open
        val closeRange = today.bar.high - today.bar.low
        val closeStrength = if (closeRange > 0.0) (today.bar.close - today.bar.low) / closeRange else 0.0
        val nearMa = listOfNotNull(today.ma250, today.ma60, today.ma20).any {
            abs(today.bar.close - it) / it <= config.gameKLineNearMaPct
        }
        val previousBearTodayBull = previousBear && todayBull
        val reboundGeHalf = reboundRatio >= config.reboundRatioThreshold
        val closeStrengthOk = closeStrength >= config.closeStrengthThreshold
        val volumeNotShrink = today.bar.volume >= yesterday.bar.volume * config.gameKLineVolumeRatio
        val strictGameKLine = previousBearTodayBull &&
            reboundGeHalf &&
            yangBaoYin &&
            nearMa &&
            closeStrengthOk &&
            volumeNotShrink

        var score = 0
        val reasons = mutableListOf<String>()

        fun mark(condition: Boolean, points: Int, reason: String) {
            if (condition) {
                score += points
                reasons += reason
            }
        }

        mark(previousBearTodayBull, 20, "前阴后阳")
        mark(reboundGeHalf, 25, "反弹修复前阴线${pct(config.reboundRatioThreshold * 100.0)}以上")
        mark(yangBaoYin, 20, "阳包阴")
        mark(nearMa, 20, "靠近MA250/MA60/MA20不超${pct(config.gameKLineNearMaPct * 100.0)}")
        mark(closeStrengthOk, 10, "收盘位置不低于${pct(config.closeStrengthThreshold * 100.0)}")
        mark(volumeNotShrink, 5, "成交量不低于昨日${format(config.gameKLineVolumeRatio)}倍")

        if (!strictGameKLine || score < config.gameKLineMinScore) return null

        return StrategySignal(
            tradeDate = today.bar.tradeDate,
            stock = stock,
            strategy = "博弈K",
            score = score.coerceAtMost(100),
            level = if (yangBaoYin && nearMa) SignalLevel.STRONG else SignalLevel.NORMAL,
            reasons = reasons,
            metrics = listOf(
                "反弹比例" to pct(reboundRatio * 100.0),
                "收盘强度" to pct(closeStrength * 100.0),
                "距MA250" to today.ma250?.let { pct(abs(today.bar.close - it) / it * 100.0) }.orEmpty(),
            ),
            buyTrigger = "次日突破博弈K线高点，或回踩实体中位不破。",
            stopLoss = "跌破博弈K线最低价，或跌破MA250。",
            ruleChecks = listOf(
                RuleCheck("前阴后阳", previousBearTodayBull),
                RuleCheck("反弹修复前阴线${pct(config.reboundRatioThreshold * 100.0)}以上", reboundGeHalf),
                RuleCheck("阳包阴", yangBaoYin),
                RuleCheck("靠近MA250/MA60/MA20不超${pct(config.gameKLineNearMaPct * 100.0)}", nearMa),
                RuleCheck("收盘位置不低于${pct(config.closeStrengthThreshold * 100.0)}", closeStrengthOk),
                RuleCheck("成交量不低于昨日${format(config.gameKLineVolumeRatio)}倍", volumeNotShrink),
            ),
        )
    }

    private fun evaluateLowLevelStart(
        stock: StockProfile,
        rawBars: List<DailyBar>,
        bars: List<EnrichedBar>,
        config: StrategyConfig,
    ): StrategySignal? {
        val today = bars.last()
        val last120 = rawBars.takeLast(120)
        val last30 = rawBars.takeLast(30)
        val last20 = rawBars.takeLast(20)
        val previous20 = rawBars.dropLast(20).takeLast(20)
        val previous5 = rawBars.dropLast(1).takeLast(5)
        if (last120.size < 120 || last30.size < 30 || last20.size < 20 || previous20.size < 20 || previous5.size < 5) {
            return null
        }

        val low120 = last120.minOf { it.low }
        val high30 = last30.maxOf { it.high }
        val low30 = last30.minOf { it.low }
        val high20 = last20.maxOf { it.high }
        val low20 = last20.minOf { it.low }
        val previous20Low = previous20.minOf { it.low }
        val avgVolume5 = previous5.map { it.volume }.average()
        val ma20 = today.ma20 ?: return null
        val ma20ThreeDaysAgo = rawBars.movingAverage(rawBars.lastIndex - 3, 20) ?: return null
        val ma60 = today.ma60 ?: return null
        val closeRange = today.bar.high - today.bar.low

        val amountOk = today.bar.amount >= config.minAmount
        val notOneWord = today.bar.preClose <= 0.0 || abs(today.bar.high - today.bar.low) / today.bar.preClose > 0.002
        val low120DistancePct = if (low120 > 0.0) (today.bar.close / low120 - 1.0) * 100.0 else Double.MAX_VALUE
        val low120DistanceOk = low120DistancePct in 0.0..25.0
        val low20VsPrevious20Pct = if (previous20Low > 0.0) (low20 / previous20Low - 1.0) * 100.0 else -Double.MAX_VALUE
        val lowNotBreakingDown = low20VsPrevious20Pct >= -3.0
        val amplitude30Pct = if (low30 > 0.0) (high30 / low30 - 1.0) * 100.0 else Double.MAX_VALUE
        val volatilityCompressed = amplitude30Pct <= 35.0
        val closeAboveMa20 = today.bar.close >= ma20
        val ma20FlatUp = ma20 >= ma20ThreeDaysAgo * 0.995
        val closeNearMa60 = today.bar.close >= ma60 * 0.97
        val volumeRatio5 = if (avgVolume5 > 0.0) today.bar.volume / avgVolume5 else 0.0
        val volumeBreakout = volumeRatio5 >= 1.2
        val bullCandle = today.bar.close > today.bar.open
        val closeStrength = if (closeRange > 0.0) (today.bar.close - today.bar.low) / closeRange else 0.0
        val closeStrengthOk = closeStrength >= 0.60
        val nearHigh20Pct = if (high20 > 0.0) (today.bar.close / high20 - 1.0) * 100.0 else -Double.MAX_VALUE
        val nearHigh20 = nearHigh20Pct >= -3.0
        val pctChangeOk = today.bar.pctChg in 1.0..8.0

        val passed = amountOk &&
            notOneWord &&
            low120DistanceOk &&
            lowNotBreakingDown &&
            volatilityCompressed &&
            closeAboveMa20 &&
            ma20FlatUp &&
            closeNearMa60 &&
            volumeBreakout &&
            bullCandle &&
            closeStrengthOk &&
            nearHigh20 &&
            pctChangeOk
        if (!passed) return null

        val score = listOf(
            10 to amountOk,
            8 to notOneWord,
            12 to low120DistanceOk,
            10 to lowNotBreakingDown,
            10 to volatilityCompressed,
            10 to closeAboveMa20,
            8 to ma20FlatUp,
            8 to closeNearMa60,
            8 to volumeBreakout,
            6 to bullCandle,
            5 to closeStrengthOk,
            5 to nearHigh20,
        ).sumOf { (points, ok) -> if (ok) points else 0 }.coerceAtMost(100)

        val reasons = mutableListOf<String>()
        fun add(condition: Boolean, reason: String) {
            if (condition) reasons += reason
        }
        add(low120DistanceOk, "股价距离120日低点不超过25%")
        add(lowNotBreakingDown, "近20日低点未明显下移")
        add(volatilityCompressed, "近30日振幅收敛")
        add(closeAboveMa20 && ma20FlatUp, "站上MA20且MA20走平")
        add(closeNearMa60, "收盘未明显跌离MA60")
        add(volumeBreakout && bullCandle, "放量阳线启动")
        add(closeStrengthOk && nearHigh20, "收盘强度较高并接近20日高点")
        add(pctChangeOk, "当日涨幅处于温和启动区间")

        return StrategySignal(
            tradeDate = today.bar.tradeDate,
            stock = stock,
            strategy = "低位启动",
            score = score,
            level = if (score >= 90) SignalLevel.STRONG else SignalLevel.NORMAL,
            reasons = reasons,
            metrics = listOf(
                "距120日低点" to pct(low120DistancePct),
                "30日振幅" to pct(amplitude30Pct),
                "量能" to "${format(volumeRatio5)}x",
                "收盘强度" to pct(closeStrength * 100.0),
                "距20日高点" to pct(nearHigh20Pct),
            ),
            buyTrigger = "次日不追高，优先等待回踩MA20或突破20日高点确认。",
            stopLoss = "跌破MA20且收不回，或跌破近20日低点。",
            ruleChecks = listOf(
                RuleCheck("成交额达到门槛", amountOk),
                RuleCheck("非极端一字K", notOneWord),
                RuleCheck("距120日低点不超过25%", low120DistanceOk),
                RuleCheck("近20日低点未明显下移", lowNotBreakingDown),
                RuleCheck("近30日振幅不超过35%", volatilityCompressed),
                RuleCheck("收盘站上MA20", closeAboveMa20),
                RuleCheck("MA20走平或微升", ma20FlatUp),
                RuleCheck("收盘不低于MA60下方3%", closeNearMa60),
                RuleCheck("成交量大于近5日均量1.2倍", volumeBreakout),
                RuleCheck("当日阳线", bullCandle),
                RuleCheck("收盘强度不低于60%", closeStrengthOk),
                RuleCheck("收盘距20日高点不低于-3%", nearHigh20),
                RuleCheck("当日涨幅在1%到8%", pctChangeOk),
            ),
        )
    }

    private fun List<DailyBar>.movingAverage(index: Int, period: Int): Double? {
        if (index + 1 < period) return null
        var sum = 0.0
        for (i in index + 1 - period..index) {
            sum += this[i].close
        }
        return sum / period
    }

    private fun List<DailyBar>.runningCloseSums(): DoubleArray {
        val sums = DoubleArray(size + 1)
        forEachIndexed { index, bar ->
            sums[index + 1] = sums[index] + bar.close
        }
        return sums
    }

    private fun DoubleArray.movingAverage(index: Int, period: Int): Double? {
        if (index + 1 < period) return null
        return (this[index + 1] - this[index + 1 - period]) / period
    }

    private fun pct(value: Double): String = "${format(value)}%"

    private fun amount(value: Double): String =
        when {
            value >= 100_000_000 -> "${format(value / 100_000_000)}亿"
            value >= 10_000 -> "${format(value / 10_000)}万"
            else -> format(value)
        }

    private fun format(value: Double): String = String.format("%.2f", value)
}
