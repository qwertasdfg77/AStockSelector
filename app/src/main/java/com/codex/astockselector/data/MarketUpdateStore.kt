package com.codex.astockselector.data

import com.codex.astockselector.model.StrategySignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class MarketUpdateState(
    val isRunning: Boolean = false,
    val dataSource: String = "未更新",
    val statusText: String = "暂无筛选结果。点击刷新数据或设置页的智能更新并筛选后显示。",
    val signals: List<StrategySignal> = emptyList(),
    val finished: Boolean = false,
    val errorText: String? = null,
)

object MarketUpdateStore {
    private val _state = MutableStateFlow(MarketUpdateState())
    val state: StateFlow<MarketUpdateState> = _state

    fun start(message: String, dataSource: String = "智能更新") {
        _state.value = _state.value.copy(
            isRunning = true,
            dataSource = dataSource,
            statusText = message,
            finished = false,
            errorText = null,
        )
    }

    fun progress(message: String, dataSource: String = _state.value.dataSource) {
        _state.value = _state.value.copy(
            isRunning = true,
            dataSource = dataSource,
            statusText = message,
            finished = false,
            errorText = null,
        )
    }

    fun complete(signals: List<StrategySignal>, message: String, dataSource: String = _state.value.dataSource) {
        _state.value = _state.value.copy(
            isRunning = false,
            dataSource = dataSource,
            statusText = message,
            signals = signals,
            finished = true,
            errorText = null,
        )
    }

    fun fail(message: String, dataSource: String = _state.value.dataSource) {
        _state.value = _state.value.copy(
            isRunning = false,
            dataSource = dataSource,
            statusText = message,
            finished = true,
            errorText = message,
        )
    }
}
