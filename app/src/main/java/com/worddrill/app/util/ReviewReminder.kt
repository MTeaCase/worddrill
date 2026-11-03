package com.worddrill.app.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.worddrill.app.R
import com.worddrill.app.data.WordRepository
import com.worddrill.app.ui.MainActivity
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 每日复习提醒。
 *
 * 间隔重复只有在"到点有人来练"时才有效，所以每天固定时间用 WorkManager 查一次到期词数，
 * 有到期单词才发通知，点通知直接进首页的复习入口。
 */
object ReviewReminder {

    const val CHANNEL_ID = "worddrill_review"
    private const val CHANNEL_NAME = "复习提醒"
    private const val WORK_NAME = "worddrill-review-reminder"
    private const val NOTIFICATION_ID = 1001

    const val EXTRA_OPEN_REVIEW = "open_review"

    /** 开启提醒：按设置里的小时排一个每天一次的任务。 */
    fun schedule(context: Context) {
        if (!Settings.reminderEnabled(context)) {
            cancel(context)
            return
        }
        val request = PeriodicWorkRequestBuilder<ReviewReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(millisUntilNext(Settings.reminderHour(context)), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    /** 距下一个整点的毫秒数；如果今天这个点还没到就取今天。 */
    internal fun millisUntilNext(hour: Int, now: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis - now
    }

    /** 通知权限是否可用（Android 13+ 需要运行时授权）。 */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** 有到期单词就发一条通知。 */
    fun show(context: Context, due: Int) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "每天提醒你复习即将遗忘的单词" }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_REVIEW, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("该复习了")
            .setContentText("今天有 $due 个单词到期，按遗忘曲线该见一面了")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}

/** 后台任务：查一次到期词数，有就提醒。 */
class ReviewReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val due = runCatching { WordRepository(applicationContext).dueReviewCount() }.getOrDefault(0)
        if (due > 0) ReviewReminder.show(applicationContext, due)
        return Result.success()
    }
}
