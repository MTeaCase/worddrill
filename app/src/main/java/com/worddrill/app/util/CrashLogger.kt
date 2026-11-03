package com.worddrill.app.util

import android.content.Context
import android.os.Build
import com.worddrill.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录：把未捕获异常的堆栈写到应用私有目录，保留最近 5 份。
 *
 * 起因是有用户反馈「偶尔卡死后词库进度丢失」，但拿不到任何异常信息。
 * 这里不改默认行为（写完仍然交给系统处理），只是多留一份现场。
 */
object CrashLogger {

    private const val DIR = "crash"
    private const val KEEP = 5

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            // 交回系统默认处理，保持原有的崩溃行为
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp.txt")
        file.writeText(
            buildString {
                appendLine("时间：$stamp")
                appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("线程：${thread.name}")
                appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine()
                appendLine(error.stackTraceToString())
            },
        )
        trim(dir)
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(KEEP).forEach { it.delete() }
    }

    /** 最近一份崩溃日志的内容，没有则返回 null。 */
    fun latest(context: Context): String? {
        val dir = File(context.applicationContext.filesDir, DIR)
        val newest = dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return runCatching { newest.readText() }.getOrNull()
    }

    fun latestTime(context: Context): Long {
        val dir = File(context.applicationContext.filesDir, DIR)
        return dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.maxOfOrNull { it.lastModified() }
            ?: 0L
    }
}
