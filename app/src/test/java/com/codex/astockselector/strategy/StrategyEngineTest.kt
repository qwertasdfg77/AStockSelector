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
