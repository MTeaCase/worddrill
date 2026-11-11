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

    /** 星标词数量，给复习页的入口卡片用。 */
    fun starredCount(bookId: Long): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM words WHERE book_id = ? AND blocked = 0 AND starred = 1",
            arrayOf(bookId.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** 错题数量。 */
    fun mistakeCount(bookId: Long): Int =
        db.rawQuery(
            """
            SELECT COUNT(*) FROM mistakes m JOIN words w ON w._id = m.word_id
            WHERE w.book_id = ? AND w.blocked = 0
            """.trimIndent(),
            arrayOf(bookId.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---------- 单词 ----------

    fun words(
        bookId: Long,
        filter: WordFilter = WordFilter.ALL,
        query: String = "",
        limit: Int = 5000,
        mistakeSort: MistakeSort = MistakeSort.RECENT,
    ): List<Word> {
        val args = mutableListOf(bookId.toString())
        val sb = StringBuilder("SELECT $WORD_COLUMNS FROM words w")
        when (filter) {
            WordFilter.MISTAKES -> sb.append(" JOIN mistakes m ON m.word_id = w._id")
            WordFilter.LEARNED -> sb.append(" JOIN practice_log p ON p.word_id = w._id")
            else -> Unit
        }
        if (filter == WordFilter.MISTAKES || filter == WordFilter.LEARNED) {
            sb.append(" LEFT JOIN review r ON r.word_id = w._id")
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
        sb.append(
            when (filter) {
                WordFilter.MISTAKES -> when (mistakeSort) {
                    MistakeSort.RECENT -> " ORDER BY m.last_at DESC"
                    MistakeSort.WRONG -> " ORDER BY m.wrong DESC, m.last_at DESC"
                    MistakeSort.DUE -> " ORDER BY COALESCE(r.due_at, 0) ASC"
                    MistakeSort.ALPHABET -> " ORDER BY w.word COLLATE NOCASE ASC"
                }

                WordFilter.LEARNED -> " ORDER BY p.last_at DESC"
                else -> " ORDER BY w.ord ASC"
            },
        )
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
            "SELECT _id, book_id, ord, word, pos, meaning, starred, blocked, example, phonetic_us, phonetic_uk, phrases FROM words WHERE blocked = 0 AND _id IN ($placeholders)"
        val byId = db.rawQuery(sql, ids.map { it.toString() }.toTypedArray()).use { c ->
            c.toWords().associateBy { it.id }
        }
        return ids.toList().mapNotNull { byId[it] }
    }

    fun mistakes(bookId: Long? = null, sort: MistakeSort = MistakeSort.RECENT): List<MistakeItem> {
        val sql = StringBuilder(
            """
            SELECT $WORD_COLUMNS, m.wrong, m.mode, m.last_at, m.streak,
                   COALESCE(r.level, 0), COALESCE(r.due_at, 0), COALESCE(r.lapses, 0)
            FROM mistakes m
            JOIN words w ON w._id = m.word_id
            LEFT JOIN review r ON r.word_id = w._id
            WHERE w.blocked = 0
            """.trimIndent(),
        )
        val args = mutableListOf<String>()
        if (bookId != null) {
            sql.append(" AND w.book_id = ?")
            args.add(bookId.toString())
        }
        sql.append(
            when (sort) {
                MistakeSort.RECENT -> " ORDER BY m.last_at DESC"
                MistakeSort.WRONG -> " ORDER BY m.wrong DESC, m.last_at DESC"
                MistakeSort.DUE -> " ORDER BY COALESCE(r.due_at, 0) ASC"
                MistakeSort.ALPHABET -> " ORDER BY w.word COLLATE NOCASE ASC"
            },
        )
        val items = db.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) add(c.toMistake())
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
        val sql = "SELECT _id, book_id, ord, word, pos, meaning, starred, blocked, example, phonetic_us, phonetic_uk, phrases FROM words WHERE blocked = 0 LIMIT $limit"
        return db.rawQuery(sql, null).use { c -> c.toWords() }
    }

    /**
     * 掌握度：按复习等级分四档（0 级 / 1–2 级 / 3–4 级 / 5 级以上），
     * 另外单独统计「从没练过」的词数——0 级里混着这两种，分开看更准。
     */
    fun masteryStats(bookId: Long? = null): MasteryStats {
        val where = if (bookId == null) "w.blocked = 0" else "w.book_id = ? AND w.blocked = 0"
        val args = if (bookId == null) emptyArray<String>() else arrayOf(bookId.toString())
        val sql =
            """
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN COALESCE(r.level, 0) = 0 THEN 1 ELSE 0 END) AS fresh,
                   SUM(CASE WHEN r.level BETWEEN 1 AND 2 THEN 1 ELSE 0 END) AS learning,
                   SUM(CASE WHEN r.level BETWEEN 3 AND 4 THEN 1 ELSE 0 END) AS familiar,
                   SUM(CASE WHEN r.level >= 5 THEN 1 ELSE 0 END) AS mastered,
                   SUM(CASE WHEN p.word_id IS NULL THEN 1 ELSE 0 END) AS untouched
            FROM words w
            LEFT JOIN review r ON r.word_id = w._id
            LEFT JOIN practice_log p ON p.word_id = w._id
            WHERE $where
            """.trimIndent()
        return db.rawQuery(sql, args).use { c ->
            if (!c.moveToFirst()) {
                MasteryStats(0, 0, 0)
            } else {
                MasteryStats(
                    total = c.getInt(0),
                    fresh = c.getInt(1),
                    learning = c.getInt(2),
                    familiar = c.getInt(3),
                    mastered = c.getInt(4),
                    untouched = c.getInt(5),
                )
            }
        }
    }

    /** 「已学过」页面头部：练过的词里，1–4 级 / 5 级以上 / 易忘各多少。 */
    fun learnedStats(bookId: Long): LearnedStats {
        val total = wordCount(bookId)
        val sql =
            """
            SELECT COUNT(*),
                   SUM(CASE WHEN COALESCE(r.level, 0) BETWEEN 1 AND 4 THEN 1 ELSE 0 END),
                   SUM(CASE WHEN COALESCE(r.level, 0) >= 5 THEN 1 ELSE 0 END),
                   SUM(CASE WHEN COALESCE(m.wrong, 0) > 0 OR COALESCE(r.lapses, 0) >= 2
                            THEN 1 ELSE 0 END)
            FROM practice_log p
            JOIN words w ON w._id = p.word_id
            LEFT JOIN review r ON r.word_id = p.word_id
            LEFT JOIN mistakes m ON m.word_id = p.word_id
            WHERE w.book_id = ? AND w.blocked = 0
            """.trimIndent()
        return db.rawQuery(sql, arrayOf(bookId.toString())).use { c ->
            if (!c.moveToFirst()) {
                LearnedStats(0, total, 0, 0, 0)
            } else {
                LearnedStats(c.getInt(0), total, c.getInt(1), c.getInt(2), c.getInt(3))
            }
        }
    }

    /** 批量取单词的练习 / 复习进度，给列表的行内小字用。 */
    fun progressFor(wordIds: LongArray): Map<Long, WordProgress> {
        if (wordIds.isEmpty()) return emptyMap()
        val placeholders = wordIds.joinToString(",") { "?" }
        val sql =
            """
            SELECT p.word_id, p.times, p.last_at,
                   COALESCE(r.level, 0), COALESCE(r.due_at, 0), COALESCE(r.lapses, 0),
                   COALESCE(m.wrong, 0), COALESCE(m.mode, ''), COALESCE(m.streak, 0)
            FROM practice_log p
            LEFT JOIN review r ON r.word_id = p.word_id
            LEFT JOIN mistakes m ON m.word_id = p.word_id
            WHERE p.word_id IN ($placeholders)
            """.trimIndent()
        val args = wordIds.map { it.toString() }.toTypedArray()
        return db.rawQuery(sql, args).use { c ->
            val map = HashMap<Long, WordProgress>(wordIds.size)
            while (c.moveToNext()) {
                map[c.getLong(0)] = WordProgress(
                    times = c.getInt(1),
                    lastAt = c.getLong(2),
                    level = c.getInt(3),
                    dueAt = c.getLong(4),
                    lapses = c.getInt(5),
                    wrong = c.getInt(6),
                    lastMode = c.getString(7) ?: "",
                    streak = c.getInt(8),
                )
            }
            map
        }
    }

    /** 错得最多的词，用于报告页的「最该复习的词」。 */
    fun topWrongWords(bookId: Long? = null, limit: Int = 8): List<MistakeItem> {
        val sql = StringBuilder(
            """
            SELECT $WORD_COLUMNS, m.wrong, m.mode, m.last_at, m.streak,
                   COALESCE(r.level, 0), COALESCE(r.due_at, 0), COALESCE(r.lapses, 0)
            FROM mistakes m
            JOIN words w ON w._id = m.word_id
            LEFT JOIN review r ON r.word_id = w._id
            WHERE w.blocked = 0
            """.trimIndent(),
        )
        val args = mutableListOf<String>()
        if (bookId != null) {
            sql.append(" AND w.book_id = ?")
            args.add(bookId.toString())
        }
        sql.append(" ORDER BY m.wrong DESC, m.last_at DESC LIMIT $limit")
        return db.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) add(c.toMistake())
            }
        }
    }

    /** 随机取一个未屏蔽的词，给桌面小组件用。 */
    fun randomWord(): Word? {
        val sql =
            "SELECT _id, book_id, ord, word, pos, meaning, starred, blocked, example, phonetic_us, phonetic_uk, phrases FROM words " +
                "WHERE blocked = 0 ORDER BY RANDOM() LIMIT 1"
        return db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.toWord() else null }
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
            SELECT w._id, w.book_id, w.ord, w.word, w.pos, w.meaning, w.starred, w.blocked, w.example, w.phonetic_us, w.phonetic_uk, w.phrases
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

    fun studySummary(days: Int, dailyGoal: Int, bookId: Long? = null): StudySummary {
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
            dueReview = dueReviewCount(bookId = bookId),
            days = stats,
        )
    }

    fun clearDaily() {
        db.delete("daily", null, null)
    }

    // ---------- 备份 / 恢复 ----------

    /** 导出：非默认词库（含自组单词）+ 错题 + 星标 + 打卡 + 复习排期。 */
    fun exportBackupJson(): String {
        val root = org.json.JSONObject()
        root.put("format", BACKUP_FORMAT)
        root.put("version", 2)
        root.put("exportedAt", System.currentTimeMillis())

        val bookArray = org.json.JSONArray()
        for (book in books().filter { !it.builtin }) {
            val entry = org.json.JSONObject()
            entry.put("name", book.name)
            val wordArray = org.json.JSONArray()
            db.rawQuery(
                "SELECT word, pos, meaning, starred, blocked, example, phonetic_us, phonetic_uk, phrases FROM words WHERE book_id = ? ORDER BY ord",
                arrayOf(book.id.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val item = org.json.JSONObject()
                    item.put("w", c.getString(0))
                    item.put("pos", c.getString(1) ?: "")
                    item.put("m", c.getString(2) ?: "")
                    item.put("star", c.getInt(3) == 1)
                    item.put("block", c.getInt(4) == 1)
                    item.put("ex", c.getString(5) ?: "")
                    item.put("us", c.getString(6) ?: "")
                    item.put("uk", c.getString(7) ?: "")
                    item.put("ph", c.getString(8) ?: "")
                    wordArray.put(item)
                }
            }
            entry.put("words", wordArray)
            bookArray.put(entry)
        }
        root.put("books", bookArray)

        val mistakes = org.json.JSONArray()
        db.rawQuery(
            "SELECT w.word, m.wrong, m.mode, m.last_at FROM mistakes m JOIN words w ON w._id = m.word_id",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                mistakes.put(
                    org.json.JSONObject().apply {
                        put("w", c.getString(0))
                        put("wrong", c.getInt(1))
                        put("mode", c.getString(2))
                        put("lastAt", c.getLong(3))
                    },
                )
            }
        }
        root.put("mistakes", mistakes)

        val starred = org.json.JSONArray()
        db.rawQuery("SELECT word FROM words WHERE starred = 1", null).use { c ->
            while (c.moveToNext()) starred.put(c.getString(0))
        }
        root.put("starred", starred)

        val daily = org.json.JSONArray()
        db.rawQuery("SELECT date, answered, correct FROM daily ORDER BY date", null).use { c ->
            while (c.moveToNext()) {
                daily.put(
                    org.json.JSONObject().apply {
                        put("d", c.getString(0))
                        put("a", c.getInt(1))
                        put("c", c.getInt(2))
                    },
                )
            }
        }
        root.put("daily", daily)

        val review = org.json.JSONArray()
        db.rawQuery(
            "SELECT w.word, r.level, r.due_at, r.last_at, r.lapses FROM review r JOIN words w ON w._id = r.word_id",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                review.put(
                    org.json.JSONObject().apply {
                        put("w", c.getString(0))
                        put("level", c.getInt(1))
                        put("dueAt", c.getLong(2))
                        put("lastAt", c.getLong(3))
                        put("lapses", c.getInt(4))
                    },
                )
            }
        }
        root.put("review", review)

        // 练习记录：首页「已练 N / M 词」和「不重复抽题」都依赖这张表，
        // 之前漏了导出，导致导入备份后已练数变成 0。
        val practice = org.json.JSONArray()
        db.rawQuery(
            "SELECT w.word, p.times, p.last_at FROM practice_log p JOIN words w ON w._id = p.word_id",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                practice.put(
                    org.json.JSONObject().apply {
                        put("w", c.getString(0))
                        put("times", c.getInt(1))
                        put("lastAt", c.getLong(2))
                    },
                )
            }
        }
        root.put("practice", practice)
        return root.toString()
    }

    /** 导入：把备份内容合并进本地（不删除、不覆盖已有单词）。返回给用户看的摘要。 */
    fun importBackupJson(text: String): String {
        val root = org.json.JSONObject(text)
        require(root.optString("format") == BACKUP_FORMAT) { "不是 WordDrill 的备份文件" }

        var addedBooks = 0
        var addedWords = 0
        var mergedStars = 0
        var mergedMistakes = 0
        var mergedDays = 0
        var mergedReview = 0
        var mergedPractice = 0

        db.beginTransaction()
        try {
            val existingBooks = books().associateBy { it.name }
            val bookArray = root.optJSONArray("books") ?: org.json.JSONArray()
            for (i in 0 until bookArray.length()) {
                val bookJson = bookArray.getJSONObject(i)
                val name = bookJson.optString("name").ifBlank { "导入词库" }
                var bookId = existingBooks[name]?.id ?: -1L
                if (bookId < 0) {
                    bookId = addBook(name)
                    addedBooks++
                }
                val words = bookJson.optJSONArray("words") ?: org.json.JSONArray()
                for (j in 0 until words.length()) {
                    val w = words.getJSONObject(j)
                    val text2 = w.optString("w").trim()
                    if (text2.isEmpty()) continue
                    val exists = db.rawQuery(
                        "SELECT _id FROM words WHERE book_id = ? AND lower(word) = lower(?) LIMIT 1",
                        arrayOf(bookId.toString(), text2),
                    ).use { it.moveToFirst() }
                    val wordId: Long
                    if (exists) {
                        wordId = db.rawQuery(
                            "SELECT _id FROM words WHERE book_id = ? AND lower(word) = lower(?) LIMIT 1",
                            arrayOf(bookId.toString(), text2),
                        ).use { if (it.moveToFirst()) it.getLong(0) else -1L }
                    } else {
                        val nextOrd = db.rawQuery(
                            "SELECT COALESCE(MAX(ord), 0) + 1 FROM words WHERE book_id = ?",
                            arrayOf(bookId.toString()),
                        ).use { if (it.moveToFirst()) it.getInt(0) else 1 }
                        val cv = ContentValues().apply {
                            put("book_id", bookId)
                            put("ord", nextOrd)
                            put("word", text2)
                            put("pos", w.optString("pos", ""))
                            put("meaning", w.optString("m", ""))
                            put("example", w.optString("ex", ""))
                            put("phonetic_us", w.optString("us", ""))
                            put("phonetic_uk", w.optString("uk", ""))
                            put("phrases", w.optString("ph", ""))
                            put("starred", 0)
                            put("blocked", 0)
                            put("created_at", System.currentTimeMillis())
                        }
                        wordId = db.insertOrThrow("words", null, cv)
                        addedWords++
                    }
                    if (wordId > 0) {
                        if (w.optBoolean("star", false)) {
                            db.execSQL("UPDATE words SET starred = 1 WHERE _id = ? AND starred = 0", arrayOf<Any>(wordId))
                            mergedStars++
                        }
                        if (w.optBoolean("block", false)) {
                            db.execSQL("UPDATE words SET blocked = 1 WHERE _id = ?", arrayOf<Any>(wordId))
                        }
                    }
                }
            }

            val starred = root.optJSONArray("starred") ?: org.json.JSONArray()
            for (i in 0 until starred.length()) {
                val key = starred.optString(i).trim()
                if (key.isEmpty()) continue
                db.execSQL(
                    "UPDATE words SET starred = 1 WHERE lower(word) = lower(?) AND starred = 0",
                    arrayOf<Any>(key),
                )
                mergedStars++
            }

            val mistakes = root.optJSONArray("mistakes") ?: org.json.JSONArray()
            for (i in 0 until mistakes.length()) {
                val m = mistakes.getJSONObject(i)
                val key = m.optString("w").trim()
                if (key.isEmpty()) continue
                val wrong = m.optInt("wrong", 1)
                val mode = m.optString("mode", PracticeMode.EN_ZH.name)
                val lastAt = m.optLong("lastAt", System.currentTimeMillis())
                val ids = ArrayList<Long>()
                db.rawQuery("SELECT _id FROM words WHERE lower(word) = lower(?)", arrayOf(key)).use { c ->
                    while (c.moveToNext()) ids.add(c.getLong(0))
                }
                for (id in ids) {
                    val current = db.rawQuery(
                        "SELECT wrong, last_at FROM mistakes WHERE word_id = ?",
                        arrayOf(id.toString()),
                    ).use { if (it.moveToFirst()) it.getInt(0) to it.getLong(1) else 0 to 0L }
                    val mergedWrong = maxOf(current.first, wrong)
                    val mergedLast = maxOf(current.second, lastAt)
                    db.execSQL(
                        "INSERT OR REPLACE INTO mistakes(word_id, mode, wrong, last_at) VALUES(?,?,?,?)",
                        arrayOf<Any>(id, mode, mergedWrong, mergedLast),
                    )
                    mergedMistakes++
                }
            }

            val daily = root.optJSONArray("daily") ?: org.json.JSONArray()
            for (i in 0 until daily.length()) {
                val d = daily.getJSONObject(i)
                val date = d.optString("d")
                if (date.isBlank()) continue
                val answered = d.optInt("a", 0)
                val correct = d.optInt("c", 0)
                val current = db.rawQuery(
                    "SELECT answered, correct FROM daily WHERE date = ?",
                    arrayOf(date),
                ).use { if (it.moveToFirst()) it.getInt(0) to it.getInt(1) else 0 to 0 }
                db.execSQL(
                    "INSERT OR REPLACE INTO daily(date, answered, correct) VALUES(?,?,?)",
                    arrayOf<Any>(date, maxOf(current.first, answered), maxOf(current.second, correct)),
                )
                mergedDays++
            }

            val review = root.optJSONArray("review") ?: org.json.JSONArray()
            for (i in 0 until review.length()) {
                val r = review.getJSONObject(i)
                val key = r.optString("w").trim()
                if (key.isEmpty()) continue
                val ids = ArrayList<Long>()
                db.rawQuery("SELECT _id FROM words WHERE lower(word) = lower(?)", arrayOf(key)).use { c ->
                    while (c.moveToNext()) ids.add(c.getLong(0))
                }
                for (id in ids) {
                    val has = db.rawQuery("SELECT 1 FROM review WHERE word_id = ?", arrayOf(id.toString()))
                        .use { it.moveToFirst() }
                    if (has) continue
                    db.execSQL(
                        "INSERT INTO review(word_id, level, due_at, last_at, lapses) VALUES(?,?,?,?,?)",
                        arrayOf<Any>(
                            id,
                            r.optInt("level", 0),
                            r.optLong("dueAt", System.currentTimeMillis()),
                            r.optLong("lastAt", System.currentTimeMillis()),
                            r.optInt("lapses", 0),
                        ),
                    )
                    mergedReview++
                }
            }

            (root.optJSONArray("practice") ?: org.json.JSONArray()).let { practice ->
                for (i in 0 until practice.length()) {
                    val p = practice.getJSONObject(i)
                    val key = p.optString("w").trim()
                    if (key.isEmpty()) continue
                    val times = p.optInt("times", 0)
                    val lastAt = p.optLong("lastAt", System.currentTimeMillis())
                    val ids = ArrayList<Long>()
                    db.rawQuery("SELECT _id FROM words WHERE lower(word) = lower(?)", arrayOf(key)).use { c ->
                        while (c.moveToNext()) ids.add(c.getLong(0))
                    }
                    for (id in ids) {
                        val current = db.rawQuery(
                            "SELECT times, last_at FROM practice_log WHERE word_id = ?",
                            arrayOf(id.toString()),
                        ).use { if (it.moveToFirst()) it.getInt(0) to it.getLong(1) else 0 to 0L }
                        db.execSQL(
                            "INSERT OR REPLACE INTO practice_log(word_id, times, last_at) VALUES(?,?,?)",
                            arrayOf<Any>(id, maxOf(current.first, times), maxOf(current.second, lastAt)),
                        )
                        mergedPractice++
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return "导入完成：新增词库 $addedBooks 个、单词 $addedWords 个；合并星标 $mergedStars 个、" +
            "错题 $mergedMistakes 条、打卡 $mergedDays 天、复习 $mergedReview 条、练习记录 $mergedPractice 条"
    }

    // ---------- 复习（间隔重复） ----------

    /** 今天到期、且没被屏蔽的单词数。[bookId] 不为空时只统计该词库。 */
    fun dueReviewCount(
        now: Long = System.currentTimeMillis(),
        bookId: Long? = null,
    ): Int {
        val bookClause = if (bookId == null) "" else " AND w.book_id = ?"
        val args = if (bookId == null) arrayOf(now.toString()) else arrayOf(now.toString(), bookId.toString())
        return db.rawQuery(
            """
            SELECT COUNT(*) FROM review r
            JOIN words w ON w._id = r.word_id
            WHERE r.due_at <= ? AND w.blocked = 0$bookClause
            """.trimIndent(),
            args,
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** 到期单词，按「等级低（更不熟）优先、到期早优先」排序。 */
    fun dueReviewWords(
        limit: Int = 100,
        now: Long = System.currentTimeMillis(),
        bookId: Long? = null,
    ): List<Word> {
        val bookClause = if (bookId == null) "" else " AND w.book_id = ?"
        val args = if (bookId == null) arrayOf(now.toString()) else arrayOf(now.toString(), bookId.toString())
        val sql =
            """
            SELECT $WORD_COLUMNS
            FROM review r JOIN words w ON w._id = r.word_id
            WHERE r.due_at <= ? AND w.blocked = 0$bookClause
            ORDER BY r.level ASC, r.due_at ASC
            LIMIT $limit
            """.trimIndent()
        return db.rawQuery(sql, args).use { c -> c.toWords() }
    }

    /** 未来若干天每天到期的复习量（今天含已逾期的），用于报告页提示。 */
    fun reviewForecast(days: Int, now: Long = System.currentTimeMillis()): List<Int> {
        if (days <= 0) return emptyList()
        val counts = IntArray(days)
        val end = now + days * ReviewScheduler.DAY_MS
        db.rawQuery(
            """
            SELECT r.due_at FROM review r JOIN words w ON w._id = r.word_id
            WHERE r.due_at <= ? AND w.blocked = 0
            """.trimIndent(),
            arrayOf(end.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                // 已经逾期的词也算今天的任务，否则「今天 0 个、明天 3 个」会很奇怪
                val index = ((c.getLong(0) - now) / ReviewScheduler.DAY_MS).coerceAtLeast(0L).toInt()
                if (index in 0 until days) counts[index]++
            }
        }
        return counts.toList()
    }

    fun setStar(wordId: Long, starred: Boolean) {
        val cv = ContentValues().apply { put("starred", if (starred) 1 else 0) }
        db.update("words", cv, "_id = ?", arrayOf(wordId.toString()))
    }

    /** 缓存 AI 生成的例句，格式「英文||中文」。 */
    fun setExample(wordId: Long, en: String, zh: String) {
        val value = if (en.isBlank()) "" else "$en${Word.EXAMPLE_SEP}$zh"
        db.update("words", ContentValues().apply { put("example", value) }, "_id = ?", arrayOf(wordId.toString()))
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
        val sb = StringBuilder("SELECT $WORD_COLUMNS FROM words w")
        when (filter) {
            WordFilter.MISTAKES -> sb.append(" JOIN mistakes m ON m.word_id = w._id")
            WordFilter.LEARNED -> sb.append(" JOIN practice_log p ON p.word_id = w._id")
            else -> Unit
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

    fun recordAnswer(wordId: Long, mode: PracticeMode, correct: Boolean, elapsedMs: Long = 0L) {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            if (correct) {
                // 连续答对够次数才移出错题本：蒙对一次不该把错题一笔勾销
                db.execSQL(
                    "UPDATE mistakes SET streak = streak + 1 WHERE word_id = ?",
                    arrayOf<Any>(wordId),
                )
                db.execSQL(
                    "DELETE FROM mistakes WHERE word_id = ? AND streak >= $MISTAKE_STREAK_TO_CLEAR",
                    arrayOf<Any>(wordId),
                )
            } else {
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO mistakes(word_id, mode, wrong, last_at, streak)
                    VALUES(?, ?, COALESCE((SELECT wrong FROM mistakes WHERE word_id = ?), 0) + 1, ?, 0)
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
            val (currentLevel, currentLapses, currentEase) = db.rawQuery(
                "SELECT level, lapses, ease FROM review WHERE word_id = ?",
                arrayOf(wordId.toString()),
            ).use { c ->
                if (c.moveToFirst()) {
                    Triple(c.getInt(0), c.getInt(1), c.getDouble(2))
                } else {
                    Triple(0, 0, ReviewScheduler.DEFAULT_EASE)
                }
            }
            val rating = if (correct) ReviewRating.KNOW else ReviewRating.FORGOT
            val nextLevel = ReviewScheduler.nextLevel(currentLevel, rating)
            val ease = ReviewScheduler.nextEase(currentEase, rating)
            val dueAt = ReviewScheduler.nextDueAt(currentLevel, rating, now, currentEase)
            val lapses = if (correct) currentLapses else currentLapses + 1
            db.execSQL(
                "INSERT OR REPLACE INTO review(word_id, level, due_at, last_at, lapses, ease) VALUES(?,?,?,?,?,?)",
                arrayOf<Any>(wordId, nextLevel, dueAt, now, lapses, ease),
            )
            // 题型级统计：只留最近 90 天，避免这张表无限长
            db.execSQL(
                "INSERT INTO practice_event(word_id, mode, correct, elapsed_ms, at) VALUES(?,?,?,?,?)",
                arrayOf<Any>(wordId, mode.name, if (correct) 1 else 0, elapsedMs, now),
            )
            db.execSQL(
                "DELETE FROM practice_event WHERE at < ?",
                arrayOf<Any>(now - EVENT_KEEP_MS),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 三档自评：直接改写 review 的排期，作为判分结果的覆盖项。
     * 「忘了」同时记进错题本，「认识」则按连续答对把错题冲掉。
     */
    fun rateWord(wordId: Long, rating: ReviewRating) {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val (currentLevel, currentLapses, currentEase) = db.rawQuery(
                "SELECT level, lapses, ease FROM review WHERE word_id = ?",
                arrayOf(wordId.toString()),
            ).use { c ->
                if (c.moveToFirst()) {
                    Triple(c.getInt(0), c.getInt(1), c.getDouble(2))
                } else {
                    Triple(0, 0, ReviewScheduler.DEFAULT_EASE)
                }
            }
            val nextLevel = ReviewScheduler.nextLevel(currentLevel, rating)
            val ease = ReviewScheduler.nextEase(currentEase, rating)
            val dueAt = ReviewScheduler.nextDueAt(currentLevel, rating, now, currentEase)
            val lapses = if (rating == ReviewRating.FORGOT) currentLapses + 1 else currentLapses
            db.execSQL(
                "INSERT OR REPLACE INTO review(word_id, level, due_at, last_at, lapses, ease) VALUES(?,?,?,?,?,?)",
                arrayOf<Any>(wordId, nextLevel, dueAt, now, lapses, ease),
            )
            if (ReviewScheduler.addsToMistakes(rating)) {
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO mistakes(word_id, mode, wrong, last_at, streak)
                    VALUES(?, ?, COALESCE((SELECT wrong FROM mistakes WHERE word_id = ?), 0) + 1, ?, 0)
                    """.trimIndent(),
                    arrayOf<Any>(wordId, RATING_MODE, wordId, now),
                )
            } else if (rating == ReviewRating.KNOW) {
                db.execSQL(
                    "UPDATE mistakes SET streak = streak + 1 WHERE word_id = ?",
                    arrayOf<Any>(wordId),
                )
                db.execSQL(
                    "DELETE FROM mistakes WHERE word_id = ? AND streak >= $MISTAKE_STREAK_TO_CLEAR",
                    arrayOf<Any>(wordId),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- 每日学习包 ----------

    /**
     * 一键开练的组成：到期复习 → 错题 → 没练过的新词，三类各自有限额，
     * 同一个词只出现一次（先到先得）。
     */
    fun dailyPack(
        bookId: Long,
        newLimit: Int,
        reviewLimit: Int,
        mistakeLimit: Int,
        now: Long = System.currentTimeMillis(),
    ): DailyPack {
        val due = if (reviewLimit <= 0) {
            emptyList()
        } else {
            db.rawQuery(
                """
                SELECT w._id FROM words w JOIN review r ON r.word_id = w._id
                WHERE w.book_id = ? AND w.blocked = 0 AND r.due_at <= ?
                ORDER BY r.due_at ASC LIMIT ?
                """.trimIndent(),
                arrayOf(bookId.toString(), now.toString(), reviewLimit.toString()),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        }

        val picked = due.toMutableSet()
        val mistakes = if (mistakeLimit <= 0) {
            emptyList()
        } else {
            val rows = db.rawQuery(
                """
                SELECT w._id FROM mistakes m JOIN words w ON w._id = m.word_id
                WHERE w.book_id = ? AND w.blocked = 0
                ORDER BY m.wrong DESC, m.last_at DESC
                """.trimIndent(),
                arrayOf(bookId.toString()),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            rows.filter { it !in picked }.take(mistakeLimit)
        }
        picked += mistakes

        val fresh = if (newLimit <= 0) {
            emptyList()
        } else {
            val rows = db.rawQuery(
                """
                SELECT w._id FROM words w
                WHERE w.book_id = ? AND w.blocked = 0
                  AND NOT EXISTS(SELECT 1 FROM practice_log p WHERE p.word_id = w._id)
                ORDER BY w.ord ASC
                """.trimIndent(),
                arrayOf(bookId.toString()),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            rows.filter { it !in picked }.take(newLimit)
        }

        return DailyPack(due = due, mistakes = mistakes, fresh = fresh)
    }

    /** 拼写相近、容易混的词：同首字母、长度差 ≤2、编辑距离 1–2。 */
    fun confusableWords(wordId: Long, limit: Int = 3): List<Word> {
        val target = wordsByIds(longArrayOf(wordId)).firstOrNull() ?: return emptyList()
        val key = target.text.lowercase()
        if (key.length < 3) return emptyList()
        return words(target.bookId, limit = 5000)
            .asSequence()
            .filter { it.id != target.id }
            .filter { TextDistance.similarLength(it.text.lowercase(), key) }
            .filter { it.text.firstOrNull()?.lowercaseChar() == key.first() }
            .map { candidate -> candidate to TextDistance.editDistance(candidate.text.lowercase(), key) }
            .filter { (_, distance) -> distance in 1..2 }
            .sortedWith(compareBy({ it.second }, { it.first.ord }))
            .take(limit)
            .map { it.first }
            .toList()
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

    // ---------- 统计 / 成就 / 每日一词 ----------

    /** 题型级统计（最近 N 天），用于「哪个题型最容易错」。 */
    fun modeStats(days: Int = 30): List<ModeStat> {
        val since = System.currentTimeMillis() - days * ReviewScheduler.DAY_MS
        return db.rawQuery(
            "SELECT mode, COUNT(*), SUM(correct) FROM practice_event WHERE at >= ? GROUP BY mode",
            arrayOf(since.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ModeStat(mode = c.getString(0) ?: "", total = c.getInt(1), correct = c.getInt(2)))
                }
            }
        }
    }

    /** 最近 N 天的平均答题耗时（毫秒），没有数据返回 0。 */
    fun averageElapsedMs(days: Int = 30): Int {
        val since = System.currentTimeMillis() - days * ReviewScheduler.DAY_MS
        return db.rawQuery(
            "SELECT AVG(elapsed_ms) FROM practice_event WHERE at >= ? AND elapsed_ms > 0",
            arrayOf(since.toString()),
        ).use { c -> if (c.moveToFirst()) c.getDouble(0).toInt() else 0 }
    }

    /** 一年打卡热力图。 */
    fun heatmap(days: Int = 365): List<DailyStat> = dailyStats(days)

    fun achievementSnapshot(): AchievementSnapshot {
        val practiced = db.rawQuery("SELECT COUNT(*) FROM practice_log", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        val mastered = db.rawQuery("SELECT COUNT(*) FROM review WHERE level >= 5", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        val fast = db.rawQuery(
            "SELECT COUNT(*) FROM practice_event WHERE correct = 1 AND elapsed_ms BETWEEN 1 AND $FAST_MS",
            null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val studied = db.rawQuery("SELECT date FROM daily WHERE answered > 0", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        return AchievementSnapshot(
            practicedWords = practiced,
            streak = StreakCalc.current(today(), studied),
            masteredWords = mastered,
            fastCorrect = fast,
        )
    }

    /** 写入新解锁的成就，返回这一次新拿到的（用来弹提示）。 */
    fun checkAchievements(): List<AchievementDef> {
        val snapshot = achievementSnapshot()
        val reached = Achievements.reached(snapshot)
        val known = db.rawQuery("SELECT key FROM achievements", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        val fresh = reached.filter { it.key !in known }
        if (fresh.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            fresh.forEach {
                db.execSQL(
                    "INSERT OR IGNORE INTO achievements(key, unlocked_at) VALUES(?, ?)",
                    arrayOf<Any>(it.key, now),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return fresh
    }

    /** 全部成就 + 解锁时间（0 表示还没解锁）。 */
    fun achievementState(): List<Pair<AchievementDef, Long>> {
        val unlocked = db.rawQuery("SELECT key, unlocked_at FROM achievements", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
        }
        return Achievements.ALL.map { it to (unlocked[it.key] ?: 0L) }
    }

    /**
     * 小组件「每日一词」：按日期取模，一天之内固定，跨天自动换。
     * 用 ord 直接算偏移，不用把整本词库读进内存。
     */
    fun wordForDate(bookId: Long, dayIndex: Long = System.currentTimeMillis() / ReviewScheduler.DAY_MS): Word? {
        val count = wordCount(bookId)
        if (count <= 0) return null
        val offset = Math.floorMod(dayIndex, count.toLong()).toInt()
        return db.rawQuery(
            "SELECT $WORD_COLUMNS FROM words w WHERE w.book_id = ? AND w.blocked = 0 " +
                "ORDER BY w.ord ASC LIMIT 1 OFFSET $offset",
            arrayOf(bookId.toString()),
        ).use { c -> if (c.moveToNext()) c.toWord() else null }
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
                """
                INSERT INTO words(book_id, ord, word, pos, meaning, starred, example,
                                  phonetic_us, phonetic_uk, phrases, created_at)
                VALUES(?,?,?,?,?,0,?,?,?,?,?)
                """.trimIndent(),
            )
            // 自建词库也能白拿音标和例句：内置词库里已经有一条同名词就照抄过来
            val enrichment = HashMap<String, Array<String>>()
            db.rawQuery(
                """
                SELECT lower(word), phonetic_us, phonetic_uk, phrases, example FROM words
                WHERE phonetic_us <> '' OR phonetic_uk <> '' OR example <> ''
                """.trimIndent(),
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(0) ?: continue
                    if (enrichment.containsKey(key)) continue
                    enrichment[key] = arrayOf(
                        c.getString(1) ?: "",
                        c.getString(2) ?: "",
                        c.getString(3) ?: "",
                        c.getString(4) ?: "",
                    )
                }
            }
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
                val extra = enrichment[entry.word.trim().lowercase()]
                ord++
                insert.clearBindings()
                insert.bindLong(1, targetId)
                insert.bindLong(2, ord.toLong())
                insert.bindString(3, entry.word)
                insert.bindString(4, entry.pos)
                insert.bindString(5, entry.meaning)
                insert.bindString(6, extra?.get(3) ?: "")
                insert.bindString(7, extra?.get(0) ?: "")
                insert.bindString(8, extra?.get(1) ?: "")
                insert.bindString(9, extra?.get(2) ?: "")
                insert.bindLong(10, now)
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

    private companion object {
        const val BACKUP_FORMAT = "worddrill-backup"

        /** 答错后要连续答对几次才移出错题本。 */
        const val MISTAKE_STREAK_TO_CLEAR = 2

        /** 自评「忘了」写进错题本时用的题型标记（不是真的题型）。 */
        const val RATING_MODE = "SELF_RATING"

        /** 答题事件只保留最近 90 天，超出的每次写入时顺手删掉。 */
        const val EVENT_KEEP_MS = 90L * ReviewScheduler.DAY_MS

        /** 3 秒内答对算「秒答」，成就判定用。 */
        const val FAST_MS = 3000L

        const val WORD_COLUMNS =
            "w._id, w.book_id, w.ord, w.word, w.pos, w.meaning, w.starred, w.blocked, w.example, " +
            "w.phonetic_us, w.phonetic_uk, w.phrases"
    }

    private fun Cursor.toMistake(): MistakeItem = MistakeItem(
        word = toWord(),
        wrongCount = getInt(12),
        mode = getString(13) ?: "",
        lastAt = getLong(14),
        streak = getInt(15),
        level = getInt(16),
        dueAt = getLong(17),
        lapses = getInt(18),
    )

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
        phoneticUs = getString(9) ?: "",
        phoneticUk = getString(10) ?: "",
        phrases = getString(11) ?: "",
    )

    private fun Cursor.toWords(): List<Word> = buildList {
        while (moveToNext()) add(toWord())
    }
}

