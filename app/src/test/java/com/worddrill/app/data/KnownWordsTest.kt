package com.worddrill.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.worddrill.app.parser.VocabEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 首练时手动标「已掌握」的词（`known`）：不再抽到、不进复习、仍算「学过」，
 * 也不该在恢复备份之后丢掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KnownWordsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = WordRepository(context)

    private fun makeBook(vararg words: String): Long {
        val entries = words.map { VocabEntry(it, "n.", "释义 $it") }
        return repo.importEntries(null, "已掌握测试", entries).bookId
    }

    private fun word(bookId: Long, text: String): Word =
        repo.words(bookId).first { it.text == text }

    /** 未来 30 天的复习排期总量：用来判断「有没有被排进复习」。 */
    private fun scheduledReviews(): Int = repo.reviewForecast(30).sum()

    @Test
    fun setKnownClearsMistakeAndReview() {
        val bookId = makeBook("alpha", "beta")
        val alpha = word(bookId, "alpha")
        repo.recordAnswer(alpha.id, PracticeMode.EN_ZH, correct = false)

        assertTrue("先确认答错会进错题本", repo.mistakes(bookId).any { it.word.text == "alpha" })
        assertTrue("先确认答错会排复习", repo.progressFor(longArrayOf(alpha.id)).isNotEmpty())

        repo.setKnown(alpha.id, known = true)

        assertTrue(repo.isKnown(alpha.id))
        assertTrue("标已掌握时应该清掉错题", repo.mistakes(bookId).isEmpty())
        assertEquals("标已掌握时应该清掉复习排期", 0, scheduledReviews())
    }

    @Test
    fun knownWordIsNeverPickedAgain() {
        val bookId = makeBook("alpha", "beta")
        val alpha = word(bookId, "alpha")
        repo.setKnown(alpha.id, known = true)

        val picked = repo.pickWords(bookId, 1, 10, count = 10, allowRepeat = true)
        assertEquals(listOf("beta"), picked.map { it.text })
    }

    @Test
    fun knownWordStaysOutOfDailyPack() {
        val bookId = makeBook("alpha", "beta")
        val alpha = word(bookId, "alpha")
        repo.setKnown(alpha.id, known = true)

        val pack = repo.dailyPack(bookId, newLimit = 10, reviewLimit = 10, mistakeLimit = 10)
        assertFalse("已掌握的词不该出现在学习包里", pack.wordIds.contains(alpha.id))
        assertEquals("剩下的新词还是会出现", listOf("beta"), repo.wordsByIds(pack.wordIds.toLongArray()).map { it.text })
    }

    @Test
    fun knownCountsAsLearnedAndMastered() {
        val bookId = makeBook("alpha", "beta")
        val alpha = word(bookId, "alpha")
        repo.setKnown(alpha.id, known = true)

        val mastery = repo.masteryStats(bookId)
        assertEquals(2, mastery.total)
        assertEquals("已掌握按 5 级计", 1, mastery.mastered)
        assertEquals("已掌握的词不该再算新词", 1, mastery.fresh)

        val learned = repo.learnedStats(bookId)
        assertEquals("已学过要包含手动标已掌握的词", 1, learned.learned)
        assertEquals(1, learned.mastered)
    }

    @Test
    fun learnedFilterShowsKnownWord() {
        val bookId = makeBook("alpha", "beta")
        val alpha = word(bookId, "alpha")
        repo.setKnown(alpha.id, known = true)

        val learned = repo.words(bookId, WordFilter.LEARNED)
        assertEquals(listOf("alpha"), learned.map { it.text })
    }

    @Test
    fun knownWordIsNotScheduledByAnswer() {
        val bookId = makeBook("alpha")
        val alpha = word(bookId, "alpha")
        repo.setKnown(alpha.id, known = true)

        repo.recordAnswer(alpha.id, PracticeMode.EN_ZH, correct = true)

        assertEquals("已掌握的词答题也不该重新排复习", 0, scheduledReviews())
    }

    @Test
    fun backupRoundTripKeepsKnown() {
        val bookId = makeBook("alpha", "beta")
        val alpha = word(bookId, "alpha")
        repo.setKnown(alpha.id, known = true)

        val backup = repo.exportBackupJson()
        assertTrue("备份要带 known 数组", backup.contains("\"known\""))

        repo.setKnown(alpha.id, known = false)
        assertFalse(repo.isKnown(alpha.id))

        repo.importBackupJson(backup)
        assertTrue("恢复备份后已掌握状态应回来", repo.isKnown(word(bookId, "alpha").id))
    }

    @Test
    fun clearPracticeLogKeepsKnown() {
        val bookId = makeBook("alpha")
        val alpha = word(bookId, "alpha")
        repo.recordAnswer(alpha.id, PracticeMode.EN_ZH, correct = true)
        repo.setKnown(alpha.id, known = true)

        repo.clearPracticeLog()
        assertEquals("清空练习记录不影响已掌握", 0, repo.practicedCount(bookId))
        assertTrue(repo.isKnown(alpha.id))
    }
}