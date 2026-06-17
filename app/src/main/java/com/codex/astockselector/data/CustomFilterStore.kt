package com.codex.astockselector.data

import android.content.Context
import com.codex.astockselector.model.CustomConditionMode
import com.codex.astockselector.model.CustomFilterConfig
import com.codex.astockselector.model.CustomMatchMode
import com.codex.astockselector.model.CustomScheme
import org.json.JSONObject

object CustomFilterStore {
    private const val PREFS_NAME = "custom_filter_schemes"

    fun load(context: Context, scheme: CustomScheme): CustomFilterConfig {
        val raw = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(scheme.name, null)
        return raw?.let { parse(it, scheme) } ?: CustomFilterConfig(scheme = scheme)
    }

    fun save(context: Context, config: CustomFilterConfig) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(config.scheme.name, serialize(config))
            .apply()
    }

    fun copyToNextScheme(context: Context, config: CustomFilterConfig): CustomFilterConfig {
        val target = when (config.scheme) {
            CustomScheme.Default -> CustomScheme.Mine1
            CustomScheme.Mine1 -> CustomScheme.Mine2
            CustomScheme.Mine2 -> CustomScheme.Mine1
        }
        val copied = config.copy(scheme = target)
        save(context, copied)
        return copied
    }

    private fun serialize(config: CustomFilterConfig): String = JSONObject().apply {
        put("matchMode", config.matchMode.name)
        put("baseExcludeSt", config.baseExcludeSt)
        put("baseMinAmount", config.baseMinAmount)
        put("baseMinBars", config.baseMinBars)

        putMode("trendCloseAboveMa", config.trendCloseAboveMa)
        putMode("trendMa250FlatUp", config.trendMa250FlatUp)
        putMode("trendBullAlignment", config.trendBullAlignment)
        putMode("trendBreakHigh", config.trendBreakHigh)
        put("trendMaPeriod", config.trendMaPeriod)
        put("trendDays", config.trendDays)
        put("trendHighDays", config.trendHighDays)
        put("trendRequireMaUp", config.trendRequireMaUp)

        putMode("momentumTodayRise", config.momentumTodayRise)
        putMode("momentumPeriodRise", config.momentumPeriodRise)
        putMode("momentumConsecutiveRise", config.momentumConsecutiveRise)
        putMode("momentumNewHigh", config.momentumNewHigh)
        putMode("momentumReboundRepair", config.momentumReboundRepair)
        put("momentumTodayPctMin", config.momentumTodayPctMin)
        put("momentumRiseDays", config.momentumRiseDays)
        put("momentumRiseMinPct", config.momentumRiseMinPct)
        put("momentumRiseMaxPct", config.momentumRiseMaxPct)
        put("momentumConsecutiveDays", config.momentumConsecutiveDays)
        put("momentumHighDays", config.momentumHighDays)
        put("momentumReboundRatio", config.momentumReboundRatio)

        putMode("volumeMinAmount", config.volumeMinAmount)
        putMode("volumeAboveAverage", config.volumeAboveAverage)
        putMode("volumeRiseWithVolume", config.volumeRiseWithVolume)
        putMode("volumeShrinkPullback", config.volumeShrinkPullback)
        put("volumeMinAmountValue", config.volumeMinAmountValue)
        put("volumeAverageDays", config.volumeAverageDays)
        put("volumeMultiplier", config.volumeMultiplier)

        putMode("patternYangBaoYin", config.patternYangBaoYin)
        putMode("patternBearThenBull", config.patternBearThenBull)
        putMode("patternLongLowerShadow", config.patternLongLowerShadow)
        putMode("patternBigBull", config.patternBigBull)
        putMode("patternSmallRange", config.patternSmallRange)
        putMode("patternGapUp", config.patternGapUp)
        putMode("patternBodyRatio", config.patternBodyRatio)
        put("patternBodyMinRatio", config.patternBodyMinRatio)
        put("patternLowerShadowMinRatio", config.patternLowerShadowMinRatio)
        put("patternUpperShadowMaxRatio", config.patternUpperShadowMaxRatio)
        put("patternBigBullPct", config.patternBigBullPct)
        put("patternSmallDays", config.patternSmallDays)
        put("patternSmallAmplitudePct", config.patternSmallAmplitudePct)
        put("patternRequireBull", config.patternRequireBull)
        put("patternCloseHighRatio", config.patternCloseHighRatio)

        putMode("maNear", config.maNear)
        putMode("ma60Near", config.ma60Near)
        putMode("maPullbackHold", config.maPullbackHold)
        putMode("maCrossUp", config.maCrossUp)
        putMode("maConverge", config.maConverge)
        put("maPeriod", config.maPeriod)
        put("maNearPct", config.maNearPct)
        put("maRequireAbove", config.maRequireAbove)
        put("maRequireCrossToday", config.maRequireCrossToday)
        put("maConvergePct", config.maConvergePct)

        putMode("limitNear", config.limitNear)
        putMode("limitFirstBoard", config.limitFirstBoard)
        putMode("limitNoRecent", config.limitNoRecent)
        putMode("limitBoardCount", config.limitBoardCount)
        putMode("limitNotOneWord", config.limitNotOneWord)
        putMode("limitNoBrokenBoard", config.limitNoBrokenBoard)
        put("limitNearRatio", config.limitNearRatio)
        put("limitLookbackDays", config.limitLookbackDays)
        put("limitBoardCountValue", config.limitBoardCountValue)
        put("limitExcludeSt", config.limitExcludeSt)
        put("limitExcludeOneWord", config.limitExcludeOneWord)

        putMode("volatilityTodayAmplitude", config.volatilityTodayAmplitude)
        putMode("volatilityNarrowing", config.volatilityNarrowing)
        putMode("volatilityMaxDrawdown", config.volatilityMaxDrawdown)
        putMode("volatilityVolumeWave", config.volatilityVolumeWave)
        putMode("volatilityQuietBreakout", config.volatilityQuietBreakout)
        put("volatilityMinAmplitudePct", config.volatilityMinAmplitudePct)
        put("volatilityMaxAmplitudePct", config.volatilityMaxAmplitudePct)
        put("volatilityDays", config.volatilityDays)
        put("volatilityMaxDrawdownPct", config.volatilityMaxDrawdownPct)
        put("volatilityRequireBreakout", config.volatilityRequireBreakout)
    }.toString()

    private fun parse(raw: String, scheme: CustomScheme): CustomFilterConfig = runCatching {
        val json = JSONObject(raw)
        CustomFilterConfig(
            scheme = scheme,
            matchMode = json.matchMode("matchMode", CustomMatchMode.All),
            baseExcludeSt = json.optBoolean("baseExcludeSt", true),
            baseMinAmount = json.optDouble("baseMinAmount", 50_000_000.0),
            baseMinBars = json.optInt("baseMinBars", 260),
            trendCloseAboveMa = json.mode("trendCloseAboveMa", CustomConditionMode.Required),
            trendMa250FlatUp = json.mode("trendMa250FlatUp", CustomConditionMode.Score),
            trendBullAlignment = json.mode("trendBullAlignment", CustomConditionMode.Score),
            trendBreakHigh = json.mode("trendBreakHigh", CustomConditionMode.Off),
            trendMaPeriod = json.optInt("trendMaPeriod", 250),
            trendDays = json.optInt("trendDays", 5),
            trendHighDays = json.optInt("trendHighDays", 60),
            trendRequireMaUp = json.optBoolean("trendRequireMaUp", false),
            momentumTodayRise = json.mode("momentumTodayRise", CustomConditionMode.Off),
            momentumPeriodRise = json.mode("momentumPeriodRise", CustomConditionMode.Score),
            momentumConsecutiveRise = json.mode("momentumConsecutiveRise", CustomConditionMode.Off),
            momentumNewHigh = json.mode("momentumNewHigh", CustomConditionMode.Off),
            momentumReboundRepair = json.mode("momentumReboundRepair", CustomConditionMode.Off),
            momentumTodayPctMin = json.optDouble("momentumTodayPctMin", 3.0),
            momentumRiseDays = json.optInt("momentumRiseDays", 10),
            momentumRiseMinPct = json.optDouble("momentumRiseMinPct", 0.0),
            momentumRiseMaxPct = json.optDouble("momentumRiseMaxPct", 25.0),
            momentumConsecutiveDays = json.optInt("momentumConsecutiveDays", 3),
            momentumHighDays = json.optInt("momentumHighDays", 20),
            momentumReboundRatio = json.optDouble("momentumReboundRatio", 0.60),
            volumeMinAmount = json.mode("volumeMinAmount", CustomConditionMode.Required),
            volumeAboveAverage = json.mode("volumeAboveAverage", CustomConditionMode.Score),
            volumeRiseWithVolume = json.mode("volumeRiseWithVolume", CustomConditionMode.Off),
            volumeShrinkPullback = json.mode("volumeShrinkPullback", CustomConditionMode.Off),
            volumeMinAmountValue = json.optDouble("volumeMinAmountValue", 50_000_000.0),
            volumeAverageDays = json.optInt("volumeAverageDays", 5),
            volumeMultiplier = json.optDouble("volumeMultiplier", 1.20),
            patternYangBaoYin = json.mode("patternYangBaoYin", CustomConditionMode.Off),
            patternBearThenBull = json.mode("patternBearThenBull", CustomConditionMode.Off),
            patternLongLowerShadow = json.mode("patternLongLowerShadow", CustomConditionMode.Off),
            patternBigBull = json.mode("patternBigBull", CustomConditionMode.Off),
            patternSmallRange = json.mode("patternSmallRange", CustomConditionMode.Off),
            patternGapUp = json.mode("patternGapUp", CustomConditionMode.Off),
            patternBodyRatio = json.mode("patternBodyRatio", CustomConditionMode.Off),
            patternBodyMinRatio = json.optDouble("patternBodyMinRatio", 0.50),
            patternLowerShadowMinRatio = json.optDouble("patternLowerShadowMinRatio", 0.35),
            patternUpperShadowMaxRatio = json.optDouble("patternUpperShadowMaxRatio", 0.30),
            patternBigBullPct = json.optDouble("patternBigBullPct", 5.0),
            patternSmallDays = json.optInt("patternSmallDays", 5),
            patternSmallAmplitudePct = json.optDouble("patternSmallAmplitudePct", 4.0),
            patternRequireBull = json.optBoolean("patternRequireBull", true),
            patternCloseHighRatio = json.optDouble("patternCloseHighRatio", 0.70),
            maNear = json.mode("maNear", CustomConditionMode.Required),
            ma60Near = json.mode("ma60Near", CustomConditionMode.Off),
            maPullbackHold = json.mode("maPullbackHold", CustomConditionMode.Score),
            maCrossUp = json.mode("maCrossUp", CustomConditionMode.Off),
            maConverge = json.mode("maConverge", CustomConditionMode.Off),
            maPeriod = json.optInt("maPeriod", 250),
            maNearPct = json.optDouble("maNearPct", 0.05),
            maRequireAbove = json.optBoolean("maRequireAbove", true),
            maRequireCrossToday = json.optBoolean("maRequireCrossToday", false),
            maConvergePct = json.optDouble("maConvergePct", 0.03),
            limitNear = json.mode("limitNear", CustomConditionMode.Off),
            limitFirstBoard = json.mode("limitFirstBoard", CustomConditionMode.Off),
            limitNoRecent = json.mode("limitNoRecent", CustomConditionMode.Off),
            limitBoardCount = json.mode("limitBoardCount", CustomConditionMode.Off),
            limitNotOneWord = json.mode("limitNotOneWord", CustomConditionMode.Off),
            limitNoBrokenBoard = json.mode("limitNoBrokenBoard", CustomConditionMode.Off),
            limitNearRatio = json.optDouble("limitNearRatio", 0.90),
            limitLookbackDays = json.optInt("limitLookbackDays", 20),
            limitBoardCountValue = json.optInt("limitBoardCountValue", 1),
            limitExcludeSt = json.optBoolean("limitExcludeSt", true),
            limitExcludeOneWord = json.optBoolean("limitExcludeOneWord", true),
            volatilityTodayAmplitude = json.mode("volatilityTodayAmplitude", CustomConditionMode.Off),
            volatilityNarrowing = json.mode("volatilityNarrowing", CustomConditionMode.Off),
            volatilityMaxDrawdown = json.mode("volatilityMaxDrawdown", CustomConditionMode.Off),
            volatilityVolumeWave = json.mode("volatilityVolumeWave", CustomConditionMode.Off),
            volatilityQuietBreakout = json.mode("volatilityQuietBreakout", CustomConditionMode.Off),
            volatilityMinAmplitudePct = json.optDouble("volatilityMinAmplitudePct", 0.0),
            volatilityMaxAmplitudePct = json.optDouble("volatilityMaxAmplitudePct", 8.0),
            volatilityDays = json.optInt("volatilityDays", 10),
            volatilityMaxDrawdownPct = json.optDouble("volatilityMaxDrawdownPct", 12.0),
            volatilityRequireBreakout = json.optBoolean("volatilityRequireBreakout", true),
        )
    }.getOrElse {
        CustomFilterConfig(scheme = scheme)
    }

    private fun JSONObject.putMode(name: String, mode: CustomConditionMode) {
        put(name, mode.name)
    }

    private fun JSONObject.mode(name: String, fallback: CustomConditionMode): CustomConditionMode =
        enumValueOrDefault(optString(name), fallback)

    private fun JSONObject.matchMode(name: String, fallback: CustomMatchMode): CustomMatchMode =
        enumValueOrDefault(optString(name), fallback)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
}
