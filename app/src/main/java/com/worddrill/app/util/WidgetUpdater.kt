package com.worddrill.app.util

import android.content.Context
import com.worddrill.app.data.WordRepository
import com.worddrill.app.ui.widget.Widgets

/**
 * 把首页统计和「今日一词」缓存到 SharedPreferences 并刷新桌面小组件。
 * 小组件进程里直接读缓存，避免在 widget 回调里查数据库。
 */
object WidgetUpdater {

    const val PREFS = "widget_cache"
    const val KEY_TODAY = "today"
    const val KEY_GOAL = "goal"
    const val KEY_STREAK = "streak"
    const val KEY_DUE = "due"
    const val KEY_WORD = "word"
    const val KEY_WORD_MEANING = "word_meaning"
    const val KEY_WORD_PHONETIC = "word_phonetic"
    const val KEY_WORD_PHRASE = "word_phrase"

    private data class WidgetData(
        val today: Int,
        val goal: Int,
        val streak: Int,
        val due: Int,
        val word: String,
        val meaning: String,
        val phonetic: String,
        val phrase: String,
    )

    fun refresh(context: Context) {
        val app = context.applicationContext
        val goal = Settings.dailyGoal(app)
        val accent = Settings.accent(app)
        Bg.run({
            val repo = WordRepository(app)
            val summary = repo.studySummary(7, goal)
            val due = repo.dueReviewCount()
            // 今日一词：按日期取模，一天之内不换，跨天自动换
            val bookId = Settings.selectedBookId(app)
                .takeIf { it > 0 }
                ?: repo.books().firstOrNull()?.id
                ?: -1L
            val word = if (bookId > 0) repo.wordForDate(bookId) else repo.randomWord()
            WidgetData(
                today = summary.todayAnswered,
                goal = summary.dailyGoal,
                streak = summary.streak,
                due = due,
                word = word?.text.orEmpty(),
                meaning = word?.meaningWithPos.orEmpty(),
                phonetic = word?.phonetic(accent).orEmpty(),
                phrase = if (word?.hasPhrase == true) {
                    "${word.phraseEn}  ${word.phraseZh}".trim()
                } else {
                    ""
                },
            )
        }) { result ->
            result.onSuccess { data ->
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_TODAY, data.today)
                    .putInt(KEY_GOAL, data.goal)
                    .putInt(KEY_STREAK, data.streak)
                    .putInt(KEY_DUE, data.due)
                    .putString(KEY_WORD, data.word)
                    .putString(KEY_WORD_MEANING, data.meaning)
                    .putString(KEY_WORD_PHONETIC, data.phonetic)
                    .putString(KEY_WORD_PHRASE, data.phrase)
                    .apply()
                Widgets.updateAll(app)
            }
        }
    }
}