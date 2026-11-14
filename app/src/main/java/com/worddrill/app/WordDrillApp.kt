package com.worddrill.app

import android.app.Application
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.worddrill.app.util.CrashLogger
import com.worddrill.app.util.Settings
import com.worddrill.app.util.WidgetRefresh

/** 进程入口：装一个崩溃记录器，方便排查「闪退后数据丢失」这类问题。 */
class WordDrillApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 主题偏好要在任何 Activity 创建前设好，否则会先闪一下系统主题
        AppCompatDelegate.setDefaultNightMode(
            when (Settings.themeMode(this)) {
                Settings.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                Settings.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Settings.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
        CrashLogger.install(this)
        // 每天零点后刷小组件，今日一词才会跨天换
        runCatching { WidgetRefresh.schedule(this) }
    }

    /** 顺带把设备信息暴露出去，写日志时用。 */
    val deviceSummary: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
