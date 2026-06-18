package com.codex.astockselector.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class AppUpdateRepositoryTest {
    @Test
    fun parseUpdateInfoReadsApkIntegrityFields() {
        val update = AppUpdateRepository.parseUpdateInfo(
            """
            {
              "versionCode": 17,
              "versionName": "0.2.5",
              "apkUrl": "https://example.com/app-release.apk",
              "apkSha256": "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
              "apkSize": 123456,
              "releaseNotes": "测试更新"
            }
            """.trimIndent(),
        )

        assertEquals(17L, update.versionCode)
        assertEquals("0.2.5", update.versionName)
        assertEquals("https://example.com/app-release.apk", update.apkUrl)
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", update.apkSha256)
        assertEquals(123456L, update.apkSize)
        assertEquals("测试更新", update.releaseNotes)
    }

    @Test
    fun parseUpdateInfoFallsBackToDefaultApkUrl() {
        val update = AppUpdateRepository.parseUpdateInfo(
            """
            {
              "versionCode": 17,
              "versionName": "0.2.5"
            }
            """.trimIndent(),
        )

        assertEquals("https://raw.githubusercontent.com/qwertasdfg77/astock-selector-updates/main/app-release.apk", update.apkUrl)
        assertEquals("", update.apkSha256)
        assertEquals(0L, update.apkSize)
    }

    @Test
    fun validateUpdateInfoRejectsInvalidSha256() {
        val update = validUpdateInfo().copy(apkSha256 = "not-a-sha")

        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateRepository.validateUpdateInfo(update)
        }
    }

    @Test
    fun validateUpdateInfoRejectsMissingSize() {
        val update = validUpdateInfo().copy(apkSize = 0L)

        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateRepository.validateUpdateInfo(update)
        }
    }

    @Test
    fun verifyDownloadedApkReturnsShaWhenSizeAndHashMatch() {
        val apk = writeTempApk("release-apk")
        val update = validUpdateInfo(
            apkSha256 = "a8f3d9dba2d6f10a92a50ab7ea364aa6addded90be1b37f8abc8c7c3843b9ae1",
            apkSize = apk.length(),
        )

        val actualSha = AppUpdateRepository.verifyDownloadedApk(apk, update)

        assertEquals(update.apkSha256, actualSha)
    }

    @Test
    fun verifyDownloadedApkRejectsShaMismatch() {
        val apk = writeTempApk("release-apk")
        val update = validUpdateInfo(
            apkSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            apkSize = apk.length(),
        )

        assertThrows(IllegalStateException::class.java) {
            AppUpdateRepository.verifyDownloadedApk(apk, update)
        }
    }

    @Test
    fun verifyDownloadedApkRejectsSizeMismatch() {
        val apk = writeTempApk("release-apk")
        val update = validUpdateInfo(
            apkSha256 = "a8f3d9dba2d6f10a92a50ab7ea364aa6addded90be1b37f8abc8c7c3843b9ae1",
            apkSize = apk.length() + 1,
        )

        assertThrows(IllegalStateException::class.java) {
            AppUpdateRepository.verifyDownloadedApk(apk, update)
        }
    }

    private fun validUpdateInfo(
        apkSha256: String = "a8f3d9dba2d6f10a92a50ab7ea364aa6addded90be1b37f8abc8c7c3843b9ae1",
        apkSize: Long = 11L,
    ): AppUpdateInfo =
        AppUpdateInfo(
            versionCode = 18L,
            versionName = "0.2.6",
            apkUrl = "https://example.com/app-release.apk",
            apkSha256 = apkSha256,
            apkSize = apkSize,
            releaseNotes = "测试更新",
        )

    private fun writeTempApk(content: String): File =
        File.createTempFile("astock-update-test-", ".apk").apply {
            deleteOnExit()
            writeText(content)
        }
}
