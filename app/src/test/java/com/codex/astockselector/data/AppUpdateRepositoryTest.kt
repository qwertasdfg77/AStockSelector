package com.codex.astockselector.data

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
