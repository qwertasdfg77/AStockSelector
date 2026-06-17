package com.codex.astockselector.data

import android.content.Context
import com.codex.astockselector.model.MarketSegment
import com.codex.astockselector.model.RuleCheck
import com.codex.astockselector.model.SignalLevel
import com.codex.astockselector.model.StockProfile
import com.codex.astockselector.model.StrategySignal
import org.json.JSONArray
import org.json.JSONObject

data class SavedSignalSnapshot(
    val signals: List<StrategySignal>,
    val dataSource: String,
    val statusText: String,
    val cacheDate: String = "",
    val ruleKey: String = "",
    val newSignalCodes: Set<String> = emptySet(),
)

object LastSignalStore {
    private const val PREFS_NAME = "last_signal_snapshot"
    private const val KEY_PAYLOAD = "payload"

    fun load(context: Context): SavedSignalSnapshot? {
        val payload = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PAYLOAD, null)
            ?: return null

        return runCatching {
            val root = JSONObject(payload)
            SavedSignalSnapshot(
                signals = root.optJSONArray("signals").toSignalList(),
                dataSource = root.optString("dataSource", "智能更新"),
                statusText = root.optString("statusText", "已读取上次筛选结果。"),
                cacheDate = root.optString("cacheDate"),
                ruleKey = root.optString("ruleKey"),
                newSignalCodes = root.optJSONArray("newSignalCodes").toStringList().toSet(),
            )
        }.getOrNull()
    }

    fun save(
        context: Context,
        signals: List<StrategySignal>,
        dataSource: String,
        statusText: String,
        cacheDate: String = "",
        ruleKey: String = "",
        newSignalCodes: Set<String> = emptySet(),
    ) {
        val root = JSONObject()
            .put("dataSource", dataSource)
            .put("statusText", statusText)
            .put("cacheDate", cacheDate)
            .put("ruleKey", ruleKey)
            .put("newSignalCodes", newSignalCodes.sorted().toStringJsonArray())
            .put("signals", signals.toSignalJsonArray())

        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAYLOAD, root.toString())
            .apply()
    }

    private fun List<StrategySignal>.toSignalJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { signal ->
            array.put(
                JSONObject()
                    .put("tradeDate", signal.tradeDate)
                    .put("stock", signal.stock.toJson())
                    .put("strategy", signal.strategy)
                    .put("score", signal.score)
                    .put("level", signal.level.name)
                    .put("reasons", signal.reasons.toStringJsonArray())
                    .put("metrics", signal.metrics.toPairArray())
                    .put("buyTrigger", signal.buyTrigger)
                    .put("stopLoss", signal.stopLoss)
                    .put("ruleChecks", signal.ruleChecks.toRuleCheckArray()),
            )
        }
        return array
    }

    private fun StockProfile.toJson(): JSONObject =
        JSONObject()
            .put("tsCode", tsCode)
            .put("name", name)
            .put("market", market.name)
            .put("listDate", listDate)
            .put("isSt", isSt)

    private fun List<String>.toStringJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { array.put(it) }
        return array
    }

    private fun List<Pair<String, String>>.toPairArray(): JSONArray {
        val array = JSONArray()
        forEach { (label, value) ->
            array.put(
                JSONObject()
                    .put("label", label)
                    .put("value", value),
            )
        }
        return array
    }

    private fun List<RuleCheck>.toRuleCheckArray(): JSONArray {
        val array = JSONArray()
        forEach { check ->
            array.put(
                JSONObject()
                    .put("label", check.label)
                    .put("passed", check.passed),
            )
        }
        return array
    }

    private fun JSONArray?.toSignalList(): List<StrategySignal> {
        if (this == null) return emptyList()
        val signals = mutableListOf<StrategySignal>()
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val stock = item.optJSONObject("stock").toStockProfile()
            signals += StrategySignal(
                tradeDate = item.optString("tradeDate"),
                stock = stock,
                strategy = item.optString("strategy"),
                score = item.optInt("score"),
                level = item.optString("level").toSignalLevel(),
                reasons = item.optJSONArray("reasons").toStringList(),
                metrics = item.optJSONArray("metrics").toPairList(),
                buyTrigger = item.optString("buyTrigger"),
                stopLoss = item.optString("stopLoss"),
                ruleChecks = item.optJSONArray("ruleChecks").toRuleCheckList(),
            )
        }
        return signals
    }

    private fun JSONObject?.toStockProfile(): StockProfile {
        val item = this ?: JSONObject()
        return StockProfile(
            tsCode = item.optString("tsCode"),
            name = item.optString("name"),
            market = item.optString("market").toMarketSegment(),
            listDate = item.optString("listDate"),
            isSt = item.optBoolean("isSt", false),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val items = mutableListOf<String>()
        for (index in 0 until length()) {
            items += optString(index)
        }
        return items
    }

    private fun JSONArray?.toPairList(): List<Pair<String, String>> {
        if (this == null) return emptyList()
        val pairs = mutableListOf<Pair<String, String>>()
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            pairs += item.optString("label") to item.optString("value")
        }
        return pairs
    }

    private fun JSONArray?.toRuleCheckList(): List<RuleCheck> {
        if (this == null) return emptyList()
        val checks = mutableListOf<RuleCheck>()
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            checks += RuleCheck(
                label = item.optString("label"),
                passed = item.optBoolean("passed"),
            )
        }
        return checks
    }

    private fun String.toMarketSegment(): MarketSegment =
        runCatching { MarketSegment.valueOf(this) }.getOrDefault(MarketSegment.UNKNOWN)

    private fun String.toSignalLevel(): SignalLevel =
        runCatching { SignalLevel.valueOf(this) }.getOrDefault(SignalLevel.NORMAL)
}
