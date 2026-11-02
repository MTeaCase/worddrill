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
}


