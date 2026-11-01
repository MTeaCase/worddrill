package com.worddrill.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.worddrill.app.parser.VocabEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 业务查询层。所有方法都会阻塞，调用方必须在后台线程使用。
 *
 * 关于索引：words 表里的 ord 是「入库顺序」（稳定不变），对外的 [Word.ord] 是
 * 「当前可见索引」—— 屏蔽词不占号，剩下的词从 1 连续编号，因此屏蔽/恢复后索引会自动重排。
 */
class WordRepository(context: Context) {

    private val helper = WordDb(context.applicationContext)
    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ---------- 词库 ----------

    fun books(): List<Book> {
        val sql = """
            SELECT b._id, b.name, b.builtin, COUNT(w._id) AS cnt
            FROM books b LEFT JOIN words w ON w.book_id = b._id AND w.blocked = 0
            GROUP BY b._id
            ORDER BY b.builtin DESC, b._id ASC
        """.trimIndent()
        return db.rawQuery(sql, null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Book(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getInt(3)))
                }
            }
        }
    }

    fun book(bookId: Long): Book? =
        books().firstOrNull { it.id == bookId }

    fun addBook(name: String): Long {
        val cv = ContentValues().apply {
            put("name", name.trim())
            put("builtin", 0)
            put("created_at", System.currentTimeMillis())
        }
        return db.insertOrThrow("books", null, cv)
    }

    fun deleteBook(bookId: Long) {
        val builtin = db.rawQuery("SELECT builtin FROM books WHERE _id = ?", arrayOf(bookId.toString())).use {
            it.moveToFirst() && it.getInt(0) == 1
        }
        if (!builtin) {
            db.delete("books", "_id = ?", arrayOf(bookId.toString()))
        }
    }

    fun wordCount(bookId: Long): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM words WHERE book_id = ? AND blocked = 0",
            arrayOf(bookId.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** 可见单词的索引范围；空词库返回 null。 */
    fun ordRange(bookId: Long): IntRange? {
        val count = wordCount(bookId)
        return if (count > 0) 1..count else null
    }

    fun practicedCount(bookId: Long): Int =
        db.rawQuery(
            """
            SELECT COUNT(*) FROM practice_log p
            JOIN words w ON w._id = p.word_id
            WHERE w.book_id = ? AND w.blocked = 0
            """.trimIndent(),
            arrayOf(bookId.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun blockedCount(bookId: Long): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM words WHERE book_id = ? AND blocked = 1",
            arrayOf(bookId.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---------- 单词 ----------

    fun words(
        bookId: Long,
        filter: WordFilter = WordFilter.ALL,
        query: String = "",
        limit: Int = 5000,
    ): List<Word> {
        val args = mutableListOf(bookId.toString())
        val sb = StringBuilder(
            """
            SELECT w._id, w.book_id, w.ord, w.word, w.pos, w.meaning, w.starred, w.blocked, w.example
            FROM words w
            """.trimIndent(),
        )
        if (filter == WordFilter.MISTAKES) {
            sb.append(" JOIN mistakes m ON m.word_id = w._id")
        }
        sb.append(" WHERE w.book_id = ?")
        sb.append(if (filter == WordFilter.BLOCKED) " AND w.blocked = 1" else " AND w.blocked = 0")
        if (filter == WordFilter.STARRED) sb.append(" AND w.starred = 1")
        if (query.isNotBlank()) {
            sb.append(" AND (w.word LIKE ? OR w.meaning LIKE ?)")
            val like = "%${query.trim()}%"
            args.add(like)
            args.add(like)
        }
        sb.append(if (filter == WordFilter.MISTAKES) " ORDER BY m.last_at DESC" else " ORDER BY w.ord ASC")
        sb.append(" LIMIT $limit")
        val rows = db.rawQuery(sb.toString(), args.toTypedArray()).use { c -> c.toWords() }
        if (filter == WordFilter.BLOCKED) return rows
        val index = visibleIndex(bookId)
        return rows.map { row ->
            val ord = index[row.id] ?: row.ord
            if (ord == row.ord) row else row.copy(ord = ord)
        }
    }

    fun wordsByIds(ids: LongArray): List<Word> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val sql =
            "SELECT _id, book_id, ord, word, pos, meaning, starred, blocked, example FROM words WHERE blocked = 0 AND _id IN ($placeholders)"
        val byId = db.rawQuery(sql, ids.map { it.toString() }.toTypedArray()).use { c ->
            c.toWords().associateBy { it.id }
        }
        return ids.toList().mapNotNull { byId[it] }
    }

    fun mistakes(bookId: Long? = null): List<MistakeItem> {
        val sql = StringBuilder(
            """
            SELECT w._id, w.book_id, w.ord, w.word, w.pos, w.meaning, w.starred, w.blocked, w.example, m.wrong
            FROM mistakes m JOIN words w ON w._id = m.word_id
            WHERE w.blocked = 0
            """.trimIndent(),
        )
        val args = mutableListOf<String>()
        if (bookId != null) {
            sql.append(" AND w.book_id = ?")
            args.add(bookId.toString())
        }
        sql.append(" ORDER BY m.last_at DESC")
        val items = db.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(MistakeItem(c.toWord(), c.getInt(9)))
                }
            }
        }
        if (bookId == null) return items
        val index = visibleIndex(bookId)
        return items.map { item ->
            val ord = index[item.word.id] ?: item.word.ord
            if (ord == item.word.ord) item else item.copy(word = item.word.copy(ord = ord))
        }
    }

    /** 供选择题抽取干扰项：全库单词（跨词库），不含屏蔽词。 */
    fun allWords(limit: Int = 5000): List<Word> {
        val sql = "SELECT _id, book_id, ord, word, pos, meaning, starred, blocked, example FROM words WHERE blocked = 0 LIMIT $limit"
        return db.rawQuery(sql, null).use { c -> c.toWords() }
    }

    // ---------- 词典 ----------

    /**
     * 词典搜索：英文单词或中文释义都能搜，做 LIKE 模糊匹配。
     * 排序依次是：完全匹配 > 前缀匹配 > 包含匹配，同档按词长与字母序。
     */
    fun searchWords(query: String, limit: Int = 300): List<Word> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val like = "%$q%"
        val prefix = "$q%"
        val sql =
            """
            SELECT w._id, w.book_id, w.ord, w.word, w.pos, w.meaning, w.starred, w.blocked, w.example
            FROM words w
            WHERE w.blocked = 0 AND (w.word LIKE ? OR w.meaning LIKE ?)
            ORDER BY CASE
                        WHEN lower(w.word) = lower(?) THEN 0
                        WHEN lower(w.word) LIKE lower(?) THEN 1
                        ELSE 2
                     END,
                     length(w.word) ASC, w.word COLLATE NOCASE ASC
            LIMIT $limit
            """.trimIndent()
        return db.rawQuery(sql, arrayOf(like, like, q, prefix)).use { c -> c.toWords() }
    }

    // ---------- 学习统计 ----------

    fun today(): String = todayString()

    fun dailyStats(days: Int): List<DailyStat> {
        if (days <= 0) return emptyList()
        val fmt = SimpleDateFormat(StreakCalc.DATE_FORMAT, Locale.US)
        val keys = ArrayList<String>(days)
        for (offset in days - 1 downTo 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -offset)
            keys.add(fmt.format(cal.time))
        }
        val map = HashMap<String, Pair<Int, Int>>()
        db.rawQuery(
            "SELECT date, answered, correct FROM daily WHERE date >= ?",
            arrayOf(keys.first()),
        ).use { c ->
            while (c.moveToNext()) {
                map[c.getString(0)] = c.getInt(1) to c.getInt(2)
            }
        }
        return keys.map { key ->
            val value = map[key]
            DailyStat(key, value?.first ?: 0, value?.second ?: 0)
        }
    }

    fun studySummary(days: Int, dailyGoal: Int): StudySummary {
        val stats = dailyStats(days)
        val studied: Set<String> = db.rawQuery(
            "SELECT date FROM daily WHERE answered > 0",
            null,
        ).use { c ->
            buildSet {
                while (c.moveToNext()) add(c.getString(0))
            }
        }
        val todayAnswered = stats.lastOrNull()?.answered ?: 0
        val totals = db.rawQuery(
            "SELECT COALESCE(SUM(answered), 0), COALESCE(SUM(correct), 0) FROM daily",
            null,
        ).use { c ->
            if (c.moveToFirst()) c.getInt(0) to c.getInt(1) else 0 to 0
        }
        return StudySummary(
            todayAnswered = todayAnswered,
            dailyGoal = dailyGoal,
            streak = StreakCalc.current(today(), studied),
            totalAnswered = totals.first,
            totalCorrect = totals.second,
            days = stats,
        )
    }

    fun clearDaily() {
        db.delete("daily", null, null)
    }

    fun setStar(wordId: Long, starred: Boolean) {
        val cv = ContentValues().apply { put("starred", if (starred) 1 else 0) }
        db.update("words", cv, "_id = ?", arrayOf(wordId.toString()))
    }

    /** 屏蔽 / 恢复一个单词。屏蔽词不再出现在词表、抽题和错题里；恢复后回到原来的位置。 */
    fun setBlocked(wordId: Long, blocked: Boolean) {
        val cv = ContentValues().apply { put("blocked", if (blocked) 1 else 0) }
        db.update("words", cv, "_id = ?", arrayOf(wordId.toString()))
    }

    fun unblockAll(bookId: Long) {
        db.execSQL(
            "UPDATE words SET blocked = 0 WHERE book_id = ? AND blocked = 1",
            arrayOf<Any>(bookId),
        )
    }

    // ---------- 抽题 ----------

    fun pickWords(
        bookId: Long,
        startOrd: Int,
        endOrd: Int,
        count: Int,
        allowRepeat: Boolean,
        filter: WordFilter = WordFilter.ALL,
    ): List<Word> {
        if (count <= 0 || filter == WordFilter.BLOCKED) return emptyList()
        val args = mutableListOf(bookId.toString())
        val sb = StringBuilder(
            """
            SELECT w._id, w.book_id, w.ord, w.word, w.pos, w.meaning, w.starred, w.blocked, w.example
            FROM words w
            """.trimIndent(),
        )
        if (filter == WordFilter.MISTAKES) {
            sb.append(" JOIN mistakes m ON m.word_id = w._id")
        }
        sb.append(" WHERE w.book_id = ? AND w.blocked = 0")
        if (filter == WordFilter.STARRED) sb.append(" AND w.starred = 1")
        val index = visibleIndex(bookId)
        val candidates = db.rawQuery(sb.toString(), args.toTypedArray()).use { c -> c.toWords() }
            .map { row ->
                val ord = index[row.id] ?: row.ord
                if (ord == row.ord) row else row.copy(ord = ord)
            }
            .filter { it.ord in startOrd..endOrd }

        val pool = if (filter == WordFilter.ALL && !allowRepeat) {
            val practiced = practicedIds()
            candidates.filter { it.id !in practiced }
        } else {
            candidates
        }
        return pool.shuffled().take(count)
    }

    // ---------- 答题记录 ----------

    fun recordAnswer(wordId: Long, mode: PracticeMode, correct: Boolean) {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            if (correct) {
                db.delete("mistakes", "word_id = ?", arrayOf(wordId.toString()))
            } else {
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO mistakes(word_id, mode, wrong, last_at)
                    VALUES(?, ?, COALESCE((SELECT wrong FROM mistakes WHERE word_id = ?), 0) + 1, ?)
                    """.trimIndent(),
                    arrayOf<Any>(wordId, mode.name, wordId, now),
                )
            }
            db.execSQL(
                """
                INSERT OR REPLACE INTO practice_log(word_id, times, last_at)
                VALUES(?, COALESCE((SELECT times FROM practice_log WHERE word_id = ?), 0) + 1, ?)
                """.trimIndent(),
                arrayOf<Any>(wordId, wordId, now),
            )
            val day = today()
            db.execSQL(
                """
                INSERT OR REPLACE INTO daily(date, answered, correct)
                VALUES(?, COALESCE((SELECT answered FROM daily WHERE date = ?), 0) + 1,
                          COALESCE((SELECT correct FROM daily WHERE date = ?), 0) + ?)
                """.trimIndent(),
                arrayOf<Any>(day, day, day, if (correct) 1 else 0),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun removeMistake(wordId: Long) {
        db.delete("mistakes", "word_id = ?", arrayOf(wordId.toString()))
    }

    fun clearMistakes(bookId: Long? = null) {
        if (bookId == null) {
            db.delete("mistakes", null, null)
        } else {
            db.execSQL(
                "DELETE FROM mistakes WHERE word_id IN (SELECT _id FROM words WHERE book_id = ?)",
                arrayOf<Any>(bookId),
            )
        }
    }

    fun clearPracticeLog() {
        db.delete("practice_log", null, null)
    }

    // ---------- 导入 ----------

    fun importEntries(
        bookId: Long?,
        newBookName: String?,
        entries: List<VocabEntry>,
    ): ImportResult {
        if (entries.isEmpty()) return ImportResult(bookId ?: -1, 0, 0)
        db.beginTransaction()
        try {
            val targetId = bookId ?: addBook(newBookName ?: "自组词汇库")
            var ord = db.rawQuery("SELECT COALESCE(MAX(ord), 0) FROM words WHERE book_id = ?", arrayOf(targetId.toString()))
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
            var added = 0
            var skipped = 0
            val exists = db.compileStatement("SELECT 1 FROM words WHERE book_id = ? AND lower(word) = lower(?) LIMIT 1")
            val insert = db.compileStatement(
                "INSERT INTO words(book_id, ord, word, pos, meaning, example, starred, created_at) VALUES(?,?,?,?,?,?,0,?)",
            )
            val now = System.currentTimeMillis()
            for (entry in entries) {
                exists.clearBindings()
                exists.bindLong(1, targetId)
                exists.bindString(2, entry.word)
                val dup = exists.simpleQueryForLongOrNull() != null
                if (dup) {
                    skipped++
                    continue
                }
                ord++
                insert.clearBindings()
                insert.bindLong(1, targetId)
                insert.bindLong(2, ord.toLong())
                insert.bindString(3, entry.word)
                insert.bindString(4, entry.pos)
                insert.bindString(5, entry.meaning)
                insert.bindString(6, entry.example.trim())
                insert.bindLong(7, now)
                insert.executeInsert()
                added++
            }
            db.setTransactionSuccessful()
            return ImportResult(targetId, added, skipped)
        } finally {
            db.endTransaction()
        }
    }

    private fun android.database.sqlite.SQLiteStatement.simpleQueryForLongOrNull(): Long? {
        return try {
            simpleQueryForLong()
        } catch (_: android.database.sqlite.SQLiteDoneException) {
            null
        }
    }

    /** 词库内「未屏蔽单词」的 id → 1 起连续索引。 */
    private fun visibleIndex(bookId: Long): Map<Long, Int> =
        db.rawQuery(
            "SELECT _id FROM words WHERE book_id = ? AND blocked = 0 ORDER BY ord ASC, _id ASC",
            arrayOf(bookId.toString()),
        ).use { c ->
            val map = HashMap<Long, Int>()
            var i = 1
            while (c.moveToNext()) {
                map[c.getLong(0)] = i
                i++
            }
            map
        }

    private fun todayString(): String =
        SimpleDateFormat(StreakCalc.DATE_FORMAT, Locale.US).format(Date())

    private fun practicedIds(): Set<Long> =
        db.rawQuery("SELECT word_id FROM practice_log", null).use { c ->
            buildSet {
                while (c.moveToNext()) add(c.getLong(0))
            }
        }

    private fun Cursor.toWord(): Word = Word(
        id = getLong(0),
        bookId = getLong(1),
        ord = getInt(2),
        text = getString(3),
        pos = getString(4) ?: "",
        meaning = getString(5) ?: "",
        starred = getInt(6) == 1,
        blocked = getInt(7) == 1,
        example = getString(8) ?: "",
    )

    private fun Cursor.toWords(): List<Word> = buildList {
        while (moveToNext()) add(toWord())
    }
}
