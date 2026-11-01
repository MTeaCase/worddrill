package com.worddrill.app.data

/**
 * 间隔重复（类似百词斩的复习曲线）。
 *
 * 每个单词有一个 level：
 * - 答对：level + 1，按 [INTERVALS] 里的天数安排下次复习（1 → 2 → 4 → 7 → 15 → 30 → 60 天）；
 * - 答错：level 归零，第二天再复习，并累加 lapses（遗忘次数）。
 */
object ReviewScheduler {

    const val DAY_MS = 24L * 60 * 60 * 1000
    const val MAX_LEVEL = 7
    private val INTERVALS = intArrayOf(1, 2, 4, 7, 15, 30, 60)

    fun nextLevel(currentLevel: Int, correct: Boolean): Int =
        if (correct) (currentLevel.coerceAtLeast(0) + 1).coerceAtMost(MAX_LEVEL) else 0

    /** 下次复习间隔（天）。答错固定 1 天。 */
    fun intervalDays(level: Int, correct: Boolean): Int =
        if (!correct) 1 else INTERVALS[(level.coerceIn(1, MAX_LEVEL)) - 1]

    fun nextDueAt(currentLevel: Int, correct: Boolean, now: Long): Long {
        val level = nextLevel(currentLevel, correct)
        return now + intervalDays(level, correct) * DAY_MS
    }
}
