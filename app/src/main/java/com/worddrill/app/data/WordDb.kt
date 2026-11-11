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
                phonetic_us TEXT NOT NULL DEFAULT '',
                phonetic_uk TEXT NOT NULL DEFAULT '',
                phrases TEXT NOT NULL DEFAULT '',
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
                last_at INTEGER NOT NULL,
                streak INTEGER NOT NULL DEFAULT 0
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
                lapses INTEGER NOT NULL DEFAULT 0,
                ease REAL NOT NULL DEFAULT 2.5
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_review_due ON review(due_at)")
        db.execSQL(
            """
            CREATE TABLE practice_event(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                word_id INTEGER NOT NULL,
                mode TEXT NOT NULL,
                correct INTEGER NOT NULL DEFAULT 0,
                elapsed_ms INTEGER NOT NULL DEFAULT 0,
                at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_event_at ON practice_event(at)")
        db.execSQL(
            """
            CREATE TABLE achievements(
                key TEXT PRIMARY KEY,
                unlocked_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        seedBuiltin(db)
    }

    /** 只做增量迁移，避免升级安装时把用户导入的自组词库清掉。 */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 先补列，再跑后面的迁移块：v7 的 seedBook 已经会写音标列，
        // 要是等到 v8 才加列，从 v1 升级过来的库会在种词库那步直接报 no such column。
        if (oldVersion in 1 until 8) {
            runCatching {
                db.execSQL("ALTER TABLE words ADD COLUMN phonetic_us TEXT NOT NULL DEFAULT ''")
            }
            runCatching {
                db.execSQL("ALTER TABLE words ADD COLUMN phonetic_uk TEXT NOT NULL DEFAULT ''")
            }
            runCatching {
                db.execSQL("ALTER TABLE words ADD COLUMN phrases TEXT NOT NULL DEFAULT ''")
            }
        }
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
        if (oldVersion < 6) {
            // v6：错题本改成「连续答对 2 次才移出」，需要记录已连续答对几次。
            runCatching {
                db.execSQL("ALTER TABLE mistakes ADD COLUMN streak INTEGER NOT NULL DEFAULT 0")
            }
        }
        if (oldVersion < 7) {
            // v7：新增内置「大学英语四级」词库。老用户升级时补进来，不动原有数据。
            runCatching { seedBookIfAbsent(db, CET4_NAME, ASSET_CET4) }
        }
        if (oldVersion < 8) {
            // 列在上面已经加好了，这里只做数据回填
            runCatching { backfillFromAssets(db) }
        }
        if (oldVersion < 9) {
            // v9：SM-2 的 ease、题型统计、成就。
            runCatching {
                db.execSQL("ALTER TABLE review ADD COLUMN ease REAL NOT NULL DEFAULT 2.5")
            }
            runCatching {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS practice_event(
                        _id INTEGER PRIMARY KEY AUTOINCREMENT,
                        word_id INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        correct INTEGER NOT NULL DEFAULT 0,
                        elapsed_ms INTEGER NOT NULL DEFAULT 0,
                        at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_at ON practice_event(at)") }
            runCatching {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS achievements(
                        key TEXT PRIMARY KEY,
                        unlocked_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    private fun seedBuiltin(db: SQLiteDatabase) {
        seedBook(db, BUILTIN_NAME, ASSET_3500)
        seedBook(db, CET4_NAME, ASSET_CET4)
    }

    private fun seedBook(db: SQLiteDatabase, name: String, asset: String) {
        val now = System.currentTimeMillis()
        val bookId = db.insertOrThrow("books", null, android.content.ContentValues().apply {
            put("name", name)
            put("builtin", 1)
            put("created_at", now)
        })
        val json = appContext.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(json)
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                """
                INSERT INTO words(book_id, ord, word, pos, meaning, starred, example,
                                  phonetic_us, phonetic_uk, phrases, created_at)
                VALUES(?,?,?,?,?,0,?,?,?,?,?)
                """.trimIndent(),
            )
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                stmt.clearBindings()
                stmt.bindLong(1, bookId)
                stmt.bindLong(2, (i + 1).toLong())
                stmt.bindString(3, o.getString("w"))
                stmt.bindString(4, o.optString("pos", ""))
                stmt.bindString(5, o.getString("m"))
                stmt.bindString(6, o.optString("ex", ""))
                stmt.bindString(7, o.optString("us", ""))
                stmt.bindString(8, o.optString("uk", ""))
                stmt.bindString(9, o.optString("ph", ""))
                stmt.bindLong(10, now)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 给已有单词补音标、搭配和例句：逐行按主键更新，比逐词 UPDATE ... WHERE lower(word)=?
     * 快一个数量级（后者对每行都要全表扫一遍）。
     */
    private fun backfillFromAssets(db: SQLiteDatabase) {
        val data = HashMap<String, org.json.JSONObject>()
        for (asset in listOf(ASSET_3500, ASSET_CET4)) {
            val json = appContext.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                data[o.getString("w").lowercase()] = o
            }
        }
        if (data.isEmpty()) return

        val rows = ArrayList<Pair<Long, String>>()
        db.rawQuery("SELECT _id, word FROM words", null).use { c ->
            while (c.moveToNext()) rows.add(c.getLong(0) to (c.getString(1) ?: "").lowercase())
        }

        val stmt = db.compileStatement(
            """
            UPDATE words SET phonetic_us = ?, phonetic_uk = ?, phrases = ?,
                             example = CASE WHEN example = '' THEN ? ELSE example END
            WHERE _id = ?
            """.trimIndent(),
        )
        db.beginTransaction()
        try {
            for ((id, word) in rows) {
                val o = data[word] ?: continue
                stmt.clearBindings()
                stmt.bindString(1, o.optString("us", ""))
                stmt.bindString(2, o.optString("uk", ""))
                stmt.bindString(3, o.optString("ph", ""))
                stmt.bindString(4, o.optString("ex", ""))
                stmt.bindLong(5, id)
                stmt.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 升级时补一个内置词库；已经存在（同名内置词库）就不重复导入。 */
    private fun seedBookIfAbsent(db: SQLiteDatabase, name: String, asset: String) {
        val exists = db.rawQuery(
            "SELECT COUNT(*) FROM books WHERE name = ? AND builtin = 1",
            arrayOf(name),
        ).use { if (it.moveToFirst()) it.getInt(0) > 0 else false }
        if (!exists) seedBook(db, name, asset)
    }

    companion object {
        const val NAME = "worddrill.db"
        const val VERSION = 9
        const val BUILTIN_NAME = "高中 3500 词"
        const val CET4_NAME = "大学英语四级"
        private const val ASSET_3500 = "words3500.json"
        private const val ASSET_CET4 = "words_cet4.json"
    }
}
