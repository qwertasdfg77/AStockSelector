package com.codex.astockselector.model

data class StockProfile(
    val tsCode: String,
    val name: String,
    val market: MarketSegment,
    val listDate: String,
    val isSt: Boolean = false,
)

data class DailyBar(
    val tsCode: String,
    val tradeDate: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val preClose: Double,
    val pctChg: Double,
    val volume: Double,
    val amount: Double,
)

data class EnrichedBar(
    val bar: DailyBar,
    val ma5: Double?,
    val ma10: Double?,
    val ma20: Double?,
    val ma60: Double?,
    val ma250: Double?,
)

data class StrategyConfig(
    val nearLimitRatio: Double = 0.90,
    val firstBoardLookback: Int = 20,
    val volumeMultiplier: Double = 1.20,
    val minAmount: Double = 50_000_000.0,
    val nearMaPct: Double = 0.05,
    val maxFirstBoardMaDistancePct: Double = 0.10,
    val maxNineYangRisePct: Double = 25.0,
    val minNineYangYangCount: Int = 7,
    val minNineYangNearMaCount: Int = 4,
    val nineYangMinScore: Int = 85,
    val gameKLineNearMaPct: Double = 0.03,
    val reboundRatioThreshold: Double = 0.80,
    val closeStrengthThreshold: Double = 0.80,
    val gameKLineVolumeRatio: Double = 1.20,
    val gameKLineMinScore: Int = 85,
)

enum class SignalLevel(val displayName: String) {
    STRONG("强"),
    NORMAL("普通"),
}

data class StrategySignal(
    val tradeDate: String,
    val stock: StockProfile,
    val strategy: String,
    val score: Int,
    val level: SignalLevel,
    val reasons: List<String>,
    val metrics: List<Pair<String, String>>,
    val buyTrigger: String,
    val stopLoss: String,
    val ruleChecks: List<RuleCheck> = emptyList(),
)

data class RuleCheck(
    val label: String,
    val passed: Boolean,
)
