package com.worddrill.app.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.worddrill.app.R
import com.worddrill.app.ui.DictionaryActivity
import com.worddrill.app.ui.MainActivity
import com.worddrill.app.util.ReviewReminder
import com.worddrill.app.util.WidgetUpdater

/** 读小组件缓存（WidgetUpdater 写进去的），widget 进程里不查数据库。 */
private object WidgetCache {
    fun today(context: Context) = prefs(context).getInt(WidgetUpdater.KEY_TODAY, 0)
    fun goal(context: Context) = prefs(context).getInt(WidgetUpdater.KEY_GOAL, 30).coerceAtLeast(1)
    fun streak(context: Context) = prefs(context).getInt(WidgetUpdater.KEY_STREAK, 0)
    fun due(context: Context) = prefs(context).getInt(WidgetUpdater.KEY_DUE, 0)
    fun word(context: Context) = prefs(context).getString(WidgetUpdater.KEY_WORD, "").orEmpty()
    fun meaning(context: Context) =
        prefs(context).getString(WidgetUpdater.KEY_WORD_MEANING, "").orEmpty()
    fun phonetic(context: Context) =
        prefs(context).getString(WidgetUpdater.KEY_WORD_PHONETIC, "").orEmpty()
    fun phrase(context: Context) =
        prefs(context).getString(WidgetUpdater.KEY_WORD_PHRASE, "").orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(WidgetUpdater.PREFS, Context.MODE_PRIVATE)
}

private fun clickIntent(context: Context, intent: Intent, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** 今日一词：显示干扰词和释义，点击进词典。 */
class DailyWordWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, DailyWordWidget::class.java))
            ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_word)
            val word = WidgetCache.word(context)
            val meaning = WidgetCache.meaning(context)
            val phonetic = WidgetCache.phonetic(context)
            val phrase = WidgetCache.phrase(context)
            views.setTextViewText(R.id.tvWord, word.ifBlank { "—" })
            views.setTextViewText(
                R.id.tvWordMeaning,
                meaning.ifBlank { "打开 App 开始练习" },
            )
            views.setTextViewText(
                R.id.tvWordPhrase,
                if (phonetic.isBlank()) phrase else "/$phonetic/  $phrase".trim(),
            )
            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                clickIntent(context, Intent(context, DictionaryActivity::class.java), 101),
            )
            return views
        }
    }
}

/** 待复习：只显示到期数量，点击直接进复习页。 */
class DueReviewWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, DueReviewWidget::class.java))
            ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_due)
            val due = WidgetCache.due(context)
            views.setTextViewText(R.id.tvDueCount, due.toString())
            views.setTextViewText(
                R.id.tvDueHint,
                if (due > 0) "个单词待复习" else "今天已完成",
            )
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(ReviewReminder.EXTRA_OPEN_REVIEW, true)
            views.setOnClickPendingIntent(R.id.widgetRoot, clickIntent(context, intent, 102))
            return views
        }
    }
}

/** 连续打卡：只显示连续天数。 */
class StreakWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, StreakWidget::class.java))
            ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_streak)
            val streak = WidgetCache.streak(context)
            val today = WidgetCache.today(context)
            val goal = WidgetCache.goal(context)
            views.setTextViewText(R.id.tvStreakDays, "$streak")
            views.setTextViewText(
                R.id.tvStreakHint,
                when {
                    streak <= 0 -> "今天开始"
                    today >= goal -> "今日目标已完成"
                    else -> "今日 $today / $goal 题"
                },
            )
            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                clickIntent(context, Intent(context, MainActivity::class.java), 103),
            )
            return views
        }
    }
}

/** 统一刷新所有小组件。 */
object Widgets {
    fun updateAll(context: Context) {
        WordDrillWidget.updateAll(context)
        DailyWordWidget.updateAll(context)
        DueReviewWidget.updateAll(context)
        StreakWidget.updateAll(context)
    }
}
