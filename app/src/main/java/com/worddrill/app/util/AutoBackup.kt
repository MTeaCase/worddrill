package com.worddrill.app.util

import android.content.Context
import com.worddrill.app.data.WordRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 自动备份：定时把当前数据导出一份到应用私有目录，默认每 6 小时、保留 3 份。
 *
 * 写入走「临时文件 → rename」，断电时不会留下半截 JSON；备份成功的时刻才记 last_at，
 * 失败的话下次打开应用会重试。开关、间隔、份数都在「我的 → 数据」里可调。
 */
object AutoBackup {

    private const val DIR = "autobackup"
    private const val PREFIX = "backup-"
    private const val FORMAT = "worddrill-backup"

    private const val PREFS = "autobackup"
    private const val KEY_LAST = "last_at"

    /** 距上次备份超过设定间隔就再存一份；失败静默，不影响正常使用。 */
    fun maybeRun(context: Context) {
        val app = context.applicationContext
        if (!Settings.autoBackupEnabled(app)) return
        val hours = Settings.backupIntervalHours(app)
        // 0 = 不定时写入，只保留手动入口
        if (hours <= 0) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST, 0L)
        if (System.currentTimeMillis() - last < hours * 60L * 60 * 1000) return
        snapshot(app, previousLast = last, onDone = null)
    }

    /** 设置页的「立即存一份快照」。 */
    fun snapshotNow(context: Context, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        val last = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)
        snapshot(app, previousLast = last, onDone = onDone)
    }

    private fun snapshot(app: Context, previousLast: Long, onDone: ((Boolean) -> Unit)?) {
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
            trim(dir, Settings.backupKeepCount(app))
            json.length
        }) { result ->
            val ok = result.isSuccess
            if (ok) {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                // 成功才记时间；失败时下次打开应用就重试，不用等一个间隔
                val stamp = maxOf(previousLast, System.currentTimeMillis())
                prefs.edit().putLong(KEY_LAST, stamp).apply()
            }
            onDone?.invoke(ok)
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

    fun count(context: Context): Int = snapshotFiles(context).size

    /** 清空全部快照，下次自动备份会重新开始计时。 */
    fun clearAll(context: Context) {
        val app = context.applicationContext
        runCatching { File(app.filesDir, DIR).listFiles()?.forEach { it.delete() } }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST).apply()
    }

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

    private fun trim(dir: File, keep: Int) {
        val files = dir.listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(keep.coerceAtLeast(1)).forEach { it.delete() }
    }
}