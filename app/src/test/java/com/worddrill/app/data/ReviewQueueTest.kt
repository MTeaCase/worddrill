package com.worddrill.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 复习队列的数据层行为：答错的词第二天到期，答对的按曲线延后。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewQueueTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = WordRepository(context)

    private fun firstWord(): Word {
        val book = repo.books().first { it.builtin }
        return repo.words(book.id, limit = 1).first()
    }

    @Test
    fun wrongAnswerIsDueTomorrowNotToday() {
        val word = firstWord()
        repo.recordAnswer(word.id, PracticeMode.EN_ZH, correct = false)

        val now = System.currentTimeMillis()
        assertEquals(0, repo.dueReviewCount(now))
        assertTrue(repo.dueReviewWords(limit = 10, now = now + ReviewScheduler.DAY_MS + 60_000).any { it.id == word.id })
    }

    @Test
    fun correctAnswerAlsoSchedulesNextDay() {
        val word = firstWord()
        repo.recordAnswer(word.id, PracticeMode.EN_ZH, correct = true)

        val now = System.currentTimeMillis()
        assertEquals(0, repo.dueReviewCount(now))
        assertTrue(repo.dueReviewWords(limit = 10, now = now + ReviewScheduler.DAY_MS + 60_000).any { it.id == word.id })
    }

    @Test
    fun dueListSkipsBlockedWords() {
        val word = firstWord()
        repo.recordAnswer(word.id, PracticeMode.EN_ZH, correct = false)
        repo.setBlocked(word.id, true)
        try {
            val later = System.currentTimeMillis() + ReviewScheduler.DAY_MS + 60_000
            assertTrue(repo.dueReviewWords(limit = 50, now = later).none { it.id == word.id })
            assertEquals(0, repo.dueReviewCount(later))
        } finally {
            repo.setBlocked(word.id, false)
        }
    }

    @Test
    fun forecastTodayBucketAgreesWithDueCount() {
        val word = firstWord()
        repo.recordAnswer(word.id, PracticeMode.EN_ZH, correct = false)
        val bookId = word.bookId

        val now = System.currentTimeMillis()
        // 「今天到期」和柱状图第 0 格必须是同一个判断，否则会出现「0 个单词」配「今日 8 个」
        assertEquals(0, repo.dueReviewCount(now = now, bookId = bookId))
        val forecast = repo.reviewForecast(7, now = now, bookId = bookId)
        assertEquals(0, forecast[0])
        assertEquals(1, forecast[1])

        // 过了到期时间，两边同时变成「今天 1 个」
        val later = now + ReviewScheduler.DAY_MS + 60_000
        assertEquals(1, repo.dueReviewCount(now = later, bookId = bookId))
        assertEquals(1, repo.reviewForecast(7, now = later, bookId = bookId)[0])
    }

    @Test
    fun forecastHasSevenBuckets() {
        assertEquals(7, repo.reviewForecast(7).size)
    }
}
