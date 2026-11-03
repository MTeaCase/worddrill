package com.worddrill.app.util

import android.content.Context
import com.worddrill.app.BuildConfig

/** 轻量设置存储（每日目标、发音开关、智能解析服务地址）。 */
object Settings {

    private const val PREFS = "worddrill_settings"
    private const val KEY_GOAL = "daily_goal"
    private const val KEY_TTS = "tts_enabled"
    private const val KEY_SMART_URL = "smart_parse_url"
    private const val KEY_REMINDER = "review_reminder"
    private const val KEY_REMINDER_HOUR = "review_reminder_hour"
    private const val KEY_EXAMPLE_AI = "example_ai_enabled"

    const val DEFAULT_GOAL = 30
    const val MIN_GOAL = 5
    const val MAX_GOAL = 200
    const val DEFAULT_REMINDER_HOUR = 20

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
}
