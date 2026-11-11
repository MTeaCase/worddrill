package com.worddrill.app.quiz

import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.Word
import com.worddrill.app.parser.VocabMarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun listenToMeaningAsksForMeaningAndHidesTheWord() {
        val item = QuizEngine.makeItem(all[0], all, PracticeMode.LISTEN_ZH, Random(7))
        assertEquals(PracticeMode.LISTEN_ZH, item.mode)
        assertEquals(4, item.options.size)
        assertEquals("n. 苹果", item.options[item.correctOption])
        assertFalse(item.spell)
        assertFalse("题面不能露出单词", item.prompt.contains("apple", ignoreCase = true))
    }

    @Test
    fun modeIsCarriedOnEveryItem() {
        val item = QuizEngine.makeItem(all[1], all, PracticeMode.ZH_EN_SPELL, Random(2))
        assertEquals(PracticeMode.ZH_EN_SPELL, item.mode)
    }

    @Test
    fun mixedModeIsStableForTheSameWord() {
        val first = QuizEngine.pickMixed(all[0], ttsEnabled = true)
        val second = QuizEngine.pickMixed(all[0], ttsEnabled = true)
        assertEquals(first, second)
        assertTrue(first in QuizEngine.MIXED_MODES)
    }

    @Test
    fun mixedModeSpreadsOverSeveralModes() {
        val words = (1L..40L).map { word(it, "w$it", "m$it") }
        val modes = words.map { QuizEngine.pickMixed(it, ttsEnabled = true) }.toSet()
        assertTrue("实际只分到 $modes", modes.size >= 3)
    }

    @Test
    fun mixedModeWithoutTtsNeverPicksListening() {
        val words = (1L..40L).map { word(it, "w$it", "m$it") }
        val modes = words.map { QuizEngine.pickMixed(it, ttsEnabled = false) }
        assertTrue(modes.none { it.needsTts })
    }

    @Test
    fun mixedBuildResolvesEveryItemToAConcreteMode() {
        val items = QuizEngine.build(all, all, all.size, PracticeMode.MIXED, Random(3), ttsEnabled = true)
        assertEquals(all.size, items.size)
        assertTrue(items.all { it.mode != PracticeMode.MIXED })
        assertEquals(all.size, items.map { it.word.id }.toSet().size)
    }

    @Test
    fun fastAnswerRequiresCorrectnessAndThreeSeconds() {
        assertTrue(QuizEngine.isFast(1_200, correct = true))
        assertTrue(QuizEngine.isFast(3_000, correct = true))
        assertFalse(QuizEngine.isFast(3_500, correct = true))
        assertFalse(QuizEngine.isFast(1_200, correct = false))
    }

    @Test
    fun editDistanceCountsSingleEdits() {
        assertEquals(0, QuizEngine.editDistance("apple", "apple"))
        assertEquals(1, QuizEngine.editDistance("appl", "apple"))
        assertEquals(2, QuizEngine.editDistance("recieve", "receive"))
        assertEquals(5, QuizEngine.editDistance("", "apple"))
    }

    @Test
    fun diagnosisIsSilentWhenTheAnswerIsRight() {
        assertNull(QuizEngine.diagnose("Apple", "apple"))
        assertNull(QuizEngine.diagnose("  ", "apple"))
    }

    @Test
    fun diagnosisNamesTheMissingTail() {
        val text = QuizEngine.diagnose("appl", "apple").orEmpty()
        assertTrue("实际=$text", text.contains("只差 1 个字母"))
        assertTrue("实际=$text", text.contains("后面还差 1 个字母"))
    }

    @Test
    fun diagnosisCatchesIeEiAndDoubleLetters() {
        val mixed = QuizEngine.diagnose("recieve", "receive").orEmpty()
        assertTrue("实际=$mixed", mixed.contains("记混了"))
        val doubled = QuizEngine.diagnose("necesary", "necessary").orEmpty()
        assertTrue("实际=$doubled", doubled.contains("要双写"))
    }

    // ---------- 例句填词 ----------

    @Test
    fun blankExampleCutsTheFirstOccurrence() {
        val blank = QuizEngine.blankExample("He abandoned his car last year.", "abandon")!!
        assertEquals("He ____ his car last year.", blank.line)
        assertEquals("abandoned", blank.token)
    }

    @Test
    fun blankExampleSkipsTinyWordsAndMissingWords() {
        assertNull(QuizEngine.blankExample("a cat sat", "a"))
        assertNull(QuizEngine.blankExample("no match here", "zebra"))
        assertNull(QuizEngine.blankExample("", "abandon"))
    }

    @Test
    fun exampleModeAcceptsBothInflectionAndBaseForm() {
        assertTrue(QuizEngine.isAcceptable("abandoned", "abandoned", listOf("abandon")))
        assertTrue(QuizEngine.isAcceptable("abandon", "abandoned", listOf("abandon")))
        assertFalse(QuizEngine.isAcceptable("abandons", "abandoned", listOf("abandon")))
    }

    @Test
    fun mixedModeNeverPicksExampleModeWithoutAnExample() {
        // all 里的词 example 都是空串
        val modes = (1L..60L).map { QuizEngine.pickMixed(word(it, "w$it", "m$it"), ttsEnabled = true) }
        assertTrue(modes.none { it == PracticeMode.EX_SPELL })
    }

    @Test
    fun mixedModeCanPickExampleModeWhenTheWordHasOne() {
        val withExample = all[0].copy(example = "An apple a day.||一天一苹果。")
        val modes = (1L..60L).map {
            QuizEngine.pickMixed(withExample.copy(id = it), ttsEnabled = true)
        }
        assertTrue("实际=$modes", modes.any { it == PracticeMode.EX_SPELL })
    }

    @Test
    fun exampleModeBuildsASentencePromptAndDropsWordsWithoutExamples() {
        val withExample = all[0].copy(example = "An apple a day keeps the doctor away.||一天一苹果，医生远离我。")
        val items = QuizEngine.build(
            pool = listOf(withExample) + all.drop(1),
            allWords = all,
            count = 10,
            mode = PracticeMode.EX_SPELL,
            random = Random(1),
        )
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals(PracticeMode.EX_SPELL, item.mode)
        assertTrue(item.spell)
        assertTrue(item.longPrompt)
        assertTrue(item.prompt.contains("____"))
        assertTrue(item.prompt.contains("一天一苹果"))
    }

    // ---------- 词形变化 ----------

    @Test
    fun formModeOnlyKeepsWordsThatHaveAForm() {
        val verb = word(1, "go", "走").copy(pos = "v.")
        val nouns = word(2, "child", "孩子").copy(pos = "n.")
        val prep = word(3, "on", "在…上").copy(pos = "prep.")
        val items = QuizEngine.build(
            pool = listOf(verb, nouns, prep),
            allWords = listOf(verb, nouns, prep),
            count = 10,
            mode = PracticeMode.FORM,
            random = Random(2),
        )
        assertEquals(2, items.size)
        assertEquals(setOf("went", "children"), items.map { it.answer }.toSet())
        assertTrue(items.all { it.spell })
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
