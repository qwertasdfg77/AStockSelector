package com.codex.astockselector.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String,
)

data class AppUpdateCheckResult(
    val currentVersionCode: Long,
    val latest: AppUpdateInfo,
) {
    val hasUpdate: Boolean = latest.versionCode > currentVersionCode
}

object AppUpdateRepository {
    const val UPDATE_REPOSITORY = "qwertasdfg77/astock-selector-updates"
    private const val LATEST_JSON_URL =
        "https://raw.githubusercontent.com/$UPDATE_REPOSITORY/main/latest.json"
    private const val DEFAULT_APK_URL =
        "https://raw.githubusercontent.com/$UPDATE_REPOSITORY/main/app-debug.apk"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun checkLatest(context: Context): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_JSON_URL)
            .header("Cache-Control", "no-cache")
            .build()
        val body = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("GitHub 更新信息读取失败：HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
        val json = JSONObject(body.trimStart('\uFEFF'))
        val latest = AppUpdateInfo(
            versionCode = json.optLong("versionCode", 0L),
            versionName = json.optString("versionName", ""),
            apkUrl = json.optString("apkUrl", DEFAULT_APK_URL),
            releaseNotes = json.optString("releaseNotes", ""),
        )
        AppUpdateCheckResult(
            currentVersionCode = context.currentVersionCode(),
            latest = latest,
        )
    }

    private fun Context.currentVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }
}
