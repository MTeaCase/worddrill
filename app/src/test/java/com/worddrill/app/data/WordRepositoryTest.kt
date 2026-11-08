package com.worddrill.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.worddrill.app.parser.VocabEntry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = WordRepository(context)

    @Test
    fun builtinBookIsSeededWith3500Words() {
        val books = repo.books()
        assertEquals(1, books.size)
        val builtin = books.first()
        assertTrue(builtin.builtin)
        assertTrue("词数=${builtin.wordCount}", builtin.wordCount > 3500)
        assertEquals(1..builtin.wordCount, repo.ordRange(builtin.id))
    }

    @Test
    fun wordsAreOrderedByIndexAndSearchable() {
        val book = repo.books().first()
        val first = repo.words(book.id, limit = 5)
        assertEquals(listOf(1, 2, 3, 4, 5), first.map { it.ord })
        assertEquals("a", first.first().text)

        val found = repo.words(book.id, query = "abandon")
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it.text.contains("abandon", ignoreCase = true) || it.meaning.contains("abandon") })
    }

    @Test
    fun pickWordsRespectsRangeAndCount() {
        val book = repo.books().first()
        val picked = repo.pickWords(book.id, startOrd = 10, endOrd = 20, count = 5, allowRepeat = true)
        assertEquals(5, picked.size)
        assertTrue(picked.all { it.ord in 10..20 })
        assertEquals(5, picked.map { it.id }.toSet().size)
    }

    @Test
    fun practicedWordsAreExcludedUntilLogCleared() {
        val book = repo.books().first()
        val first = repo.pickWords(book.id, 1, 10, 3, allowRepeat = false)
        assertEquals(3, first.size)
        first.forEach { repo.recordAnswer(it.id, PracticeMode.EN_ZH, correct = true) }

        val remaining = repo.pickWords(book.id, 1, 10, 10, allowRepeat = false)
        assertEquals(7, remaining.size)
        assertTrue(remaining.none { w -> first.any { it.id == w.id } })

        repo.clearPracticeLog()
        assertEquals(10, repo.pickWords(book.id, 1, 10, 10, allowRepeat = false).size)
    }

    @Test
    fun wrongAnswerGoesToMistakesAndNeedsTwoCorrectAnswersToLeave() {
        val book = repo.books().first()
        val word = repo.words(book.id, limit = 1).first()

        repo.recordAnswer(word.id, PracticeMode.EN_ZH, correct = false)
        var mistakes = repo.mistakes(book.id)
        assertEquals(1, mistakes.size)
        assertEquals(word.id, mistakes.first().word.id)
        assertEquals(1, mistakes.first().wrongCount)

        repo.recordAnswer(word.id, PracticeMode.EN_ZH, correct = false)
        mistakes = repo.mistakes(book.id)
        assertEquals(2, mistakes.first().wrongCount)

        // 蒙对一次还不够，要连续答对两次才移出
        repo.recordAnswer(word.id, PracticeMode.EN_ZH, correct = true)
        assertEquals(1, repo.mistakes(book.id).size)

        repo.recordAnswer(word.id, PracticeMode.EN_ZH, correct = true)
        assertTrue(repo.mistakes(book.id).isEmpty())
    }

    @Test
    fun starFlagPersistsAndFilters() {
        val book = repo.books().first()
        val word = repo.words(book.id, limit = 1).first()
        repo.setStar(word.id, true)

        val starred = repo.words(book.id, WordFilter.STARRED)
        assertEquals(1, starred.size)
        assertTrue(starred.first().starred)

        repo.setStar(word.id, false)
        assertTrue(repo.words(book.id, WordFilter.STARRED).isEmpty())
    }

    @Test
    fun importCreatesCustomBookInMarkdownOrderAndAppendsWithIncreasingIndex() {
        val entries = listOf(
            VocabEntry("apple", "n.", "苹果"),
            VocabEntry("pick out", "动词短语", "挑选"),
            VocabEntry("run", "v.", "跑"),
        )
        val result = repo.importEntries(null, "我的词表", entries)
        assertEquals(3, result.added)
        assertEquals(0, result.skipped)

        val book = repo.book(result.bookId)
        assertNotNull(book)
        assertEquals("我的词表", book!!.name)
        assertEquals(3, book.wordCount)

        val words = repo.words(book.id)
        assertEquals(listOf("apple", "pick out", "run"), words.map { it.text })
        assertEquals(listOf(1, 2, 3), words.map { it.ord })

        // 中途追加：跳过重复，索引继续递增（无视 markdown 里的序号）
        val appended = repo.importEntries(
            book.id,
            null,
            listOf(VocabEntry("apple", "n.", "苹果"), VocabEntry("jump", "v.", "跳")),
        )
        assertEquals(1, appended.added)
        assertEquals(1, appended.skipped)

        val after = repo.words(book.id)
        assertEquals(listOf("apple", "pick out", "run", "jump"), after.map { it.text })
        assertEquals(listOf(1, 2, 3, 4), after.map { it.ord })
    }

    @Test
    fun blockingHidesWordAndRenumbersIndexes() {
        val entries = listOf(
            VocabEntry("alpha", "n.", "甲"),
            VocabEntry("beta", "n.", "乙"),
            VocabEntry("gamma", "n.", "丙"),
            VocabEntry("delta", "n.", "丁"),
        )
        val bookId = repo.importEntries(null, "屏蔽测试", entries).bookId
        assertEquals(listOf(1, 2, 3, 4), repo.words(bookId).map { it.ord })

        repo.setBlocked(repo.words(bookId)[1].id, true)

        val visible = repo.words(bookId)
        assertEquals(listOf("alpha", "gamma", "delta"), visible.map { it.text })
        assertEquals(listOf(1, 2, 3), visible.map { it.ord })
        assertEquals(3, repo.wordCount(bookId))
        assertEquals(1..3, repo.ordRange(bookId))
        assertEquals(1, repo.blockedCount(bookId))
        assertEquals(listOf("beta"), repo.words(bookId, WordFilter.BLOCKED).map { it.text })

        // 索引 2 现在是 gamma，屏蔽词抽不到、也不会当干扰项
        assertEquals(listOf("gamma"), repo.pickWords(bookId, 2, 2, 5, allowRepeat = true).map { it.text })
        repeat(20) {
            assertTrue(repo.pickWords(bookId, 1, 3, 10, allowRepeat = true).none { it.text == "beta" })
        }
        assertTrue(repo.allWords().none { it.text == "beta" })

        // 恢复后回到原来的位置
        repo.setBlocked(repo.words(bookId, WordFilter.BLOCKED).single().id, false)
        assertEquals(listOf("alpha", "beta", "gamma", "delta"), repo.words(bookId).map { it.text })
        assertEquals(listOf(1, 2, 3, 4), repo.words(bookId).map { it.ord })
    }

    @Test
    fun unblockAllRestoresEveryBlockedWord() {
        val bookId = repo.importEntries(
            null,
            "批量恢复",
            listOf(VocabEntry("a1", "n.", "一"), VocabEntry("b1", "n.", "二"), VocabEntry("c1", "n.", "三")),
        ).bookId
        repo.words(bookId).forEach { repo.setBlocked(it.id, true) }
        assertEquals(0, repo.wordCount(bookId))
        assertEquals(3, repo.blockedCount(bookId))

        repo.unblockAll(bookId)
        assertEquals(3, repo.wordCount(bookId))
        assertEquals(0, repo.blockedCount(bookId))
        assertEquals(listOf(1, 2, 3), repo.words(bookId).map { it.ord })
    }

    @Test
    fun blockedWordDisappearsFromMistakes() {
        val bookId = repo.importEntries(null, "错题屏蔽", listOf(VocabEntry("wrongly", "adv.", "错误地"))).bookId
        val word = repo.words(bookId).single()
        repo.recordAnswer(word.id, PracticeMode.EN_ZH, correct = false)
        assertEquals(1, repo.mistakes(bookId).size)

        repo.setBlocked(word.id, true)
        assertTrue(repo.mistakes(bookId).isEmpty())
    }

    @Test
    fun deletingCustomBookRemovesItsWords() {
        val result = repo.importEntries(null, "临时", listOf(VocabEntry("temp", "n.", "临时")))
        repo.deleteBook(result.bookId)
        assertTrue(repo.books().none { it.id == result.bookId })
        assertEquals(0, repo.wordCount(result.bookId))
    }

    @Test
    fun builtinBookCannotBeDeleted() {
        val builtin = repo.books().first { it.builtin }
        repo.deleteBook(builtin.id)
        assertTrue(repo.books().any { it.id == builtin.id })
    }

    @Test
    fun learnedFilterOnlyReturnsPracticedWordsOfThatBook() {
        val bookId = repo.importEntries(
            null,
            "已学过测试",
            listOf(VocabEntry("alpha", "n.", "甲"), VocabEntry("beta", "n.", "乙")),
        ).bookId
        val words = repo.words(bookId)
        repo.recordAnswer(words.first().id, PracticeMode.EN_ZH, correct = true)

        val learned = repo.words(bookId, WordFilter.LEARNED)
        assertEquals(1, learned.size)
        assertEquals(words.first().id, learned.first().id)

        val stats = repo.learnedStats(bookId)
        assertEquals(1, stats.learned)
        assertEquals(2, stats.total)
        assertEquals(0, stats.mastered)
    }

    @Test
    fun progressForAggregatesTimesWrongCountAndMode() {
        val bookId = repo.importEntries(null, "进度测试", listOf(VocabEntry("gamma", "n.", "丙"))).bookId
        val word = repo.words(bookId).single()
        repo.recordAnswer(word.id, PracticeMode.ZH_EN_SPELL, correct = false)
        repo.recordAnswer(word.id, PracticeMode.ZH_EN_SPELL, correct = true)

        val progress = repo.progressFor(longArrayOf(word.id)).getValue(word.id)
        assertEquals(2, progress.times)
        assertEquals(1, progress.wrong)
        assertEquals(PracticeMode.ZH_EN_SPELL.name, progress.lastMode)
        assertEquals(1, progress.level)
    }

    @Test
    fun dueReviewCanBeScopedToOneBook() {
        val bookA = repo.importEntries(null, "复习A", listOf(VocabEntry("aa", "n.", "甲"))).bookId
        val bookB = repo.importEntries(null, "复习B", listOf(VocabEntry("bb", "n.", "乙"))).bookId
        repo.words(bookA).forEach { repo.recordAnswer(it.id, PracticeMode.EN_ZH, correct = false) }
        repo.words(bookB).forEach { repo.recordAnswer(it.id, PracticeMode.EN_ZH, correct = false) }

        val later = System.currentTimeMillis() + 2 * ReviewScheduler.DAY_MS
        assertEquals(1, repo.dueReviewCount(now = later, bookId = bookA))
        assertEquals(2, repo.dueReviewCount(now = later))

        val dueA = repo.dueReviewWords(limit = 100, now = later, bookId = bookA)
        assertEquals(listOf(bookA), dueA.map { it.bookId }.distinct())

        // 等一天后到期的词应该落在 forecast 的第 1 天（下标 1）
        val forecast = repo.reviewForecast(7)
        assertEquals(7, forecast.size)
        assertEquals(2, forecast[1])
    }
}
