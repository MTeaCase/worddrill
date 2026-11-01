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
    fun forecastHasSevenBuckets() {
        assertEquals(7, repo.reviewForecast(7).size)
    }
}
