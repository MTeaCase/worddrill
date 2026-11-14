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

/** 错题 CSV 与「练过的全部」复习范围。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CsvAndReviewScopeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = WordRepository(context)

    private fun makeBook(): Long {
        val entries = listOf(
            VocabEntry("zzcomma", "n.", "a,b"),
            VocabEntry("zzplain", "n.", "普通释义"),
        )
        return repo.importEntries(null, "导出测试", entries).bookId
    }

    @Test
    fun reviewCsvStartsWithBomAndQuotesCommas() {
        val bookId = makeBook()
        val words = repo.words(bookId)
        repo.recordAnswer(words.first().id, PracticeMode.EN_ZH, correct = false)

        val csv = repo.mistakesCsv(bookId, CsvFormat.REVIEW)
        assertTrue("Excel 需要 BOM 才不乱码", csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("英文,词性,中文释义,复习提示"))
        assertTrue("含逗号的释义要用引号包起来", csv.contains("\"a,b\""))
    }

    @Test
    fun practiceCsvKeepsOnlyOneColumn() {
        val bookId = makeBook()
        val words = repo.words(bookId)
        repo.recordAnswer(words.first().id, PracticeMode.EN_ZH, correct = false)

        val en = repo.mistakesCsv(bookId, CsvFormat.PRACTICE_EN)
        assertTrue(en.lines().first { it.isNotBlank() }.contains("英文"))
        assertTrue(en.contains("zzcomma"))

        val zh = repo.mistakesCsv(bookId, CsvFormat.PRACTICE_ZH)
        assertTrue(zh.contains("a,b"))
    }

    @Test
    fun allReviewWordsIgnoresDueDate() {
        val bookId = makeBook()
        val words = repo.words(bookId)
        repo.recordAnswer(words.first().id, PracticeMode.EN_ZH, correct = true)

        val all = repo.allReviewWords(limit = 50, bookId = bookId)
        assertEquals("练过的词要能不看看到期时间直接复习", 1, all.size)

        val due = repo.dueReviewWords(limit = 50, bookId = bookId)
        assertEquals("刚答对的词今天不到期", 0, due.size)
    }

    @Test
    fun replaceFromBackupOverwritesCurrentState() {
        val bookId = makeBook()
        val first = repo.words(bookId).first()
        repo.setStar(first.id, true)
        val backup = repo.exportBackupJson()

        repo.setStar(first.id, false)
        repo.setKnown(first.id, known = true)

        repo.replaceFromBackupJson(backup)

        val restored = repo.words(repo.books().first { it.name == "导出测试" }.id)
        assertTrue("恢复后星标要回来", restored.first { it.text == "zzcomma" }.starred)
        assertFalse("恢复后已掌握标记要清掉", repo.isKnown(restored.first { it.text == "zzcomma" }.id))
    }
}