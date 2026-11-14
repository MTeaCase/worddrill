package com.worddrill.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.worddrill.app.parser.VocabEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 新练习默认只抽没练过、也没标已掌握的词；「允许重复」时才放开练过的。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PracticeRulesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = WordRepository(context)

    private fun makeBook(vararg words: String): Long {
        val entries = words.map { VocabEntry(it, "n.", "释义 $it") }
        return repo.importEntries(null, "抽题规则测试", entries).bookId
    }

    @Test
    fun freshCountSkipsPracticedAndKnown() {
        val bookId = makeBook("alpha", "beta", "gamma")
        val words = repo.words(bookId)
        repo.recordAnswer(words[0].id, PracticeMode.EN_ZH, correct = true)
        repo.setKnown(words[1].id, known = true)

        assertEquals("只剩 gamma 算新词", 1, repo.freshCount(bookId))
    }

    @Test
    fun defaultPracticeOnlyDrawsUnpracticedWords() {
        val bookId = makeBook("alpha", "beta")
        val alpha = repo.words(bookId).first { it.text == "alpha" }
        repo.recordAnswer(alpha.id, PracticeMode.EN_ZH, correct = true)

        val picked = repo.pickWords(bookId, 1, 10, count = 10, allowRepeat = false)
        assertEquals(listOf("beta"), picked.map { it.text })
    }

    @Test
    fun allowRepeatDrawsPracticedWordsAgain() {
        val bookId = makeBook("alpha", "beta")
        val alpha = repo.words(bookId).first { it.text == "alpha" }
        repo.recordAnswer(alpha.id, PracticeMode.EN_ZH, correct = true)

        val picked = repo.pickWords(bookId, 1, 10, count = 10, allowRepeat = true)
        assertEquals(2, picked.size)
    }

    @Test
    fun freshCountDropsWhenEverythingIsKnown() {
        val bookId = makeBook("alpha", "beta")
        repo.words(bookId).forEach { repo.setKnown(it.id, known = true) }

        assertEquals(0, repo.freshCount(bookId))
        assertEquals(true, repo.dailyPack(bookId, 10, 10, 10).isEmpty)
    }
}