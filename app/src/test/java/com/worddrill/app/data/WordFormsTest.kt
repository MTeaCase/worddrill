package com.worddrill.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WordFormsTest {

    private fun word(text: String, pos: String) = Word(
        id = 1,
        bookId = 1,
        ord = 1,
        text = text,
        pos = pos,
        meaning = "测试",
        starred = false,
    )

    @Test
    fun irregularVerbsComeFromTheTable() {
        assertEquals("went", WordForms.pastForm("go"))
        assertEquals("wrote", WordForms.pastForm("write"))
        assertEquals("thought", WordForms.pastForm("think"))
        assertEquals("put", WordForms.pastForm("put"))
    }

    @Test
    fun regularVerbsFollowTheSuffixRules() {
        assertEquals("played", WordForms.pastForm("play"))
        assertEquals("studied", WordForms.pastForm("study"))
        assertEquals("used", WordForms.pastForm("use"))
        assertEquals("visited", WordForms.pastForm("visit"))
        assertEquals("opened", WordForms.pastForm("open"))
        assertEquals("stopped", WordForms.pastForm("stop"))
        assertEquals("planned", WordForms.pastForm("plan"))
    }

    @Test
    fun onlyVerbsAndKnownIrregularNounsGetQuestions() {
        assertEquals("went", WordForms.questionFor(word("go", "v."))?.answer)
        assertEquals("过去式", WordForms.questionFor(word("go", "v."))?.label)

        // 名词只出确定的不规则复数，规则复数和不可数名词一律不出
        assertEquals("children", WordForms.questionFor(word("child", "n."))?.answer)
        assertNull(WordForms.questionFor(word("information", "n.")))
        assertNull(WordForms.questionFor(word("advice", "n.")))

        // 形容词不出比较级（长词要用 more，容易被教错）
        assertNull(WordForms.questionFor(word("beautiful", "a.")))
        assertNull(WordForms.questionFor(word("on", "prep.")))
    }

    @Test
    fun wordsThatCannotBeConjugatedAreRejected() {
        assertNull(WordForms.pastForm(""))
        assertNull(WordForms.pastForm("rock'n'roll"))
        assertNotNull(WordForms.pastForm("like"))
    }
}