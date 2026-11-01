package com.worddrill.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用技能实际产出的《单词答案一 · 词汇表》验证解析器。 */
class RealVocabFileTest {

    private fun loadSample(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("vocab_sample.md")) {
            "测试资源 vocab_sample.md 缺失"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun parsesRealWordListFile() {
        val entries = VocabMarkdownParser.parse(loadSample())
        assertEquals(199, entries.size)

        assertEquals("pick out", entries[0].word)
        assertEquals("动词短语", entries[0].pos)
        assertEquals("精心挑选；认出来", entries[0].meaning)

        assertEquals("competence", entries[1].word)
        assertEquals("n.", entries[1].pos)
        assertEquals("能力；胜任", entries[1].meaning)

        assertEquals("remote", entries.last().word)
        assertEquals("adj.", entries.last().pos)
        assertEquals("偏远的；远程的", entries.last().meaning)

        assertTrue(entries.all { it.word.isNotBlank() && it.meaning.isNotBlank() })
        assertEquals(199, entries.map { it.word.lowercase() }.toSet().size)
    }
}
