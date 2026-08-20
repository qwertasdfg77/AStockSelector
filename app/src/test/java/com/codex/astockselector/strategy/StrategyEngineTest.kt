package com.codex.astockselector.strategy

import com.codex.astockselector.model.DailyBar
import com.codex.astockselector.model.MarketSegment
import com.codex.astockselector.model.StockProfile
import com.codex.astockselector.model.StrategyConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyEngineTest {
    private val stock = StockProfile(
        tsCode = "600000.SH",
        name = "测试股票",
        market = MarketSegment.MAIN,
        listDate = "20200101",
        isSt = false,
    )

    @Test
    fun firstBoardSignalIsDetectedNearMa250() {
        val bars = flatBars(259) + DailyBar(
            tsCode = stock.tsCode,
            tradeDate = "20261231",
            open = 10.0,
            high = 11.0,
            low = 9.8,
            close = 10.95,
            preClose = 10.0,
            pctChg = 9.5,
            volume = 2_000.0,
            amount = 60_000_000.0,
        )

        val signals = StrategyEngine.evaluate(stock, bars, StrategyConfig())

        assertTrue(signals.any { it.strategy == "年线首板" })
    }

    @Test
    fun nineYangSignalIsDetectedWhenMostRecentCandlesAreBullishNearMa250() {
        val base = flatBars(251)
        val lastNine = (0 until 9).map { offset ->
            DailyBar(
                tsCode = stock.tsCode,
                tradeDate = "202612${(23 + offset).toString().padStart(2, '0')}",
                open = 10.00 + offset * 0.01,
                high = 10.20 + offset * 0.01,
                low = 9.95 + offset * 0.01,
                close = 10.08 + offset * 0.01,
                preClose = 10.00 + offset * 0.01,
                pctChg = 0.8,
                volume = 1_000.0,
                amount = 60_000_000.0,
            )
        }

        val signals = StrategyEngine.evaluate(stock, base + lastNine, StrategyConfig())

        assertTrue(signals.any { it.strategy == "九阳蓄势" })
    }

    @Test
    fun gameKLineSignalIsDetectedWhenBullishCandleRepairsPreviousBearishCandle() {
        val yesterday = DailyBar(
            tsCode = stock.tsCode,
            tradeDate = "20261230",
            open = 10.20,
            high = 10.25,
            low = 9.55,
            close = 9.60,
            preClose = 10.0,
            pctChg = -4.0,
            volume = 1_000.0,
            amount = 60_000_000.0,
        )
        val today = DailyBar(
            tsCode = stock.tsCode,
            tradeDate = "20261231",
            open = 9.70,
            high = 10.30,
            low = 9.65,
            close = 10.25,
            preClose = 9.60,
            pctChg = 6.77,
            volume = 1_300.0,
            amount = 80_000_000.0,
        )

        val signals = StrategyEngine.evaluate(stock, flatBars(258) + yesterday + today, StrategyConfig())

        assertTrue(signals.any { it.strategy == "博弈K" })
    }

    @Test
    fun insufficientBarsReturnNoSignals() {
        val signals = StrategyEngine.evaluate(stock, flatBars(120), StrategyConfig())

        assertTrue(signals.isEmpty())
    }

    @Test
    fun stStockReturnsNoSignals() {
        val stStock = stock.copy(isSt = true)

        val signals = StrategyEngine.evaluate(stStock, flatBars(260), StrategyConfig())

        assertTrue(signals.isEmpty())
    }

    @Test
    fun minimumAmountIsAGlobalHardFilter() {
        val bars = flatBars(259) + DailyBar(
            tsCode = stock.tsCode,
            tradeDate = "20261231",
            open = 10.0,
            high = 11.0,
            low = 9.8,
            close = 10.95,
            preClose = 10.0,
            pctChg = 9.5,
            volume = 2_000.0,
            amount = 49_000_000.0,
        )

        val signals = StrategyEngine.evaluate(stock, bars, StrategyConfig(minAmount = 50_000_000.0))

        assertTrue(signals.isEmpty())
    }

    @Test
    fun lowLevelStartSignalIsDetectedAfterCompressedBaseAndVolumeBreakout() {
        val today = DailyBar(
            tsCode = stock.tsCode,
            tradeDate = "20261231",
            open = 10.0,
            high = 10.35,
            low = 9.98,
            close = 10.30,
            preClose = 10.0,
            pctChg = 3.0,
            volume = 1_300.0,
            amount = 60_000_000.0,
        )

        val signals = StrategyEngine.evaluate(stock, flatBars(259) + today, StrategyConfig())

        assertTrue(signals.any { it.strategy == "低位启动" })
    }

    @Test
    fun mainBoardMoveBelowNearLimitThresholdIsNotFirstBoard() {
        val today = DailyBar(
            tsCode = stock.tsCode,
            tradeDate = "20261231",
            open = 10.0,
            high = 10.95,
            low = 9.9,
            close = 10.89,
            preClose = 10.0,
            pctChg = 8.9,
            volume = 2_000.0,
            amount = 60_000_000.0,
        )

        val signals = StrategyEngine.evaluate(stock, flatBars(259) + today, StrategyConfig())

        assertTrue(signals.none { it.strategy == "年线首板" })
    }

    @Test
    fun buildThreeYangSignalIsDetectedWithConfirmedThreefoldVolume() {
        val firstYang = strategyBar("20261229", 10.05, 10.30, 10.00, 10.30, 10.00, 3.0, 3_000.0)
        val secondYang = strategyBar("20261230", 10.32, 10.62, 10.30, 10.60, 10.30, 2.91, 2_000.0)
        val signalYang = strategyBar("20261231", 10.62, 10.92, 10.60, 10.90, 10.60, 2.83, 1_500.0)

        val signals = StrategyEngine.evaluate(
            stock,
            flatBars(257) + firstYang + secondYang + signalYang,
            StrategyConfig(),
        )

        assertTrue(signals.any { it.strategy == "建仓三阳" })
    }

    @Test
    fun buildThreeYangRejectsVolumeBelowThreeTimesPreviousDay() {
        val firstYang = strategyBar("20261229", 10.05, 10.30, 10.00, 10.30, 10.00, 3.0, 2_999.0)
        val secondYang = strategyBar("20261230", 10.32, 10.62, 10.30, 10.60, 10.30, 2.91, 2_000.0)
        val signalYang = strategyBar("20261231", 10.62, 10.92, 10.60, 10.90, 10.60, 2.83, 1_500.0)

        val signals = StrategyEngine.evaluate(
            stock,
            flatBars(257) + firstYang + secondYang + signalYang,
            StrategyConfig(),
        )

        assertTrue(signals.none { it.strategy == "建仓三阳" })
    }

    @Test
    fun buildThreeYangRejectsLimitUpAsOnlyBaldBull() {
        val firstYang = strategyBar("20261229", 10.20, 11.00, 10.10, 11.00, 10.00, 10.0, 3_000.0)
        val secondYang = strategyBar("20261230", 11.02, 11.40, 11.00, 11.35, 11.00, 3.18, 2_000.0)
        val signalYang = strategyBar("20261231", 11.37, 11.70, 11.35, 11.65, 11.35, 2.64, 1_500.0)

        val signals = StrategyEngine.evaluate(
            stock,
            flatBars(257) + firstYang + secondYang + signalYang,
            StrategyConfig(),
        )

        assertTrue(signals.none { it.strategy == "建仓三阳" })
    }

    @Test
    fun liftThreeYangSignalIsDetectedOnThirdRisingPriceAndVolumeDay() {
        val firstYang = strategyBar("20261229", 10.05, 10.35, 10.00, 10.30, 10.00, 3.0, 1_000.0)
        val secondYang = strategyBar("20261230", 10.32, 10.65, 10.30, 10.60, 10.30, 2.91, 1_200.0)
        val thirdYang = strategyBar("20261231", 10.62, 11.02, 10.60, 11.00, 10.60, 3.77, 1_400.0)

        val signals = StrategyEngine.evaluate(
            stock,
            flatBars(257) + firstYang + secondYang + thirdYang,
            StrategyConfig(),
        )

        assertTrue(signals.any { it.strategy == "拉升三阳" })
    }

    @Test
    fun liftThreeYangRejectsNonIncreasingVolume() {
        val firstYang = strategyBar("20261229", 10.05, 10.35, 10.00, 10.30, 10.00, 3.0, 1_000.0)
        val secondYang = strategyBar("20261230", 10.32, 10.65, 10.30, 10.60, 10.30, 2.91, 1_200.0)
        val thirdYang = strategyBar("20261231", 10.62, 11.02, 10.60, 11.00, 10.60, 3.77, 1_200.0)

        val signals = StrategyEngine.evaluate(
            stock,
            flatBars(257) + firstYang + secondYang + thirdYang,
            StrategyConfig(),
        )

        assertTrue(signals.none { it.strategy == "拉升三阳" })
    }

    @Test
    fun liftThreeYangRejectsFollowingBearishDay() {
        val firstYang = strategyBar("20261228", 10.05, 10.35, 10.00, 10.30, 10.00, 3.0, 1_000.0)
        val secondYang = strategyBar("20261229", 10.32, 10.65, 10.30, 10.60, 10.30, 2.91, 1_200.0)
        val thirdYang = strategyBar("20261230", 10.62, 11.02, 10.60, 11.00, 10.60, 3.77, 1_400.0)
        val signalBear = strategyBar("20261231", 10.95, 11.00, 10.70, 10.80, 11.00, -1.82, 1_050.0)

        val signals = StrategyEngine.evaluate(
            stock,
            flatBars(256) + firstYang + secondYang + thirdYang + signalBear,
            StrategyConfig(),
        )

        assertTrue(signals.none { it.strategy == "拉升三阳" })
    }

    private fun strategyBar(
        tradeDate: String,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        preClose: Double,
        pctChg: Double,
        volume: Double,
    ): DailyBar = DailyBar(
        tsCode = stock.tsCode,
        tradeDate = tradeDate,
        open = open,
        high = high,
        low = low,
        close = close,
        preClose = preClose,
        pctChg = pctChg,
        volume = volume,
        amount = 60_000_000.0,
    )

    private fun flatBars(count: Int): List<DailyBar> =
        (1..count).map { day ->
            DailyBar(
                tsCode = stock.tsCode,
                tradeDate = "2026${((day - 1) / 31 + 1).toString().padStart(2, '0')}${((day - 1) % 31 + 1).toString().padStart(2, '0')}",
                open = 10.0,
                high = 10.1,
                low = 9.9,
                close = 10.0,
                preClose = 10.0,
                pctChg = 0.0,
                volume = 1_000.0,
                amount = 60_000_000.0,
            )
        }
}
