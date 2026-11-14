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
 * 内置词库迁移：老库里的坏词头要在升级时换成新词头，而且进度不能丢。
 *
 * 测试直接改库里词头的文本，模拟 1.6.0 那份 3,793 条的老数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordlistMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = WordRepository(context)

    private fun highSchool(): Book = repo.books().first { it.name == WordDb.BUILTIN_NAME }

    /** 把版本号抹掉，让下一次打开数据库时重新跑迁移。 */
    private fun forgetVersion() {
        context.getSharedPreferences("wordlist", Context.MODE_PRIVATE)
            .edit().putInt("version", 0).apply()
    }

    private fun count(sql: String, vararg args: Any): Int =
        WordDb(context).readableDatabase.rawQuery(sql, args.map { it.toString() }.toTypedArray())
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Test
    fun freshInstallIsMarkedAsCurrent() {
        highSchool()
        assertEquals(WordlistMigration.VERSION, WordlistMigration.storedVersion(context))
    }

    @Test
    fun redeployRenamesBrokenHeadwordsAndKeepsProgress() {
        val bookId = highSchool().id
        val sort = repo.words(bookId).first { it.text == "sort" }
        repo.setStar(sort.id, true)
        repo.recordAnswer(sort.id, PracticeMode.EN_ZH, correct = false)

        // 把词头改回 1.6.0 的坏写法，进度还挂在同一行上
        val db = WordDb(context).writableDatabase
        db.execSQL("UPDATE words SET word = 'v]' WHERE book_id = ? AND word = 'sort'", arrayOf<Any>(bookId))
        db.execSQL("UPDATE words SET word = 'best--seller' WHERE book_id = ? AND word = 'best-seller'", arrayOf<Any>(bookId))
        forgetVersion()

        val summary = repo.redeployBuiltinBooks()
        assertTrue("摘要应说明重建了什么：$summary", summary.contains("重建"))

        val words = repo.words(bookId)
        assertEquals("修完之后高中 3500 应是 3846 条", 3846, words.size)
        assertTrue("改名后应该能搜到 sort", words.any { it.text == "sort" })
        assertFalse("坏词头 v] 应该消失", words.any { it.text == "v]" })
        assertTrue("v] 上的星标应该跟到 sort", words.first { it.text == "sort" }.starred)
        assertTrue("v] 上的错题应该跟到 sort", repo.mistakes(bookId).any { it.word.text == "sort" })
        assertEquals(WordlistMigration.VERSION, WordlistMigration.storedVersion(context))
    }

    @Test
    fun openingTheDatabaseRunsMigrationAutomatically() {
        val bookId = highSchool().id
        WordDb(context).writableDatabase
            .execSQL("UPDATE words SET word = 'v]' WHERE book_id = ? AND word = 'sort'", arrayOf<Any>(bookId))
        forgetVersion()

        // 新开一个连接：onOpen 看到版本落后就该自己修
        val reopened = WordRepository(context)
        assertTrue(reopened.words(bookId).any { it.text == "sort" })
        assertEquals(WordlistMigration.VERSION, WordlistMigration.storedVersion(context))
    }

    @Test
    fun staleWordsAndTheirEventsAreRemoved() {
        val bookId = highSchool().id
        val db = WordDb(context).writableDatabase
        db.execSQL(
            """
            INSERT INTO words(book_id, ord, word, pos, meaning, starred, blocked, known, example,
                              phonetic_us, phonetic_uk, phrases, created_at)
            VALUES(?, 9999, 'zzghost', '', '孤儿词', 0, 0, 0, '', '', '', '', 0)
            """.trimIndent(),
            arrayOf<Any>(bookId),
        )
        val ghostId = db.rawQuery("SELECT _id FROM words WHERE word = 'zzghost'", null)
            .use { c -> c.moveToFirst(); c.getLong(0) }
        db.execSQL(
            "INSERT INTO practice_event(word_id, mode, correct, elapsed_ms, at) VALUES(?, 'EN_ZH', 1, 0, ?)",
            arrayOf<Any>(ghostId, System.currentTimeMillis()),
        )

        repo.redeployBuiltinBooks()

        assertEquals(0, count("SELECT COUNT(*) FROM words WHERE word = 'zzghost'"))
        assertEquals(
            "practice_event 没有外键，删词时必须连它一起清",
            0,
            count("SELECT COUNT(*) FROM practice_event WHERE word_id = ?", ghostId),
        )
    }

    @Test
    fun redeployLeavesCustomBooksAlone() {
        val customId = repo.importEntries(
            null,
            "自建词表",
            listOf(VocabEntry("v]", "n.", "自定义的词")),
        ).bookId

        repo.redeployBuiltinBooks()

        assertEquals(listOf("v]"), repo.words(customId).map { it.text })
    }
}