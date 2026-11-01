package com.worddrill.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * SQLite 存储。首次创建时把 assets/words3500.json 导入内置词库。
 * 所有调用都应发生在后台线程（仓库层已保证）。
 */
class WordDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    private val appContext = context.applicationContext

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE books(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                builtin INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE words(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id INTEGER NOT NULL REFERENCES books(_id) ON DELETE CASCADE,
                ord INTEGER NOT NULL,
                word TEXT NOT NULL,
                pos TEXT NOT NULL DEFAULT '',
                meaning TEXT NOT NULL,
                starred INTEGER NOT NULL DEFAULT 0,
                blocked INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_words_book ON words(book_id, ord)")
        db.execSQL("CREATE INDEX idx_words_star ON words(starred)")
        db.execSQL(
            """
            CREATE TABLE mistakes(
                word_id INTEGER PRIMARY KEY REFERENCES words(_id) ON DELETE CASCADE,
                mode TEXT NOT NULL,
                wrong INTEGER NOT NULL DEFAULT 1,
                last_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE practice_log(
                word_id INTEGER PRIMARY KEY REFERENCES words(_id) ON DELETE CASCADE,
                times INTEGER NOT NULL DEFAULT 1,
                last_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        seedBuiltin(db)
    }

    /** 只做增量迁移，避免升级安装时把用户导入的自组词库清掉。 */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE words ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun seedBuiltin(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        val bookId = db.insertOrThrow("books", null, android.content.ContentValues().apply {
            put("name", BUILTIN_NAME)
            put("builtin", 1)
            put("created_at", now)
        })
        val json = appContext.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(json)
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT INTO words(book_id, ord, word, pos, meaning, starred, created_at) VALUES(?,?,?,?,?,0,?)",
            )
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                stmt.clearBindings()
                stmt.bindLong(1, bookId)
                stmt.bindLong(2, (i + 1).toLong())
                stmt.bindString(3, o.getString("w"))
                stmt.bindString(4, o.optString("pos", ""))
                stmt.bindString(5, o.getString("m"))
                stmt.bindLong(6, now)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val NAME = "worddrill.db"
        const val VERSION = 2
        const val BUILTIN_NAME = "高中 3500 词"
        private const val ASSET_NAME = "words3500.json"
    }
}
