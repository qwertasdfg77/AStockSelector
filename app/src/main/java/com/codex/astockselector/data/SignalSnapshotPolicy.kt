package com.codex.astockselector.data

import com.codex.astockselector.model.StrategySignal
import java.util.Locale

object SignalSnapshotPolicy {
    fun canReuse(
        saved: SavedSignalSnapshot?,
        cacheDate: String,
        ruleKey: String,
    ): Boolean =
        saved != null &&
            cacheDate.isNotBlank() &&
            saved.cacheDate == cacheDate &&
            rulesEquivalent(saved.ruleKey, ruleKey)

    fun newSignalCodes(
        currentSignals: List<StrategySignal>,
        saved: SavedSignalSnapshot?,
        ruleKey: String,
        reusedPreviousResult: Boolean,
    ): Set<String> {
        if (reusedPreviousResult) return saved?.newSignalCodes.orEmpty()
        if (saved == null || !rulesEquivalent(saved.ruleKey, ruleKey)) return emptySet()

        val previousCodes = saved.signals.map { it.stock.tsCode }.toSet()
        return currentSignals.map { it.stock.tsCode }.toSet() - previousCodes
    }

    fun rulesEquivalent(first: String, second: String): Boolean =
        normalizeRuleKey(first) == normalizeRuleKey(second)

    private fun normalizeRuleKey(value: String): String =
        value.split("|").joinToString("|") { part ->
            val pieces = part.split("=", limit = 2)
            if (pieces.size != 2) {
                part
            } else {
                val normalizedValue = pieces[1].toDoubleOrNull()
                    ?.let { numericValue ->
                        String.format(Locale.US, "%.6f", numericValue)
                            .trimEnd('0')
                            .trimEnd('.')
                    }
                    ?: pieces[1]
                "${pieces[0]}=$normalizedValue"
            }
        }
}
