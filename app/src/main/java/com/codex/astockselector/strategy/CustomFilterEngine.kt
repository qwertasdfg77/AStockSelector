package com.codex.astockselector.strategy

import com.codex.astockselector.model.CustomConditionMode
import com.codex.astockselector.model.CustomFilterConfig
import com.codex.astockselector.model.CustomMatchMode
import com.codex.astockselector.model.DailyBar
import com.codex.astockselector.model.RuleCheck
import com.codex.astockselector.model.SignalLevel
import com.codex.astockselector.model.StockProfile
import com.codex.astockselector.model.StrategySignal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object CustomFilterEngine {
    fun evaluate(
        stock: StockProfile,
        bars: List<DailyBar>,
        config: CustomFilterConfig,
    ): StrategySignal? {
        if (bars.size < config.baseMinBars) return null
        if ((config.baseExcludeSt || config.limitExcludeSt) && stock.isSt) return null

        val sorted = if (bars.isSortedByTradeDate()) bars else bars.sortedBy { it.tradeDate }
        val today = sorted.last()
        val yesterday = sorted.getOrNull(sorted.lastIndex - 1) ?: return null
        if (today.amount < config.baseMinAmount) return null

        val conditions = mutableListOf<CustomConditionResult>()
        addTrendConditions(conditions, sorted, config)
        addMomentumConditions(conditions, sorted, config)
        addVolumeConditions(conditions, sorted, config)
        addPatternConditions(conditions, sorted, config)
        addMaPositionConditions(conditions, sorted, config)
        addLimitConditions(conditions, stock, sorted, config)
        addVolatilityConditions(conditions, sorted, config)

        val active = conditions.filter { it.mode != CustomConditionMode.Off }
        if (active.isEmpty()) return null

        val required = active.filter { it.mode == CustomConditionMode.Required }
        val requiredPassed = when {
            required.isEmpty() -> true
            config.matchMode == CustomMatchMode.All -> required.all { it.passed }
            else -> required.any { it.passed }
        }
        if (!requiredPassed) return null

        val passed = active.filter { it.passed }
        if (passed.isEmpty()) return null

        val requiredPoints = passed.filter { it.mode == CustomConditionMode.Required }.sumOf { it.points }
        val scorePoints = passed.filter { it.mode == CustomConditionMode.Score }.sumOf { it.points }
        val score = (45 + requiredPoints + scorePoints + passed.size * 2).coerceAtMost(100)

        return StrategySignal(
            tradeDate = today.tradeDate,
            stock = stock,
            strategy = "自定义筛选",
            score = score,
            level = if (score >= 85) SignalLevel.STRONG else SignalLevel.NORMAL,
            reasons = passed.map { "${it.mode.title}：${it.name}" },
            metrics = listOf(
                "组合" to config.matchMode.title,
                "命中" to "${passed.size}/${active.size}",
                "涨幅" to pct(today.pctChg),
                "成交额" to amount(today.amount),
                "距MA${config.maPeriod}" to sorted.ma(config.maPeriod)?.let { ma -> pct(abs(today.close - ma) / ma * 100.0) }.orEmpty(),
            ),
            buyTrigger = "按自定义条件命中后，结合分时承接、板块强弱和大盘环境确认，不直接追高。",
            stopLoss = "跌破关键K线低点、跌回主要均线下方，或自定义硬条件失效时复核止损。",
            ruleChecks = active.map { RuleCheck("${it.mode.title} ${it.name}", it.passed) },
        )
    }

    private fun addTrendConditions(
        result: MutableList<CustomConditionResult>,
        bars: List<DailyBar>,
        config: CustomFilterConfig,
    ) {
        val today = bars.last()
        val trendMa = bars.ma(config.trendMaPeriod)
        val ma250 = bars.ma(250)
        val ma250Past = bars.ma(250, bars.lastIndex - config.trendDays)
        val ma5 = bars.ma(5)
        val ma10 = bars.ma(10)
        val ma20 = bars.ma(20)
        val ma5Past = bars.ma(5, bars.lastIndex - config.trendDays)
        val priorHigh = bars.dropLast(1).takeLast(config.trendHighDays).maxOfOrNull { it.high }

        result.addCondition(
            "收盘价站上 MA${config.trendMaPeriod}",
            config.trendCloseAboveMa,
            trendMa != null && today.close >= trendMa,
            12,
        )
        result.addCondition(
            "MA250 近${config.trendDays}日走平或向上",
            config.trendMa250FlatUp,
            ma250 != null && ma250Past != null && ma250 >= ma250Past,
            8,
        )
        result.addCondition(
            "MA5 > MA10 > MA20 多头排列",
            config.trendBullAlignment,
            ma5 != null && ma10 != null && ma20 != null && ma5 > ma10 && ma10 > ma20 &&
                (!config.trendRequireMaUp || ma5Past == null || ma5 >= ma5Past),
            8,
        )
        result.addCondition(
            "收盘价突破${config.trendHighDays}日新高",
            config.trendBreakHigh,
            priorHigh != null && today.close >= priorHigh,
            10,
        )
    }

    private fun addMomentumConditions(
        result: MutableList<CustomConditionResult>,
        bars: List<DailyBar>,
        config: CustomFilterConfig,
    ) {
        val today = bars.last()
        val periodBars = bars.takeLast(config.momentumRiseDays)
        val periodRise = periodBars.firstOrNull()?.let { first ->
            if (first.open > 0.0) (today.close - first.open) / first.open * 100.0 else 0.0
        } ?: 0.0
        val consecutiveBars = bars.takeLast(config.momentumConsecutiveDays)
        val priorHigh = bars.dropLast(1).takeLast(config.momentumHighDays).maxOfOrNull { it.high }
        val yesterday = bars[bars.lastIndex - 1]
        val previousBody = yesterday.open - yesterday.close
        val reboundRatio = if (previousBody > 0.0) (today.close - yesterday.close) / previousBody else 0.0

        result.addCondition(
            "今日涨幅大于${pct(config.momentumTodayPctMin)}",
            config.momentumTodayRise,
            today.pctChg >= config.momentumTodayPctMin,
            10,
        )
        result.addCondition(
            "近${config.momentumRiseDays}日涨幅在${pct(config.momentumRiseMinPct)}到${pct(config.momentumRiseMaxPct)}",
            config.momentumPeriodRise,
            periodBars.size >= config.momentumRiseDays &&
                periodRise in config.momentumRiseMinPct..config.momentumRiseMaxPct,
            8,
        )
        result.addCondition(
            "连涨${config.momentumConsecutiveDays}天",
            config.momentumConsecutiveRise,
            consecutiveBars.size >= config.momentumConsecutiveDays &&
                consecutiveBars.all { it.close > it.preClose },
            8,
        )
        result.addCondition(
            "创${config.momentumHighDays}日新高",
            config.momentumNewHigh,
            priorHigh != null && today.close >= priorHigh,
            10,
        )
        result.addCondition(
            "反弹修复前阴线${pct(config.momentumReboundRatio * 100.0)}以上",
            config.momentumReboundRepair,
            previousBody > 0.0 && today.close > today.open && reboundRatio >= config.momentumReboundRatio,
            10,
        )
    }

    private fun addVolumeConditions(
        result: MutableList<CustomConditionResult>,
        bars: List<DailyBar>,
        config: CustomFilterConfig,
    ) {
        val today = bars.last()
        val avgVolume = bars.averageVolumeBeforeToday(config.volumeAverageDays)

        result.addCondition(
            "成交额大于${amount(config.volumeMinAmountValue)}",
            config.volumeMinAmount,
            today.amount >= config.volumeMinAmountValue,
            12,
        )
        result.addCondition(
            "今日成交量大于近${config.volumeAverageDays}日均量${format(config.volumeMultiplier)}倍",
            config.volumeAboveAverage,
            avgVolume != null && today.volume >= avgVolume * config.volumeMultiplier,
            8,
        )
        result.addCondition(
            "放量上涨",
            config.volumeRiseWithVolume,
            avgVolume != null && today.close > today.open && today.volume >= avgVolume * config.volumeMultiplier,
            8,
        )
        result.addCondition(
            "缩量回调",
            config.volumeShrinkPullback,
            avgVolume != null && today.close < today.preClose && today.volume <= avgVolume,
            6,
        )
    }

    private fun addPatternConditions(
        result: MutableList<CustomConditionResult>,
        bars: List<DailyBar>,
        config: CustomFilterConfig,
    ) {
        val today = bars.last()
        val yesterday = bars[bars.lastIndex - 1]
        val range = today.high - today.low
        val body = abs(today.close - today.open)
        val bodyRatio = if (range > 0.0) body / range else 0.0
        val upperShadowRatio = if (range > 0.0) (today.high - max(today.close, today.open)) / range else 0.0
        val lowerShadowRatio = if (range > 0.0) (min(today.close, today.open) - today.low) / range else 0.0
        val closeHighRatio = if (range > 0.0) (today.close - today.low) / range else 0.0
        val smallBars = bars.takeLast(config.patternSmallDays)

        result.addCondition(
            "阳包阴",
            config.patternYangBaoYin,
            yesterday.close < yesterday.open &&
                today.close > today.open &&
                today.close >= yesterday.open &&
                today.open <= yesterday.close,
            10,
        )
        result.addCondition(
            "前阴后阳",
            config.patternBearThenBull,
            yesterday.close < yesterday.open && today.close > today.open,
            8,
        )
        result.addCondition(
            "长下影线",
            config.patternLongLowerShadow,
            lowerShadowRatio >= config.patternLowerShadowMinRatio &&
                upperShadowRatio <= config.patternUpperShadowMaxRatio,
            8,
        )
        result.addCondition(
            "大阳线",
            config.patternBigBull,
            today.close > today.open && today.pctChg >= config.patternBigBullPct,
            10,
        )
        result.addCondition(
            "小阴小阳整理",
            config.patternSmallRange,
            smallBars.size >= config.patternSmallDays &&
                smallBars.all { it.amplitudePct() <= config.patternSmallAmplitudePct },
            6,
        )
        result.addCondition(
            "跳空高开",
            config.patternGapUp,
            today.open > yesterday.high && today.low >= yesterday.high,
            8,
        )
        result.addCondition(
            "实体占比大于${pct(config.patternBodyMinRatio * 100.0)}",
            config.patternBodyRatio,
            bodyRatio >= config.patternBodyMinRatio &&
                (!config.patternRequireBull || today.close > today.open) &&
                closeHighRatio >= config.patternCloseHighRatio,
            8,
        )
    }

    private fun addMaPositionConditions(
        result: MutableList<CustomConditionResult>,
        bars: List<DailyBar>,
        config: CustomFilterConfig,
    ) {
        val today = bars.last()
        val yesterday = bars[bars.lastIndex - 1]
        val ma = bars.ma(config.maPeriod)
        val yMa = bars.ma(config.maPeriod, bars.lastIndex - 1)
        val ma60 = bars.ma(60)
        val ma5 = bars.ma(5)
        val ma10 = bars.ma(10)
        val ma20 = bars.ma(20)

        result.addCondition(
            "距 MA${config.maPeriod} 不超过${pct(config.maNearPct * 100.0)}",
            config.maNear,
            ma != null &&
                abs(today.close - ma) / ma <= config.maNearPct &&
                (!config.maRequireAbove || today.close >= ma) &&
                (!config.maRequireCrossToday || (yMa != null && yesterday.close <= yMa && today.close >= ma)),
            12,
        )
        result.addCondition(
            "距 MA60 不超过${pct(config.maNearPct * 100.0)}",
            config.ma60Near,
            ma60 != null && abs(today.close - ma60) / ma60 <= config.maNearPct,
            8,
        )
        result.addCondition(
            "回踩 MA${config.maPeriod} 不破",
            config.maPullbackHold,
            ma != null && today.low <= ma * (1.0 + config.maNearPct) && today.close >= ma,
            8,
        )
        result.addCondition(
            "当天上穿 MA${config.maPeriod}",
            config.maCrossUp,
            ma != null && yMa != null && yesterday.close <= yMa && today.close >= ma,
            10,
        )
        result.addCondition(
            "MA5/10/20 粘合",
            config.maConverge,
            ma5 != null && ma10 != null && ma20 != null &&
                listOf(ma5, ma10, ma20).let { values ->
                    values.maxOrNull()!! / values.minOrNull()!! - 1.0 <= config.maConvergePct
                },
            8,
        )
    }

    private fun addLimitConditions(
        result: MutableList<CustomConditionResult>,
        stock: StockProfile,
        bars: List<DailyBar>,
        config: CustomFilterConfig,
    ) {
        val today = bars.last()
        val prior = bars.dropLast(1).takeLast(config.limitLookbackDays)
        val nearThreshold = stock.market.limitUpPct * config.limitNearRatio
        val todayNearLimit = today.pctChg >= nearThreshold
        val noRecentLimit = prior.none { it.pctChg >= nearThreshold }
        val boardCount = bars.asReversed().takeWhile { it.pctChg >= nearThreshold }.size
        val oneWord = isOneWordLimit(stock, today)
        val limitPrice = today.preClose * (1.0 + stock.market.limitUpPct / 100.0)
        val brokenBoardProxy = today.high >= limitPrice * config.limitNearRatio && today.close < limitPrice * 0.98

        result.addCondition(
            "涨幅接近涨停",
            config.limitNear,
            todayNearLimit,
            12,
        )
        result.addCondition(
            "首板",
            config.limitFirstBoard,
            todayNearLimit && noRecentLimit,
            12,
        )
        result.addCondition(
            "${config.limitLookbackDays}日内无涨停",
            config.limitNoRecent,
            noRecentLimit,
            8,
        )
        result.addCondition(
            "连板数等于${config.limitBoardCountValue}",
            config.limitBoardCount,
            boardCount == config.limitBoardCountValue,
            10,
        )
        result.addCondition(
            "非一字板",
            config.limitNotOneWord,
            !config.limitExcludeOneWord || !oneWord,
            6,
        )
        result.addCondition(
            "炸板过滤通过",
            config.limitNoBrokenBoard,
            !brokenBoardProxy,
            6,
        )
    }

    private fun addVolatilityConditions(
        result: MutableList<CustomConditionResult>,
        bars: List<DailyBar>,
        config: CustomFilterConfig,
    ) {
        val today = bars.last()
        val recent = bars.takeLast(config.volatilityDays)
        val previous = bars.dropLast(config.volatilityDays).takeLast(config.volatilityDays)
        val todayAmplitude = today.amplitudePct()
        val recentAvgAmplitude = recent.map { it.amplitudePct() }.average()
        val previousAvgAmplitude = previous.map { it.amplitudePct() }.average()
        val maxDrawdown = recent.maxDrawdownPct()
        val avgVolume = bars.averageVolumeBeforeToday(config.volatilityDays)
        val priorHigh = bars.dropLast(1).takeLast(config.volatilityDays).maxOfOrNull { it.high }

        result.addCondition(
            "今日振幅在${pct(config.volatilityMinAmplitudePct)}到${pct(config.volatilityMaxAmplitudePct)}",
            config.volatilityTodayAmplitude,
            todayAmplitude in config.volatilityMinAmplitudePct..config.volatilityMaxAmplitudePct,
            8,
        )
        result.addCondition(
            "近${config.volatilityDays}日振幅收窄",
            config.volatilityNarrowing,
            recent.size >= config.volatilityDays &&
                previous.size >= config.volatilityDays &&
                recentAvgAmplitude < previousAvgAmplitude,
            8,
        )
        result.addCondition(
            "近${config.volatilityDays}日最大回撤小于${pct(config.volatilityMaxDrawdownPct)}",
            config.volatilityMaxDrawdown,
            recent.size >= config.volatilityDays && maxDrawdown <= config.volatilityMaxDrawdownPct,
            8,
        )
        result.addCondition(
            "突然放量放波动",
            config.volatilityVolumeWave,
            avgVolume != null && today.volume >= avgVolume * 1.5 && todayAmplitude >= config.volatilityMaxAmplitudePct,
            8,
        )
        result.addCondition(
            "低波动后突破",
            config.volatilityQuietBreakout,
            recent.size >= config.volatilityDays &&
                recentAvgAmplitude <= config.volatilityMaxAmplitudePct &&
                (!config.volatilityRequireBreakout || (priorHigh != null && today.close >= priorHigh)),
            10,
        )
    }

    private data class CustomConditionResult(
        val name: String,
        val mode: CustomConditionMode,
        val passed: Boolean,
        val points: Int,
    )

    private fun MutableList<CustomConditionResult>.addCondition(
        name: String,
        mode: CustomConditionMode,
        passed: Boolean,
        points: Int,
    ) {
        if (mode != CustomConditionMode.Off) {
            add(CustomConditionResult(name, mode, passed, points))
        }
    }

    private fun List<DailyBar>.ma(period: Int, endIndex: Int = lastIndex): Double? {
        if (endIndex < 0 || endIndex + 1 < period) return null
        return subList(endIndex - period + 1, endIndex + 1).map { it.close }.average()
    }

    private fun List<DailyBar>.averageVolumeBeforeToday(days: Int): Double? {
        val items = dropLast(1).takeLast(days)
        return if (items.size >= days) items.map { it.volume }.average() else null
    }

    private fun List<DailyBar>.maxDrawdownPct(): Double {
        if (isEmpty()) return 0.0
        var peak = first().high
        var drawdown = 0.0
        forEach { bar ->
            peak = max(peak, bar.high)
            if (peak > 0.0) {
                drawdown = max(drawdown, (peak - bar.close) / peak * 100.0)
            }
        }
        return drawdown
    }

    private fun DailyBar.amplitudePct(): Double =
        if (preClose > 0.0) (high - low) / preClose * 100.0 else 0.0

    private fun isOneWordLimit(stock: StockProfile, bar: DailyBar): Boolean {
        val limitPrice = bar.preClose * (1.0 + stock.market.limitUpPct / 100.0)
        return bar.open >= limitPrice * 0.995 && bar.low >= limitPrice * 0.995
    }

    private fun List<DailyBar>.isSortedByTradeDate(): Boolean {
        for (index in 1 until size) {
            if (this[index - 1].tradeDate > this[index].tradeDate) return false
        }
        return true
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
