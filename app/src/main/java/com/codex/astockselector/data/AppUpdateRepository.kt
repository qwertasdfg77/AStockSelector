package com.codex.astockselector.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class CurrentAppVersion(
    val versionCode: Long,
    val versionName: String,
) {
    val label: String = "$versionName（$versionCode）"
}

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
    val currentVersionName: String,
    val latest: AppUpdateInfo,
) {
    val hasUpdate: Boolean = latest.versionCode > currentVersionCode
    val currentVersion: CurrentAppVersion = CurrentAppVersion(currentVersionCode, currentVersionName)
}

data class AppUpdateDownloadProgress(
    val attempt: Int,
    val maxAttempts: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
) {
    val percent: Int? =
        if (totalBytes > 0L) {
            ((bytesDownloaded * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
        } else {
            null
        }
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
    private const val DOWNLOAD_RETRY_COUNT = 3
    private const val PROGRESS_STEP_BYTES = 512 * 1024L

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun currentVersionLabel(context: Context): String = context.currentVersion().label

    suspend fun checkLatest(context: Context): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_JSON_URL.cacheBustedUrl())
            .header("Cache-Control", "no-cache")
            .build()
        val body = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("GitHub 更新信息读取失败：HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
        val latest = parseUpdateInfo(body)
        validateUpdateInfo(latest)
        val current = context.currentVersion()
        AppUpdateCheckResult(
            currentVersionCode = current.versionCode,
            currentVersionName = current.versionName,
            latest = latest,
        )
    }

    fun parseUpdateInfo(body: String): AppUpdateInfo {
        val json = JSONObject(body.trimStart('\uFEFF'))
        return AppUpdateInfo(
            versionCode = json.optLong("versionCode", 0L),
            versionName = json.optString("versionName", "").trim(),
            apkUrl = json.optString("apkUrl", DEFAULT_APK_URL).trim(),
            apkSha256 = json.optString("apkSha256", "").trim().lowercase(),
            apkSize = json.optLong("apkSize", 0L),
            releaseNotes = json.optString("releaseNotes", "").trim(),
        )
    }

    fun validateUpdateInfo(info: AppUpdateInfo) {
        require(info.versionCode > 0L) {
            "更新信息缺少有效 versionCode"
        }
        require(info.versionName.matches(Regex("""\d+\.\d+\.\d+"""))) {
            "更新信息缺少有效 versionName"
        }
        require(info.apkUrl.startsWith("https://")) {
            "更新信息缺少有效 APK 下载地址"
        }
        require(info.apkSha256.trim().lowercase().matches(Regex("[0-9a-f]{64}"))) {
            "更新信息缺少有效 SHA256"
        }
        require(info.apkSize > 0L) {
            "更新信息缺少有效 APK 大小"
        }
    }

    suspend fun downloadAndVerify(
        context: Context,
        latest: AppUpdateInfo,
        maxAttempts: Int = DOWNLOAD_RETRY_COUNT,
        onProgress: suspend (AppUpdateDownloadProgress) -> Unit = {},
    ): VerifiedUpdateApk =
        withContext(Dispatchers.IO) {
            validateUpdateInfo(latest)

            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            cleanStaleUpdateFiles(updateDir)

            val file = File(updateDir, "AStockSelector-${latest.versionName}.apk")
            val partFile = File(updateDir, "${file.name}.part")
            var lastError: Throwable? = null

            repeat(maxAttempts.coerceAtLeast(1)) { index ->
                val attempt = index + 1
                try {
                    return@withContext downloadOnce(
                        latest = latest,
                        finalFile = file,
                        partFile = partFile,
                        attempt = attempt,
                        maxAttempts = maxAttempts.coerceAtLeast(1),
                        onProgress = onProgress,
                    )
                } catch (error: Throwable) {
                    lastError = error
                    partFile.delete()
                    file.delete()
                    if (attempt < maxAttempts.coerceAtLeast(1)) {
                        delay(1_000L * attempt)
                    }
                }
            }

            error("APK 下载失败，已重试 ${maxAttempts.coerceAtLeast(1)} 次：${lastError?.message ?: "未知错误"}")
        }

    private suspend fun downloadOnce(
        latest: AppUpdateInfo,
        finalFile: File,
        partFile: File,
        attempt: Int,
        maxAttempts: Int,
        onProgress: suspend (AppUpdateDownloadProgress) -> Unit,
    ): VerifiedUpdateApk {
        partFile.delete()
        finalFile.delete()

        val request = Request.Builder()
            .url(latest.apkUrl.cacheBustedUrl())
            .header("Cache-Control", "no-cache")
            .build()

        var bytesWritten = 0L
        var lastProgressBytes = 0L
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("APK 下载失败：HTTP ${response.code}")
            }
            val body = response.body ?: error("APK 下载失败：响应为空")
            val totalBytes = if (latest.apkSize > 0L) latest.apkSize else body.contentLength()
            onProgress(
                AppUpdateDownloadProgress(
                    attempt = attempt,
                    maxAttempts = maxAttempts,
                    bytesDownloaded = 0L,
                    totalBytes = totalBytes,
                ),
            )

            body.byteStream().use { input ->
                partFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesWritten += read

                        if (
                            bytesWritten - lastProgressBytes >= PROGRESS_STEP_BYTES ||
                            (totalBytes > 0L && bytesWritten >= totalBytes)
                        ) {
                            lastProgressBytes = bytesWritten
                            onProgress(
                                AppUpdateDownloadProgress(
                                    attempt = attempt,
                                    maxAttempts = maxAttempts,
                                    bytesDownloaded = bytesWritten,
                                    totalBytes = totalBytes,
                                ),
                            )
                        }
                    }
                }
            }
        }

        val actualSha256 = verifyDownloadedApk(partFile, latest)
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        return VerifiedUpdateApk(file = finalFile, sha256 = actualSha256)
    }

    fun verifyDownloadedApk(file: File, latest: AppUpdateInfo): String {
        validateUpdateInfo(latest)
        require(file.exists()) {
            "APK 文件不存在，无法校验"
        }
        val actualSize = file.length()
        if (actualSize != latest.apkSize) {
            error("APK 大小校验失败：期望 ${latest.apkSize} 字节，实际 $actualSize 字节")
        }

        val actualSha256 = file.sha256()
        if (!actualSha256.equals(latest.apkSha256.trim(), ignoreCase = true)) {
            error("APK SHA256 校验失败，已停止安装")
        }
        return actualSha256
    }

    private fun cleanStaleUpdateFiles(updateDir: File) {
        updateDir.listFiles()
            ?.filter { file ->
                file.extension.equals("apk", ignoreCase = true) ||
                    file.name.endsWith(".part", ignoreCase = true)
            }
            ?.forEach { it.delete() }
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

    private fun Context.currentVersion(): CurrentAppVersion {
        val info = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return CurrentAppVersion(
            versionCode = versionCode,
            versionName = info.versionName.orEmpty().ifBlank { "未知版本" },
        )
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun String.cacheBustedUrl(): String {
        val separator = if (contains("?")) "&" else "?"
        return "$this${separator}cache_bust=${System.currentTimeMillis()}"
    }
}
