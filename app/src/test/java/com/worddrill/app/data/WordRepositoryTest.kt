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
        assertEquals(2, books.size)
        val builtin = books.first()
        assertTrue(builtin.builtin)
        assertEquals("高中 3500 词", builtin.name)
        assertTrue("词数=${builtin.wordCount}", builtin.wordCount > 3500)
        assertEquals(1..builtin.wordCount, repo.ordRange(builtin.id))

        // v7 新增的内置四级词库，默认仍排在高中的后面（不影响默认选中）
        val cet4 = books.first { it.name == "大学英语四级" }
        assertTrue(cet4.builtin)
        assertTrue("词数=${cet4.wordCount}", cet4.wordCount > 4000)
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

        // 第二天到期的两个词落在 forecast 的前两格里（整除可能有 1 毫秒误差）
        val forecast = repo.reviewForecast(7)
        assertEquals(7, forecast.size)
        assertEquals(2, forecast[0] + forecast[1])
    }

    // ---------- 每日学习包 ----------

    @Test
    fun dailyPackPutsDueWordsFirstThenMistakesThenFresh() {
        val bookId = repo.importEntries(
            null,
            "学习包",
            listOf(
                VocabEntry("alpha", "n.", "甲"),
                VocabEntry("beta", "n.", "乙"),
                VocabEntry("gamma", "n.", "丙"),
                VocabEntry("delta", "n.", "丁"),
            ),
        ).bookId
        val words = repo.words(bookId)
        // alpha 答对（明天到期），beta 答错（进错题本）
        repo.recordAnswer(words[0].id, PracticeMode.EN_ZH, correct = true)
        repo.recordAnswer(words[1].id, PracticeMode.EN_ZH, correct = false)

        val tomorrow = System.currentTimeMillis() + ReviewScheduler.DAY_MS + 1_000
        val pack = repo.dailyPack(bookId, newLimit = 1, reviewLimit = 5, mistakeLimit = 1, now = tomorrow)

        // 两个词明天都到期：先练最早到期的 alpha，beta 已经在 due 里就不再进错题
        assertEquals(words[0].id, pack.due.first())
        assertTrue("beta 已到期，不应重复出现在错题里", pack.mistakes.isEmpty())
        assertEquals(1, pack.fresh.size)
        // 三类之间不重复
        assertEquals(pack.wordIds.size, pack.wordIds.toSet().size)
    }

    // ---------- 音标 / 例句 / 易混词 ----------

    @Test
    fun builtinWordsCarryPhoneticsExamplesAndPhrases() {
        val book = repo.books().first()
        val abandon = repo.words(book.id, query = "abandon").first { it.text == "abandon" }
        assertTrue("美音=${abandon.phoneticUs}", abandon.phoneticUs.isNotEmpty())
        assertTrue("英音=${abandon.phoneticUk}", abandon.phoneticUk.isNotEmpty())
        assertTrue("例句=${abandon.example}", abandon.hasExample)
        // 口音切换取不同的列，缺一列时回退到另一列
        assertEquals(abandon.phoneticUs, abandon.phonetic(Accent.US))
        assertEquals(abandon.phoneticUk, abandon.phonetic(Accent.UK))
        val bare = abandon.copy(phoneticUk = "")
        assertEquals(bare.phoneticUs, bare.phonetic(Accent.UK))
    }

    @Test
    fun importedWordBorrowsPhoneticAndExampleFromBuiltin() {
        val bookId = repo.importEntries(null, "借音标", listOf(VocabEntry("abandon", "v.", "放弃"))).bookId
        val word = repo.words(bookId).single()
        assertTrue(word.phoneticUs.isNotEmpty())
        assertTrue(word.hasExample)
    }

    @Test
    fun confusableWordsFindsNearMisspellingsInTheSameBook() {
        val bookId = repo.importEntries(
            null,
            "易混词",
            listOf(
                VocabEntry("adapt", "v.", "适应"),
                VocabEntry("adopt", "v.", "采用"),
                VocabEntry("adept", "a.", "熟练的"),
                VocabEntry("zebra", "n.", "斑马"),
            ),
        ).bookId
        val adapt = repo.words(bookId).first { it.text == "adapt" }
        val near = repo.confusableWords(adapt.id, limit = 3)
        assertEquals(2, near.size)
        assertEquals(setOf("adopt", "adept"), near.map { it.text }.toSet())
    }

    @Test
    fun dailyPackHonoursZeroQuotasAndDropsDuplicates() {
        val bookId = repo.importEntries(
            null,
            "学习包限额",
            listOf(VocabEntry("one", "n.", "一"), VocabEntry("two", "n.", "二")),
        ).bookId
        val words = repo.words(bookId)
        repo.recordAnswer(words[0].id, PracticeMode.EN_ZH, correct = false)

        val now = System.currentTimeMillis()
        val empty = repo.dailyPack(bookId, newLimit = 0, reviewLimit = 0, mistakeLimit = 0, now = now)
        assertTrue(empty.isEmpty)

        // 今天不到期：错题仍然出现，但不会在 due 里重复
        val pack = repo.dailyPack(bookId, newLimit = 5, reviewLimit = 5, mistakeLimit = 5, now = now)
        assertEquals(0, pack.due.size)
        assertEquals(listOf(words[0].id), pack.mistakes)
        assertTrue(pack.fresh.isNotEmpty())
        assertTrue(pack.fresh.none { it == words[0].id })
    }

    @Test
    fun selfRatingOverwritesTheAnswerBasedSchedule() {
        val bookId = repo.importEntries(null, "自评测试", listOf(VocabEntry("rate", "n.", "评分"))).bookId
        val word = repo.words(bookId).single()
        repo.recordAnswer(word.id, PracticeMode.EN_ZH, correct = true)
        assertEquals(1, repo.progressFor(longArrayOf(word.id)).getValue(word.id).level)

        repo.rateWord(word.id, ReviewRating.KNOW)
        assertEquals(2, repo.progressFor(longArrayOf(word.id)).getValue(word.id).level)

        repo.rateWord(word.id, ReviewRating.FORGOT)
        val progress = repo.progressFor(longArrayOf(word.id)).getValue(word.id)
        assertEquals(0, progress.level)
        assertEquals(1, progress.lapses)
        assertEquals(1, progress.wrong)
        assertEquals("SELF_RATING", progress.lastMode)
    }

    // ---------- 题型统计 / 成就 / 每日一词 ----------

    @Test
    fun answerEventsFeedModeStatsAndAverageTime() {
        val bookId = repo.importEntries(null, "统计测试", listOf(VocabEntry("stat", "n.", "统计"))).bookId
        val word = repo.words(bookId).single()
        repo.recordAnswer(word.id, PracticeMode.ZH_EN, correct = true, elapsedMs = 1_200)
        repo.recordAnswer(word.id, PracticeMode.ZH_EN, correct = false, elapsedMs = 4_000)

        val stat = repo.modeStats(30).first { it.mode == PracticeMode.ZH_EN.name }
        assertEquals(2, stat.total)
        assertEquals(1, stat.correct)
        assertEquals(50, stat.accuracy)
        assertEquals(2_600, repo.averageElapsedMs(30))
    }

    @Test
    fun achievementsUnlockOnlyOnce() {
        val bookId = repo.importEntries(null, "成就测试", listOf(VocabEntry("badge", "n.", "徽章"))).bookId
        val word = repo.words(bookId).single()
        repo.recordAnswer(word.id, PracticeMode.EN_ZH, correct = true, elapsedMs = 900)

        val first = repo.checkAchievements()
        assertTrue("应该拿到第一步，实际=${first.map { it.key }}", first.any { it.key == "first_word" })
        assertTrue(repo.checkAchievements().none { it.key == "first_word" })
        assertTrue(repo.achievementState().any { it.first.key == "first_word" && it.second > 0L })
    }

    @Test
    fun pickWordsFiltersBeforeSampling() {
        // 20 个名词 + 5 个动词：要求只出动词时必须凑满 5 题，
        // 而不是「先抽 20 个再筛」那种只剩零星的写法
        val entries = (1..20).map { VocabEntry("noun$it", "n.", "名词$it") } +
            (1..5).map { VocabEntry("verb$it", "v.", "动词$it") }
        val bookId = repo.importEntries(null, "筛后抽题", entries).bookId

        val verbs = repo.pickWords(bookId, 1, 25, 10, allowRepeat = true) { it.pos.startsWith("v") }
        assertEquals(5, verbs.size)
        assertTrue(verbs.all { it.pos.startsWith("v") })
    }

    @Test
    fun dailyWordIsStableWithinADayAndChangesNextDay() {
        val book = repo.books().first()
        val today = repo.wordForDate(book.id, 1_000)
        val again = repo.wordForDate(book.id, 1_000)
        val tomorrow = repo.wordForDate(book.id, 1_001)
        assertNotNull(today)
        assertEquals(today?.id, again?.id)
        assertTrue("跨天应该换一个词", today?.id != tomorrow?.id)
    }

    @Test
    fun forgotRatingStillZeroesTheLevelAndCountsTheLapse() {
        val bookId = repo.importEntries(null, "Ease 测试", listOf(VocabEntry("ease", "n.", "难度"))).bookId
        val word = repo.words(bookId).single()
        repo.recordAnswer(word.id, PracticeMode.EN_ZH, correct = true)
        repo.rateWord(word.id, ReviewRating.KNOW)
        assertEquals(2, repo.progressFor(longArrayOf(word.id)).getValue(word.id).level)

        repo.rateWord(word.id, ReviewRating.FORGOT)
        val progress = repo.progressFor(longArrayOf(word.id)).getValue(word.id)
        assertEquals(0, progress.level)
        assertEquals(1, progress.lapses)
    }
}
