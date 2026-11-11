package com.worddrill.app.data

/** 成就的判定依据，全部可以从现有表里数出来。 */
enum class AchievementMetric {
    PRACTICED_WORDS,
    STREAK,
    MASTERED_WORDS,
    FAST_CORRECT,
}

data class AchievementDef(
    val key: String,
    val title: String,
    val detail: String,
    val metric: AchievementMetric,
    val target: Int,
)

/** 一次判定所需的全部数字。 */
data class AchievementSnapshot(
    val practicedWords: Int = 0,
    val streak: Int = 0,
    val masteredWords: Int = 0,
    val fastCorrect: Int = 0,
)

/** 成就目录与判定，纯逻辑，可单测。 */
object Achievements {

    val ALL = listOf(
        AchievementDef("first_word", "第一步", "练会第一个词", AchievementMetric.PRACTICED_WORDS, 1),
        AchievementDef("words_100", "一百词", "练过 100 个词", AchievementMetric.PRACTICED_WORDS, 100),
        AchievementDef("words_500", "五百词", "练过 500 个词", AchievementMetric.PRACTICED_WORDS, 500),
        AchievementDef("streak_3", "三天不辍", "连续打卡 3 天", AchievementMetric.STREAK, 3),
        AchievementDef("streak_7", "一周坚持", "连续打卡 7 天", AchievementMetric.STREAK, 7),
        AchievementDef("streak_30", "月度全勤", "连续打卡 30 天", AchievementMetric.STREAK, 30),
        AchievementDef("mastered_50", "小有所成", "掌握 50 个词", AchievementMetric.MASTERED_WORDS, 50),
        AchievementDef("fast_20", "秒答高手", "3 秒内答对 20 题", AchievementMetric.FAST_CORRECT, 20),
    )

    fun valueOf(metric: AchievementMetric, snapshot: AchievementSnapshot): Int = when (metric) {
        AchievementMetric.PRACTICED_WORDS -> snapshot.practicedWords
        AchievementMetric.STREAK -> snapshot.streak
        AchievementMetric.MASTERED_WORDS -> snapshot.masteredWords
        AchievementMetric.FAST_CORRECT -> snapshot.fastCorrect
    }

    /** 当前已达成的成就（不关心是否已经记录过）。 */
    fun reached(snapshot: AchievementSnapshot): List<AchievementDef> =
        ALL.filter { valueOf(it.metric, snapshot) >= it.target }

    /** 某个成就的进度：0..1。 */
    fun progressOf(def: AchievementDef, snapshot: AchievementSnapshot): Float {
        if (def.target <= 0) return 1f
        return (valueOf(def.metric, snapshot).toFloat() / def.target).coerceIn(0f, 1f)
    }
}