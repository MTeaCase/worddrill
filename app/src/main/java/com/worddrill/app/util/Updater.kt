package com.worddrill.app.util

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.worddrill.app.BuildConfig
import com.worddrill.app.R
import com.worddrill.app.databinding.DialogUpdateProgressBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本检查 + 自动下载安装。
 *
 * 更新清单放在 Cloudflare Pages / Workers 上（国内可直连），格式：
 *
 * ```json
 * {
 *   "versionCode": 5,
 *   "versionName": "1.2.0",
 *   "content": "1. 新增 xxx\n2. 修复 yyy",
 *   "url": "https://xxx.pages.dev/WordDrill-1.2.0.apk",
 *   "size": 5333256,
 *   "md5": "6f8c35df3c75ee8dca244d5ceb8d1a78"
 * }
 * ```
 *
 * `size` / `md5` 可选，填了会在安装前校验，防止下载到 Cloudflare 的错误页或残缺文件。
 *
 * 流程：启动时自动检查（6 小时内只查一次）→ 弹窗 → 点「立即更新」→ 应用内下载
 * （带进度条，可取消）→ 校验 → 交给系统安装器（Android 8+ 会先引导授权「安装未知应用」）。
 */
object Updater {

    private const val PREFS = "updater"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIP_VERSION = "skip_version"
    private const val SIX_HOURS = 6L * 60 * 60 * 1000

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val content: String,
        val url: String,
        val size: Long = 0L,
        val md5: String = "",
    )

    /** 启动时调用：6 小时内只检查一次，失败静默。 */
    fun autoCheck(activity: AppCompatActivity) {
        if (activity.isFinishing) return
        // Robolectric 单测里没有网络，跳过以免拖慢/干扰测试
        if (android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) return
        val prefs = prefs(activity)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < SIX_HOURS) return
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        check(activity, manual = false)
    }

    /** 检查更新。[manual] = true 时（设置页按钮）失败/已是最新都会给提示。 */
    fun check(activity: AppCompatActivity, manual: Boolean) {
        if (activity.isFinishing) return
        val urls = updateUrls()
        if (urls.isEmpty()) {
            if (manual) dialog(activity, activity.getString(R.string.update_title), activity.getString(R.string.update_no_url))
            return
        }
        if (manual) toast(activity, activity.getString(R.string.update_checking))
        Bg.run({ fetch(urls) }) { result ->
            if (activity.isFinishing || activity.isDestroyed) return@run
            result.onSuccess { info ->
                val current = appVersionCode(activity)
                when {
                    info != null && info.versionCode > current -> {
                        // 用户上次选过「以后再说」的版本，自动检查时不再打扰；手动检查仍然提示
                        val skipped = prefs(activity).getInt(KEY_SKIP_VERSION, 0)
                        if (manual || skipped < info.versionCode) showUpdate(activity, info)
                    }
                    manual -> dialog(
                        activity,
                        activity.getString(R.string.update_title),
                        activity.getString(R.string.update_latest, BuildConfig.VERSION_NAME),
                    )
                }
            }.onFailure { error ->
                if (manual) {
                    dialog(
                        activity,
                        activity.getString(R.string.update_check_failed_title),
                        activity.getString(R.string.update_failed, error.message ?: "网络不可用"),
                    )
                }
            }
        }
    }

    private fun showUpdate(activity: AppCompatActivity, info: UpdateInfo) {
        val body = StringBuilder()
        if (info.size > 0) body.append(activity.getString(R.string.update_size, ApkDownloader.formatSize(info.size))).append('\n')
        body.append(info.content.ifBlank { activity.getString(R.string.update_default_content) })
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_found_title, info.versionName))
            .setMessage(body.toString().trim())
            .setPositiveButton(R.string.update_download) { _, _ -> startDownload(activity, info) }
            .setNegativeButton(R.string.update_later) { _, _ ->
                prefs(activity).edit().putInt(KEY_SKIP_VERSION, info.versionCode).apply()
            }
            .show()
    }

    /** 应用内下载 + 校验 + 拉起安装器。 */
    private fun startDownload(activity: AppCompatActivity, info: UpdateInfo) {
        val dir = apkDir(activity)
        if (dir == null) {
            dialog(activity, activity.getString(R.string.update_download_failed_title), activity.getString(R.string.update_no_storage))
            return
        }
        // 清掉上次残留的包
        runCatching { dir.listFiles()?.forEach { file -> if (file.isFile) file.delete() } }

        val safeName = info.versionName.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val target = File(dir, "WordDrill-$safeName.apk")

        val view = DialogUpdateProgressBinding.inflate(activity.layoutInflater)
        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_downloading, info.versionName))
            .setView(view.root)
            .setNegativeButton(R.string.update_cancel, null)
            .setCancelable(false)
            .create()

        val downloader = ApkDownloader(info.url, target)
        var cancelled = false

        progressDialog.setOnShowListener {
            progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                cancelled = true
                downloader.cancel()
                view.tvProgress.text = activity.getString(R.string.update_cancelled)
                progressDialog.dismiss()
            }
        }
        progressDialog.show()
        view.tvProgress.text = activity.getString(R.string.update_connecting)

        // 单独开线程：Bg 的线程是数据库专用的，几 MB 的下载不能堵住它
        val worker = Thread({
            val result = runCatching {
                downloader.download { percent, done, total ->
                    Bg.post {
                        if (activity.isFinishing || activity.isDestroyed || !progressDialog.isShowing) return@post
                        if (percent >= 0) {
                            view.progress.setProgressCompat(percent, true)
                            view.tvProgress.text = activity.getString(
                                R.string.update_progress,
                                percent,
                                ApkDownloader.formatSize(done),
                                ApkDownloader.formatSize(total),
                            )
                        } else {
                            view.tvProgress.text = activity.getString(
                                R.string.update_progress_unknown,
                                ApkDownloader.formatSize(done),
                            )
                        }
                    }
                }
                target
            }
            Bg.post {
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (cancelled || activity.isFinishing || activity.isDestroyed) return@post
                result.onSuccess { apk ->
                    val error = ApkInstaller.verify(activity, apk, info.md5, info.versionCode)
                    if (error != null) {
                        dialog(activity, activity.getString(R.string.update_apk_invalid_title), error)
                        return@post
                    }
                    toast(activity, activity.getString(R.string.update_installing_hint))
                    ApkInstaller.install(activity, apk)
                }.onFailure { error ->
                    dialog(
                        activity,
                        activity.getString(R.string.update_download_failed_title),
                        activity.getString(R.string.update_download_failed, error.message ?: "未知错误"),
                    )
                }
            }
        }, "worddrill-update")
        worker.isDaemon = true
        worker.start()
    }

    /** 下载目录：外部私有目录优先（不需要任何存储权限），不可用时退回内部存储。 */
    private fun apkDir(context: Context): File? {
        val base = context.getExternalFilesDir("apk") ?: File(context.filesDir, "apk")
        if (base.exists()) return base
        return if (base.mkdirs()) base else null
    }

    /**
     * 更新清单地址，允许在 gradle 里用逗号分隔配多个镜像，按顺序尝试。
     * 例如 Cloudflare Pages 主地址 + 一个备用地址。
     */
    private fun updateUrls(): List<String> = BuildConfig.UPDATE_URL
        .split(',', ';', '\n')
        .map { it.trim() }
        .filter { it.startsWith("http") }

    private fun fetch(urls: List<String>): UpdateInfo? {
        var lastError: Throwable? = null
        for (url in urls) {
            try {
                fetchOne(url)?.let { return it }
            } catch (error: Throwable) {
                lastError = error
            }
        }
        lastError?.let { throw it }
        return null
    }

    private fun fetchOne(url: String): UpdateInfo? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WordDrill/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseManifest(text)
        } finally {
            connection.disconnect()
        }
    }

    /** 解析更新清单。抽出来是为了能在单测里跑，不依赖网络。 */
    internal fun parseManifest(text: String): UpdateInfo? = runCatching {
        val json = JSONObject(text)
        val code = json.optInt("versionCode", 0)
        val downloadUrl = json.optString("url", "")
        if (code <= 0 || downloadUrl.isBlank()) return null
        UpdateInfo(
            versionCode = code,
            versionName = json.optString("versionName", code.toString()),
            content = content(json),
            url = downloadUrl,
            size = json.optLong("size", 0L),
            md5 = json.optString("md5", ""),
        )
    }.getOrNull()

    /** content 既可以是字符串，也可以是字符串数组。 */
    private fun content(json: JSONObject): String {
        val array = json.optJSONArray("content")
        if (array == null) return json.optString("content", "").replace("\\n", "\n")
        return (0 until array.length()).joinToString("\n") { array.optString(it, "") }
    }

    private fun appVersionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionCode
    }.getOrDefault(0)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dialog(activity: AppCompatActivity, title: String, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.update_ok, null)
            .show()
    }

    private fun toast(activity: AppCompatActivity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
