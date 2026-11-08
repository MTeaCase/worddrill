package com.worddrill.app.data

/**
 * 一个单词条目。bookId 为所属词库，ord 为词库内当前可见索引（屏蔽词不占索引，从 1 连续编号）。
 * blocked 为 true 表示该词被屏蔽，不参与列表与练习。
 */
data class Word(
    val id: Long,
    val bookId: Long,
    val ord: Int,
    val text: String,
    val pos: String,
    val meaning: String,
    val starred: Boolean,
    val blocked: Boolean = false,
    /** 例句缓存，格式「英文例句||中文翻译」；没有则为空串。 */
    val example: String = "",
) {
    /** 展示用释义：词性 + 中文。 */
    val meaningWithPos: String
        get() = if (pos.isBlank()) meaning else "$pos $meaning"

    val exampleEn: String
        get() = example.substringBefore(EXAMPLE_SEP).trim()

    val exampleZh: String
        get() = example.substringAfter(EXAMPLE_SEP, "").trim()

    val hasExample: Boolean
        get() = exampleEn.isNotEmpty()

    companion object {
        const val EXAMPLE_SEP = "||"
    }
}

/** 某一天的练习统计。date 为 yyyy-MM-dd。 */
data class DailyStat(
    val date: String,
    val answered: Int,
    val correct: Int,
) {
    val accuracy: Int
        get() = if (answered <= 0) 0 else correct * 100 / answered
}

/** 首页 / 报告页用的汇总数据。 */
data class StudySummary(
    val todayAnswered: Int,
    val dailyGoal: Int,
    val streak: Int,
    val totalAnswered: Int,
    val totalCorrect: Int,
    /** 今天到期需要复习的单词数。 */
    val dueReview: Int,
    val days: List<DailyStat>,
)

data class Book(
    val id: Long,
    val name: String,
    val builtin: Boolean,
    val wordCount: Int,
)

data class MistakeItem(
    val word: Word,
    val wrongCount: Int,
    /** 最近一次答错时的题型（PracticeMode.name）。 */
    val mode: String = "",
    /** 最近一次答错的时间。 */
    val lastAt: Long = 0L,
    /** 答错之后连续答对的次数，达到阈值会自动移出错题本。 */
    val streak: Int = 0,
    /** 当前复习等级。 */
    val level: Int = 0,
    /** 下次复习时间。 */
    val dueAt: Long = 0L,
    /** 累计遗忘（答错归零）次数。 */
    val lapses: Int = 0,
)

/** 单词的练习 / 复习进度，用于「已学过」和错题本的行内小字。 */
data class WordProgress(
    /** 练过几次。 */
    val times: Int = 0,
    /** 上次练习时间。 */
    val lastAt: Long = 0L,
    /** 复习等级 0–7。 */
    val level: Int = 0,
    /** 下次复习时间。 */
    val dueAt: Long = 0L,
    /** 答错归零次数。 */
    val lapses: Int = 0,
    /** 错过几次。 */
    val wrong: Int = 0,
    /** 最近一次答错时的题型（PracticeMode.name），没有错题记录时为空。 */
    val lastMode: String = "",
    /** 答错之后连续答对的次数。 */
    val streak: Int = 0,
)

/** 「已学过」页面的头部统计。 */
data class LearnedStats(
    val learned: Int,
    val total: Int,
    /** 复习等级 1–4 级。 */
    val learning: Int,
    /** 复习等级 ≥ 5 级。 */
    val mastered: Int,
    /** 属于「易忘」：答错过，或复习时归零（lapses）≥ 2 次。 */
    val weak: Int,
)

/** 掌握度分布：按复习等级分四档，另附「从没练过」的数量。 */
data class MasteryStats(
    val total: Int,
    /** 0 级。 */
    val fresh: Int,
    /** 1–2 级。 */
    val learning: Int,
    /** 3–4 级。 */
    val familiar: Int = 0,
    /** ≥ 5 级。 */
    val mastered: Int = 0,
    /** 没有任何练习记录的词。 */
    val untouched: Int = 0,
) {
    val masteredPercent: Int
        get() = if (total <= 0) 0 else mastered * 100 / total

    val learningPercent: Int
        get() = if (total <= 0) 0 else learning * 100 / total
}

/** 错题本的排序方式。 */
enum class MistakeSort {
    /** 最近答错的排前面。 */
    RECENT,

    /** 错得最多的排前面。 */
    WRONG,

    /** 最该复习（最快到期）的排前面。 */
    DUE,

    /** 按字母顺序。 */
    ALPHABET,
}

data class ImportResult(
    val bookId: Long,
    val added: Int,
    val skipped: Int,
)

enum class PracticeMode(val label: String, val subtitle: String) {
    EN_ZH("英译汉", "看英文，从四个中文释义中选"),
    ZH_EN("汉译英", "看中文，从四个英文单词中选"),
    ZH_EN_SPELL("单词默写", "看中文，自己拼写英文"),
    LISTEN_SPELL("听音拼写", "听发音，自己拼出英文单词"),
}

enum class WordFilter {
    ALL,
    STARRED,
    MISTAKES,
    BLOCKED,

    /** 当前词库里练过至少一次的词。 */
    LEARNED,
}

/** 一次答题的记录，练习结束后传给结果页。 */
data class AnswerRecord(
    val wordId: Long,
    val word: String,
    val meaning: String,
    val answer: String,
    val userAnswer: String,
    val correct: Boolean,
)
