package com.codex.astockselector.model

enum class MarketSegment(
    val displayName: String,
    val limitUpPct: Double,
) {
    MAIN("主板", 10.0),
    CHINEXT("创业板", 20.0),
    STAR("科创板", 20.0),
    BSE("北交所", 30.0),
    UNKNOWN("其他", 10.0),
}
