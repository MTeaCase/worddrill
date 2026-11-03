package com.worddrill.app.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.worddrill.app.R
import com.worddrill.app.ui.MainActivity
import com.worddrill.app.util.Settings
import com.worddrill.app.util.WidgetUpdater

/** 桌面小组件：今日进度 + 连续打卡天数，点击进入首页。 */
class WordDrillWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    companion object {

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, WordDrillWidget::class.java))
            if (ids.isEmpty()) return
            ids.forEach { id ->
                manager.updateAppWidget(id, buildViews(context))
            }
        }

        fun buildViews(context: Context): RemoteViews {
            val prefs = context.getSharedPreferences(WidgetUpdater.PREFS, Context.MODE_PRIVATE)
            val today = prefs.getInt(WidgetUpdater.KEY_TODAY, 0)
            val goal = prefs.getInt(WidgetUpdater.KEY_GOAL, Settings.DEFAULT_GOAL).coerceAtLeast(1)
            val streak = prefs.getInt(WidgetUpdater.KEY_STREAK, 0)
            val due = prefs.getInt(WidgetUpdater.KEY_DUE, 0)
            val word = prefs.getString(WidgetUpdater.KEY_WORD, "").orEmpty()
            val meaning = prefs.getString(WidgetUpdater.KEY_WORD_MEANING, "").orEmpty()

            val views = RemoteViews(context.packageName, R.layout.widget_worddrill)
            views.setTextViewText(R.id.tvWidgetProgress, "$today / $goal")
            views.setProgressBar(R.id.pbWidget, goal, today.coerceIn(0, goal), false)
            views.setTextViewText(
                R.id.tvWidgetStreak,
                if (streak > 0) "连续 $streak 天" else "今天开始打卡",
            )
            views.setTextViewText(
                R.id.tvWidgetDue,
                if (due > 0) "待复习 $due 词" else "今天没有到期复习",
            )
            views.setTextViewText(
                R.id.tvWidgetWord,
                if (word.isNotBlank()) "今日一词：$word  $meaning" else "",
            )

            val intent = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pending)
            return views
        }
    }
}
