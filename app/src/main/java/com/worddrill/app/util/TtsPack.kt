package com.worddrill.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.worddrill.app.BuildConfig
import com.worddrill.app.R
import com.worddrill.app.databinding.DialogUpdateProgressBinding
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 离线语音包（独立安装的 TTS 引擎 App）。
 *
 * 为什么是「另一个 App」而不是主包里带原生库：
 * Android 10 起不允许从可写目录 dlopen 原生库，sherpa-onnx 的 .so 必须打进某个 APK 的
 * lib/ 里才能加载。所以它做成一个标准 TTS 引擎（com.worddrill.tts），主包只负责
 * 「下载 → 校验 → 拉起安装器 → 记状态」这几件事，主包体积几乎不变。
 *
 * 清单 tts-engine.json 与更新清单同一套路（Gitee raw 优先、CF Pages 兜底），
 * 这样引擎单独升级不用发主 App 版本。
 */
object TtsPack {

    /** 引擎 APK 的包名，改这里要同步改 Tts.kt 和主 App 的 <queries>。 */
    const val ENGINE_PACKAGE = "com.worddrill.tts"

    private const val PREFS = "tts_pack"
    private const val KEY_LAST_CHECK = "last_check"
    private const val CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(2)

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val size: Long = 0L,
        val md5: String = "",
        val content: String = "",
    )

    /** 设备的 ABI 能不能跑它；32 位机器直接不给入口，免得装个用不了的包。 */
    fun isSupported(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    /** 已安装的版本号；没装返回 0。 */
    fun installedVersion(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(ENGINE_PACKAGE, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(0)

    fun isInstalled(context: Context): Boolean = installedVersion(context) > 0

    /** 设置页那一行的右侧文案。 */
    fun label(context: Context, newest: Info?): String {
        if (!isSupported()) return context.getString(R.string.tts_pack_unsupported)
        val installed = installedVersion(context)
        return when {
            newest != null && newest.versionCode > installed -> context.getString(
                R.string.tts_pack_outdated,
                newest.versionName,
            )
            installed > 0 -> context.getString(R.string.tts_pack_installed, versionName(context))
            else -> context.getString(R.string.tts_pack_not_installed)
        }
    }

    private fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(ENGINE_PACKAGE, 0)
        info.versionName.orEmpty()
    }.getOrDefault("")

    /** 已安装版本的显示名（如 1.0.0）；没装返回空串。 */
    fun installedName(context: Context): String = versionName(context)

    /**
     * 拉清单拿最新版。失败就返回 null（设置页只显示本地状态，不打扰用户）。
     * 两小时内重复进设置页不重复请求。
     */
    fun newest(context: Context, force: Boolean = false): Info? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!force && System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return null
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

        val urls = BuildConfig.TTS_ENGINE_MANIFEST
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.startsWith("http") }
        var best: Info? = null
        for (url in urls) {
            val info = runCatching { fetchOne(url) }.getOrNull() ?: continue
            if (best == null || info.versionCode > best.versionCode) best = info
        }
        // 拉成功才记时间：全挂了下次进来还能重试
        if (best != null) prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        return best
    }

    private fun fetchOne(url: String): Info? {
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
            parse(text)
        } finally {
            connection.disconnect()
        }
    }

    /** 解析清单；抽出来是为了能在单测里跑，不依赖网络。 */
    internal fun parse(text: String): Info? = runCatching {
        val json = JSONObject(text)
        val code = json.optInt("versionCode", 0)
        val downloadUrl = json.optString("url", "")
        if (code <= 0 || downloadUrl.isBlank()) return null
        Info(
            versionCode = code,
            versionName = json.optString("versionName", code.toString()),
            url = downloadUrl,
            size = json.optLong("size", 0L),
            md5 = json.optString("md5", ""),
            content = json.optString("content", ""),
        )
    }.getOrNull()

    // ---------------------------------------------------------------- 交互

    /** 点「离线语音包」这一行时的弹窗。 */
    fun showDialog(activity: AppCompatActivity, newest: Info?, onChanged: () -> Unit) {
        val installed = installedVersion(activity)
        val supported = isSupported()

        if (!supported) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.tts_pack_title)
                .setMessage(R.string.tts_pack_message_unsupported)
                .setPositiveButton(R.string.update_ok, null)
                .show()
            return
        }

        if (installed <= 0) {
            if (newest == null) {
                // 拉不到清单就不猜地址：直接说明连不上，避免下到一个 HTML 或过期包
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.tts_pack_title)
                    .setMessage(R.string.tts_pack_no_manifest)
                    .setPositiveButton(R.string.update_ok, null)
                    .show()
                return
            }
            val size = newest.size.takeIf { it > 0 }?.let { ApkDownloader.formatSize(it) }
            val message = buildString {
                append(activity.getString(R.string.tts_pack_message_not_installed))
                if (size != null) append("\n\n").append(activity.getString(R.string.tts_pack_size, size))
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.tts_pack_title)
                .setMessage(message)
                .setNegativeButton(R.string.update_cancel, null)
                .setPositiveButton(R.string.tts_pack_download) { _, _ ->
                    download(activity, newest, onChanged)
                }
                .show()
            return
        }

        val hasUpdate = newest != null && newest.versionCode > installed
        val message = buildString {
            append(
                activity.getString(
                    R.string.tts_pack_message_installed,
                    versionName(activity),
                ),
            )
            if (hasUpdate && newest != null) {
                append("\n\n").append(activity.getString(R.string.tts_pack_message_outdated, newest.versionName))
            }
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.tts_pack_title)
            .setMessage(message)
            .setNegativeButton(R.string.update_ok, null)
            .setNeutralButton(R.string.tts_pack_uninstall) { _, _ -> uninstall(activity) }
        if (hasUpdate && newest != null) {
            builder.setPositiveButton(activity.getString(R.string.tts_pack_update, newest.versionName)) { _, _ ->
                download(activity, newest, onChanged)
            }
        } else {
            builder.setPositiveButton(R.string.tts_pack_test) { _, _ ->
                Tts.refreshEngine()
                Tts.speak(activity, "hello, this is a test") { reason -> toast(activity, reason) }
            }
        }
        builder.show()
    }

    /** 下载 + MD5/包名校验 + 拉起系统安装器。流程和自动更新完全一致。 */
    fun download(activity: AppCompatActivity, info: Info, onChanged: () -> Unit) {
        if (info.url.isBlank()) {
            toast(activity, activity.getString(R.string.update_no_url))
            return
        }
        val dir = apkDir(activity)
        if (dir == null) {
            toast(activity, activity.getString(R.string.update_no_storage))
            return
        }
        runCatching { dir.listFiles()?.forEach { if (it.isFile) it.delete() } }

        val safeName = info.versionName.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val target = File(dir, "WordDrill-tts-engine-$safeName.apk")

        val view = DialogUpdateProgressBinding.inflate(activity.layoutInflater)
        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.tts_pack_downloading))
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
                    val error = ApkInstaller.verify(
                        activity,
                        apk,
                        info.md5,
                        info.versionCode,
                        expectedPackage = ENGINE_PACKAGE,
                    )
                    if (error != null) {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.update_apk_invalid_title)
                            .setMessage(error)
                            .setPositiveButton(R.string.update_ok, null)
                            .show()
                        return@onSuccess
                    }
                    toast(activity, activity.getString(R.string.tts_pack_installing))
                    ApkInstaller.install(activity, apk)
                    onChanged()
                }.onFailure { error ->
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.update_download_failed_title)
                        .setMessage(
                            activity.getString(
                                R.string.update_download_failed,
                                error.message ?: "未知错误",
                            ),
                        )
                        .setPositiveButton(R.string.update_ok, null)
                        .show()
                }
            }
        }, "worddrill-tts-pack")
        worker.isDaemon = true
        worker.start()
    }

    fun uninstall(activity: Activity) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$ENGINE_PACKAGE"))
        runCatching { activity.startActivity(intent) }
            .onFailure { toast(activity, activity.getString(R.string.tts_pack_uninstall_failed)) }
    }

    private fun apkDir(context: Context): File? {
        val base = context.getExternalFilesDir("apk") ?: File(context.filesDir, "apk")
        if (base.exists()) return base
        return if (base.mkdirs()) base else null
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}