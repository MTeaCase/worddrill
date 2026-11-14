package com.worddrill.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.worddrill.app.parser.VocabEntry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 备份导出 / 导入合并的行为。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = WordRepository(context)

    private fun sampleBook(): Long {
        val entries = listOf(
            VocabEntry("zzapple", "n.", "测试苹果"),
            VocabEntry("zzbanana", "n.", "测试香蕉"),
        )
        val bookId = repo.importEntries(null, "我的备份词表", entries).bookId
        val words = repo.words(bookId)
        repo.setStar(words.first().id, true)
        repo.recordAnswer(words.last().id, PracticeMode.EN_ZH, correct = false)
        return bookId
    }

    @Test
    fun exportContainsAllSections() {
        sampleBook()
        val json = repo.exportBackupJson()
        assertTrue(json.contains("worddrill-backup"))
        assertTrue(json.contains("我的备份词表"))
        assertTrue(json.contains("\"mistakes\""))
        assertTrue(json.contains("\"starred\""))
        assertTrue(json.contains("\"daily\""))
        assertTrue(json.contains("\"review\""))
        assertTrue(json.contains("\"practice\""))
    }

    @Test
    fun importRestoresPracticedCount() {
        val bookId = sampleBook()
        val practicedBefore = repo.practicedCount(bookId)
        assertTrue("样例数据里应该已经有练习记录", practicedBefore > 0)
        val backup = repo.exportBackupJson()

        // 模拟换机 / 清除数据后的状态
        repo.clearPracticeLog()
        assertEquals(0, repo.practicedCount(bookId))

        val summary = repo.importBackupJson(backup)
        assertTrue("摘要里应包含练习记录", summary.contains("练习记录"))
        assertEquals("导入备份后已练词数应恢复", practicedBefore, repo.practicedCount(bookId))
    }

    @Test
    fun importMergesWithoutDuplicating() {
        sampleBook()
        val backup = repo.exportBackupJson()
        val before = repo.books().size
        val wordsBefore = repo.books().first { it.name == "我的备份词表" }.wordCount
        val mistakesBefore = repo.mistakes(null).size

        val summary = repo.importBackupJson(backup)
        assertTrue(summary.contains("导入完成"))

        assertEquals(before, repo.books().size)
        assertEquals(wordsBefore, repo.books().first { it.name == "我的备份词表" }.wordCount)
        assertEquals(mistakesBefore, repo.mistakes(null).size)
    }

    @Test
    fun importRejectsForeignJson() {
        val error = runCatching { repo.importBackupJson("{\"format\":\"other\"}") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    /**
     * 修词库后 `v]` → `sort`、`ad.` → `either`，老备份里还是旧词头。
     * 不套改名表的话，这些进度会「恢复成功但一条都没落上」。
     */
    @Test
    fun importMapsRenamedHeadwordsFromOldBackup() {
        val entries = listOf(
            VocabEntry("sort", "n.", "种类"),
            VocabEntry("either", "adj.&conj.&adv.", "两者之一"),
            VocabEntry("best-seller", "n.", "畅销书"),
            VocabEntry("after", "adv.&prep.&conj.", "在…之后"),
        )
        val bookId = repo.importEntries(null, "改名兼容词表", entries).bookId
        val json = """
            {
              "format": "worddrill-backup",
              "version": 2,
              "starred": ["v]", "ad."],
              "mistakes": [{"w": "best--seller", "wrong": 3, "mode": "EN_ZH", "lastAt": 1}],
              "review": [{"w": "afte", "level": 4, "dueAt": 0, "lastAt": 1, "lapses": 0}],
              "practice": [{"w": "v]", "times": 2, "lastAt": 1}, {"w": "afte", "times": 1, "lastAt": 1}]
            }
        """.trimIndent()
        repo.importBackupJson(json)

        val words = repo.words(bookId).associateBy { it.text }
        assertTrue("v] 的星标应落到 sort", words.getValue("sort").starred)
        assertTrue("ad. 的星标应落到 either", words.getValue("either").starred)
        assertTrue("错题应落到 best-seller", repo.mistakes(bookId).any { it.word.text == "best-seller" })
        assertEquals(
            "旧词头 afte 的复习等级应落到 after",
            4,
            repo.progressFor(longArrayOf(words.getValue("after").id)).values.first().level,
        )
        assertEquals(
            "旧词头 v] 的练习次数应落到 sort",
            2,
            repo.progressFor(longArrayOf(words.getValue("sort").id)).values.first().times,
        )
    }

    /** 屏蔽状态也要能随备份恢复：内置词库不导 words，全靠 blocked 数组。 */
    @Test
    fun exportAndImportKeepsBlockedWords() {
        val entries = listOf(
            VocabEntry("zzkeep", "n.", "保留"),
            VocabEntry("zzhide", "n.", "屏蔽"),
        )
        val bookId = repo.importEntries(null, "屏蔽备份词表", entries).bookId
        val hide = repo.words(bookId).first { it.text == "zzhide" }
        repo.setBlocked(hide.id, true)

        val backup = repo.exportBackupJson()
        assertTrue("备份要带 blocked 数组", backup.contains("\"blocked\""))

        repo.unblockAll(bookId)
        assertTrue("先确认恢复前是可见的", repo.words(bookId).any { it.text == "zzhide" })

        repo.importBackupJson(backup)
        assertEquals(listOf("zzhide"), repo.words(bookId, WordFilter.BLOCKED).map { it.text })
    }
}


