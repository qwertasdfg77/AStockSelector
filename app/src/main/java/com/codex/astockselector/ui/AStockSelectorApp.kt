package com.codex.astockselector.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codex.astockselector.data.AppUpdateRepository
import com.codex.astockselector.data.CacheMarketRepository
import com.codex.astockselector.data.CustomFilterStore
import com.codex.astockselector.data.LastSignalStore
import com.codex.astockselector.data.MarketCacheInfo
import com.codex.astockselector.data.MarketUpdateStore
import com.codex.astockselector.data.SavedSignalSnapshot
import com.codex.astockselector.model.CustomConditionMode
import com.codex.astockselector.model.CustomFilterConfig
import com.codex.astockselector.model.CustomMatchMode
import com.codex.astockselector.model.CustomScheme
import com.codex.astockselector.model.SignalLevel
import com.codex.astockselector.model.StrategyConfig
import com.codex.astockselector.model.StrategySignal
import com.codex.astockselector.service.MarketUpdateService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AStockSelectorApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateState by MarketUpdateStore.state.collectAsState()

    var selectedTab by remember { mutableStateOf(AppTab.Today) }
    var nearMaPct by remember { mutableDoubleStateOf(0.05) }
    var minAmount by remember { mutableDoubleStateOf(50_000_000.0) }
    var notifyEnabled by remember { mutableStateOf(true) }
    var enabledStrategies by remember { mutableStateOf(loadSelectedStrategies(context)) }
    var dataSource by remember { mutableStateOf(updateState.dataSource) }
    var statusText by remember { mutableStateOf(updateState.statusText) }
    var isLoading by remember { mutableStateOf(updateState.isRunning) }
    var cacheInfo by remember { mutableStateOf(MarketCacheInfo()) }
    var appUpdateStatus by remember { mutableStateOf("尚未检测程序更新。") }
    var isCheckingAppUpdate by remember { mutableStateOf(false) }
    var customConfig by remember { mutableStateOf(CustomFilterStore.load(context, CustomScheme.Default)) }
    var customSignals by remember { mutableStateOf(emptyList<StrategySignal>()) }
    var customStatusText by remember { mutableStateOf("还没有自定义筛选结果。") }
    var isCustomLoading by remember { mutableStateOf(false) }

    val config = StrategyConfig(
        nearMaPct = nearMaPct,
        minAmount = minAmount,
    )
    var signals by remember { mutableStateOf(emptyList<StrategySignal>()) }
    var newSignalCodes by remember { mutableStateOf(emptySet<String>()) }
    val visibleSignals = remember(signals, enabledStrategies, newSignalCodes) {
        signals.filterByStrategies(enabledStrategies).prioritizeNewSignals(newSignalCodes)
    }
    val strategySummary = enabledStrategies.summary()
    val displayStatusText = remember(statusText, isLoading, signals, visibleSignals, strategySummary) {
        buildDisplayStatusText(
            rawStatusText = statusText,
            isLoading = isLoading,
            allSignals = signals,
            visibleSignals = visibleSignals,
            strategySummary = strategySummary,
        )
    }

    LaunchedEffect(updateState) {
        dataSource = updateState.dataSource
        statusText = updateState.statusText
        isLoading = updateState.isRunning
        if (updateState.finished) {
            if (updateState.errorText == null) {
                val latestCacheInfo = CacheMarketRepository.cacheInfo(context)
                val saved = LastSignalStore.load(context)
                val nextNewSignalCodes = updateState.signals.newSignalCodesComparedWith(
                    saved = saved,
                    ruleKey = config.signalCacheKey(),
                    statusText = updateState.statusText,
                )
                cacheInfo = latestCacheInfo
                newSignalCodes = nextNewSignalCodes
                signals = updateState.signals
                LastSignalStore.save(
                    context = context,
                    signals = updateState.signals,
                    dataSource = updateState.dataSource,
                    statusText = updateState.statusText,
                    cacheDate = latestCacheInfo.dateEnd,
                    ruleKey = config.signalCacheKey(),
                    newSignalCodes = nextNewSignalCodes,
                )
            }
            selectedTab = AppTab.Today
        } else if (updateState.signals.isNotEmpty()) {
            signals = updateState.signals
        }
    }

    LaunchedEffect(Unit) {
        cacheInfo = CacheMarketRepository.cacheInfo(context)
        LastSignalStore.load(context)?.let { saved ->
            if (saved.ruleKey.equivalentSignalRuleKey(config.signalCacheKey())) {
                signals = saved.signals
                dataSource = saved.dataSource
                statusText = saved.statusText
                newSignalCodes = saved.newSignalCodes
            } else {
                signals = emptyList()
                newSignalCodes = emptySet()
                statusText = "战法规则已更新，请点击刷新数据重新筛选。"
            }
        }
    }

    fun toggleStrategy(option: StrategyOption) {
        val nextStrategies = if (option in enabledStrategies) {
            if (enabledStrategies.size == 1) {
                statusText = "至少保留一个战法。"
                enabledStrategies
            } else {
                enabledStrategies - option
            }
        } else {
            enabledStrategies + option
        }
        enabledStrategies = nextStrategies
        saveSelectedStrategies(context, nextStrategies)
    }

    fun smartUpdateData() {
        selectedTab = AppTab.Today
        dataSource = "智能更新"
        val ruleKey = config.signalCacheKey()
        isLoading = true
        statusText = "智能更新已启动：正在检查缓存与规则是否可复用..."
        MarketUpdateStore.start(statusText, "智能更新")

        scope.launch {
            val freshness = CacheMarketRepository.cacheFreshness(context)
            cacheInfo = freshness.info
            val saved = LastSignalStore.load(context)
            val canReuse = freshness.isLatest &&
                saved != null &&
                saved.cacheDate == freshness.info.dateEnd &&
                saved.ruleKey.equivalentSignalRuleKey(ruleKey) &&
                saved.signals.isNotEmpty()

            if (canReuse) {
                val message = "缓存已是收盘最新数据（${freshness.info.dateEnd}），规则未变化，直接使用上次全量筛选结果。"
                MarketUpdateStore.complete(saved.signals, message, "智能更新")
                return@launch
            }

            statusText = if (freshness.isLatest) {
                "缓存已是收盘最新数据（${freshness.info.dateEnd}），规则或结果已变化，开始本地重新筛选..."
            } else {
                val currentDate = if (freshness.info.exists) freshness.info.dateEnd.ifBlank { "无" } else "无缓存"
                "缓存最新日期 $currentDate，目标收盘日期 ${freshness.expectedDate}，开始更新并筛选..."
            }
            MarketUpdateStore.start(statusText, "智能更新")

            val intent = Intent(context, MarketUpdateService::class.java)
                .putExtra(MarketUpdateService.EXTRA_NEAR_MA_PCT, nearMaPct)
                .putExtra(MarketUpdateService.EXTRA_MIN_AMOUNT, minAmount)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    fun refreshCacheInfo() {
        scope.launch {
            cacheInfo = CacheMarketRepository.cacheInfo(context)
            statusText = if (cacheInfo.exists) {
                "已发现本地缓存：${cacheInfo.stockCount} 只股票，${cacheInfo.dailyBarCount} 行K线。"
            } else {
                "未发现本地缓存 market_cache.db。"
            }
        }
    }

    fun checkAppUpdate() {
        if (isCheckingAppUpdate) return
        isCheckingAppUpdate = true
        appUpdateStatus = "正在从 GitHub 检测程序更新..."
        scope.launch {
            runCatching {
                AppUpdateRepository.checkLatest(context)
            }.onSuccess { result ->
                if (result.hasUpdate) {
                    appUpdateStatus = "发现新版 ${result.latest.versionName}，正在打开下载链接。"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.latest.apkUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } else {
                    appUpdateStatus = "当前已是最新版本：${result.latest.versionName}。"
                }
            }.onFailure { error ->
                appUpdateStatus = "检测程序更新失败：${error.message ?: "未知错误"}"
            }
            isCheckingAppUpdate = false
        }
    }

    fun requestBackgroundPermission() {
        requestIgnoreBatteryOptimizations(context)
    }

    fun runCustomFilter() {
        if (isCustomLoading) return
        selectedTab = AppTab.Custom
        isCustomLoading = true
        customStatusText = "准备按${customConfig.scheme.title}开始自定义筛选..."
        scope.launch {
            runCatching {
                CacheMarketRepository.loadCustomSignals(context, customConfig) { message ->
                    customStatusText = message
                }
            }.onSuccess { result ->
                customSignals = result
                customStatusText = "自定义筛选完成：${customConfig.scheme.title} 命中 ${result.size} 只股票。"
            }.onFailure { error ->
                customStatusText = "自定义筛选失败：${error.message ?: "未知错误"}"
            }
            isCustomLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("A股年线选股", fontWeight = FontWeight.SemiBold)
                        Text(
                            "$dataSource · ${if (isLoading) "更新中 · $strategySummary" else strategySummary}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = !isLoading,
                        onClick = ::smartUpdateData,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新数据")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                AppTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.title) },
                    )
                }
            }

            when (selectedTab) {
                AppTab.Today -> TodaySignals(
                    signals = visibleSignals,
                    newSignalCodes = newSignalCodes,
                    dataSource = dataSource,
                    statusText = displayStatusText,
                    isLoading = isLoading,
                    strategySummary = strategySummary,
                )

                AppTab.Custom -> CustomFilterPage(
                    config = customConfig,
                    onConfigChange = { customConfig = it },
                    signals = customSignals,
                    statusText = customStatusText,
                    isLoading = isCustomLoading,
                    onRunFilter = ::runCustomFilter,
                    onSchemeSelected = { scheme ->
                        customConfig = CustomFilterStore.load(context, scheme)
                        customStatusText = "已切换到${scheme.title}。"
                    },
                    onSaveScheme = {
                        CustomFilterStore.save(context, customConfig)
                        customStatusText = "已保存${customConfig.scheme.title}。"
                    },
                    onCopyScheme = {
                        customConfig = CustomFilterStore.copyToNextScheme(context, customConfig)
                        customStatusText = "已复制并切换到${customConfig.scheme.title}。"
                    },
                )

                AppTab.Settings -> SettingsPage(
                    nearMaPct = nearMaPct,
                    onNearMaPctChange = { nearMaPct = it.toNearMaStep() },
                    minAmount = minAmount,
                    onMinAmountChange = { minAmount = it.toMinAmountStep() },
                    notifyEnabled = notifyEnabled,
                    onNotifyEnabledChange = { notifyEnabled = it },
                    enabledStrategies = enabledStrategies,
                    onStrategyToggle = ::toggleStrategy,
                    dataSource = dataSource,
                    statusText = displayStatusText,
                    isLoading = isLoading,
                    onSmartUpdateData = ::smartUpdateData,
                    appUpdateStatus = appUpdateStatus,
                    isCheckingAppUpdate = isCheckingAppUpdate,
                    onCheckAppUpdate = ::checkAppUpdate,
                    cacheInfo = cacheInfo,
                    onRefreshCacheInfo = ::refreshCacheInfo,
                    onRequestBackgroundPermission = ::requestBackgroundPermission,
                )
            }
        }
    }
}

private enum class AppTab(val title: String) {
    Today("今日信号"),
    Custom("自定义筛选"),
    Settings("设置"),
}

private enum class StrategyOption(
    val title: String,
    val strategyName: String,
) {
    FirstBoard("年线首板", "年线首板"),
    NineYang("九阳蓄势", "九阳蓄势"),
    GameKLine("博弈K", "博弈K"),
    LowLevelStart("低位启动", "低位启动"),
}

private fun List<StrategySignal>.filterByStrategies(options: Set<StrategyOption>): List<StrategySignal> {
    val selectedOptions = StrategyOption.entries.filter { it in options }
    val selectedStrategies = selectedOptions.map { it.strategyName }.toSet()

    return groupBy { it.stock.tsCode }
        .values
        .mapNotNull { stockSignals ->
            val signalsByStrategy = stockSignals
                .filter { it.strategy in selectedStrategies }
                .associateBy { it.strategy }

            if (selectedOptions.all { it.strategyName in signalsByStrategy }) {
                selectedOptions
                    .mapNotNull { option -> signalsByStrategy[option.strategyName] }
                    .mergeForDisplay()
            } else {
                null
            }
        }
}

private fun List<StrategySignal>.prioritizeNewSignals(newSignalCodes: Set<String>): List<StrategySignal> {
    if (newSignalCodes.isEmpty()) return this
    val (newSignals, oldSignals) = partition { it.stock.tsCode in newSignalCodes }
    return newSignals + oldSignals
}

private fun List<StrategySignal>.newSignalCodesComparedWith(
    saved: SavedSignalSnapshot?,
    ruleKey: String,
    statusText: String,
): Set<String> {
    if (statusText.contains("直接使用上次全量筛选结果")) {
        return saved?.newSignalCodes.orEmpty()
    }
    if (saved == null || !saved.ruleKey.equivalentSignalRuleKey(ruleKey) || saved.signals.isEmpty()) {
        return emptySet()
    }

    val previousCodes = saved.signals.map { it.stock.tsCode }.toSet()
    if (previousCodes.isEmpty()) return emptySet()
    return map { it.stock.tsCode }.toSet() - previousCodes
}

private fun String.equivalentSignalRuleKey(other: String): Boolean =
    normalizeSignalRuleKey() == other.normalizeSignalRuleKey()

private fun String.normalizeSignalRuleKey(): String =
    split("|").joinToString("|") { part ->
        val pieces = part.split("=", limit = 2)
        if (pieces.size != 2) {
            part
        } else {
            val value = pieces[1].toDoubleOrNull()
                ?.let { numericValue ->
                    String.format(java.util.Locale.US, "%.6f", numericValue)
                        .trimEnd('0')
                        .trimEnd('.')
                }
                ?: pieces[1]
            "${pieces[0]}=$value"
        }
    }

private fun Double.ruleValue(): String =
    String.format(java.util.Locale.US, "%.6f", this)
        .trimEnd('0')
        .trimEnd('.')

private fun Double.toNearMaStep(): Double =
    (this * 100.0).roundToInt().coerceIn(2, 10) / 100.0

private fun Double.toMinAmountStep(): Double =
    (this / 10_000_000.0).roundToInt().coerceIn(1, 30) * 10_000_000.0

private fun Double.roundToStep(min: Double, max: Double, step: Double): Double =
    ((this - min) / step).roundToInt()
        .let { min + it * step }
        .coerceIn(min, max)

private fun List<CustomConditionMode>.isActive(): Boolean =
    any { it != CustomConditionMode.Off }

private fun List<StrategySignal>.mergeForDisplay(): StrategySignal {
    if (size == 1) return first()

    val ordered = sortedBy { signal ->
        StrategyOption.entries.indexOfFirst { it.strategyName == signal.strategy }.let { index ->
            if (index >= 0) index else Int.MAX_VALUE
        }
    }
    val best = ordered.maxBy { it.score }
    val mergedStrategy = ordered.joinToString(" + ") { signal -> signal.strategy.displayStrategyTitle() }
    val mergedMetrics = ordered.flatMap { signal ->
        signal.metrics
            .filter { it.second.isNotBlank() }
            .map { metric -> "${signal.strategy.displayStrategyTitle()}-${metric.first}" to metric.second }
    }
    val mergedReasons = ordered.flatMap { signal ->
        signal.reasons.map { reason -> "${signal.strategy.displayStrategyTitle()}：$reason" }
    }.distinct()
    val mergedRuleChecks = ordered.flatMap { signal ->
        signal.ruleChecks.map { check ->
            check.copy(label = "${signal.strategy.displayStrategyTitle()}：${check.label}")
        }
    }

    return best.copy(
        strategy = mergedStrategy,
        score = ordered.maxOf { it.score },
        level = if (ordered.any { it.level == SignalLevel.STRONG }) SignalLevel.STRONG else SignalLevel.NORMAL,
        reasons = mergedReasons,
        metrics = mergedMetrics,
        buyTrigger = ordered.joinToString("；") { signal ->
            "${signal.strategy.displayStrategyTitle()}：${signal.buyTrigger.trimEnd('。')}"
        } + "。",
        stopLoss = ordered.joinToString("；") { signal ->
            "${signal.strategy.displayStrategyTitle()}：${signal.stopLoss.trimEnd('。')}"
        } + "。",
        ruleChecks = mergedRuleChecks,
    )
}

private fun String.displayStrategyTitle(): String =
    StrategyOption.entries.firstOrNull { it.strategyName == this }?.title ?: this

private fun StrategyConfig.signalCacheKey(): String =
    listOf(
        "rules=$SIGNAL_RULE_VERSION",
        "nearLimitRatio=${nearLimitRatio.ruleValue()}",
        "firstBoardLookback=$firstBoardLookback",
        "volumeMultiplier=${volumeMultiplier.ruleValue()}",
        "minAmount=${minAmount.toMinAmountStep().ruleValue()}",
        "nearMaPct=${nearMaPct.toNearMaStep().ruleValue()}",
        "maxFirstBoardMaDistancePct=${maxFirstBoardMaDistancePct.ruleValue()}",
        "maxNineYangRisePct=${maxNineYangRisePct.ruleValue()}",
        "minNineYangYangCount=$minNineYangYangCount",
        "minNineYangNearMaCount=$minNineYangNearMaCount",
        "nineYangMinScore=$nineYangMinScore",
        "gameKLineNearMaPct=${gameKLineNearMaPct.ruleValue()}",
        "reboundRatioThreshold=${reboundRatioThreshold.ruleValue()}",
        "closeStrengthThreshold=${closeStrengthThreshold.ruleValue()}",
        "gameKLineVolumeRatio=${gameKLineVolumeRatio.ruleValue()}",
        "gameKLineMinScore=$gameKLineMinScore",
    ).joinToString("|")

private fun Set<StrategyOption>.summary(): String =
    StrategyOption.entries
        .filter { it in this }
        .joinToString(" / ") { it.title }

private fun buildDisplayStatusText(
    rawStatusText: String,
    isLoading: Boolean,
    allSignals: List<StrategySignal>,
    visibleSignals: List<StrategySignal>,
    strategySummary: String,
): String {
    if (isLoading) {
        return if (allSignals.isEmpty()) {
            rawStatusText
        } else {
            "$rawStatusText 当前显示：$strategySummary ${visibleSignals.size} 只旧结果，刷新完成后自动替换。"
        }
    }

    if (allSignals.isEmpty()) return rawStatusText

    val prefix = if (rawStatusText.contains("直接使用上次全量筛选结果")) {
        "$rawStatusText "
    } else {
        ""
    }
    return if (visibleSignals.isEmpty()) {
        "${prefix}当前选择：$strategySummary，暂无同时命中；全量刷新结果 ${allSignals.size} 条。"
    } else {
        "${prefix}当前选择：$strategySummary，显示 ${visibleSignals.size} 只符合当前选择；全量刷新结果 ${allSignals.size} 条。"
    }
}

private const val STRATEGY_PREFS_NAME = "strategy_selection"
private const val STRATEGY_PREFS_KEY = "enabled_strategies"
private const val SIGNAL_RULE_VERSION = "20260616_low_level_start_v1"

private fun loadSelectedStrategies(context: Context): Set<StrategyOption> {
    val saved = context
        .getSharedPreferences(STRATEGY_PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet(STRATEGY_PREFS_KEY, null)
        ?: return StrategyOption.entries.toSet()

    val options = saved.mapNotNull { name ->
        StrategyOption.entries.firstOrNull { it.name == name }
    }.toSet()
    return options.ifEmpty { StrategyOption.entries.toSet() }
}

private fun saveSelectedStrategies(context: Context, options: Set<StrategyOption>) {
    context
        .getSharedPreferences(STRATEGY_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(STRATEGY_PREFS_KEY, options.map { it.name }.toSet())
        .apply()
}

@Composable
private fun TodaySignals(
    signals: List<StrategySignal>,
    newSignalCodes: Set<String>,
    dataSource: String,
    statusText: String,
    isLoading: Boolean,
    strategySummary: String,
) {
    SignalList(
        signals = signals,
        newSignalCodes = newSignalCodes,
        emptyText = "当前选择：$strategySummary，暂无同时命中。点击右上角刷新会更新全部战法全量结果。",
        header = {
            DataStatusCard(
                dataSource = dataSource,
                statusText = statusText,
                isLoading = isLoading,
            )
        },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CustomFilterPage(
    config: CustomFilterConfig,
    onConfigChange: (CustomFilterConfig) -> Unit,
    signals: List<StrategySignal>,
    statusText: String,
    isLoading: Boolean,
    onRunFilter: () -> Unit,
    onSchemeSelected: (CustomScheme) -> Unit,
    onSaveScheme: () -> Unit,
    onCopyScheme: () -> Unit,
) {
    SignalList(
        signals = signals,
        newSignalCodes = emptySet(),
        emptyText = "暂无自定义筛选结果。调整条件后点击开始筛选。",
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DataStatusCard(
                    dataSource = "自定义筛选",
                    statusText = statusText,
                    isLoading = isLoading,
                )

                SettingCard {
                    Text("筛选方案", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CustomScheme.entries.forEach { scheme ->
                            FilterChip(
                                selected = config.scheme == scheme,
                                onClick = { onSchemeSelected(scheme) },
                                enabled = !isLoading,
                                label = { Text(scheme.title) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading,
                            onClick = onSaveScheme,
                        ) {
                            Text("保存当前方案")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading,
                            onClick = onCopyScheme,
                        ) {
                            Text("复制方案")
                        }
                    }
                }

                SettingCard {
                    Text("条件组合方式", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CustomMatchMode.entries.forEach { mode ->
                            FilterChip(
                                selected = config.matchMode == mode,
                                onClick = { onConfigChange(config.copy(matchMode = mode)) },
                                enabled = !isLoading,
                                label = { Text(if (mode == CustomMatchMode.All) "全部满足（且）" else "任意满足（或）") },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "基础过滤始终是硬条件；这里控制“必须”条件之间按且还是按或组合，加分项只影响排序。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    onClick = onRunFilter,
                ) {
                    Text(if (isLoading) "正在自定义筛选..." else "开始筛选")
                }

                CustomBaseFilterCard(
                    config = config,
                    enabled = !isLoading,
                    onConfigChange = onConfigChange,
                )
                CustomTrendCard(config, !isLoading, onConfigChange)
                CustomMomentumCard(config, !isLoading, onConfigChange)
                CustomVolumeCard(config, !isLoading, onConfigChange)
                CustomPatternCard(config, !isLoading, onConfigChange)
                CustomMaPositionCard(config, !isLoading, onConfigChange)
                CustomLimitCard(config, !isLoading, onConfigChange)
                CustomVolatilityCard(config, !isLoading, onConfigChange)
            }
        },
    )
}

@Composable
private fun CustomBaseFilterCard(
    config: CustomFilterConfig,
    enabled: Boolean,
    onConfigChange: (CustomFilterConfig) -> Unit,
) {
    SettingCard {
        Text("基础过滤", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("排除 ST")
                Text("基础过滤不参与加分，未通过会直接排除。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Switch(
                checked = config.baseExcludeSt,
                onCheckedChange = { onConfigChange(config.copy(baseExcludeSt = it)) },
                enabled = enabled,
            )
        }
        AmountStepSlider(
            title = "最低成交额",
            value = config.baseMinAmount,
            min = 10_000_000.0,
            max = 300_000_000.0,
            step = 10_000_000.0,
            enabled = enabled,
            onValueChange = { onConfigChange(config.copy(baseMinAmount = it)) },
        )
        IntChoiceRow(
            title = "最少 K 线数量",
            selected = config.baseMinBars,
            values = listOf(120, 200, 260),
            enabled = enabled,
            onSelect = { onConfigChange(config.copy(baseMinBars = it)) },
        )
    }
}

@Composable
private fun CustomTrendCard(
    config: CustomFilterConfig,
    enabled: Boolean,
    onConfigChange: (CustomFilterConfig) -> Unit,
) {
    CustomModuleCard(
        title = "趋势类",
        active = listOf(config.trendCloseAboveMa, config.trendMa250FlatUp, config.trendBullAlignment, config.trendBreakHigh).isActive(),
        enabled = enabled,
        onActiveChange = { active ->
            onConfigChange(
                if (active) {
                    config.copy(trendCloseAboveMa = CustomConditionMode.Required)
                } else {
                    config.copy(
                        trendCloseAboveMa = CustomConditionMode.Off,
                        trendMa250FlatUp = CustomConditionMode.Off,
                        trendBullAlignment = CustomConditionMode.Off,
                        trendBreakHigh = CustomConditionMode.Off,
                    )
                },
            )
        },
    ) {
        ConditionStateRow("股价站上 MA${config.trendMaPeriod}", config.trendCloseAboveMa, enabled) {
            onConfigChange(config.copy(trendCloseAboveMa = it))
        }
        ConditionStateRow("MA250 走平或向上", config.trendMa250FlatUp, enabled) {
            onConfigChange(config.copy(trendMa250FlatUp = it))
        }
        ConditionStateRow("MA5 > MA10 > MA20 多头排列", config.trendBullAlignment, enabled) {
            onConfigChange(config.copy(trendBullAlignment = it))
        }
        ConditionStateRow("收盘价突破 N 日新高", config.trendBreakHigh, enabled) {
            onConfigChange(config.copy(trendBreakHigh = it))
        }
        IntChoiceRow("均线周期", config.trendMaPeriod, listOf(5, 10, 20, 60, 120, 250), enabled) {
            onConfigChange(config.copy(trendMaPeriod = it))
        }
        IntChoiceRow("趋势判断天数", config.trendDays, listOf(3, 5, 10), enabled) {
            onConfigChange(config.copy(trendDays = it))
        }
        StepSlider("新高周期：${config.trendHighDays}日", config.trendHighDays.toDouble(), 10.0, 120.0, 10.0, enabled) {
            onConfigChange(config.copy(trendHighDays = it.roundToInt()))
        }
        ToggleRow("要求短均线向上", config.trendRequireMaUp, enabled) {
            onConfigChange(config.copy(trendRequireMaUp = it))
        }
    }
}

@Composable
private fun CustomMomentumCard(
    config: CustomFilterConfig,
    enabled: Boolean,
    onConfigChange: (CustomFilterConfig) -> Unit,
) {
    CustomModuleCard(
        title = "涨幅动量类",
        active = listOf(
            config.momentumTodayRise,
            config.momentumPeriodRise,
            config.momentumConsecutiveRise,
            config.momentumNewHigh,
            config.momentumReboundRepair,
        ).isActive(),
        enabled = enabled,
        onActiveChange = { active ->
            onConfigChange(
                if (active) {
                    config.copy(momentumPeriodRise = CustomConditionMode.Score)
                } else {
                    config.copy(
                        momentumTodayRise = CustomConditionMode.Off,
                        momentumPeriodRise = CustomConditionMode.Off,
                        momentumConsecutiveRise = CustomConditionMode.Off,
                        momentumNewHigh = CustomConditionMode.Off,
                        momentumReboundRepair = CustomConditionMode.Off,
                    )
                },
            )
        },
    ) {
        ConditionStateRow("今日涨幅大于 X%", config.momentumTodayRise, enabled) {
            onConfigChange(config.copy(momentumTodayRise = it))
        }
        ConditionStateRow("近 N 日涨幅在 X% 到 Y%", config.momentumPeriodRise, enabled) {
            onConfigChange(config.copy(momentumPeriodRise = it))
        }
        ConditionStateRow("连涨 N 天", config.momentumConsecutiveRise, enabled) {
            onConfigChange(config.copy(momentumConsecutiveRise = it))
        }
        ConditionStateRow("创 N 日新高", config.momentumNewHigh, enabled) {
            onConfigChange(config.copy(momentumNewHigh = it))
        }
        ConditionStateRow("反弹修复前阴线比例大于 X%", config.momentumReboundRepair, enabled) {
            onConfigChange(config.copy(momentumReboundRepair = it))
        }
        PercentStepSlider("今日涨幅下限", config.momentumTodayPctMin, 0.0, 10.0, 0.5, enabled) {
            onConfigChange(config.copy(momentumTodayPctMin = it))
        }
        IntChoiceRow("统计天数", config.momentumRiseDays, listOf(3, 5, 10, 20), enabled) {
            onConfigChange(config.copy(momentumRiseDays = it))
        }
        PercentStepSlider("阶段涨幅上限", config.momentumRiseMaxPct, 5.0, 60.0, 1.0, enabled) {
            onConfigChange(config.copy(momentumRiseMaxPct = it))
        }
        StepSlider("反弹比例：${String.format("%.0f", config.momentumReboundRatio * 100)}%", config.momentumReboundRatio, 0.3, 1.0, 0.05, enabled) {
            onConfigChange(config.copy(momentumReboundRatio = it))
        }
        IntChoiceRow("新高周期", config.momentumHighDays, listOf(10, 20, 30, 60), enabled) {
            onConfigChange(config.copy(momentumHighDays = it))
        }
    }
}

@Composable
private fun CustomVolumeCard(
    config: CustomFilterConfig,
    enabled: Boolean,
    onConfigChange: (CustomFilterConfig) -> Unit,
) {
    CustomModuleCard(
        title = "成交量 / 成交额类",
        active = listOf(config.volumeMinAmount, config.volumeAboveAverage, config.volumeRiseWithVolume, config.volumeShrinkPullback).isActive(),
        enabled = enabled,
        onActiveChange = { active ->
            onConfigChange(
                if (active) {
                    config.copy(volumeMinAmount = CustomConditionMode.Required)
                } else {
                    config.copy(
                        volumeMinAmount = CustomConditionMode.Off,
                        volumeAboveAverage = CustomConditionMode.Off,
                        volumeRiseWithVolume = CustomConditionMode.Off,
                        volumeShrinkPullback = CustomConditionMode.Off,
                    )
                },
            )
        },
    ) {
        ConditionStateRow("成交额大于 X 万", config.volumeMinAmount, enabled) {
            onConfigChange(config.copy(volumeMinAmount = it))
        }
        ConditionStateRow("今日成交量大于近 N 日均量 X 倍", config.volumeAboveAverage, enabled) {
            onConfigChange(config.copy(volumeAboveAverage = it))
        }
        ConditionStateRow("放量上涨", config.volumeRiseWithVolume, enabled) {
            onConfigChange(config.copy(volumeRiseWithVolume = it))
        }
        ConditionStateRow("缩量回调", config.volumeShrinkPullback, enabled) {
            onConfigChange(config.copy(volumeShrinkPullback = it))
        }
        AmountStepSlider("最低成交额", config.volumeMinAmountValue, 10_000_000.0, 300_000_000.0, 10_000_000.0, enabled) {
            onConfigChange(config.copy(volumeMinAmountValue = it))
        }
        IntChoiceRow("均量周期", config.volumeAverageDays, listOf(5, 10, 20), enabled) {
            onConfigChange(config.copy(volumeAverageDays = it))
        }
        DoubleChoiceRow("放量倍数", config.volumeMultiplier, listOf(1.2, 1.5, 2.0), enabled) {
            onConfigChange(config.copy(volumeMultiplier = it))
        }
    }
}

@Composable
private fun CustomPatternCard(
    config: CustomFilterConfig,
    enabled: Boolean,
    onConfigChange: (CustomFilterConfig) -> Unit,
) {
    CustomModuleCard(
        title = "K线形态类",
        active = listOf(
            config.patternYangBaoYin,
            config.patternBearThenBull,
            config.patternLongLowerShadow,
            config.patternBigBull,
            config.patternSmallRange,
            config.patternGapUp,
            config.patternBodyRatio,
        ).isActive(),
        enabled = enabled,
        onActiveChange = { active ->
            onConfigChange(
                if (active) {
                    config.copy(patternBearThenBull = CustomConditionMode.Score)
                } else {
                    config.copy(
                        patternYangBaoYin = CustomConditionMode.Off,
                        patternBearThenBull = CustomConditionMode.Off,
                        patternLongLowerShadow = CustomConditionMode.Off,
                        patternBigBull = CustomConditionMode.Off,
                        patternSmallRange = CustomConditionMode.Off,
                        patternGapUp = CustomConditionMode.Off,
                        patternBodyRatio = CustomConditionMode.Off,
                    )
                },
            )
        },
    ) {
        ConditionStateRow("阳包阴", config.patternYangBaoYin, enabled) {
            onConfigChange(config.copy(patternYangBaoYin = it))
        }
        ConditionStateRow("前阴后阳", config.patternBearThenBull, enabled) {
            onConfigChange(config.copy(patternBearThenBull = it))
        }
        ConditionStateRow("长下影线", config.patternLongLowerShadow, enabled) {
            onConfigChange(config.copy(patternLongLowerShadow = it))
        }
        ConditionStateRow("大阳线", config.patternBigBull, enabled) {
            onConfigChange(config.copy(patternBigBull = it))
        }
        ConditionStateRow("小阴小阳整理", config.patternSmallRange, enabled) {
            onConfigChange(config.copy(patternSmallRange = it))
        }
        ConditionStateRow("跳空高开", config.patternGapUp, enabled) {
            onConfigChange(config.copy(patternGapUp = it))
        }
        ConditionStateRow("实体占比大于 X%", config.patternBodyRatio, enabled) {
            onConfigChange(config.copy(patternBodyRatio = it))
        }
        PercentStepSlider("实体比例", config.patternBodyMinRatio * 100.0, 20.0, 90.0, 5.0, enabled) {
            onConfigChange(config.copy(patternBodyMinRatio = it / 100.0))
        }
        PercentStepSlider("下影线比例", config.patternLowerShadowMinRatio * 100.0, 10.0, 70.0, 5.0, enabled) {
            onConfigChange(config.copy(patternLowerShadowMinRatio = it / 100.0))
        }
        PercentStepSlider("大阳线涨幅", config.patternBigBullPct, 3.0, 10.0, 0.5, enabled) {
            onConfigChange(config.copy(patternBigBullPct = it))
        }
        ToggleRow("要求阳线", config.patternRequireBull, enabled) {
            onConfigChange(config.copy(patternRequireBull = it))
        }
    }
}

@Composable
private fun CustomMaPositionCard(
    config: CustomFilterConfig,
    enabled: Boolean,
    onConfigChange: (CustomFilterConfig) -> Unit,
) {
    CustomModuleCard(
        title = "均线位置类",
        active = listOf(config.maNear, config.ma60Near, config.maPullbackHold, config.maCrossUp, config.maConverge).isActive(),
        enabled = enabled,
        onActiveChange = { active ->
            onConfigChange(
                if (active) {
                    config.copy(maNear = CustomConditionMode.Required)
                } else {
                    config.copy(
                        maNear = CustomConditionMode.Off,
                        ma60Near = CustomConditionMode.Off,
                        maPullbackHold = CustomConditionMode.Off,
                        maCrossUp = CustomConditionMode.Off,
                        maConverge = CustomConditionMode.Off,
                    )
                },
            )
        },
    ) {
        ConditionStateRow("距 MA${config.maPeriod} 不超过 X%", config.maNear, enabled) {
            onConfigChange(config.copy(maNear = it))
        }
        ConditionStateRow("距 MA60 不超过 X%", config.ma60Near, enabled) {
            onConfigChange(config.copy(ma60Near = it))
        }
        ConditionStateRow("回踩 MA${config.maPeriod} 不破", config.maPullbackHold, enabled) {
            onConfigChange(config.copy(maPullbackHold = it))
        }
        ConditionStateRow("上穿 MA${config.maPeriod}", config.maCrossUp, enabled) {
            onConfigChange(config.copy(maCrossUp = it))
        }
        ConditionStateRow("多均线粘合", config.maConverge, enabled) {
            onConfigChange(config.copy(maConverge = it))
        }
        IntChoiceRow("均线类型", config.maPeriod, listOf(20, 60, 120, 250), enabled) {
            onConfigChange(config.copy(maPeriod = it))
        }
        PercentStepSlider("允许偏离范围", config.maNearPct * 100.0, 2.0, 10.0, 1.0, enabled) {
            onConfigChange(config.copy(maNearPct = it / 100.0))
        }
        ToggleRow("要求收盘在线上", config.maRequireAbove, enabled) {
            onConfigChange(config.copy(maRequireAbove = it))
        }
        ToggleRow("要求当天上穿", config.maRequireCrossToday, enabled) {
            onConfigChange(config.copy(maRequireCrossToday = it))
        }
    }
}

@Composable
private fun CustomLimitCard(
    config: CustomFilterConfig,
    enabled: Boolean,
    onConfigChange: (CustomFilterConfig) -> Unit,
) {
    CustomModuleCard(
        title = "A股涨停 / 连板类",
        active = listOf(
            config.limitNear,
            config.limitFirstBoard,
            config.limitNoRecent,
            config.limitBoardCount,
            config.limitNotOneWord,
            config.limitNoBrokenBoard,
        ).isActive(),
        enabled = enabled,
        onActiveChange = { active ->
            onConfigChange(
                if (active) {
                    config.copy(limitNear = CustomConditionMode.Score)
                } else {
                    config.copy(
                        limitNear = CustomConditionMode.Off,
                        limitFirstBoard = CustomConditionMode.Off,
                        limitNoRecent = CustomConditionMode.Off,
                        limitBoardCount = CustomConditionMode.Off,
                        limitNotOneWord = CustomConditionMode.Off,
                        limitNoBrokenBoard = CustomConditionMode.Off,
                    )
                },
            )
        },
    ) {
        ConditionStateRow("涨幅接近涨停", config.limitNear, enabled) {
            onConfigChange(config.copy(limitNear = it))
        }
        ConditionStateRow("首板", config.limitFirstBoard, enabled) {
            onConfigChange(config.copy(limitFirstBoard = it))
        }
        ConditionStateRow("N 日内无涨停", config.limitNoRecent, enabled) {
            onConfigChange(config.copy(limitNoRecent = it))
        }
        ConditionStateRow("连板数等于 N", config.limitBoardCount, enabled) {
            onConfigChange(config.copy(limitBoardCount = it))
        }
        ConditionStateRow("非一字板", config.limitNotOneWord, enabled) {
            onConfigChange(config.copy(limitNotOneWord = it))
        }
        ConditionStateRow("炸板过滤", config.limitNoBrokenBoard, enabled) {
            onConfigChange(config.copy(limitNoBrokenBoard = it))
        }
        DoubleChoiceRow("涨停接近比例", config.limitNearRatio, listOf(0.90, 0.95, 0.98), enabled, label = { "${String.format("%.0f", it * 100)}%" }) {
            onConfigChange(config.copy(limitNearRatio = it))
        }
        IntChoiceRow("回看天数", config.limitLookbackDays, listOf(10, 20, 30), enabled) {
            onConfigChange(config.copy(limitLookbackDays = it))
        }
        IntChoiceRow("连板数量", config.limitBoardCountValue, listOf(1, 2, 3, 4), enabled) {
            onConfigChange(config.copy(limitBoardCountValue = it))
        }
        ToggleRow("排除 ST", config.limitExcludeSt, enabled) {
            onConfigChange(config.copy(limitExcludeSt = it))
        }
        ToggleRow("排除一字板", config.limitExcludeOneWord, enabled) {
            onConfigChange(config.copy(limitExcludeOneWord = it))
        }
        Text(
            "炸板过滤只用日K近似判断，不能精确识别分时回封。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun CustomVolatilityCard(
    config: CustomFilterConfig,
    enabled: Boolean,
    onConfigChange: (CustomFilterConfig) -> Unit,
) {
    CustomModuleCard(
        title = "波动率 / 振幅类",
        active = listOf(
            config.volatilityTodayAmplitude,
            config.volatilityNarrowing,
            config.volatilityMaxDrawdown,
            config.volatilityVolumeWave,
            config.volatilityQuietBreakout,
        ).isActive(),
        enabled = enabled,
        onActiveChange = { active ->
            onConfigChange(
                if (active) {
                    config.copy(volatilityMaxDrawdown = CustomConditionMode.Score)
                } else {
                    config.copy(
                        volatilityTodayAmplitude = CustomConditionMode.Off,
                        volatilityNarrowing = CustomConditionMode.Off,
                        volatilityMaxDrawdown = CustomConditionMode.Off,
                        volatilityVolumeWave = CustomConditionMode.Off,
                        volatilityQuietBreakout = CustomConditionMode.Off,
                    )
                },
            )
        },
    ) {
        ConditionStateRow("今日振幅小于 / 大于 X%", config.volatilityTodayAmplitude, enabled) {
            onConfigChange(config.copy(volatilityTodayAmplitude = it))
        }
        ConditionStateRow("近 N 日振幅收窄", config.volatilityNarrowing, enabled) {
            onConfigChange(config.copy(volatilityNarrowing = it))
        }
        ConditionStateRow("近 N 日最大回撤小于 X%", config.volatilityMaxDrawdown, enabled) {
            onConfigChange(config.copy(volatilityMaxDrawdown = it))
        }
        ConditionStateRow("突然放量放波动", config.volatilityVolumeWave, enabled) {
            onConfigChange(config.copy(volatilityVolumeWave = it))
        }
        ConditionStateRow("低波动后突破", config.volatilityQuietBreakout, enabled) {
            onConfigChange(config.copy(volatilityQuietBreakout = it))
        }
        PercentStepSlider("振幅上限", config.volatilityMaxAmplitudePct, 2.0, 15.0, 1.0, enabled) {
            onConfigChange(config.copy(volatilityMaxAmplitudePct = it))
        }
        IntChoiceRow("统计天数", config.volatilityDays, listOf(5, 10, 20), enabled) {
            onConfigChange(config.copy(volatilityDays = it))
        }
        PercentStepSlider("最大回撤", config.volatilityMaxDrawdownPct, 5.0, 30.0, 1.0, enabled) {
            onConfigChange(config.copy(volatilityMaxDrawdownPct = it))
        }
        ToggleRow("要求突破", config.volatilityRequireBreakout, enabled) {
            onConfigChange(config.copy(volatilityRequireBreakout = it))
        }
    }
}

@Composable
private fun CustomModuleCard(
    title: String,
    active: Boolean,
    enabled: Boolean,
    onActiveChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    SettingCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Switch(
                checked = active,
                onCheckedChange = onActiveChange,
                enabled = enabled,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (active) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        } else {
            Text("模块关闭，不参与筛选。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionStateRow(
    title: String,
    mode: CustomConditionMode,
    enabled: Boolean,
    onModeChange: (CustomConditionMode) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CustomConditionMode.entries.forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = { onModeChange(option) },
                    enabled = enabled,
                    label = { Text(option.title) },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntChoiceRow(
    title: String,
    selected: Int,
    values: List<Int>,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    ChoiceRow(title, selected, values, enabled, label = { it.toString() }, onSelect = onSelect)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoubleChoiceRow(
    title: String,
    selected: Double,
    values: List<Double>,
    enabled: Boolean,
    label: (Double) -> String = { String.format("%.1f", it) },
    onSelect: (Double) -> Unit,
) {
    ChoiceRow(title, selected, values, enabled, label = label, onSelect = onSelect)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
    title: String,
    selected: T,
    values: List<T>,
    enabled: Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    enabled = enabled,
                    label = { Text(label(value)) },
                )
            }
        }
    }
}

@Composable
private fun AmountStepSlider(
    title: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    enabled: Boolean,
    onValueChange: (Double) -> Unit,
) {
    StepSlider(
        title = "$title：${String.format("%.0f", value / 10_000)}万",
        value = value,
        min = min,
        max = max,
        step = step,
        enabled = enabled,
        onValueChange = onValueChange,
    )
}

@Composable
private fun PercentStepSlider(
    title: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    enabled: Boolean,
    onValueChange: (Double) -> Unit,
) {
    StepSlider(
        title = "$title：${String.format("%.1f", value)}%",
        value = value,
        min = min,
        max = max,
        step = step,
        enabled = enabled,
        onValueChange = onValueChange,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepSlider(
    title: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    enabled: Boolean,
    onValueChange: (Double) -> Unit,
) {
    val stepped = value.roundToStep(min, max, step)
    Text(title, style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = stepped.toFloat(),
        onValueChange = { onValueChange(it.toDouble().roundToStep(min, max, step)) },
        valueRange = min.toFloat()..max.toFloat(),
        steps = (((max - min) / step).roundToInt() - 1).coerceAtLeast(0),
        enabled = enabled,
        thumb = { RoundSliderThumb(enabled = enabled) },
    )
}

@Composable
private fun SignalList(
    signals: List<StrategySignal>,
    newSignalCodes: Set<String>,
    emptyText: String,
    header: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (header != null) {
            item {
                header()
            }
        }

        if (signals.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(emptyText, color = MaterialTheme.colorScheme.secondary)
                }
            }
        } else {
            itemsIndexed(signals, key = { _, signal -> "${signal.tradeDate}-${signal.stock.tsCode}-${signal.strategy}" }) { index, signal ->
                SignalCard(
                    index = index + 1,
                    signal = signal,
                    isNew = signal.stock.tsCode in newSignalCodes,
                )
            }
        }
    }
}

@Composable
private fun DataStatusCard(
    dataSource: String,
    statusText: String,
    isLoading: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(dataSource, style = MaterialTheme.typography.labelLarge)
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalCard(index: Int, signal: StrategySignal, isNew: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$index. ${signal.stock.name} ${signal.stock.tsCode}",
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (isNew) {
                            Text(
                                "新",
                                modifier = Modifier
                                    .background(Color(0xFFD32F2F), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Text(
                        "${signal.strategy} · ${signal.tradeDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                LevelChip(signal.level, signal.score)
            }

            Spacer(Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                signal.metrics.filter { it.second.isNotBlank() }.forEach { metric ->
                    AssistChip(
                        onClick = {},
                        label = { Text("${metric.first} ${metric.second}") },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("命中原因", style = MaterialTheme.typography.labelLarge)
            signal.reasons.forEach { reason ->
                Text("· $reason", style = MaterialTheme.typography.bodySmall)
            }

            if (signal.ruleChecks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val failedChecks = signal.ruleChecks.count { !it.passed }
                Text("规则核验", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (failedChecks == 0) {
                        "全部核验项通过，符合当前显示标准。"
                    } else {
                        "评分已达到命中线，${failedChecks}项未通过，需人工复核。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    signal.ruleChecks.forEach { check ->
                        FilterChip(
                            selected = check.passed,
                            onClick = {},
                            label = {
                                Text("${if (check.passed) "通过" else "未过"} ${check.label}")
                            },
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            Text("买点：${signal.buyTrigger}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text("风控：${signal.stopLoss}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LevelChip(level: SignalLevel, score: Int) {
    val selected = level == SignalLevel.STRONG
    FilterChip(
        selected = selected,
        onClick = {},
        label = { Text("${level.displayName} $score") },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(
    nearMaPct: Double,
    onNearMaPctChange: (Double) -> Unit,
    minAmount: Double,
    onMinAmountChange: (Double) -> Unit,
    notifyEnabled: Boolean,
    onNotifyEnabledChange: (Boolean) -> Unit,
    enabledStrategies: Set<StrategyOption>,
    onStrategyToggle: (StrategyOption) -> Unit,
    dataSource: String,
    statusText: String,
    isLoading: Boolean,
    onSmartUpdateData: () -> Unit,
    appUpdateStatus: String,
    isCheckingAppUpdate: Boolean,
    onCheckAppUpdate: () -> Unit,
    cacheInfo: MarketCacheInfo,
    onRefreshCacheInfo: () -> Unit,
    onRequestBackgroundPermission: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            DataStatusCard(
                dataSource = dataSource,
                statusText = statusText,
                isLoading = isLoading,
            )
        }
        item {
            Text("策略参数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        item {
            SettingCard {
                Text("战法选择", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StrategyOption.entries.forEach { option ->
                        FilterChip(
                            selected = option in enabledStrategies,
                            onClick = { onStrategyToggle(option) },
                            enabled = !isLoading,
                            label = { Text(option.title) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "这里控制今日信号的显示过滤；多选时必须同时满足已选战法。刷新始终更新全部战法的全量结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        item {
            SettingCard {
                Text("年线/均线附近范围：${String.format("%.1f", nearMaPct * 100)}%")
                Slider(
                    value = nearMaPct.toNearMaStep().toFloat(),
                    onValueChange = { onNearMaPctChange(it.toDouble().toNearMaStep()) },
                    valueRange = 0.02f..0.10f,
                    enabled = !isLoading,
                    thumb = { RoundSliderThumb(enabled = !isLoading) },
                )
                Text(
                    "用于九阳蓄势的位置过滤；博弈K固定使用3%靠近均线范围。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        item {
            SettingCard {
                Text("最低成交额：${String.format("%.0f", minAmount / 10_000)}万")
                Slider(
                    value = (minAmount.toMinAmountStep() / 100_000_000.0).toFloat(),
                    onValueChange = { onMinAmountChange((it.toDouble() * 100_000_000.0).toMinAmountStep()) },
                    valueRange = 0.1f..3.0f,
                    enabled = !isLoading,
                    thumb = { RoundSliderThumb(enabled = !isLoading) },
                )
                Text(
                    "用于过滤流动性不足的股票。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        item {
            SettingCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("每日收盘后提醒")
                        Text(
                            "后续可继续接入自动定时更新。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Switch(
                        checked = notifyEnabled,
                        onCheckedChange = onNotifyEnabledChange,
                        enabled = !isLoading,
                    )
                }
            }
        }
        item {
            SettingCard {
                Text("数据更新与筛选", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (cacheInfo.exists) {
                        "已发现缓存：${cacheInfo.stockCount} 只股票，${cacheInfo.dailyBarCount} 行K线，${String.format("%.1f", cacheInfo.sizeMb)}MB。\n" +
                            "日期范围：${cacheInfo.dateStart.ifBlank { "-" }} 到 ${cacheInfo.dateEnd.ifBlank { "-" }}。"
                    } else {
                        "未发现 market_cache.db。点击后会先联网生成缓存，再筛选。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    onClick = onSmartUpdateData,
                ) {
                    Text("智能更新并筛选")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && !isCheckingAppUpdate,
                    onClick = onCheckAppUpdate,
                ) {
                    Text(if (isCheckingAppUpdate) "正在检测程序更新..." else "检测程序更新")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    appUpdateStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "点击后先判断缓存是否为收盘最新数据；已最新则直接筛选全部战法全量结果，未最新则联网更新并合并缓存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    onClick = onRefreshCacheInfo,
                ) {
                    Text("刷新缓存状态")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRequestBackgroundPermission,
                ) {
                    Text("允许后台运行")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "联网更新会在前台服务中后台运行，更新完成后自动清理过早K线，只保留最近约320个交易日。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
private fun RoundSliderThumb(enabled: Boolean) {
    val color = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(color, CircleShape)
    )
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    val packageName = context.packageName
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
        !powerManager.isIgnoringBatteryOptimizations(packageName)
    ) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { context.startActivity(intent) }
            .onFailure { openAppDetails(context) }
    } else {
        openAppDetails(context)
    }
}

private fun openAppDetails(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}
