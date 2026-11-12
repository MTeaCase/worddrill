package com.worddrill.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 更新清单解析 + 安装包前置校验（不联网、不装包）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun parsesFullManifest() {
        val info = Updater.parseManifest(
            """
            {
              "versionCode": 5,
              "versionName": "1.2.0",
              "content": "1. 新增自动更新\n2. 修复若干问题",
              "url": "https://worddrill-update.pages.dev/WordDrill-1.2.0.apk",
              "size": 5333256,
              "md5": "6f8c35df3c75ee8dca244d5ceb8d1a78"
            }
            """.trimIndent(),
        )
        assertNotNull(info)
        val parsed = requireNotNull(info)
        assertEquals(5, parsed.versionCode)
        assertEquals("1.2.0", parsed.versionName)
        assertTrue(parsed.content.startsWith("1. 新增自动更新"))
        assertTrue(parsed.url.endsWith(".apk"))
        assertEquals(5333256L, parsed.size)
        assertEquals("6f8c35df3c75ee8dca244d5ceb8d1a78", parsed.md5)
    }

    @Test
    fun contentCanBeStringArray() {
        val info = Updater.parseManifest(
            """{"versionCode":6,"versionName":"1.3.0","content":["第一行","第二行"],"url":"https://x/a.apk"}""",
        )
        assertEquals("第一行\n第二行", info?.content)
    }

    @Test
    fun optionalFieldsDefaultToEmpty() {
        val info = Updater.parseManifest("""{"versionCode":6,"url":"https://x/a.apk"}""")
        assertEquals("6", info?.versionName)
        assertEquals(0L, info?.size)
        assertEquals("", info?.md5)
        assertEquals("", info?.content)
    }

    @Test
    fun rejectsManifestWithoutUrl() {
        assertNull(Updater.parseManifest("""{"versionCode":6,"versionName":"1.3.0"}"""))
    }

    @Test
    fun rejectsManifestWithZeroVersionCode() {
        assertNull(Updater.parseManifest("""{"versionCode":0,"url":"https://x/a.apk"}"""))
    }

    @Test
    fun rejectsHtmlErrorPage() {
        assertNull(Updater.parseManifest("<!DOCTYPE html><html><body>404</body></html>"))
    }

    @Test
    fun apkHeaderIsDetectedAndHtmlRejected() {
        val apk = temp.newFile("ok.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))
        }
        assertTrue(ApkDownloader.looksLikeApk(apk))

        val html = temp.newFile("bad.apk").apply {
            writeText("<html>not found</html>")
        }
        assertFalse(ApkDownloader.looksLikeApk(html))

        val empty = temp.newFile("empty.apk")
        assertFalse(ApkDownloader.looksLikeApk(empty))
        assertFalse("不存在的文件", ApkDownloader.looksLikeApk(File(temp.root, "none.apk")))
    }

    @Test
    fun md5IsLowercaseHex() {
        val file = temp.newFile("a.bin").apply { writeText("abc") }
        // md5("abc") = 900150983cd24fb0d6963f7d28e17f72
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ApkDownloader.md5(file))
    }

    @Test
    fun sizeFormatting() {
        assertEquals("512 B", ApkDownloader.formatSize(512))
        assertEquals("2 KB", ApkDownloader.formatSize(2048))
        assertEquals("5.1 MB", ApkDownloader.formatSize(5333256))
    }

    // ---------- 多清单源的选路 ----------

    private fun info(code: Int, name: String) =
        Updater.UpdateInfo(code, name, "", "https://x/$code.apk")

    @Test
    fun fetchTakesTheNewestSource() {
        val sources = mapOf("gitee" to info(9, "1.4.9"), "cf" to info(12, "1.5.2"))
        val picked = Updater.fetch(listOf("gitee", "cf"), currentVersion = 10) { sources[it] }
        assertEquals(12, picked?.versionCode)
    }

    @Test
    fun fetchSkipsBrokenSourcesAndKeepsLooking() {
        val picked = Updater.fetch(listOf("down", "empty", "ok"), currentVersion = 10) { url ->
            when (url) {
                "down" -> throw java.io.IOException("timeout")
                "empty" -> null
                else -> info(12, "1.5.2")
            }
        }
        assertEquals(12, picked?.versionCode)
    }

    @Test
    fun fetchReturnsTheNewestManifestEvenWhenItIsOlderThanInstalled() {
        // fetch 只负责挑「最好的一份」，是否提示更新由调用方比较版本号决定
        val picked = Updater.fetch(listOf("a", "b"), currentVersion = 10) { info(8, "1.4.0") }
        assertEquals(8, picked?.versionCode)
    }

    @Test
    fun fetchStopsAtTheFirstSourceWithAnUpdate() {
        var calls = 0
        val picked = Updater.fetch(listOf("a", "b", "c"), currentVersion = 10) {
            calls++
            info(12, "1.5.2")
        }
        assertEquals(12, picked?.versionCode)
        assertEquals("正常情况只发一次请求", 1, calls)
    }

    @Test
    fun fetchSurfacesTheLastErrorWhenNothingWorks() {
        val failure = runCatching {
            Updater.fetch(listOf("a", "b"), currentVersion = 10) { throw java.io.IOException("boom") }
        }
        assertTrue(failure.isFailure)
        assertEquals("boom", failure.exceptionOrNull()?.message)
    }
}
