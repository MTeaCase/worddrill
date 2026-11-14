package com.worddrill.app.quiz

import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 干扰项：形近优先，但绝不把「另一个正确答案」放进去。 */
class ConfuseTest {

    private fun word(id: Long, text: String, meaning: String, pos: String = "v.") =
        Word(id = id, bookId = 1, ord = id.toInt(), text = text, pos = pos, meaning = meaning, starred = false)

    private val words = listOf(
        word(1, "adapt", "适应"),
        word(2, "adopt", "采用"),
        word(3, "adept", "熟练的", "adj."),
        word(4, "adaptation", "改编", "n."),
        word(5, "abandon", "放弃"),
        word(6, "quit", "放弃"),
        word(7, "quiet", "安静的", "adj."),
        word(8, "quite", "相当", "adv."),
        word(9, "apple", "苹果", "n."),
        word(10, "apply", "申请"),
    )
    private val pool = Confuse.Pool(words)

    @Test
    fun picksLookalikesAndSkipsSameFamily() {
        val correct = words.first { it.text == "adapt" }
        val picked = Confuse.pickDistractors(correct, pool, Confuse.Mode.EN_ZH, 3, Random(3), vary = false)
        assertTrue("应该挑到 adopt / adept 这类形近词", picked.any { it.text == "adopt" || it.text == "adept" })
        assertFalse("同词族的 adaptation 不能当干扰项", picked.any { it.text == "adaptation" })
        assertFalse(picked.any { it.id == correct.id })
    }

    @Test
    fun neverOffersAnAmbiguousMeaning() {
        val abandon = words.first { it.text == "abandon" }
        val picked = Confuse.pickDistractors(abandon, pool, Confuse.Mode.EN_ZH, 3, Random(5), vary = false)
        assertFalse("放弃 与 放弃 释义相同，不能同框", picked.any { it.text == "quit" })
    }

    @Test
    fun optionsAreUniqueAndCarryMeanings() {
        val item = QuizEngine.makeItem(
            words.first { it.text == "adapt" },
            words,
            PracticeMode.ZH_EN,
            Random(1),
        )
        assertEquals(4, item.options.size)
        assertEquals(4, item.options.toSet().size)
        assertEquals("汉译英要带上每个选项的释义", 4, item.optionMeanings.size)
        assertEquals(item.answer, item.options[item.correctOption])
    }

    @Test
    fun englishToChineseHasNoExtraMeanings() {
        val item = QuizEngine.makeItem(words.first { it.text == "adapt" }, words, PracticeMode.EN_ZH, Random(2))
        assertTrue(item.optionMeanings.isEmpty())
        assertEquals(4, item.options.size)
    }

    @Test
    fun tinyPoolStillGetsFourOptionsWhenPossible() {
        val two = listOf(word(1, "alpha", "甲"), word(2, "beta", "乙"))
        val item = QuizEngine.makeItem(two.first(), two, PracticeMode.EN_ZH, Random(2))
        assertEquals(2, item.options.size)
        assertTrue(item.correctOption in 0..1)
    }

    @Test
    fun skipsBrokenEntries() {
        val dirtyWords = listOf(
            word(1, "adapt", "适应"),
            word(2, "v]", "种类"),
            word(3, "afte", "在后"),
        )
        val picked = Confuse.pickDistractors(
            dirtyWords.first(),
            Confuse.Pool(dirtyWords),
            Confuse.Mode.EN_ZH,
            3,
            Random(4),
            vary = false,
        )
        assertTrue("坏词头不能当选项", picked.none { it.text == "v]" || it.text == "afte" })
    }

    @Test
    fun sameFamilyOnlyMatchesDerivations() {
        assertTrue(Confuse.sameFamily("adapt", "adaptation"))
        assertFalse(Confuse.sameFamily("adapt", "adopt"))
        assertFalse(Confuse.sameFamily("cat", "cats"))
    }
}