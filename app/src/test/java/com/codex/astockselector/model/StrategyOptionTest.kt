package com.codex.astockselector.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyOptionTest {
    @Test
    fun nineUniqueStrategiesAreAvailable() {
        assertEquals(9, StrategyOption.entries.size)
        assertEquals(9, StrategyOption.entries.map { it.strategyName }.toSet().size)
    }

    @Test
    fun oldSelectionDoesNotAutomaticallyEnableNewStrategies() {
        val names = setOf("FirstBoard", "GameKLine")
        assertEquals(setOf(StrategyOption.FirstBoard, StrategyOption.GameKLine), StrategyOption.fromSavedNames(names))
        assertFalse(StrategyOption.PanicRepair in StrategyOption.fromSavedNames(names))
    }

    @Test
    fun newStrategyNamesRoundTripThroughPreferences() {
        val selected = setOf(StrategyOption.PanicRepair, StrategyOption.TrendEngulf, StrategyOption.PullbackRestart)
        assertEquals(selected, StrategyOption.fromSavedNames(selected.map { it.name }.toSet()))
    }

    @Test
    fun missingOrObsoletePreferencesPreserveSixStrategyDefault() {
        assertEquals(6, StrategyOption.fromSavedNames(null).size)
        assertEquals(StrategyOption.defaultSelection, StrategyOption.fromSavedNames(setOf("RemovedStrategy")))
        assertTrue(StrategyOption.FirstBoard in StrategyOption.defaultSelection)
    }
}
