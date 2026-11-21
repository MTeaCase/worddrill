package com.worddrill.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 离线语音包清单解析（不联网、不装包）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsPackTest {

    @Test
    fun parsesFullManifest() {
        val info = TtsPack.parse(
            """
            {
              "versionCode": 3,
              "versionName": "1.1.0",
              "content": "1. 换用更清晰的音色",
              "url": "https://gitee.com/MTeaCase/worddrilltts/releases/download/v1.1.0/WordDrill-tts-engine-1.1.0.apk",
              "size": 49345678,
              "md5": "0123456789abcdef0123456789abcdef"
            }
            """.trimIndent(),
        )
        val parsed = requireNotNull(info)
        assertEquals(3, parsed.versionCode)
        assertEquals("1.1.0", parsed.versionName)
        assertEquals(49345678L, parsed.size)
        assertEquals("0123456789abcdef0123456789abcdef", parsed.md5)
        assertEquals(
            "https://gitee.com/MTeaCase/worddrilltts/releases/download/v1.1.0/WordDrill-tts-engine-1.1.0.apk",
            parsed.url,
        )
    }

    @Test
    fun rejectsManifestWithoutUrl() {
        assertNull(TtsPack.parse("""{"versionCode": 2, "versionName": "1.0.1"}"""))
    }

    @Test
    fun rejectsManifestWithoutVersionCode() {
        assertNull(TtsPack.parse("""{"versionName": "1.0.1", "url": "https://example.com/a.apk"}"""))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(TtsPack.parse("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun toleratesMissingOptionalFields() {
        val parsed = requireNotNull(
            TtsPack.parse("""{"versionCode": 2, "url": "https://example.com/a.apk"}"""),
        )
        assertEquals("2", parsed.versionName)
        assertEquals(0L, parsed.size)
        assertEquals("", parsed.md5)
    }
}