package com.worddrill.app.ui.mine

import android.Manifest
import android.app.TimePickerDialog
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.worddrill.app.BuildConfig
import com.worddrill.app.R
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.FragmentMineBinding
import com.worddrill.app.ui.AboutDialog
import com.worddrill.app.ui.DictionaryActivity
import com.worddrill.app.ui.PrivacyActivity
import com.worddrill.app.util.AutoBackup
import com.worddrill.app.util.Bg
import com.worddrill.app.util.ReviewReminder
import com.worddrill.app.util.Settings
import com.worddrill.app.util.Tts
import com.worddrill.app.util.Updater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「我的」页：今日概览 + 全部设置。
 * 原来设置是单独的 SettingsActivity，和这里重复；现在统一放这一页，点开关就地生效，不再跳转。
 */
class MineFragment : Fragment() {

    private var _binding: FragmentMineBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: WordRepository
    private var suppressSwitchEvent = false

    private val notifyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Settings.setReminderEnabled(requireContext(), granted)
            if (granted) {
                ReviewReminder.schedule(requireContext())
            } else {
                toast("没有通知权限，无法提醒")
            }
            suppressSwitchEvent = true
            if (_binding != null) binding.swReminder.isChecked = granted
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = WordRepository(requireContext())

        binding.swTts.setOnCheckedChangeListener { _, checked ->
            Settings.setTtsEnabled(requireContext(), checked)
            if (checked) Tts.speak(requireContext(), "hello")
        }

        binding.swReminder.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchEvent) return@setOnCheckedChangeListener
            onReminderToggled(checked)
        }
        binding.tvReminderTime.setOnClickListener { pickReminderTime() }

        binding.swExampleAi.setOnCheckedChangeListener { _, checked ->
            Settings.setExampleAiEnabled(requireContext(), checked)
            toast(if (checked) "已开启，打开词条时会自动生成例句" else "已关闭，不再请求例句")
        }

        binding.btnExport.setOnClickListener { startExport() }
        binding.btnImport.setOnClickListener { startImport() }
        binding.btnRestoreAuto.setOnClickListener { restoreAutoBackup() }
        binding.btnClearLog.setOnClickListener { confirmClearLog() }
        binding.btnDictionary.setOnClickListener {
            startActivity(DictionaryActivity.intent(requireContext()))
        }
        binding.btnCheckUpdate.setOnClickListener { Updater.check(activity(), manual = true) }
        binding.btnSmartParseUrl.setOnClickListener { editSmartParseUrl() }
        binding.btnPrivacy.setOnClickListener { startActivity(PrivacyActivity.intent(requireContext())) }
        binding.btnLicense.setOnClickListener { startActivity(PrivacyActivity.licenseIntent(requireContext())) }
        binding.btnAbout.setOnClickListener { AboutDialog.show(activity()) }

        binding.tvVersion.text = "WordDrill ${BuildConfig.VERSION_NAME}"
        refreshSwitches()
        refreshSmartParseUrl()
        refreshAutoBackupTime()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) refresh()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun refresh() {
        refreshSwitches()
        refreshReminderText()
        refreshAutoBackupTime()
        refreshSmartParseUrl()
    }

    private fun activity(): AppCompatActivity = requireActivity() as AppCompatActivity

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun refreshSwitches() {
        val context = requireContext()
        suppressSwitchEvent = true
        binding.swTts.isChecked = Settings.ttsEnabled(context)
        binding.swReminder.isChecked = Settings.reminderEnabled(context)
        binding.swExampleAi.isChecked = Settings.exampleAiEnabled(context)
        suppressSwitchEvent = false
        refreshReminderText()
    }

    private fun onReminderToggled(enabled: Boolean) {
        val context = requireContext()
        if (enabled && !ReviewReminder.canNotify(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                toast("系统里关掉了本应用的通知，去设置里打开后才能提醒")
                suppressSwitchEvent = true
                binding.swReminder.isChecked = false
                suppressSwitchEvent = false
                Settings.setReminderEnabled(context, false)
            }
            return
        }
        Settings.setReminderEnabled(context, enabled)
        if (enabled) {
            ReviewReminder.schedule(context)
            toast("已开启，每天 ${hourLabel()} 提醒")
        } else {
            ReviewReminder.cancel(context)
        }
        refreshReminderText()
    }

    private fun pickReminderTime() {
        val context = requireContext()
        if (!Settings.reminderEnabled(context)) {
            toast("先打开上面的开关")
            return
        }
        TimePickerDialog(context, { _, picked, _ ->
            Settings.setReminderHour(context, picked)
            ReviewReminder.schedule(context)
            refreshReminderText()
        }, Settings.reminderHour(context), 0, true).show()
    }

    private fun refreshReminderText() {
        if (_binding == null) return
        binding.tvReminderTime.text = if (Settings.reminderEnabled(requireContext())) {
            getString(R.string.settings_reminder_on, hourLabel())
        } else {
            getString(R.string.settings_reminder_off)
        }
    }

    private fun hourLabel(): String =
        String.format(Locale.US, "%02d:00", Settings.reminderHour(requireContext()))

    // ---------- 备份 ----------

    private fun startExport() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        exportLauncher.launch("worddrill-backup-$stamp.json")
    }

    private fun writeBackup(uri: Uri) {
        val context = requireContext()
        Bg.run({
            val json = repo.exportBackupJson()
            context.contentResolver.openOutputStream(uri)?.use { out ->
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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_import)
            .setMessage("会把备份里的词库、错题、星标、打卡记录合并到本机，已有的单词不会被删除或覆盖。")
            .setPositiveButton("选择文件") { _, _ ->
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun readBackup(uri: Uri) {
        val context = requireContext()
        Bg.run({
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalStateException("无法读取文件")
            repo.importBackupJson(text)
        }) { result ->
            result.onSuccess { summary ->
                AlertDialog.Builder(requireContext())
                    .setTitle("导入完成")
                    .setMessage(summary)
                    .setPositiveButton("好", null)
                    .show()
            }.onFailure { toast("导入失败：${it.message}") }
        }
    }

    private fun refreshAutoBackupTime() {
        if (_binding == null) return
        val at = AutoBackup.latestTime(requireContext())
        binding.tvAutoBackupTime.text = if (at > 0L) {
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(at))
            getString(R.string.settings_auto_backup_time, stamp)
        } else {
            getString(R.string.settings_auto_backup_none)
        }
    }

    private fun restoreAutoBackup() {
        val text = AutoBackup.latest(requireContext())
        if (text == null) {
            toast("还没有自动备份")
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_restore_auto)
            .setMessage("会把自动备份里的词库、错题、星标、打卡、练习进度合并回本机，不会删除现有内容。")
            .setPositiveButton("恢复") { _, _ ->
                Bg.run({ repo.importBackupJson(text) }) { result ->
                    result.onSuccess { summary ->
                        AlertDialog.Builder(requireContext())
                            .setTitle("恢复完成")
                            .setMessage(summary)
                            .setPositiveButton("好", null)
                            .show()
                    }.onFailure { toast("恢复失败：${it.message}") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearLog() {
        AlertDialog.Builder(requireContext())
            .setTitle("清空练习记录？")
            .setMessage("清空后所有单词都会重新被视为「没练过」，星标和错题不受影响。")
            .setPositiveButton("清空") { _, _ ->
                Bg.run({ repo.clearPracticeLog() }) { result ->
                    result.onSuccess {
                        toast("已清空练习记录")
                    }.onFailure { toast("清空失败：${it.message}") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 其他 ----------

    private fun refreshSmartParseUrl() {
        if (_binding == null) return
        val current = Settings.smartParseUrl(requireContext())
        val isDefault = current == BuildConfig.SMART_PARSE_URL
        binding.tvSmartParseUrl.text = if (isDefault) "默认：$current" else "$current（已自定义）"
    }

    private fun editSmartParseUrl() {
        val context = requireContext()
        val input = EditText(context).apply {
            setText(Settings.smartParseUrl(context))
            setSingleLine(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val density = resources.displayMetrics.density
        val container = FrameLayout(context).apply {
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_smart_parse)
            .setMessage("填部署好的接口地址，多个地址用英文逗号分隔，会按顺序回退")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                Settings.setSmartParseUrl(context, input.text.toString())
                refreshSmartParseUrl()
                toast("已保存")
            }
            .setNeutralButton("恢复默认") { _, _ ->
                Settings.setSmartParseUrl(context, "")
                refreshSmartParseUrl()
                toast("已恢复默认地址")
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
