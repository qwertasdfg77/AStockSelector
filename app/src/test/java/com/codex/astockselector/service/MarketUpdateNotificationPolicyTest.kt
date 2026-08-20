package com.codex.astockselector.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketUpdateNotificationPolicyTest {
    @Test
    fun repeatedTextIsNotPostedAgain() {
        assertFalse(
            shouldPostMarketUpdateNotification(
                force = false,
                nowMs = 2_000L,
                text = "阶段3/5：正在补K线 10/100",
                lastAtMs = 1_000L,
                lastStage = "阶段3/5",
                lastText = "阶段3/5：正在补K线 10/100",
            ),
        )
    }

    @Test
    fun sameStageProgressIsLimitedToOncePerSecond() {
        val nextText = "阶段3/5：正在补K线 20/100"
        assertFalse(
            shouldPostMarketUpdateNotification(
                force = false,
                nowMs = 1_999L,
                text = nextText,
                lastAtMs = 1_000L,
                lastStage = "阶段3/5",
                lastText = "阶段3/5：正在补K线 10/100",
            ),
        )
        assertTrue(
            shouldPostMarketUpdateNotification(
                force = false,
                nowMs = 2_000L,
                text = nextText,
                lastAtMs = 1_000L,
                lastStage = "阶段3/5",
                lastText = "阶段3/5：正在补K线 10/100",
            ),
        )
    }

    @Test
    fun stageChangesAndForcedFinalStatePostImmediately() {
        assertTrue(
            shouldPostMarketUpdateNotification(
                force = false,
                nowMs = 1_100L,
                text = "阶段4/5：正在保存K线",
                lastAtMs = 1_000L,
                lastStage = "阶段3/5",
                lastText = "阶段3/5：正在补K线 10/100",
            ),
        )
        assertTrue(
            shouldPostMarketUpdateNotification(
                force = true,
                nowMs = 1_100L,
                text = "智能更新筛选完成",
                lastAtMs = 1_000L,
                lastStage = "阶段5/5",
                lastText = "阶段5/5：正在筛选",
            ),
        )
    }
}
