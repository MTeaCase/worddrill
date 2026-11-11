package com.worddrill.app.data

/** 三档自评。答题对错之外，让用户自己说一句「到底记住了没有」。 */
enum class ReviewRating(val label: String) {
    /** 认识：正常升级。 */
    KNOW("认识"),

    /** 模糊：等级不掉，明天再来一次。 */
    FUZZY("模糊"),

    /** 忘了：等级归零，明天再来，并累加遗忘次数。 */
    FORGOT("忘了"),
}

/**
 * 间隔重复（类似百词斩的复习曲线）。
 *
 * 每个单词有一个 level：
 * - 答对：level + 1，按 [INTERVALS] 里的天数安排下次复习（1 → 2 → 4 → 7 → 15 → 30 → 60 天）；
 * - 答错：level 归零，第二天再复习，并累加 lapses（遗忘次数）。
 *
 * 三档自评是判分的覆盖项：用户点了「忘了」，哪怕刚才蒙对也按忘了算。
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

    // ---------- 三档自评 ----------

    /**
     * 自评后的等级：
     * - 认识 → +1（封顶）；
     * - 模糊 → 保级，但不低于 1 级；
     * - 忘了 → 0 级。
     */
    fun nextLevel(currentLevel: Int, rating: ReviewRating): Int = when (rating) {
        ReviewRating.KNOW -> (currentLevel.coerceAtLeast(0) + 1).coerceAtMost(MAX_LEVEL)
        ReviewRating.FUZZY -> currentLevel.coerceAtLeast(1)
        ReviewRating.FORGOT -> 0
    }

    /** 自评后的间隔：认识按等级曲线走，模糊和忘了都是明天再来。 */
    fun intervalDays(level: Int, rating: ReviewRating): Int = when (rating) {
        ReviewRating.KNOW -> INTERVALS[(level.coerceIn(1, MAX_LEVEL)) - 1]
        ReviewRating.FUZZY, ReviewRating.FORGOT -> 1
    }

    fun nextDueAt(currentLevel: Int, rating: ReviewRating, now: Long): Long {
        val level = nextLevel(currentLevel, rating)
        return now + intervalDays(level, rating) * DAY_MS
    }

    /** 自评「忘了」时是否要顺带记进错题本。 */
    fun addsToMistakes(rating: ReviewRating): Boolean = rating == ReviewRating.FORGOT
}