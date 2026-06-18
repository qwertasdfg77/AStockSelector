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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import com.codex.astockselector.data.AppUpdateCheckResult
import com.codex.astockselector.data.AppUpdateDownloadProgress
import com.codex.astockselector.data.AppUpdateInfo
import com.codex.astockselector.data.AppUpdateRepository
import com.codex.astockselector.data.CacheMarketRepository
import com.codex.astockselector.data.LastSignalStore
import com.codex.astockselector.data.MarketCacheInfo
import com.codex.astockselector.data.MarketUpdateStore
import com.codex.astockselector.data.SavedSignalSnapshot
import com.codex.astockselector.model.SignalLevel
import com.codex.astockselector.model.StrategyConfig
import com.codex.astockselector.model.StrategySignal
import com.codex.astockselector.model.strategyRuleKey
import com.codex.astockselector.service.MarketUpdateService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var pendingAppUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var appUpdateStatus by remember {
        mutableStateOf("当前版本：${AppUpdateRepository.currentVersionLabel(context)}。尚未检测程序更新。")
    }
    var isCheckingAppUpdate by remember { mutableStateOf(false) }
    val appUpdateButtonText = when {
        isCheckingAppUpdate -> "正在处理程序更新..."
        pendingAppUpdate != null -> "下载更新 ${pendingAppUpdate?.versionName.orEmpty()}"
        else -> "检测程序更新"
    }

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
                    ruleKey = config.strategyRuleKey(),
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
                    ruleKey = config.strategyRuleKey(),
                    newSignalCodes = nextNewSignalCodes,
                )
                CacheMarketRepository.markNewSignalCodes(
                    context = context,
                    ruleKey = config.strategyRuleKey(),
                    tradeDate = latestCacheInfo.dateEnd,
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
            if (saved.ruleKey.equivalentSignalRuleKey(config.strategyRuleKey())) {
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
        val ruleKey = config.strategyRuleKey()
        isLoading = true
        statusText = "阶段1/5：智能更新已启动，正在检查缓存与规则是否可复用..."
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
                "阶段5/5：缓存已是收盘最新数据（${freshness.info.dateEnd}），规则或结果已变化，开始本地增量筛选..."
            } else if (freshness.info.exists && freshness.info.dateEnd >= freshness.expectedDate && freshness.info.retryableFailedStockCount > 0) {
                "阶段2/5：缓存已是收盘最新数据，但有 ${freshness.info.retryableFailedStockCount} 只失败股票需要补读..."
            } else {
                val currentDate = if (freshness.info.exists) freshness.info.dateEnd.ifBlank { "无" } else "无缓存"
                "阶段1/5：缓存最新日期 $currentDate，目标收盘日期 ${freshness.expectedDate}，开始读取股票列表..."
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

    fun rebuildCache() {
        selectedTab = AppTab.Settings
        dataSource = "重建缓存"
        isLoading = true
        statusText = "阶段1/5：重建缓存已启动，正在清理旧缓存并重新读取K线..."
        MarketUpdateStore.start(statusText, "重建缓存")

        val intent = Intent(context, MarketUpdateService::class.java)
            .putExtra(MarketUpdateService.EXTRA_NEAR_MA_PCT, nearMaPct)
            .putExtra(MarketUpdateService.EXTRA_MIN_AMOUNT, minAmount)
            .putExtra(MarketUpdateService.EXTRA_REBUILD_CACHE, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun refreshCacheInfo() {
        scope.launch {
            cacheInfo = CacheMarketRepository.cacheInfo(context)
            statusText = if (cacheInfo.exists) {
                "已发现本地缓存：${cacheInfo.stockCount} 只股票，${cacheInfo.dailyBarCount} 行K线，失败队列 ${cacheInfo.failedStockCount} 只。"
            } else {
                "未发现本地缓存 market_cache.db。"
            }
        }
    }

    fun checkAppUpdate() {
        if (isCheckingAppUpdate) return
        isCheckingAppUpdate = true
        pendingAppUpdate = null
        appUpdateStatus = "当前版本：${AppUpdateRepository.currentVersionLabel(context)}。\n正在从 GitHub 检测程序更新..."
        scope.launch {
            runCatching {
                AppUpdateRepository.checkLatest(context)
            }.onSuccess { result ->
                if (result.hasUpdate) {
                    pendingAppUpdate = result.latest
                    appUpdateStatus = buildAppUpdateAvailableStatus(result)
                } else {
                    pendingAppUpdate = null
                    appUpdateStatus = buildAppUpdateCurrentStatus(result)
                }
            }.onFailure { error ->
                pendingAppUpdate = null
                appUpdateStatus = "检测程序更新失败：${error.message ?: "未知错误"}"
            }
            isCheckingAppUpdate = false
        }
    }

    fun downloadPendingAppUpdate() {
        val latest = pendingAppUpdate ?: run {
            checkAppUpdate()
            return
        }
        if (isCheckingAppUpdate) return
        isCheckingAppUpdate = true
        val currentLabel = AppUpdateRepository.currentVersionLabel(context)
        appUpdateStatus = "当前版本：$currentLabel。\n准备下载新版 ${latest.versionName}..."
        scope.launch {
            runCatching {
                AppUpdateRepository.downloadAndVerify(context, latest) { progress ->
                    withContext(Dispatchers.Main) {
                        appUpdateStatus = buildAppUpdateDownloadStatus(
                            currentLabel = currentLabel,
                            latest = latest,
                            progress = progress,
                        )
                    }
                }
            }.onSuccess { apk ->
                pendingAppUpdate = null
                appUpdateStatus =
                    "当前版本：$currentLabel。\n新版 ${latest.versionName} 已通过 SHA256 和大小校验，正在打开安装器。"
                context.startActivity(AppUpdateRepository.installIntent(context, apk))
            }.onFailure { error ->
                appUpdateStatus =
                    "当前版本：$currentLabel。\n新版 ${latest.versionName} 下载或校验失败：${error.message ?: "未知错误"}\n可再次点击“下载更新”重试。"
            }
            isCheckingAppUpdate = false
        }
    }

    fun requestBackgroundPermission() {
        requestIgnoreBatteryOptimizations(context)
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
                    appUpdateButtonText = appUpdateButtonText,
                    isCheckingAppUpdate = isCheckingAppUpdate,
                    onAppUpdateAction = {
                        if (pendingAppUpdate == null) {
                            checkAppUpdate()
                        } else {
                            downloadPendingAppUpdate()
                        }
                    },
                    cacheInfo = cacheInfo,
                    onRefreshCacheInfo = ::refreshCacheInfo,
                    onRebuildCache = ::rebuildCache,
                    onRequestBackgroundPermission = ::requestBackgroundPermission,
                )
            }
        }
    }
}

private enum class AppTab(val title: String) {
    Today("今日信号"),
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

private fun buildAppUpdateAvailableStatus(result: AppUpdateCheckResult): String =
    "当前版本：${result.currentVersion.label}。\n" +
        "发现新版：${result.latest.versionName}（${result.latest.versionCode}）。\n" +
        "更新说明：${result.latest.releaseNotes.ifBlank { "暂无更新说明。" }}\n" +
        "点击“下载更新”后会下载 APK，校验 SHA256 和大小，通过后再打开安装器。"

private fun buildAppUpdateCurrentStatus(result: AppUpdateCheckResult): String =
    "当前版本：${result.currentVersion.label}。\n" +
        "最新版本：${result.latest.versionName}（${result.latest.versionCode}）。\n" +
        "当前已是最新版本。"

private fun buildAppUpdateDownloadStatus(
    currentLabel: String,
    latest: AppUpdateInfo,
    progress: AppUpdateDownloadProgress,
): String {
    val totalMb = progress.totalBytes.toMegabytesText()
    val downloadedMb = progress.bytesDownloaded.toMegabytesText()
    val percentText = progress.percent?.let { "$it%" } ?: "计算中"
    return "当前版本：$currentLabel。\n" +
        "正在下载新版 ${latest.versionName}：$percentText（$downloadedMb / $totalMb）。\n" +
        "第 ${progress.attempt}/${progress.maxAttempts} 次尝试，下载完成后会自动校验安装包。"
}

private fun Long.toMegabytesText(): String =
    if (this > 0L) {
        String.format("%.1fMB", this / 1024.0 / 1024.0)
    } else {
        "未知大小"
    }

private fun Double.toNearMaStep(): Double =
    (this * 100.0).roundToInt().coerceIn(2, 10) / 100.0

private fun Double.toMinAmountStep(): Double =
    (this / 10_000_000.0).roundToInt().coerceIn(1, 30) * 10_000_000.0

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
    appUpdateButtonText: String,
    isCheckingAppUpdate: Boolean,
    onAppUpdateAction: () -> Unit,
    cacheInfo: MarketCacheInfo,
    onRefreshCacheInfo: () -> Unit,
    onRebuildCache: () -> Unit,
    onRequestBackgroundPermission: () -> Unit,
) {
    var showRebuildCacheDialog by remember { mutableStateOf(false) }

    if (showRebuildCacheDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildCacheDialog = false },
            title = { Text("警告：确认重建缓存？") },
            text = {
                Text(
                    "这会删除手机上的旧K线缓存，并重新联网下载最近约270个交易日的数据。过程可能耗时较长，也会消耗流量；如果中途网络失败，需要重新更新或再次重建。\n\n" +
                        "不会清除战法选择和参数设置。建议只在缓存异常、更新反复失败、数据缺失或筛选结果明显不对时使用。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebuildCacheDialog = false
                        onRebuildCache()
                    },
                ) {
                    Text("删除并重建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildCacheDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

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
                            "日期范围：${cacheInfo.dateStart.ifBlank { "-" }} 到 ${cacheInfo.dateEnd.ifBlank { "-" }}。\n" +
                            "最新日覆盖率：${String.format("%.1f", cacheInfo.latestDateCoveragePct)}%，失败队列：${cacheInfo.failedStockCount} 只，可重试 ${cacheInfo.retryableFailedStockCount} 只。"
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
                    onClick = onAppUpdateAction,
                ) {
                    Text(appUpdateButtonText)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    appUpdateStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "点击后先判断缓存是否为收盘最新数据；已最新且无失败则复用结果，未最新则只更新缺目标交易日、失败或新增股票。",
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
                    enabled = !isLoading,
                    onClick = { showRebuildCacheDialog = true },
                ) {
                    Text("重建缓存")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "重建缓存会删除旧K线缓存并重新下载最近约270个交易日，不会清除战法选择和参数设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRequestBackgroundPermission,
                ) {
                    Text("允许后台运行")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "联网更新会在前台服务中后台运行，更新完成后自动清理过早K线，只保留最近约270个交易日。",
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
