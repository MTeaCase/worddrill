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
                example TEXT NOT NULL DEFAULT '',
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
        db.execSQL(
            """
            CREATE TABLE daily(
                date TEXT PRIMARY KEY,
                answered INTEGER NOT NULL DEFAULT 0,
                correct INTEGER NOT NULL DEFAULT 0
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
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE words ADD COLUMN example TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS daily(
                    date TEXT PRIMARY KEY,
                    answered INTEGER NOT NULL DEFAULT 0,
                    correct INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            backfillExamples(db)
        }
    }

    /** 老版本升级上来时，给内置词库补上例句（自组词库不动）。 */
    private fun backfillExamples(db: SQLiteDatabase) {
        val examples = loadExamples() ?: return
        val bookId = db.rawQuery("SELECT _id FROM books WHERE builtin = 1 LIMIT 1", null).use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        }
        if (bookId <= 0) return
        val pending = ArrayList<Pair<Long, String>>()
        db.rawQuery(
            "SELECT _id, word FROM words WHERE book_id = ? AND example = ''",
            arrayOf(bookId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val entry = examples.optJSONObject(c.getString(1).lowercase()) ?: continue
                val en = entry.optString("en").trim()
                val zh = entry.optString("zh").trim()
                if (en.isNotEmpty()) pending.add(c.getLong(0) to "$en${com.worddrill.app.data.Word.SEP}$zh")
            }
        }
        if (pending.isEmpty()) return
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("UPDATE words SET example = ? WHERE _id = ?")
            for ((id, example) in pending) {
                stmt.clearBindings()
                stmt.bindString(1, example)
                stmt.bindLong(2, id)
                stmt.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun loadExamples(): org.json.JSONObject? = runCatching {
        appContext.assets.open(EXAMPLES_ASSET).bufferedReader(Charsets.UTF_8).use {
            org.json.JSONObject(it.readText())
        }
    }.getOrNull()

    private fun seedBuiltin(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        val bookId = db.insertOrThrow("books", null, android.content.ContentValues().apply {
            put("name", BUILTIN_NAME)
            put("builtin", 1)
            put("created_at", now)
        })
        val json = appContext.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(json)
        val examples = loadExamples()
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT INTO words(book_id, ord, word, pos, meaning, example, starred, created_at) VALUES(?,?,?,?,?,?,0,?)",
            )
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val text = o.getString("w")
                val entry = examples?.optJSONObject(text.lowercase())
                val example = if (entry != null) {
                    val en = entry.optString("en").trim()
                    val zh = entry.optString("zh").trim()
                    if (en.isEmpty()) "" else "$en${Word.SEP}$zh"
                } else {
                    ""
                }
                stmt.clearBindings()
                stmt.bindLong(1, bookId)
                stmt.bindLong(2, (i + 1).toLong())
                stmt.bindString(3, text)
                stmt.bindString(4, o.optString("pos", ""))
                stmt.bindString(5, o.getString("m"))
                stmt.bindString(6, example)
                stmt.bindLong(7, now)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val NAME = "worddrill.db"
        const val VERSION = 3
        const val BUILTIN_NAME = "高中 3500 词"
        private const val ASSET_NAME = "words3500.json"
        private const val EXAMPLES_ASSET = "examples.json"
    }
}
