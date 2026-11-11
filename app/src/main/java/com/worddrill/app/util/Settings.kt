package com.worddrill.app.util

import android.content.Context
import com.worddrill.app.BuildConfig
import com.worddrill.app.data.Accent

/** 轻量设置存储（每日目标、发音开关、智能解析服务地址）。 */
object Settings {

    private const val PREFS = "worddrill_settings"
    private const val KEY_GOAL = "daily_goal"
    private const val KEY_TTS = "tts_enabled"
    private const val KEY_SMART_URL = "smart_parse_url"
    private const val KEY_REMINDER = "review_reminder"
    private const val KEY_REMINDER_HOUR = "review_reminder_hour"
    private const val KEY_EXAMPLE_AI = "example_ai_enabled"
    private const val KEY_BOOK = "selected_book"
    private const val KEY_ACCENT = "accent"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_SELF_RATE = "self_rating"
    private const val KEY_NEW_QUOTA = "quota_new"
    private const val KEY_REVIEW_QUOTA = "quota_review"
    private const val KEY_MISTAKE_QUOTA = "quota_mistake"
    private const val KEY_EXAM_NAME = "exam_name"
    private const val KEY_EXAM_DATE = "exam_date"

    const val DEFAULT_GOAL = 30
    const val MIN_GOAL = 5
    const val MAX_GOAL = 200
    const val DEFAULT_REMINDER_HOUR = 20

    /** 三档语速，单位是 TTS 的 setSpeechRate 倍率。 */
    val SPEECH_RATES = floatArrayOf(0.75f, 1.0f, 1.25f)

    const val DEFAULT_NEW_QUOTA = 20
    const val DEFAULT_REVIEW_QUOTA = 30
    const val DEFAULT_MISTAKE_QUOTA = 10
    const val MIN_QUOTA = 0
    const val MAX_QUOTA = 100

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dailyGoal(context: Context): Int =
        prefs(context).getInt(KEY_GOAL, DEFAULT_GOAL).coerceIn(MIN_GOAL, MAX_GOAL)

    fun setDailyGoal(context: Context, goal: Int) {
        prefs(context).edit().putInt(KEY_GOAL, goal.coerceIn(MIN_GOAL, MAX_GOAL)).apply()
    }

    fun ttsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_TTS, true)

    fun setTtsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TTS, enabled).apply()
    }

    /** 智能解析服务地址；没手动设置过就用编译进去的默认值。 */
    fun smartParseUrl(context: Context): String =
        prefs(context).getString(KEY_SMART_URL, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.SMART_PARSE_URL

    /** 拆成地址列表，按顺序回退。 */
    fun smartParseUrls(context: Context): List<String> = smartParseUrl(context)
        .split(',', ';', '\n')
        .map { it.trim() }
        .filter { it.startsWith("http") }

    fun setSmartParseUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SMART_URL, url.trim()).apply()
    }

    /** 每日复习提醒。默认关闭，开启后由 WorkManager 每天固定时间检查一次。 */
    fun reminderEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_REMINDER, false)

    fun setReminderEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REMINDER, enabled).apply()
    }

    /** 提醒时间（小时，0–23），默认晚上 8 点。 */
    fun reminderHour(context: Context): Int =
        prefs(context).getInt(KEY_REMINDER_HOUR, DEFAULT_REMINDER_HOUR).coerceIn(0, 23)

    fun setReminderHour(context: Context, hour: Int) {
        prefs(context).edit().putInt(KEY_REMINDER_HOUR, hour.coerceIn(0, 23)).apply()
    }

    /** AI 例句生成。默认关闭，开启后词典里打开没有例句的词才会去请求。 */
    fun exampleAiEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_EXAMPLE_AI, false)

    fun setExampleAiEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXAMPLE_AI, enabled).apply()
    }

    /** 当前选中的词库。底部导航切页面时靠它保持选择一致，进程被杀也能恢复。 */
    fun selectedBookId(context: Context): Long = prefs(context).getLong(KEY_BOOK, -1L)

    fun setSelectedBookId(context: Context, bookId: Long) {
        prefs(context).edit().putLong(KEY_BOOK, bookId).apply()
    }

    /** 口音（美式 / 英式）。音标显示与语音合成都跟着它。 */
    fun accent(context: Context): Accent = when (prefs(context).getString(KEY_ACCENT, null)) {
        Accent.UK.name -> Accent.UK
        else -> Accent.US
    }

    fun setAccent(context: Context, accent: Accent) {
        prefs(context).edit().putString(KEY_ACCENT, accent.name).apply()
    }

    /** 复读语速，限定在三档之内，越界值回落到 1.0。 */
    fun speechRate(context: Context): Float {
        val value = prefs(context).getFloat(KEY_SPEECH_RATE, 1.0f)
        return SPEECH_RATES.firstOrNull { kotlin.math.abs(it - value) < 0.01f } ?: 1.0f
    }

    fun setSpeechRate(context: Context, rate: Float) {
        val safe = SPEECH_RATES.firstOrNull { kotlin.math.abs(it - rate) < 0.01f } ?: 1.0f
        prefs(context).edit().putFloat(KEY_SPEECH_RATE, safe).apply()
    }

    /** 答完题后是否显示「认识 / 模糊 / 忘了」三档自评。 */
    fun selfRatingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SELF_RATE, false)

    fun setSelfRatingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SELF_RATE, enabled).apply()
    }

    /** 每日学习包的三类配额，彼此独立。 */
    fun newQuota(context: Context): Int =
        prefs(context).getInt(KEY_NEW_QUOTA, DEFAULT_NEW_QUOTA).coerceIn(MIN_QUOTA, MAX_QUOTA)

    fun setNewQuota(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_NEW_QUOTA, value.coerceIn(MIN_QUOTA, MAX_QUOTA)).apply()
    }

    fun reviewQuota(context: Context): Int =
        prefs(context).getInt(KEY_REVIEW_QUOTA, DEFAULT_REVIEW_QUOTA).coerceIn(MIN_QUOTA, MAX_QUOTA)

    fun setReviewQuota(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_REVIEW_QUOTA, value.coerceIn(MIN_QUOTA, MAX_QUOTA)).apply()
    }

    fun mistakeQuota(context: Context): Int =
        prefs(context).getInt(KEY_MISTAKE_QUOTA, DEFAULT_MISTAKE_QUOTA).coerceIn(MIN_QUOTA, MAX_QUOTA)

    fun setMistakeQuota(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MISTAKE_QUOTA, value.coerceIn(MIN_QUOTA, MAX_QUOTA)).apply()
    }

    /** 考试倒计时：名称与日期（毫秒，取当天 00:00）。0 表示没设置。 */
    fun examName(context: Context): String =
        prefs(context).getString(KEY_EXAM_NAME, "").orEmpty().trim()

    fun setExamName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_EXAM_NAME, name.trim()).apply()
    }

    fun examDate(context: Context): Long = prefs(context).getLong(KEY_EXAM_DATE, 0L)

    fun setExamDate(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_EXAM_DATE, millis).apply()
    }
}
