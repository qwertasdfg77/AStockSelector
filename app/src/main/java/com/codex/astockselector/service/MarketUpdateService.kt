package com.codex.astockselector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.codex.astockselector.MainActivity
import com.codex.astockselector.data.CacheMarketRepository
import com.codex.astockselector.data.MarketUpdateStore
import com.codex.astockselector.model.StrategyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MarketUpdateService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runningJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private data class NotificationProgress(
        val max: Int,
        val current: Int,
        val indeterminate: Boolean,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startAsForeground("阶段1/5：正在准备读取A股数据...")

        if (runningJob?.isActive == true) {
            MarketUpdateStore.progress("后台读取已在运行中，请等待当前任务完成。")
            updateNotification("后台读取已在运行中")
            return START_STICKY
        }

        val config = StrategyConfig(
            nearMaPct = intent?.getDoubleExtra(EXTRA_NEAR_MA_PCT, 0.05) ?: 0.05,
            minAmount = intent?.getDoubleExtra(EXTRA_MIN_AMOUNT, 50_000_000.0) ?: 50_000_000.0,
        )
        val rebuildCache = intent?.getBooleanExtra(EXTRA_REBUILD_CACHE, false) ?: false
        val dataSource = if (rebuildCache) "重建缓存" else DATA_SOURCE

        acquireWakeLock()
        MarketUpdateStore.start(
            if (rebuildCache) {
                "阶段1/5：重建缓存已启动，正在清理旧缓存并重新读取K线..."
            } else {
                "阶段1/5：智能更新已启动，正在检查本地缓存是否为收盘最新数据..."
            },
            dataSource,
        )
        runningJob = serviceScope.launch {
            try {
                val signals = if (rebuildCache) {
                    CacheMarketRepository.rebuildCacheAndLoadSignals(this@MarketUpdateService, config) { message ->
                        MarketUpdateStore.progress(message, dataSource)
                        updateNotification(message)
                    }
                } else {
                    CacheMarketRepository.loadSmartSignals(this@MarketUpdateService, config) { message ->
                        MarketUpdateStore.progress(message, dataSource)
                        updateNotification(message)
                    }
                }
                val message = if (rebuildCache) {
                    "重建缓存并筛选完成：命中 ${signals.size} 条信号。"
                } else {
                    "智能更新筛选完成：命中 ${signals.size} 条信号。"
                }
                MarketUpdateStore.complete(signals, message, dataSource)
                updateNotification(message)
            } catch (error: Exception) {
                val message = if (rebuildCache) {
                    "重建缓存失败：${error.message ?: "未知错误"}"
                } else {
                    "更新失败：${error.message ?: "未知错误"}"
                }
                MarketUpdateStore.fail(message, dataSource)
                updateNotification(message)
            } finally {
                releaseWakeLock()
                stopForegroundCompat()
                stopSelf(startId)
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (runningJob?.isActive == true) {
            MarketUpdateStore.progress("任务列表已移除，前台服务仍在后台读取。")
            updateNotification("任务列表已移除，后台读取继续")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runningJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, ongoing = runningJob?.isActive == true))
    }

    private fun buildNotification(text: String, ongoing: Boolean): Notification {
        val openIntent = Intent().setClassName(packageName, MainActivity::class.java.name)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val progress = if (ongoing) {
            parseNotificationProgress(text)
        } else {
            NotificationProgress(max = 0, current = 0, indeterminate = false)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("A股选股后台更新")
            .setContentText(text.take(90))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(progress.max, progress.current, progress.indeterminate)
            .build()
    }

    private fun parseNotificationProgress(text: String): NotificationProgress {
        val match = Regex("""(\d+)\s*/\s*(\d+)""").findAll(text).lastOrNull()
            ?: return NotificationProgress(max = 0, current = 0, indeterminate = true)
        val current = match.groupValues[1].toIntOrNull() ?: 0
        val max = match.groupValues[2].toIntOrNull() ?: 0
        return if (max > 0 && current in 0..max) {
            NotificationProgress(max = max, current = current, indeterminate = false)
        } else {
            NotificationProgress(max = 0, current = 0, indeterminate = true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "A股选股后台读取",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "读取A股列表、K线和选股进度"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:MarketUpdate",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        if (lock?.isHeld == true) {
            lock.release()
        }
        wakeLock = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val EXTRA_NEAR_MA_PCT = "nearMaPct"
        const val EXTRA_MIN_AMOUNT = "minAmount"
        const val EXTRA_REBUILD_CACHE = "rebuildCache"
        private const val DATA_SOURCE = "智能更新"
        private const val CHANNEL_ID = "market_update"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }
}
