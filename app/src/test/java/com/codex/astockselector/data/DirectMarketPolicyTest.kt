package com.codex.astockselector.data

import com.codex.astockselector.model.DailyBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectMarketPolicyTest {
    @Test
    fun emptyOrPartialStockListIsRejected() {
        assertFalse(DirectMarketRepository.isPlausibleStockListSize(0))
        assertFalse(DirectMarketRepository.isPlausibleStockListSize(2_999))
        assertTrue(DirectMarketRepository.isPlausibleStockListSize(3_000))
    }

    @Test
    fun majorityProbeDateWinsOverSingleNewerOutlier() {
        val selected = DirectMarketRepository.selectDetectedTradeDate(
            listOf(
                "20260814",
                "20260814",
                "20260814",
                "20260815",
            ),
        )

        assertEquals("20260814", selected)
    }

    @Test
    fun latestDateWinsWhenProbeCountsTie() {
        val selected = DirectMarketRepository.selectDetectedTradeDate(
            listOf("20260813", "20260814"),
        )

        assertEquals("20260814", selected)
    }

    @Test
    fun cacheBarsMustReachExpectedTradeDateBeforePrimarySourceIsAccepted() {
        val staleBars = listOf(bar("20260819"))
        val currentBars = listOf(bar("20260819"), bar("20260820"))

        assertFalse(DirectMarketRepository.hasExpectedLatestTradeDate(emptyList(), "20260820"))
        assertFalse(DirectMarketRepository.hasExpectedLatestTradeDate(staleBars, "20260820"))
        assertTrue(DirectMarketRepository.hasExpectedLatestTradeDate(currentBars, "20260820"))
        assertTrue(DirectMarketRepository.hasExpectedLatestTradeDate(staleBars, ""))
    }

    private fun bar(tradeDate: String): DailyBar =
        DailyBar(
            tsCode = "600000.SH",
            tradeDate = tradeDate,
            open = 10.0,
            high = 10.2,
            low = 9.8,
            close = 10.1,
            preClose = 10.0,
            pctChg = 1.0,
            volume = 1_000_000.0,
            amount = 100_000_000.0,
        )
}
