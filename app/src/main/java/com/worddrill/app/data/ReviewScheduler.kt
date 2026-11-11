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
 * 间隔重复：SM-2 的简化实现。
 *
 * 每个单词有一个 level（0–7）和一个 ease（难度系数，默认 2.5，范围 1.3–3.0）：
 * - 认识：level+1，ease 略升，间隔按 `基础曲线 × ease/2.5` 拉长；
 * - 模糊：level 保持，ease 下调，明天再来；
 * - 忘了：level 归零，ease 下调更多，明天再来，并累加 lapses。
 *
 * ease 默认 2.5 时 `ease/2.5 = 1`，间隔就是原来的 1/2/4/7/15/30/60 天曲线，
 * 老用户的到期时间不会因为升级而乱跳。
 */
object ReviewScheduler {

    const val DAY_MS = 24L * 60 * 60 * 1000
    const val MAX_LEVEL = 7

    /** SM-2 的中性难度系数，也是老曲线对应的系数。 */
    const val DEFAULT_EASE = 2.5
    const val MIN_EASE = 1.3
    const val MAX_EASE = 3.0

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

    // ---------- 三档自评 + ease ----------

    fun nextLevel(currentLevel: Int, rating: ReviewRating): Int = when (rating) {
        ReviewRating.KNOW -> (currentLevel.coerceAtLeast(0) + 1).coerceAtMost(MAX_LEVEL)
        ReviewRating.FUZZY -> currentLevel.coerceAtLeast(1)
        ReviewRating.FORGOT -> 0
    }

    /** 每答一次，难度系数按自评结果微调。 */
    fun nextEase(currentEase: Double, rating: ReviewRating): Double {
        val base = currentEase.takeIf { it > 0.0 } ?: DEFAULT_EASE
        val delta = when (rating) {
            ReviewRating.KNOW -> 0.10
            ReviewRating.FUZZY -> -0.14
            ReviewRating.FORGOT -> -0.20
        }
        return (base + delta).coerceIn(MIN_EASE, MAX_EASE)
    }

    /** 认识按「基础曲线 × ease/2.5」拉长；模糊和忘了都是明天再来。 */
    fun intervalDays(level: Int, rating: ReviewRating, ease: Double = DEFAULT_EASE): Int {
        if (rating != ReviewRating.KNOW) return 1
        val base = INTERVALS[(level.coerceIn(1, MAX_LEVEL)) - 1]
        val factor = (ease.takeIf { it > 0.0 } ?: DEFAULT_EASE) / DEFAULT_EASE
        return Math.round(base * factor).toInt().coerceIn(1, 365)
    }

    fun nextDueAt(
        currentLevel: Int,
        rating: ReviewRating,
        now: Long,
        ease: Double = DEFAULT_EASE,
    ): Long {
        val level = nextLevel(currentLevel, rating)
        return now + intervalDays(level, rating, ease) * DAY_MS
    }

    /** 自评「忘了」时是否要顺带记进错题本。 */
    fun addsToMistakes(rating: ReviewRating): Boolean = rating == ReviewRating.FORGOT
}