package com.worddrill.app.util

import com.worddrill.app.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * 更新包下载器。
 *
 * 参照 XUpdate 的做法：把 APK 下载到应用自己的外部私有目录
 * （`Android/data/包名/files/apk/`），这样 Android 10+ 的分区存储下也不需要任何存储权限，
 * 下载完直接交给 FileProvider 以 content:// 形式交给系统安装器。
 */
class ApkDownloader(
    private val url: String,
    private val target: File,
) {

    @Volatile
    private var cancelled = false

    @Volatile
    private var connection: HttpURLConnection? = null

    /** 取消下载。可以从主线程调用，阻塞中的 download() 会抛 IOException 退出。 */
    fun cancel() {
        cancelled = true
        runCatching { connection?.disconnect() }
    }

    /**
     * 阻塞下载。[onProgress] 参数为（百分比，已下载字节，总字节），
     * 总长度未知时百分比为 -1。回调已经节流，不会刷爆主线程。
     *
     * @return 下载到的文件字节数
     */
    fun download(onProgress: (percent: Int, done: Long, total: Long) -> Unit): Long {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "WordDrill/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/octet-stream, */*")
        }
        connection = conn
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("服务器返回 $code")

            // contentLengthLong 需要 API 24，本应用 minSdk 24，可以直接用
            val total = conn.contentLengthLong
            val dir = target.parentFile
            if (dir != null && !dir.exists() && !dir.mkdirs()) throw IOException("无法创建下载目录")
            if (target.exists() && !target.delete()) throw IOException("无法清理旧文件")

            var done = 0L
            var lastPercent = -2
            var lastEmit = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) throw IOException("已取消下载")
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) ((done * 100) / total).toInt() else -1
                        val now = System.currentTimeMillis()
                        if (percent != lastPercent || now - lastEmit > 200) {
                            lastPercent = percent
                            lastEmit = now
                            onProgress(percent, done, total)
                        }
                    }
                    out.flush()
                }
            }
            if (cancelled) throw IOException("已取消下载")
            if (total > 0 && done != total) throw IOException("下载不完整（$done/$total 字节）")
            if (done <= 0L) throw IOException("下载内容为空")
            return done
        } finally {
            runCatching { conn.disconnect() }
            connection = null
        }
    }

    companion object {

        /** APK 是 zip 包，头四个字节固定是 PK\003\004；用来挡住「下载到的是网页」的情况。 */
        fun looksLikeApk(file: File): Boolean {
            if (!file.isFile || file.length() < 4) return false
            return runCatching {
                file.inputStream().use { input ->
                    val head = ByteArray(4)
                    if (input.read(head) != 4) return false
                    head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                }
            }.getOrDefault(false)
        }

        /** 计算文件 MD5（小写十六进制）。 */
        fun md5(file: File): String = runCatching {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val bytes = digest.digest()
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) sb.append(String.format(Locale.US, "%02x", b))
            sb.toString()
        }.getOrDefault("")

        /** 人类可读的文件大小，如 6.1 MB。 */
        fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
