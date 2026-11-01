package com.worddrill.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 词典搜索、每日统计、例句三块新功能的回归测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryAndStatsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = WordRepository(context)

    @Test
    fun searchFindsWordByEnglishAndByChinese() {
        val byEnglish = repo.searchWords("aband")
        assertTrue("英文模糊搜索应有结果", byEnglish.any { it.text.contains("aband", ignoreCase = true) })

        val byChinese = repo.searchWords("放弃")
        assertTrue("中文释义搜索应有结果", byChinese.isNotEmpty())
        assertTrue(byChinese.all { it.meaning.contains("放弃") })
    }

    @Test
    fun searchRanksExactMatchFirst() {
        val found = repo.searchWords("abandon")
        assertTrue(found.isNotEmpty())
        assertEquals("abandon", found.first().text)
    }

    @Test
    fun searchIgnoresBlockedWords() {
        val book = repo.books().first()
        val word = repo.words(book.id, limit = 1).first()
        repo.setBlocked(word.id, true)
        try {
            assertTrue(repo.searchWords(word.text, limit = 50).none { it.id == word.id })
        } finally {
            repo.setBlocked(word.id, false)
        }
    }

    @Test
    fun searchWithBlankQueryReturnsNothing() {
        assertTrue(repo.searchWords("   ").isEmpty())
    }

    @Test
    fun builtinWordsCarryExampleSentences() {
        val withExample = repo.allWords().count { it.hasExample }
        assertTrue("内置词库应基本全覆盖例句，实际 $withExample 条", withExample > 3700)
        val sample = repo.allWords().first { it.hasExample }
        assertTrue(sample.exampleEn.isNotBlank())
        assertTrue(sample.exampleZh.isNotBlank())
    }

    @Test
    fun recordingAnswersFeedsDailyStats() {
        repo.clearDaily()
        val book = repo.books().first()
        val words = repo.words(book.id, limit = 4)
        repo.recordAnswer(words[0].id, PracticeMode.EN_ZH, correct = true)
        repo.recordAnswer(words[1].id, PracticeMode.EN_ZH, correct = true)
        repo.recordAnswer(words[2].id, PracticeMode.EN_ZH, correct = false)

        val stats = repo.dailyStats(7)
        assertEquals(7, stats.size)
        val today = stats.last()
        assertEquals(3, today.answered)
        assertEquals(2, today.correct)
        assertEquals(66, today.accuracy)
    }

    @Test
    fun studySummaryComputesStreakAndTotals() {
        repo.clearDaily()
        val book = repo.books().first()
        val word = repo.words(book.id, limit = 1).first()
        repo.recordAnswer(word.id, PracticeMode.ZH_EN, correct = true)

        val summary = repo.studySummary(days = 7, dailyGoal = 30)
        assertEquals(1, summary.todayAnswered)
        assertEquals(1, summary.streak)
        assertEquals(1, summary.totalAnswered)
        assertEquals(1, summary.totalCorrect)
        assertEquals(30, summary.dailyGoal)
    }
}
