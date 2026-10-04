package com.codex.astockselector.strategy

import com.codex.astockselector.model.DailyBar
import com.codex.astockselector.model.MarketSegment
import com.codex.astockselector.model.StockProfile
import com.codex.astockselector.model.StrategyConfig
import com.codex.astockselector.model.strategyRuleKey
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchStrategyEngineTest {
    private val stock = StockProfile("600000.SH", "规则测试", MarketSegment.MAIN, "19991110")
    private val config = StrategyConfig()

    @Test
    fun panicRepairIsDetectedWithoutLegacy260BarWarmup() {
        val bars = panicBars()
        assertTrue(bars.size < 260)
        assertTrue(StrategyEngine.evaluate(stock, bars, config).any { it.strategy == "恐慌修复" })
        assertTrue(ResearchStrategyEngine.isCoarseCandidate(bars))
    }

    @Test
    fun trendEngulfDoesNotRequireSignalDayVolumeFloor() {
        val bars = engulfBars()
        assertTrue(bars.last().volume < bars.dropLast(1).takeLast(5).map { it.volume }.average())
        assertTrue(StrategyEngine.evaluate(stock, bars, config).any { it.strategy == "趋势反包" })
    }

    @Test
    fun trendEngulfStillRequiresAnchorVolume() {
        val bars = engulfBars().toMutableList()
        bars[bars.lastIndex - 3] = bars[bars.lastIndex - 3].copy(volume = 110.0)
        assertTrue(StrategyEngine.evaluate(stock, bars, config).none { it.strategy == "趋势反包" })
    }

    @Test
    fun pullbackRestartIsDetectedAfterShrinkageAndBreakout() {
        val bars = pullbackBars()
        assertTrue(StrategyEngine.evaluate(stock, bars, config).any { it.strategy == "缩量再起" })
        assertTrue(ResearchStrategyEngine.isCoarseCandidate(bars))
    }

    @Test
    fun pullbackRestartRejectsUnshrunkCorrection() {
        val bars = pullbackBars().toMutableList()
        for (index in bars.lastIndex - 3 until bars.lastIndex) {
            bars[index] = bars[index].copy(volume = 100.0)
        }
        assertTrue(StrategyEngine.evaluate(stock, bars, config).none { it.strategy == "缩量再起" })
    }

    @Test
    fun lessThan120BarsAndStStocksAreRejected() {
        assertTrue(StrategyEngine.evaluate(stock, panicBars().takeLast(119), config).isEmpty())
        assertTrue(StrategyEngine.evaluate(stock.copy(isSt = true), panicBars(), config).isEmpty())
    }

    @Test
    fun allThreeUseTheConfiguredMinimumAmount() {
        for (bars in listOf(panicBars(), engulfBars(), pullbackBars())) {
            val lowAmount = bars.dropLast(1) + bars.last().copy(amount = 49_999_999.0)
            assertTrue(StrategyEngine.evaluate(stock, lowAmount, config).isEmpty())
        }
    }

    @Test
    fun nonFiniteAmountIsRejected() {
        val bars = panicBars().toMutableList()
        bars[bars.lastIndex] = bars.last().copy(amount = Double.NaN)
        assertTrue(ResearchStrategyEngine.evaluate(stock, bars, config).isEmpty())
    }

    @Test
    fun expiredInvalidValuesDoNotPoisonCurrentRollingAverages() {
        val bars = panicBars().toMutableList()
        bars[0] = bars[0].copy(close = Double.NaN, volume = Double.NaN)
        assertTrue(ResearchStrategyEngine.evaluate(stock, bars, config).any { it.strategy == "恐慌修复" })
    }

    @Test
    fun invalidVolumeAndNominalPriceBandAreRejected() {
        val bars = panicBars().toMutableList()
        bars[bars.lastIndex - 10] = bars[bars.lastIndex - 10].copy(volume = 0.0)
        assertTrue(StrategyEngine.evaluate(stock, bars, config).isEmpty())
        val band = panicBars().toMutableList()
        band[band.lastIndex - 20] = band[band.lastIndex - 20].copy(high = 20.0)
        assertTrue(StrategyEngine.evaluate(stock, band, config).isEmpty())
    }

    @Test
    fun signalsHavePassedChecksAndAreNotWinProbabilityScores() {
        val signals = ResearchStrategyEngine.evaluate(stock, engulfBars(), config)
        assertEquals("趋势反包", signals.single().strategy)
        assertEquals(100, signals.single().score)
        assertTrue(signals.single().ruleChecks.all { it.passed })
    }

    @Test
    fun ruleKeyInvalidatesOldResultsWithoutChangingKlineSchema() {
        assertTrue(config.strategyRuleKey().contains("rules=20261004_research_three_v1"))
        assertFalse(config.strategyRuleKey().contains("20260820_three_yang_balanced_v2"))
    }

    private fun panicBars(): List<DailyBar> {
        val bars = series(false).toMutableList()
        val t = bars.lastIndex
        bars[t - 4] = bars[t - 4].copy(open = 9.95, high = 10.0, low = 9.55, close = 9.60, volume = 150.0)
        bars[t - 3] = bars[t - 3].copy(open = 9.65, high = 9.75, low = 9.58, close = 9.70, volume = 90.0)
        bars[t - 2] = bars[t - 2].copy(open = 9.70, high = 9.80, low = 9.60, close = 9.75, volume = 80.0)
        bars[t - 1] = bars[t - 1].copy(open = 9.75, high = 9.85, low = 9.65, close = 9.80, volume = 85.0)
        bars[t] = bars[t].copy(open = 9.80, high = 10.0, low = 9.78, close = 9.95, volume = 110.0)
        return normalize(bars)
    }

    private fun engulfBars(): List<DailyBar> {
        val bars = series(true).toMutableList()
        val t = bars.lastIndex
        bars[t - 3] = bars[t - 3].copy(open = 11.26, high = 11.30, low = 10.99, close = 11.02, volume = 150.0)
        bars[t - 2] = bars[t - 2].copy(open = 11.02, high = 11.11, low = 11.00, close = 11.08, volume = 90.0)
        bars[t - 1] = bars[t - 1].copy(open = 11.08, high = 11.15, low = 11.06, close = 11.12, volume = 90.0)
        bars[t] = bars[t].copy(open = 11.14, high = 11.34, low = 11.12, close = 11.31, volume = 60.0)
        return normalize(bars)
    }

    private fun pullbackBars(): List<DailyBar> {
        val bars = series(true).toMutableList()
        val t = bars.lastIndex
        bars[t - 3] = bars[t - 3].copy(open = 11.23, high = 11.24, low = 11.10, close = 11.18, volume = 60.0)
        bars[t - 2] = bars[t - 2].copy(open = 11.18, high = 11.22, low = 11.10, close = 11.17, volume = 60.0)
        bars[t - 1] = bars[t - 1].copy(open = 11.17, high = 11.23, low = 11.12, close = 11.20, volume = 60.0)
        bars[t] = bars[t].copy(open = 11.21, high = 11.30, low = 11.20, close = 11.29, volume = 110.0)
        return normalize(bars)
    }

    private fun series(rising: Boolean): List<DailyBar> {
        var day = LocalDate.of(2026, 1, 2)
        return (0 until 130).map { index ->
            while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) day = day.plusDays(1)
            val date = day.format(DateTimeFormatter.BASIC_ISO_DATE)
            day = day.plusDays(1)
            val close = if (rising) 10.0 + index * 0.01 else 10.0
            DailyBar(stock.tsCode, date, if (rising) close - 0.02 else close,
                close + 0.1, close - 0.1, close, close, 0.0, 100.0, 60_000_000.0)
        }
    }

    private fun normalize(bars: List<DailyBar>): List<DailyBar> = bars.mapIndexed { index, bar ->
        val previous = if (index == 0) bar.open else bars[index - 1].close
        bar.copy(preClose = previous, pctChg = (bar.close - previous) / previous * 100.0)
    }
}
