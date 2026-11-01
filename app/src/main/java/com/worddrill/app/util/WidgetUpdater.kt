package com.worddrill.app.util

import android.content.Context
import com.worddrill.app.data.WordRepository
import com.worddrill.app.ui.widget.WordDrillWidget

/**
 * 把首页统计缓存到 SharedPreferences 并刷新桌面小组件。
 * 小组件进程里直接读缓存，避免在 widget 回调里查数据库。
 */
object WidgetUpdater {

    const val PREFS = "widget_cache"
    const val KEY_TODAY = "today"
    const val KEY_GOAL = "goal"
    const val KEY_STREAK = "streak"

    fun refresh(context: Context) {
        val app = context.applicationContext
        val goal = Settings.dailyGoal(app)
        Bg.run({ WordRepository(app).studySummary(7, goal) }) { result ->
            result.onSuccess { summary ->
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_TODAY, summary.todayAnswered)
                    .putInt(KEY_GOAL, summary.dailyGoal)
                    .putInt(KEY_STREAK, summary.streak)
                    .apply()
                WordDrillWidget.updateAll(app)
            }
        }
    }
}
