package com.isaakhanimann.journal.ui.tabs.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun `parseVersionTuple handles zh-plus suffix and v prefix`() {
        assertEquals(listOf(13, 5, 0), AppUpdateChecker.parseVersionTuple("13.5.0-zh-plus"))
        assertEquals(listOf(13, 4, 1), AppUpdateChecker.parseVersionTuple("v13.4.1"))
        assertEquals(listOf(14, 0), AppUpdateChecker.parseVersionTuple("V14.0+build9"))
        assertEquals(emptyList<Int>(), AppUpdateChecker.parseVersionTuple(""))
        assertEquals(emptyList<Int>(), AppUpdateChecker.parseVersionTuple("nightly-build"))
    }

    @Test
    fun `isRemoteVersionNewer compares semantic tuples accurately`() {
        assertFalse(AppUpdateChecker.isRemoteVersionNewer("13.5.0-zh-plus", "v13.4.1"))
        assertFalse(AppUpdateChecker.isRemoteVersionNewer("13.5.0-zh-plus", "v13.5.0"))
        assertFalse(AppUpdateChecker.isRemoteVersionNewer("13.5.0-zh-plus", "v13.5.0-zh-plus"))

        assertTrue(AppUpdateChecker.isRemoteVersionNewer("13.5.0-zh-plus", "v13.5.1"))
        assertTrue(AppUpdateChecker.isRemoteVersionNewer("13.5.0-zh-plus", "v13.6"))
        assertTrue(AppUpdateChecker.isRemoteVersionNewer("13.5.0-zh-plus", "v14.0.0"))
        assertTrue(AppUpdateChecker.isRemoteVersionNewer("13.4", "v13.4.1"))

        assertFalse(AppUpdateChecker.isRemoteVersionNewer("13.5.0-zh-plus", "draft-tag"))
    }

    @Test
    fun `parseLatestReleaseJson selects apk asset and release notes`() {
        val sampleJson = """
            {
              "tag_name": "v13.5.0",
              "name": "PsychonautWiki Journal+ v13.5.0",
              "body": "## 更新内容\n- 新增跨日血药实况曲线\n- 新增应用内检查更新",
              "html_url": "https://github.com/yuanyuan2233/PsychonautWiki-Journal-Plus/releases/tag/v13.5.0",
              "published_at": "2026-11-16T00:00:00Z",
              "assets": [
                {
                  "name": "SHA256SUMS.txt",
                  "browser_download_url": "https://github.com/yuanyuan2233/PsychonautWiki-Journal-Plus/releases/download/v13.5.0/SHA256SUMS.txt"
                },
                {
                  "name": "PsychonautWiki-Journal-v13.5.0.apk",
                  "browser_download_url": "https://github.com/yuanyuan2233/PsychonautWiki-Journal-Plus/releases/download/v13.5.0/PsychonautWiki-Journal-v13.5.0.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = AppUpdateChecker.parseLatestReleaseJson(sampleJson)
        assertEquals("v13.5.0", parsed.tagName)
        assertEquals("PsychonautWiki Journal+ v13.5.0", parsed.releaseName)
        assertTrue(parsed.body.contains("新增跨日血药实况曲线"))
        assertEquals(
            "https://github.com/yuanyuan2233/PsychonautWiki-Journal-Plus/releases/tag/v13.5.0",
            parsed.htmlUrl
        )
        assertEquals(
            "https://github.com/yuanyuan2233/PsychonautWiki-Journal-Plus/releases/download/v13.5.0/PsychonautWiki-Journal-v13.5.0.apk",
            parsed.apkDownloadUrl
        )
    }

    @Test
    fun `parseLatestReleaseJson handles null body and ignores non-https apk urls`() {
        val minimalJson = """
            {
              "tag_name": "v13.4.1",
              "name": "",
              "body": null,
              "html_url": "http://insecure.example.com/release",
              "assets": [
                {
                  "name": "app.apk",
                  "browser_download_url": "http://insecure.example.com/app.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = AppUpdateChecker.parseLatestReleaseJson(minimalJson)
        assertEquals("v13.4.1", parsed.tagName)
        assertEquals("v13.4.1", parsed.releaseName)
        assertEquals("", parsed.body)
        assertEquals(GITHUB_RELEASES_LATEST_URL, parsed.htmlUrl)
        assertNull(parsed.apkDownloadUrl)
    }

    @Test(expected = IllegalStateException::class)
    fun `parseLatestReleaseJson rejects payload without tag_name`() {
        AppUpdateChecker.parseLatestReleaseJson("""{"message": "Not Found"}""")
    }
}
