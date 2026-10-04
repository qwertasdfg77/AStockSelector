package com.codex.astockselector.model

enum class StrategyOption(val title: String, val strategyName: String = title) {
    FirstBoard("年线首板"),
    NineYang("九阳蓄势"),
    GameKLine("博弈K"),
    LowLevelStart("低位启动"),
    BuildThreeYang("建仓三阳"),
    LiftThreeYang("拉升三阳"),
    PanicRepair("恐慌修复"),
    TrendEngulf("趋势反包"),
    PullbackRestart("缩量再起");

    companion object {
        val defaultSelection: Set<StrategyOption> = setOf(
            FirstBoard, NineYang, GameKLine, LowLevelStart, BuildThreeYang, LiftThreeYang,
        )

        fun fromSavedNames(names: Set<String>?): Set<StrategyOption> =
            names?.mapNotNull { name -> entries.firstOrNull { it.name == name } }
                ?.toSet()
                ?.ifEmpty { defaultSelection }
                ?: defaultSelection
    }
}
