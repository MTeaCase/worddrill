package com.worddrill.app.util

import android.content.Context
import com.worddrill.app.data.WordRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 自动备份：每 6 小时把当前数据导出一份到应用私有目录，最多保留 3 份。
 *
 * 起因是有用户反馈「闪退后词库进度丢失」——如果数据库被系统判定损坏、或者用户在
 * 异常状态下清过记录，至少还能从这份快照里捞回来。
 *
 * 两个细节：写入走「临时文件 → rename」，断电时不会留下半截 JSON；
 * 备份成功的时刻才记 last_at，失败的话下次打开应用会重试。
 */
object AutoBackup {

    private const val DIR = "autobackup"
    private const val PREFIX = "backup-"
    private const val KEEP = 3
    private const val INTERVAL = 6L * 60 * 60 * 1000
    private const val FORMAT = "worddrill-backup"

    private const val PREFS = "autobackup"
    private const val KEY_LAST = "last_at"

    /** 距上次备份超过 6 小时就再存一份；失败静默，不影响正常使用。 */
    fun maybeRun(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST, 0L)
        if (System.currentTimeMillis() - last < INTERVAL) return

        Bg.run({
            val json = WordRepository(app).exportBackupJson()
            val dir = File(app.filesDir, DIR).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val target = File(dir, "$PREFIX$stamp.json")
            val tmp = File(dir, "$PREFIX$stamp.json.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(target)) {
                // 有些文件系统 rename 会失败，退化成复制；失败就整份丢掉，不留半截
                runCatching { tmp.copyTo(target, overwrite = true) }
                tmp.delete()
            }
            trim(dir)
            json.length
        }) { result ->
            result.onSuccess {
                // 成功才记时间：失败时下次打开应用就重试，不用等 6 小时
                prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            }
        }
    }

    /** 最新一份可用的自动备份内容，没有则返回 null。坏掉的文件会跳过。 */
    fun latest(context: Context): String? {
        for (file in snapshotFiles(context)) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (isValidBackup(text)) return text
        }
        return null
    }

    fun latestTime(context: Context): Long =
        snapshotFiles(context).maxOfOrNull { it.lastModified() } ?: 0L

    /** 按时间倒序的完整快照（排除写了一半的 .tmp）。 */
    private fun snapshotFiles(context: Context): List<File> {
        val dir = File(context.applicationContext.filesDir, DIR)
        return dir.listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 只认自己的备份格式，避免把半截/乱码文件当成备份恢复。 */
    private fun isValidBackup(text: String): Boolean = runCatching {
        val root = JSONObject(text)
        root.optString("format") == FORMAT && root.optInt("version", 0) >= 1
    }.getOrDefault(false)

    private fun trim(dir: File) {
        val files = dir.listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(KEEP).forEach { it.delete() }
    }
}