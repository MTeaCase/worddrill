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
) {
    /** 展示用释义：词性 + 中文。 */
    val meaningWithPos: String
        get() = if (pos.isBlank()) meaning else "$pos $meaning"
}

data class Book(
    val id: Long,
    val name: String,
    val builtin: Boolean,
    val wordCount: Int,
)

data class MistakeItem(
    val word: Word,
    val wrongCount: Int,
)

data class ImportResult(
    val bookId: Long,
    val added: Int,
    val skipped: Int,
)

enum class PracticeMode(val label: String, val subtitle: String) {
    EN_ZH("英译汉", "看英文，从四个中文释义中选"),
    ZH_EN("汉译英", "看中文，从四个英文单词中选"),
    ZH_EN_SPELL("单词默写", "看中文，自己拼写英文"),
}

enum class WordFilter {
    ALL,
    STARRED,
    MISTAKES,
    BLOCKED,
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
