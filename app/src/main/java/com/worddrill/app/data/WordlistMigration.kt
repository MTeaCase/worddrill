package com.worddrill.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray

/**
 * 内置词库迁移：把修好的词表部署进老用户的数据库。
 *
 * 只替换 assets 里的 JSON 只对新安装生效——老用户的 SQLite 里种的还是旧词表。
 * 这里按「规范化词头」把新旧词对齐，尽量复用旧的 `_id`：星标、错题、复习排期、
 * 练习记录全都挂在 word_id 上，复用 id 就等于进度跟着走；改名（`v]` → `sort`）
 * 也一样，不用逐张表搬。
 *
 * 失败会回滚并且不写版本号，下次打开再试。
 */
object WordlistMigration {

    /** 当前内置词表的数据版本。词表再改一次就 +1。 */
    const val VERSION = 1

    private const val PREFS = "wordlist"
    private const val KEY_VERSION = "version"

    private val BUILTIN_BOOKS = listOf(
        WordDb.BUILTIN_NAME to "words3500.json",
        WordDb.CET4_NAME to "words_cet4.json",
    )

    /** SQLite 单条语句最多 999 个参数，删除按批走。 */
    private const val DELETE_BATCH = 200

    private class OldRow(
        val id: Long,
        val word: String,
        val example: String,
        val us: String,
        val uk: String,
        val ph: String,
    )

    private class Entry(
        val word: String,
        val pos: String,
        val meaning: String,
        val us: String,
        val uk: String,
        val ex: String,
        val ph: String,
    )

    private class Stats(var reused: Int = 0, var renamed: Int = 0, var inserted: Int = 0, var removed: Int = 0)

    /** 每次打开数据库都看一眼版本；落后就跑一次迁移。 */
    fun maybeMigrate(context: Context, db: SQLiteDatabase) {
        if (storedVersion(context) >= VERSION) return
        if (rebuild(context, db).ok) saveVersion(context)
    }

    /** 设置页手动「重新部署默认词库」。返回给用户看的摘要。 */
    fun redeploy(context: Context, db: SQLiteDatabase): String {
        val report = rebuild(context, db)
        if (report.ok) saveVersion(context)
        return report.summary
    }

    /** 新安装种完词库后直接标版本，免得首次打开又白重建一遍。 */
    fun markCurrent(context: Context) {
        saveVersion(context)
    }

    fun storedVersion(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VERSION, 0)

    private fun saveVersion(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VERSION, VERSION)
            .apply()
    }

    private class Report(val ok: Boolean, val summary: String)

    private fun rebuild(context: Context, db: SQLiteDatabase): Report {
        val total = Stats()
        db.beginTransaction()
        try {
            for ((name, asset) in BUILTIN_BOOKS) {
                val bookId = bookIdOf(db, name) ?: continue
                val entries = readAsset(context, asset) ?: continue
                val stats = rebuildBook(context, db, bookId, entries)
                total.reused += stats.reused
                total.renamed += stats.renamed
                total.inserted += stats.inserted
                total.removed += stats.removed
            }
            db.setTransactionSuccessful()
        } catch (error: Throwable) {
            return Report(false, "重建内置词库失败：${error.message}")
        } finally {
            db.endTransaction()
        }
        val summary = "内置词库已重建：沿用 ${total.reused} 条、改名 ${total.renamed} 条、" +
            "新增 ${total.inserted} 条、删除 ${total.removed} 条"
        return Report(true, summary)
    }

    private fun bookIdOf(db: SQLiteDatabase, name: String): Long? =
        db.rawQuery("SELECT _id FROM books WHERE name = ?", arrayOf(name))
            .use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun readAsset(context: Context, asset: String): List<Entry>? = runCatching {
        val text = context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONArray(text)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val word = item.optString("w").trim()
                if (word.isEmpty()) continue
                add(
                    Entry(
                        word = word,
                        pos = item.optString("pos"),
                        meaning = item.optString("m"),
                        us = item.optString("us"),
                        uk = item.optString("uk"),
                        ex = item.optString("ex"),
                        ph = item.optString("ph"),
                    ),
                )
            }
        }
    }.getOrNull()

    private fun rebuildBook(
        context: Context,
        db: SQLiteDatabase,
        bookId: Long,
        entries: List<Entry>,
    ): Stats {
        val stats = Stats()
        val rows = db.rawQuery(
            "SELECT _id, word, example, phonetic_us, phonetic_uk, phrases FROM words WHERE book_id = ?",
            arrayOf(bookId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        OldRow(
                            id = cursor.getLong(0),
                            word = cursor.getString(1) ?: "",
                            example = cursor.getString(2) ?: "",
                            us = cursor.getString(3) ?: "",
                            uk = cursor.getString(4) ?: "",
                            ph = cursor.getString(5) ?: "",
                        ),
                    )
                }
            }
        }

        // 规范化词头 → 旧行（同名不同义如 may/May 会落到同一个键，按队列先到先得）
        val buckets = HashMap<String, ArrayDeque<OldRow>>()
        for (row in rows) {
            buckets.getOrPut(WordRenames.normalizeKey(row.word)) { ArrayDeque() }.addLast(row)
        }

        // 改名词的旧键 → 新键：新旧词头对不上时靠它接上进度
        val reverse = HashMap<String, String>()
        for (row in rows) {
            val oldKey = WordRenames.normalizeKey(row.word)
            val newKey = WordRenames.normalizeKey(WordRenames.canonical(context, row.word))
            if (newKey != oldKey) reverse[newKey] = oldKey
        }

        val update = db.compileStatement(
            """
            UPDATE words SET word = ?, pos = ?, meaning = ?, ord = ?, example = ?,
                             phonetic_us = ?, phonetic_uk = ?, phrases = ?
            WHERE _id = ?
            """.trimIndent(),
        )
        val insert = db.compileStatement(
            """
            INSERT INTO words(book_id, ord, word, pos, meaning, starred, blocked, known, example,
                              phonetic_us, phonetic_uk, phrases, created_at)
            VALUES(?,?,?,?,?,0,0,0,?,?,?,?,?)
            """.trimIndent(),
        )
        val now = System.currentTimeMillis()
        val used = HashSet<Long>()

        fun claim(key: String?): OldRow? {
            if (key == null) return null
            val queue = buckets[key] ?: return null
            while (queue.isNotEmpty()) {
                val row = queue.removeFirst()
                if (row.id in used) continue
                used.add(row.id)
                return row
            }
            return null
        }

        var ord = 0
        for (entry in entries) {
            ord += 1
            val key = WordRenames.normalizeKey(entry.word)
            val exact = claim(key)
            val renamed = if (exact == null) claim(reverse[key]) else null
            val source = exact ?: renamed

            if (source == null) {
                insert.clearBindings()
                insert.bindLong(1, bookId)
                insert.bindLong(2, ord.toLong())
                insert.bindString(3, entry.word)
                insert.bindString(4, entry.pos)
                insert.bindString(5, entry.meaning)
                insert.bindString(6, entry.ex)
                insert.bindString(7, entry.us)
                insert.bindString(8, entry.uk)
                insert.bindString(9, entry.ph)
                insert.bindLong(10, now)
                insert.executeInsert()
                stats.inserted += 1
                continue
            }

            update.clearBindings()
            update.bindString(1, entry.word)
            update.bindString(2, entry.pos)
            update.bindString(3, entry.meaning)
            update.bindLong(4, ord.toLong())
            // 用户自己缓存过的例句优先保留，其余富字段以新词表为准
            update.bindString(5, source.example.ifBlank { entry.ex })
            update.bindString(6, entry.us.ifBlank { source.us })
            update.bindString(7, entry.uk.ifBlank { source.uk })
            update.bindString(8, entry.ph.ifBlank { source.ph })
            update.bindLong(9, source.id)
            update.executeUpdateDelete()
            if (renamed != null) stats.renamed += 1 else stats.reused += 1
        }

        // 新词表里没有的旧词：确实是坏行，连进度一起删（FK 会带走错题/复习/练习记录）
        val stale = rows.map { it.id }.filter { it !in used }
        if (stale.isNotEmpty()) {
            for (chunk in stale.chunked(DELETE_BATCH)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val args = chunk.map { it.toString() }.toTypedArray()
                // practice_event 没有外键，必须手动清，否则分题型正确率会留孤儿
                db.execSQL("DELETE FROM practice_event WHERE word_id IN ($placeholders)", args)
                db.execSQL("DELETE FROM words WHERE _id IN ($placeholders)", args)
            }
            stats.removed += stale.size
        }
        return stats
    }
}