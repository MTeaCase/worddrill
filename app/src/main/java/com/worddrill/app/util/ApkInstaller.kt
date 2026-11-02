package com.worddrill.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.worddrill.app.R
import java.io.File

/**
 * APK 安装器。
 *
 * 这套流程照 XUpdate 的 Android 8+ 实现来：
 *  1. Android 7.0+ 不能再把 file:// 交给安装器，必须用 FileProvider 生成 content://
 *  2. Android 8.0+ 需要用户授予「安装未知应用」权限（REQUEST_INSTALL_PACKAGES），
 *     没授权时跳到系统的 ACTION_MANAGE_UNKNOWN_APP_SOURCES 设置页
 *  3. 从设置页返回后（Activity onResume）自动继续安装，不用用户再点一次
 */
object ApkInstaller {

    const val MIME_APK = "application/vnd.android.package-archive"

    /** 已经下载好、正在等用户去授权安装的包 */
    private var pending: File? = null

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** 是否还缺「安装未知应用」权限（Android 8.0 起才需要） */
    fun needsGrant(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()

    /**
     * 安装（或在缺权限时先引导授权）。
     * 缺权限时把包暂存起来，用户从设置页回来后由 [onActivityResume] 自动继续。
     */
    fun install(activity: Activity, file: File) {
        if (!file.isFile || file.length() <= 0L) {
            toast(activity, activity.getString(R.string.update_apk_missing))
            return
        }
        if (needsGrant(activity)) {
            pending = file
            showGrantDialog(activity)
            return
        }
        doInstall(activity, file)
    }

    /** 在 Activity.onResume 里调用：授权回来后接着装。 */
    fun onActivityResume(activity: Activity) {
        val file = pending ?: return
        if (needsGrant(activity)) return
        pending = null
        doInstall(activity, file)
    }

    fun cancelPending() {
        pending = null
    }

    private fun doInstall(activity: Activity, file: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(activity, authority(activity), file)
        }.getOrNull()
        if (uri == null) {
            toast(activity, activity.getString(R.string.update_apk_missing))
            return
        }

        // 少数 ROM 的安装器不会自动继承 FLAG_GRANT_READ_URI_PERMISSION，这里显式授权一遍
        for (pkg in INSTALLER_PACKAGES) {
            runCatching {
                activity.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
        }
        runCatching { activity.startActivity(intent) }
            .onFailure {
                toast(activity, activity.getString(R.string.update_install_no_installer))
            }
    }

    private fun showGrantDialog(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_grant_title)
            .setMessage(R.string.update_grant_message)
            .setPositiveButton(R.string.update_grant_go) { _, _ -> openUnknownSourcesSettings(activity) }
            .setNegativeButton(R.string.update_grant_cancel) { _, _ -> cancelPending() }
            .setCancelable(false)
            .show()
    }

    private fun openUnknownSourcesSettings(activity: Activity) {
        val packageUri = Uri.parse("package:${activity.packageName}")
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri))
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in candidates) {
            val opened = runCatching {
                activity.startActivity(intent)
                true
            }.getOrDefault(false)
            if (opened) return
        }
        toast(activity, activity.getString(R.string.update_grant_no_settings))
    }

    /**
     * 安装包校验，返回错误描述；通过校验返回 null。
     * 主要用来挡住「下载到的是 Cloudflare 错误页 / 文件被截断 / 别人的包」。
     */
    fun verify(
        context: Context,
        file: File,
        expectedMd5: String?,
        minVersionCode: Int,
    ): String? {
        if (!file.isFile || file.length() <= 0L) return "文件为空"
        if (!ApkDownloader.looksLikeApk(file)) return "不是有效的 APK 文件"

        if (!expectedMd5.isNullOrBlank()) {
            val actual = ApkDownloader.md5(file)
            if (!actual.equals(expectedMd5.trim(), ignoreCase = true)) {
                return "MD5 校验失败（期望 $expectedMd5，实际 $actual）"
            }
        }

        val info = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return "无法读取安装包信息"

        if (info.packageName != context.packageName) {
            return "安装包包名是 ${info.packageName}，与本机 ${context.packageName} 不一致"
        }

        @Suppress("DEPRECATION")
        val code = info.versionCode
        if (minVersionCode > 0 && code < minVersionCode) {
            return "安装包版本是 $code，低于更新清单里的 $minVersionCode"
        }
        return null
    }

    /** 常见机型的系统安装器包名，用于显式授予读取 APK 的权限 */
    private val INSTALLER_PACKAGES = listOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.package_installer",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.oppo.packageinstaller",
        "com.vivo.packageinstaller",
        "com.huawei.packageinstaller",
    )

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
