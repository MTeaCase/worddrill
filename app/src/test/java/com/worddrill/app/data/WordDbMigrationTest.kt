package com.worddrill.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 升级安装（v1 数据库 → v2）不能把用户已经导入的自组词库清掉。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordDbMigrationTest {

    @Test
    fun upgradeFromV1KeepsImportedWordsAndAddsBlockedColumn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath(WordDb.NAME)
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()

        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                """
                CREATE TABLE books(
                    _id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    builtin INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            old.execSQL(
                """
                CREATE TABLE words(
                    _id INTEGER PRIMARY KEY AUTOINCREMENT,
                    book_id INTEGER NOT NULL REFERENCES books(_id) ON DELETE CASCADE,
                    ord INTEGER NOT NULL,
                    word TEXT NOT NULL,
                    pos TEXT NOT NULL DEFAULT '',
                    meaning TEXT NOT NULL,
                    starred INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            old.execSQL(
                """
                CREATE TABLE mistakes(
                    word_id INTEGER PRIMARY KEY REFERENCES words(_id) ON DELETE CASCADE,
                    mode TEXT NOT NULL,
                    wrong INTEGER NOT NULL DEFAULT 1,
                    last_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            old.execSQL(
                """
                CREATE TABLE practice_log(
                    word_id INTEGER PRIMARY KEY REFERENCES words(_id) ON DELETE CASCADE,
                    times INTEGER NOT NULL DEFAULT 1,
                    last_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            old.execSQL("INSERT INTO books(name, builtin, created_at) VALUES('旧词库', 0, 1)")
            old.execSQL(
                "INSERT INTO words(book_id, ord, word, pos, meaning, starred, created_at) " +
                    "VALUES(1, 1, 'legacy', 'n.', '旧词', 1, 1)",
            )
            old.version = 1
        }

        val repo = WordRepository(context)
        val book = repo.books().single()
        assertEquals("旧词库", book.name)
        assertFalse(book.builtin)
        assertEquals(1, book.wordCount)

        val word = repo.words(book.id).single()
        assertEquals("legacy", word.text)
        assertEquals(1, word.ord)
        assertTrue(word.starred)
        assertFalse(word.blocked)

        repo.setBlocked(word.id, true)
        assertEquals(1, repo.blockedCount(book.id))
        assertEquals(0, repo.wordCount(book.id))
    }
}
