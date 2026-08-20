package com.codex.astockselector.data

import com.codex.astockselector.model.MarketSegment
import com.codex.astockselector.model.SignalLevel
import com.codex.astockselector.model.StockProfile
import com.codex.astockselector.model.StrategySignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalSnapshotPolicyTest {
    @Test
    fun emptySignalSnapshotCanBeReused() {
        val saved = snapshot(signals = emptyList())

        assertTrue(SignalSnapshotPolicy.canReuse(saved, "20260814", RULE_KEY))
    }

    @Test
    fun mismatchedCacheDateCannotBeReused() {
        val saved = snapshot(signals = emptyList())

        assertFalse(SignalSnapshotPolicy.canReuse(saved, "20260815", RULE_KEY))
    }

    @Test
    fun firstSignalAfterPreviousEmptyDayIsMarkedNew() {
        val saved = snapshot(signals = emptyList())
        val signal = signal("600000.SH")

        val newCodes = SignalSnapshotPolicy.newSignalCodes(
            currentSignals = listOf(signal),
            saved = saved,
            ruleKey = RULE_KEY,
            reusedPreviousResult = false,
        )

        assertEquals(setOf("600000.SH"), newCodes)
    }

    @Test
    fun reusedSnapshotKeepsSavedNewMarkers() {
        val saved = snapshot(signals = listOf(signal("600000.SH")), newCodes = setOf("600000.SH"))

        val newCodes = SignalSnapshotPolicy.newSignalCodes(
            currentSignals = saved.signals,
            saved = saved,
            ruleKey = RULE_KEY,
            reusedPreviousResult = true,
        )

        assertEquals(setOf("600000.SH"), newCodes)
    }

    @Test
    fun numericallyEquivalentRuleKeysMatch() {
        assertTrue(
            SignalSnapshotPolicy.rulesEquivalent(
                "rules=v|minAmount=50000000.000000|nearMaPct=0.050000",
                "rules=v|minAmount=50000000|nearMaPct=0.05",
            ),
        )
    }

    @Test
    fun differentRuleVersionsCannotReuseSnapshot() {
        assertFalse(
            SignalSnapshotPolicy.rulesEquivalent(
                "rules=three_yang_v1|minAmount=50000000",
                "rules=three_yang_balanced_v2|minAmount=50000000",
            ),
        )
    }

    private fun snapshot(
        signals: List<StrategySignal>,
        newCodes: Set<String> = emptySet(),
    ): SavedSignalSnapshot =
        SavedSignalSnapshot(
            signals = signals,
            dataSource = "智能更新",
            statusText = "完成",
            cacheDate = "20260814",
            ruleKey = RULE_KEY,
            newSignalCodes = newCodes,
        )

    private fun signal(code: String): StrategySignal =
        StrategySignal(
            tradeDate = "20260814",
            stock = StockProfile(code, "测试股票", MarketSegment.MAIN, "20200101", false),
            strategy = "九阳蓄势",
            score = 85,
            level = SignalLevel.NORMAL,
            reasons = emptyList(),
            metrics = emptyList(),
            buyTrigger = "",
            stopLoss = "",
        )

    private companion object {
        const val RULE_KEY = "rules=v|minAmount=50000000.000000|nearMaPct=0.050000"
    }
}
