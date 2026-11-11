package com.worddrill.app

import android.app.Application
import android.os.Build
import com.worddrill.app.util.CrashLogger
import com.worddrill.app.util.WidgetRefresh

/** 进程入口：装一个崩溃记录器，方便排查「闪退后数据丢失」这类问题。 */
class WordDrillApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // 每天零点后刷小组件，今日一词才会跨天换
        runCatching { WidgetRefresh.schedule(this) }
    }

    /** 顺带把设备信息暴露出去，写日志时用。 */
    val deviceSummary: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
