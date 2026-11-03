package com.worddrill.app.util

import android.content.Context
import com.worddrill.app.data.WordRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动备份：每 6 小时把当前数据导出一份到应用私有目录，最多保留 3 份。
 *
 * 起因是有用户反馈「闪退后词库进度丢失」——如果数据库被系统判定损坏、或者用户在
 * 异常状态下清过记录，至少还能从这份快照里捞回来。
 */
object AutoBackup {

    private const val DIR = "autobackup"
    private const val PREFIX = "backup-"
    private const val KEEP = 3
    private const val INTERVAL = 6L * 60 * 60 * 1000

    private const val PREFS = "autobackup"
    private const val KEY_LAST = "last_at"

    /** 距上次备份超过 6 小时就再存一份；失败静默，不影响正常使用。 */
    fun maybeRun(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST, 0L)
        if (System.currentTimeMillis() - last < INTERVAL) return
        prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()

        Bg.run({
            val json = WordRepository(app).exportBackupJson()
            val dir = File(app.filesDir, DIR).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(dir, "$PREFIX$stamp.json").writeText(json)
            trim(dir)
            json.length
        }) { result ->
            result.onFailure { /* 备份失败不打扰用户 */ }
        }
    }

    /** 最新一份自动备份的内容，没有则返回 null。 */
    fun latest(context: Context): String? {
        val dir = File(context.applicationContext.filesDir, DIR)
        val newest = dir.listFiles { file -> file.name.startsWith(PREFIX) }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return runCatching { newest.readText() }.getOrNull()
    }

    fun latestTime(context: Context): Long {
        val dir = File(context.applicationContext.filesDir, DIR)
        return dir.listFiles { file -> file.name.startsWith(PREFIX) }
            ?.maxOfOrNull { it.lastModified() }
            ?: 0L
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { file -> file.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(KEEP).forEach { it.delete() }
    }
}
