package com.worddrill.app.util

import android.content.Context

/** 轻量设置存储（每日目标、发音开关）。 */
object Settings {

    private const val PREFS = "worddrill_settings"
    private const val KEY_GOAL = "daily_goal"
    private const val KEY_TTS = "tts_enabled"

    const val DEFAULT_GOAL = 30
    const val MIN_GOAL = 5
    const val MAX_GOAL = 200

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
}
