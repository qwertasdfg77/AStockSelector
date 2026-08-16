package com.codex.astockselector.data

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
}
