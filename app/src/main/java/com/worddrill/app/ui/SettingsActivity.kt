package com.worddrill.app.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.worddrill.app.BuildConfig
import com.worddrill.app.R
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivitySettingsBinding
import com.worddrill.app.util.ApkInstaller
import com.worddrill.app.util.Bg
import com.worddrill.app.util.ReviewReminder
import com.worddrill.app.util.Settings
import com.worddrill.app.util.Tts
import com.worddrill.app.util.Updater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置：单词朗读、备份与恢复、检查更新、关于。 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repo: WordRepository
    private var suppressSwitchEvent = false

    private val notifyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Settings.setReminderEnabled(this, granted)
            if (granted) {
                ReviewReminder.schedule(this)
            } else {
                toast("没有通知权限，无法提醒")
            }
            suppressSwitchEvent = true
            binding.swReminder.isChecked = granted
            suppressSwitchEvent = false
            refreshReminderText()
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { writeBackup(it) }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { readBackup(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        repo = WordRepository(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.swTts.isChecked = Settings.ttsEnabled(this)
        binding.swTts.setOnCheckedChangeListener { _, checked ->
            Settings.setTtsEnabled(this, checked)
            if (checked) Tts.speak(this, "hello")
        }

        binding.btnExport.setOnClickListener { startExport() }
        binding.btnImport.setOnClickListener { startImport() }
        binding.btnCheckUpdate.setOnClickListener { Updater.check(this, manual = true) }
        binding.btnSmartParseUrl.setOnClickListener { editSmartParseUrl() }
        binding.btnAbout.setOnClickListener { AboutDialog.show(this) }
        binding.btnPrivacy.setOnClickListener { startActivity(PrivacyActivity.intent(this)) }

        binding.swReminder.isChecked = Settings.reminderEnabled(this)
        refreshReminderText()
        binding.swReminder.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchEvent) return@setOnCheckedChangeListener
            onReminderToggled(checked)
        }
        binding.tvReminderTime.setOnClickListener { pickReminderTime() }

        binding.swExampleAi.isChecked = Settings.exampleAiEnabled(this)
        binding.swExampleAi.setOnCheckedChangeListener { _, checked ->
            Settings.setExampleAiEnabled(this, checked)
            toast(if (checked) "已开启，打开词条时会自动生成例句" else "已关闭，不再请求例句")
        }

        refreshSmartParseUrl()
        binding.tvVersion.text = "WordDrill ${BuildConfig.VERSION_NAME}"
    }

    private fun onReminderToggled(enabled: Boolean) {
        if (enabled && !ReviewReminder.canNotify(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                toast("系统里关掉了本应用的通知，去设置里打开后才能提醒")
                suppressSwitchEvent = true
                binding.swReminder.isChecked = false
                suppressSwitchEvent = false
                Settings.setReminderEnabled(this, false)
            }
            return
        }
        Settings.setReminderEnabled(this, enabled)
        if (enabled) {
            ReviewReminder.schedule(this)
            toast("已开启，每天 ${hourLabel()} 提醒")
        } else {
            ReviewReminder.cancel(this)
        }
        refreshReminderText()
    }

    private fun pickReminderTime() {
        if (!Settings.reminderEnabled(this)) {
            toast("先打开上面的开关")
            return
        }
        val hour = Settings.reminderHour(this)
        TimePickerDialog(this, { _, picked, _ ->
            Settings.setReminderHour(this, picked)
            ReviewReminder.schedule(this)
            refreshReminderText()
        }, hour, 0, true).show()
    }

    private fun refreshReminderText() {
        binding.tvReminderTime.text = if (Settings.reminderEnabled(this)) {
            getString(R.string.settings_reminder_on, hourLabel())
        } else {
            getString(R.string.settings_reminder_off)
        }
    }

    private fun hourLabel(): String = String.format(Locale.US, "%02d:00", Settings.reminderHour(this))

    override fun onResume() {
        super.onResume()
        // 从「安装未知应用」设置页返回后，继续未完成的安装
        ApkInstaller.onActivityResume(this)
    }

    private fun startExport() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        exportLauncher.launch("worddrill-backup-$stamp.json")
    }

    private fun refreshSmartParseUrl() {
        val current = Settings.smartParseUrl(this)
        val isDefault = current == BuildConfig.SMART_PARSE_URL
        binding.tvSmartParseUrl.text =
            if (isDefault) "默认：$current" else "$current（已自定义）"
    }

    /** 部署到 Netlify 后地址不一定固定，这里允许直接改，不用重新打包。 */
    private fun editSmartParseUrl() {
        val input = EditText(this).apply {
            setText(Settings.smartParseUrl(this@SettingsActivity))
            setSingleLine(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val density = resources.displayMetrics.density
        val container = FrameLayout(this).apply {
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_smart_parse)
            .setMessage("填 Netlify 上部署好的接口地址，例如\nhttps://你的站点.netlify.app/api/smart-parse")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                Settings.setSmartParseUrl(this, input.text.toString())
                refreshSmartParseUrl()
                toast("已保存")
            }
            .setNeutralButton("恢复默认") { _, _ ->
                Settings.setSmartParseUrl(this, "")
                refreshSmartParseUrl()
                toast("已恢复默认地址")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun writeBackup(uri: Uri) {
        Bg.run({
            val json = repo.exportBackupJson()
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: throw IllegalStateException("无法写入文件")
            json.length
        }) { result ->
            result.onSuccess { length ->
                toast("已导出备份（${length / 1024} KB）")
            }.onFailure { toast("导出失败：${it.message}") }
        }
    }

    private fun startImport() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_import)
            .setMessage("会把备份里的词库、错题、星标、打卡记录**合并**到本机，已有的单词不会被删除或覆盖。")
            .setPositiveButton("选择文件") { _, _ ->
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun readBackup(uri: Uri) {
        Bg.run({
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalStateException("无法读取文件")
            repo.importBackupJson(text)
        }) { result ->
            result.onSuccess { summary ->
                AlertDialog.Builder(this)
                    .setTitle("导入完成")
                    .setMessage(summary)
                    .setPositiveButton("好", null)
                    .show()
            }.onFailure { toast("导入失败：${it.message}") }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
