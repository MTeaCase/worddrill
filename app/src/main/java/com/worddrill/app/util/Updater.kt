package com.worddrill.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.worddrill.app.BuildConfig
import com.worddrill.app.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本检查。更新清单放在 Cloudflare Pages / Workers 上（国内可直连），格式：
 *
 * ```json
 * {
 *   "versionCode": 5,
 *   "versionName": "1.2.0",
 *   "content": "1. 新增 xxx\n2. 修复 yyy",
 *   "url": "https://xxx.pages.dev/WordDrill-1.2.0.apk"
 * }
 * ```
 *
 * 只在启动时（6 小时间隔）和设置页手动点击时访问，不做任何其他网络请求。
 */
object Updater {

    private const val PREFS = "updater"
    private const val KEY_LAST_CHECK = "last_check"
    private const val SIX_HOURS = 6L * 60 * 60 * 1000

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val content: String,
        val url: String,
    )

    fun autoCheck(activity: AppCompatActivity) {
        if (activity.isFinishing) return
        // Robolectric 单测里没有网络，跳过以免拖慢/干扰测试
        if (android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < SIX_HOURS) return
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        check(activity, manual = false)
    }

    fun check(activity: AppCompatActivity, manual: Boolean) {
        val url = BuildConfig.UPDATE_URL
        if (url.isBlank()) {
            if (manual) dialog(activity, "无法检查更新", activity.getString(R.string.update_no_url))
            return
        }
        if (manual) toast(activity, activity.getString(R.string.update_checking))
        Bg.run({ fetch(url) }) { result ->
            if (activity.isFinishing || activity.isDestroyed) return@run
            result.onSuccess { info ->
                val current = appVersionCode(activity)
                when {
                    info != null && info.versionCode > current -> showUpdate(activity, info)
                    manual -> dialog(activity, "检查更新", activity.getString(R.string.update_latest))
                }
            }.onFailure { error ->
                if (manual) {
                    dialog(
                        activity,
                        "检查更新失败",
                        activity.getString(R.string.update_failed, error.message ?: "网络不可用"),
                    )
                }
            }
        }
    }

    private fun showUpdate(activity: AppCompatActivity, info: UpdateInfo) {
        val content = info.content.ifBlank { "建议更新到最新版本。" }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_found_title, info.versionName))
            .setMessage(content)
            .setPositiveButton(R.string.update_download) { _, _ -> openBrowser(activity, info.url) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun openBrowser(activity: AppCompatActivity, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { activity.startActivity(intent) }
            .onFailure { toast(activity, "没有可用的浏览器") }
    }

    private fun fetch(url: String): UpdateInfo? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WordDrill/${BuildConfig.VERSION_NAME}")
        }
        return try {
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            val code = json.optInt("versionCode", 0)
            val downloadUrl = json.optString("url", "")
            if (code <= 0 || downloadUrl.isBlank()) return null
            UpdateInfo(
                versionCode = code,
                versionName = json.optString("versionName", code.toString()),
                content = json.optString("content", "").replace("\\n", "\n"),
                url = downloadUrl,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun appVersionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionCode
    }.getOrDefault(0)

    private fun dialog(activity: AppCompatActivity, title: String, message: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun toast(activity: AppCompatActivity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
