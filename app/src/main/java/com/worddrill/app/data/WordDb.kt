package com.worddrill.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * SQLite 存储。首次创建时把 assets/words3500.json 导入内置词库。
 * 所有调用都应发生在后台线程（仓库层已保证）。
 */
class WordDb(context: Context) : SQLiteOpenHelper(
    context,
    NAME,
    null,
    VERSION,
    // 数据库被判定损坏时，默认处理是直接删库重建，所有进度就没了。
    // 这里先把原文件复制一份留证，再交给默认逻辑重建，至少还能人工抢救。
    android.database.DatabaseErrorHandler { db ->
        runCatching {
            val path = db.path ?: return@runCatching
            val file = java.io.File(path)
            if (file.exists()) {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                file.copyTo(java.io.File(file.parentFile, "${file.name}.corrupt-$stamp"), overwrite = true)
            }
        }
        android.database.DefaultDatabaseErrorHandler().onCorruption(db)
    },
) {

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
        // example 列当前不再使用（例句功能暂时下线）。保留这一列是为了让新旧安装的
        // 表结构一致——删列需要重建表，而重建会触发子表的 ON DELETE CASCADE，
        // 有丢数据的风险。以后恢复例句功能可以直接复用。
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
        db.execSQL(
            """
            CREATE TABLE review(
                word_id INTEGER PRIMARY KEY REFERENCES words(_id) ON DELETE CASCADE,
                level INTEGER NOT NULL DEFAULT 0,
                due_at INTEGER NOT NULL,
                last_at INTEGER NOT NULL,
                lapses INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_review_due ON review(due_at)")
        seedBuiltin(db)
    }

    /** 只做增量迁移，避免升级安装时把用户导入的自组词库清掉。 */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE words ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // v3 加的例句列。3 之后的版本里例句用过、下线过、又加回来了，
            // 所以这里用 runCatching 兜住「列已存在」的情况。
            runCatching {
                db.execSQL("ALTER TABLE words ADD COLUMN example TEXT NOT NULL DEFAULT ''")
            }
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS daily(
                    date TEXT PRIMARY KEY,
                    answered INTEGER NOT NULL DEFAULT 0,
                    correct INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }
        if (oldVersion < 4) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS review(
                    word_id INTEGER PRIMARY KEY REFERENCES words(_id) ON DELETE CASCADE,
                    level INTEGER NOT NULL DEFAULT 0,
                    due_at INTEGER NOT NULL,
                    last_at INTEGER NOT NULL,
                    lapses INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_review_due ON review(due_at)")
            // 老用户练过的词全部进入复习队列（立即到期），保证升级后就有内容可复习
            db.execSQL(
                """
                INSERT OR IGNORE INTO review(word_id, level, due_at, last_at, lapses)
                SELECT p.word_id, 0, p.last_at, p.last_at, 0 FROM practice_log p
                """.trimIndent(),
            )
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
        const val VERSION = 5
        const val BUILTIN_NAME = "高中 3500 词"
        private const val ASSET_NAME = "words3500.json"
    }
}
