package com.codex.astockselector.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String,
    val apkSize: Long,
    val releaseNotes: String,
)

data class AppUpdateCheckResult(
    val currentVersionCode: Long,
    val latest: AppUpdateInfo,
) {
    val hasUpdate: Boolean = latest.versionCode > currentVersionCode
}

data class VerifiedUpdateApk(
    val file: File,
    val sha256: String,
)

object AppUpdateRepository {
    const val UPDATE_REPOSITORY = "qwertasdfg77/astock-selector-updates"
    private const val LATEST_JSON_URL =
        "https://raw.githubusercontent.com/$UPDATE_REPOSITORY/main/latest.json"
    private const val DEFAULT_APK_URL =
        "https://raw.githubusercontent.com/$UPDATE_REPOSITORY/main/app-release.apk"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
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
        val latest = parseUpdateInfo(body)
        AppUpdateCheckResult(
            currentVersionCode = context.currentVersionCode(),
            latest = latest,
        )
    }

    fun parseUpdateInfo(body: String): AppUpdateInfo {
        val json = JSONObject(body.trimStart('\uFEFF'))
        return AppUpdateInfo(
            versionCode = json.optLong("versionCode", 0L),
            versionName = json.optString("versionName", ""),
            apkUrl = json.optString("apkUrl", DEFAULT_APK_URL),
            apkSha256 = json.optString("apkSha256", "").lowercase(),
            apkSize = json.optLong("apkSize", 0L),
            releaseNotes = json.optString("releaseNotes", ""),
        )
    }

    suspend fun downloadAndVerify(context: Context, latest: AppUpdateInfo): VerifiedUpdateApk =
        withContext(Dispatchers.IO) {
            val expectedSha256 = latest.apkSha256.trim().lowercase()
            require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) {
                "更新信息缺少有效 SHA256，已停止下载"
            }

            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            updateDir.listFiles()
                ?.filter { it.extension.equals("apk", ignoreCase = true) }
                ?.forEach { it.delete() }

            val file = File(updateDir, "AStockSelector-${latest.versionName}.apk")
            val request = Request.Builder()
                .url(latest.apkUrl)
                .header("Cache-Control", "no-cache")
                .build()

            val digest = MessageDigest.getInstance("SHA-256")
            var bytesWritten = 0L
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("APK 下载失败：HTTP ${response.code}")
                }
                val body = response.body ?: error("APK 下载失败：响应为空")
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            bytesWritten += read
                        }
                    }
                }
            }

            if (latest.apkSize > 0 && bytesWritten != latest.apkSize) {
                file.delete()
                error("APK 大小校验失败：期望 ${latest.apkSize} 字节，实际 $bytesWritten 字节")
            }

            val actualSha256 = digest.digest().toHex()
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                file.delete()
                error("APK SHA256 校验失败，已停止安装")
            }

            VerifiedUpdateApk(file = file, sha256 = actualSha256)
        }

    fun installIntent(context: Context, apk: VerifiedUpdateApk): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk.file,
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }
}
