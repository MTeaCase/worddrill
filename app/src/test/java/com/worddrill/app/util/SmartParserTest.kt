package com.worddrill.app.util

import com.worddrill.app.parser.VocabMarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 智能解析：服务返回值的清洗与解析（不联网）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartParserTest {

    @Test
    fun parsesWellFormedResponse() {
        val result = SmartParser.parseResponse(
            """
            {"ok":true,"count":2,"markdown":"| 英文 | 词性 | 汉语 |\n| --- | --- | --- |\n| abandon | v. | 放弃 |\n| ability | n. | 能力 |"}
            """.trimIndent(),
        )
        assertEquals(2, result.count)
        assertTrue(result.markdown.contains("abandon"))
        assertEquals(2, VocabMarkdownParser.parse(result.markdown).size)
    }

    @Test
    fun stripsCodeFenceAndChatter() {
        val raw = """
            好的，整理结果如下：
            ```
            | 英文 | 词性 | 汉语 |
            | --- | --- | --- |
            | apple | n. | 苹果 |
            ```
            希望对你有帮助。
        """.trimIndent()
        val markdown = SmartParser.sanitize(raw)
        assertEquals(
            """
            | 英文 | 词性 | 汉语 |
            | --- | --- | --- |
            | apple | n. | 苹果 |
            """.trimIndent(),
            markdown,
        )
        assertEquals(1, VocabMarkdownParser.parse(markdown).size)
    }

    @Test
    fun addsHeaderWhenModelOmitsIt() {
        val markdown = SmartParser.sanitize("| apple | n. | 苹果 |\n| pear | n. | 梨 |")
        assertTrue(markdown.startsWith("| 英文 | 词性 | 汉语 |"))
        assertEquals(2, VocabMarkdownParser.parse(markdown).size)
    }

    @Test
    fun countsRowsWhenServerOmitsCount() {
        val result = SmartParser.parseResponse(
            """{"markdown":"| 英文 | 词性 | 汉语 |\n| --- | --- | --- |\n| a | | 一 |\n| b | | 二 |"}""",
        )
        assertEquals(2, result.count)
    }

    @Test
    fun throwsWhenServerReportsError() {
        val error = assertThrows(IllegalStateException::class.java) {
            SmartParser.parseResponse("""{"ok":false,"error":"商汤接口返回 401"}""")
        }
        assertEquals("商汤接口返回 401", error.message)
    }

    @Test
    fun throwsWhenNothingParsed() {
        assertThrows(IllegalStateException::class.java) {
            SmartParser.parseResponse("""{"ok":true,"markdown":"抱歉，我无法处理","count":0}""")
        }
    }

    @Test
    fun throwsOnNonJsonBody() {
        assertThrows(IllegalStateException::class.java) {
            SmartParser.parseResponse("<html>502 Bad Gateway</html>")
        }
    }
}
