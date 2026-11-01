package com.worddrill.app.quiz

import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.Word
import com.worddrill.app.parser.VocabMarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuizEngineTest {

    private fun word(id: Long, text: String, meaning: String, pos: String = "n.") =
        Word(id = id, bookId = 1, ord = id.toInt(), text = text, pos = pos, meaning = meaning, starred = false)

    private val all = listOf(
        word(1, "apple", "苹果"),
        word(2, "banana", "香蕉"),
        word(3, "cherry", "樱桃"),
        word(4, "dragon", "龙"),
        word(5, "eagle", "鹰"),
        word(6, "forest", "森林"),
    )

    @Test
    fun englishToChineseHasFourUniqueOptionsAndOneCorrect() {
        val item = QuizEngine.makeItem(all[0], all, PracticeMode.EN_ZH, Random(7))
        assertEquals(4, item.options.size)
        assertEquals(4, item.options.toSet().size)
        assertEquals("n. 苹果", item.answer)
        assertEquals("n. 苹果", item.options[item.correctOption])
        assertFalse(item.spell)
    }

    @Test
    fun chineseToEnglishOptionsAreEnglishWords() {
        val item = QuizEngine.makeItem(all[1], all, PracticeMode.ZH_EN, Random(3))
        assertEquals("banana", item.answer)
        assertEquals(4, item.options.size)
        assertTrue(item.options.contains("banana"))
        assertEquals("n. 香蕉", item.prompt)
    }

    @Test
    fun spellingItemHasNoOptions() {
        val item = QuizEngine.makeItem(all[2], all, PracticeMode.ZH_EN_SPELL, Random(1))
        assertTrue(item.spell)
        assertTrue(item.options.isEmpty())
        assertEquals("cherry", item.answer)
        assertEquals("n. 樱桃", item.prompt)
    }

    @Test
    fun buildRespectsCountAndUniqueness() {
        val items = QuizEngine.build(all, all, 4, PracticeMode.EN_ZH, Random(11))
        assertEquals(4, items.size)
        assertEquals(4, items.map { it.word.id }.toSet().size)
    }

    @Test
    fun smallPoolStillBuildsValidOptions() {
        val small = all.take(2)
        val item = QuizEngine.makeItem(small[0], small, PracticeMode.EN_ZH, Random(5))
        assertEquals(2, item.options.size)
        assertEquals("n. 苹果", item.options[item.correctOption])
    }

    @Test
    fun answerCheckIgnoresCaseAndExtraSpaces() {
        assertTrue(QuizEngine.isCorrect("  Apple ", "apple"))
        assertTrue(QuizEngine.isCorrect("pick   out", "pick out"))
        assertFalse(QuizEngine.isCorrect("", "apple"))
        assertFalse(QuizEngine.isCorrect("apples", "apple"))
    }

    @Test
    fun hintRevealsFirstLetterOnly() {
        assertEquals("a _ _ _ _", QuizEngine.hint("apple"))
        assertEquals("p _ _ _ / o _ _", QuizEngine.hint("pick out"))
    }

    @Test
    fun markdownTableParsingKeepsOrderAndSkipsHeader() {
        val md = """
            # 词汇表

            | # | 英文 | 词性 | 汉语 |
            |---|------|------|------|
            | 1 | pick out | 动词短语 | 精心挑选；认出来 |
            | 2 | competence | n. | 能力；胜任 |
            | 7 | tricky | adj. | 难对付的，棘手的 |
        """.trimIndent()
        val entries = VocabMarkdownParser.parse(md)
        assertEquals(3, entries.size)
        assertEquals("pick out", entries[0].word)
        assertEquals("动词短语", entries[0].pos)
        assertEquals("competence", entries[1].word)
        assertEquals("tricky", entries[2].word)
    }

    @Test
    fun markdownWithoutIndexColumnAndDuplicates() {
        val md = """
            | 英文 | 词性 | 汉语 |
            | --- | --- | --- |
            | apple | n. | 苹果 |
            | Apple | n. | 苹果（重复） |
            | run | v. | 跑 |
        """.trimIndent()
        val entries = VocabMarkdownParser.parse(md)
        assertEquals(2, entries.size)
        assertEquals("apple", entries[0].word)
        assertEquals("苹果", entries[0].meaning)
        assertEquals("run", entries[1].word)
    }
}
