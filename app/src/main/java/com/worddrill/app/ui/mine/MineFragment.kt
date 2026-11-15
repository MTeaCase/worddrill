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
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.worddrill.app.BuildConfig
import com.worddrill.app.R
import com.worddrill.app.data.Accent
import com.worddrill.app.data.Achievements
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.FragmentMineBinding
import com.worddrill.app.ui.AboutDialog
import com.worddrill.app.ui.DictionaryActivity
import com.worddrill.app.ui.PrivacyActivity
import com.worddrill.app.ui.VocabTestActivity
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
                toast("未获得通知权限，无法提醒")
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
            toast(if (checked) "已启用例句生成" else "已关闭例句生成")
        }

        binding.swSelfRating.setOnCheckedChangeListener { _, checked ->
            Settings.setSelfRatingEnabled(requireContext(), checked)
        }

        binding.swVaryDistractors.setOnCheckedChangeListener { _, checked ->
            Settings.setVaryDistractors(requireContext(), checked)
        }

        binding.swRevealMeanings.setOnCheckedChangeListener { _, checked ->
            Settings.setRevealOptionMeanings(requireContext(), checked)
        }

        binding.swAutoBackup.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchEvent) return@setOnCheckedChangeListener
            Settings.setAutoBackupEnabled(requireContext(), checked)
            refreshBackupControls()
        }
        binding.rowTheme.setOnClickListener { pickTheme() }
        binding.rowBackupInterval.setOnClickListener { editBackupInterval() }
        binding.rowBackupKeep.setOnClickListener { editBackupKeep() }
        binding.btnSnapshotNow.setOnClickListener { snapshotNow() }
        binding.btnClearCache.setOnClickListener { confirmClearCache() }

        binding.rowAccent.setOnClickListener { pickAccent() }
        binding.rowRate.setOnClickListener { pickRate() }
        binding.rowQuota.setOnClickListener { editQuotas() }
        binding.rowHelp.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_body)
                .setPositiveButton("关闭", null)
                .show()
        }
        binding.rowExam.setOnClickListener { pickExam() }
        binding.rowAchievements.setOnClickListener { showAchievements() }
        binding.rowVocabTest.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), VocabTestActivity::class.java))
        }

        binding.btnExport.setOnClickListener { startExport() }
        binding.btnImport.setOnClickListener { startImport() }
        binding.btnRestoreAuto.setOnClickListener { restoreAutoBackup() }
        binding.btnRedeploy.setOnClickListener { confirmRedeploy() }
        binding.btnClearLog.setOnClickListener { confirmClearLog() }
        binding.btnDictionary.setOnClickListener {
            startActivity(DictionaryActivity.intent(requireContext()))
        }
        binding.btnCheckUpdate.setOnClickListener { Updater.check(activity(), manual = true) }
        binding.btnPrivacy.setOnClickListener { startActivity(PrivacyActivity.intent(requireContext())) }
        binding.btnLicense.setOnClickListener { startActivity(PrivacyActivity.licenseIntent(requireContext())) }
        binding.btnAbout.setOnClickListener { AboutDialog.show(activity()) }

        binding.tvVersion.text = "WordDrill ${BuildConfig.VERSION_NAME}"
        refreshSwitches()
        refreshVoiceSettings()
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
        refreshVoiceSettings()
        refreshReminderText()
        refreshAutoBackupTime()
        refreshBackupControls()
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
        binding.swSelfRating.isChecked = Settings.selfRatingEnabled(context)
        binding.swVaryDistractors.isChecked = Settings.varyDistractors(context)
        binding.swRevealMeanings.isChecked = Settings.revealOptionMeanings(context)
        binding.swAutoBackup.isChecked = Settings.autoBackupEnabled(context)
        suppressSwitchEvent = false
        refreshReminderText()
    }

    /** 口音、语速、学习包配额这三行的当前值。 */
    private fun refreshVoiceSettings() {
        if (_binding == null) return
        val context = requireContext()
        binding.tvAccent.text = Settings.accent(context).label
        binding.tvRate.text = "${Settings.speechRate(context)}×"
        binding.tvQuota.text = buildString {
            append("新 ").append(Settings.newQuota(context))
            append(" · 复习 ").append(Settings.reviewQuota(context))
            append(" · 错题 ").append(Settings.mistakeQuota(context))
        }
        binding.tvExam.text = examLabel()
        binding.tvTheme.text = themeLabel(Settings.themeMode(requireContext()))
        refreshBackupControls()
        Bg.run({ repo.achievementState().count { it.second > 0 } }) { result ->
            result.onSuccess { unlocked ->
                if (_binding != null) {
                    binding.tvAchievements.text = "$unlocked / ${Achievements.ALL.size}"
                }
            }
        }
    }

    private fun examLabel(): String {
        val date = Settings.examDate(requireContext())
        if (date <= 0L) return "未设置"
        val days = ((date - System.currentTimeMillis()) / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
        val name = Settings.examName(requireContext()).ifBlank { "考试" }
        return "$name · $days 天"
    }

    /** 考试倒计时：先填名字，再选日期，两步都很轻。 */
    private fun pickExam() {
        val context = requireContext()
        val input = EditText(context).apply {
            setText(Settings.examName(context))
            hint = "考试名称"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val density = resources.displayMetrics.density
        val container = FrameLayout(context).apply {
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_exam)
            .setMessage(getString(R.string.settings_exam_desc))
            .setView(container)
            .setPositiveButton("下一步") { _, _ ->
                Settings.setExamName(context, input.text.toString().trim().ifBlank { "考试" })
                pickExamDate()
            }
            .setNeutralButton("清除") { _, _ ->
                Settings.setExamName(context, "")
                Settings.setExamDate(context, 0L)
                refreshVoiceSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickExamDate() {
        val context = requireContext()
        val calendar = java.util.Calendar.getInstance().apply {
            val saved = Settings.examDate(context)
            if (saved > 0L) {
                timeInMillis = saved
            } else {
                add(java.util.Calendar.DAY_OF_YEAR, 90)
            }
        }
        android.app.DatePickerDialog(
            context,
            { _, year, month, day ->
                val picked = java.util.Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                Settings.setExamDate(context, picked.timeInMillis)
                refreshVoiceSettings()
                toast("已保存：${Settings.examName(context)} ${year}-${month + 1}-${day}")
            },
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH),
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun showAchievements() {
        Bg.run({ repo.checkAchievements(); repo.achievementState() }) { result ->
            result.onSuccess { list ->
                if (_binding == null || !isAdded) return@onSuccess
                val text = list.joinToString("\n") { (def, at) ->
                    val mark = if (at > 0L) "已获得" else "未获得"
                    val whenText = if (at > 0L) {
                        SimpleDateFormat("MM-dd", Locale.US).format(Date(at))
                    } else {
                        "待解锁"
                    }
                    "$mark · ${def.title}：${def.detail}（$whenText）"
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_achievements)
                    .setMessage(text)
                    .setPositiveButton("关闭", null)
                    .show()
            }
        }
    }

    private fun pickAccent() {
        val context = requireContext()
        val current = Settings.accent(context)
        val options = Accent.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_accent)
            .setSingleChoiceItems(options, current.ordinal) { dialog, which ->
                Settings.setAccent(context, Accent.entries[which])
                Tts.refreshVoice()
                refreshVoiceSettings()
                dialog.dismiss()
                Tts.speak(context, "hello")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickRate() {
        val context = requireContext()
        val current = Settings.speechRate(context)
        val labels = Settings.SPEECH_RATES.map { rate ->
            when (rate) {
                0.75f -> "慢 0.75×"
                1.25f -> "快 1.25×"
                else -> "正常 1.0×"
            }
        }.toTypedArray()
        val index = Settings.SPEECH_RATES.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
            .coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_speech_rate)
            .setSingleChoiceItems(labels, index) { dialog, which ->
                Settings.setSpeechRate(context, Settings.SPEECH_RATES[which])
                Tts.refreshVoice()
                refreshVoiceSettings()
                dialog.dismiss()
                Tts.speak(context, "hello, this is a test")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editQuotas() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun input(hint: String, value: Int): EditText = EditText(context).apply {
            this.hint = hint
            setText(value.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val newInput = input("每天新词数", Settings.newQuota(context))
        val reviewInput = input("每天复习数", Settings.reviewQuota(context))
        val mistakeInput = input("每天错题数", Settings.mistakeQuota(context))
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
            addView(newInput)
            addView(reviewInput)
            addView(mistakeInput)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_quota)
            .setMessage("三类配额相互独立；均为 0 时学习包为空。")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                fun parse(view: EditText, fallback: Int): Int =
                    view.text?.toString()?.trim()?.toIntOrNull()
                        ?.coerceIn(Settings.MIN_QUOTA, Settings.MAX_QUOTA) ?: fallback
                Settings.setNewQuota(context, parse(newInput, Settings.newQuota(context)))
                Settings.setReviewQuota(context, parse(reviewInput, Settings.reviewQuota(context)))
                Settings.setMistakeQuota(context, parse(mistakeInput, Settings.mistakeQuota(context)))
                refreshVoiceSettings()
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onReminderToggled(enabled: Boolean) {
        val context = requireContext()
        if (enabled && !ReviewReminder.canNotify(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                toast("系统通知已关闭，请在设置中开启")
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
            toast("请先开启复习提醒")
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
            .setMessage("将合并备份中的词库、错题、星标与打卡记录，不删除或覆盖已有单词。")
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
                    .setPositiveButton("关闭", null)
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
            .setMessage("将用自动备份覆盖当前数据：词库、错题、星标与练习记录都回到快照时刻。")
            .setPositiveButton("恢复") { _, _ ->
                Bg.run({ repo.replaceFromBackupJson(text) }) { result ->
                    result.onSuccess { summary ->
                        AlertDialog.Builder(requireContext())
                            .setTitle("恢复完成")
                            .setMessage(summary)
                            .setPositiveButton("关闭", null)
                            .show()
                    }.onFailure { toast("恢复失败：${it.message}") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 手动重建内置词库：两次确认，先把话说清楚。 */
    private fun confirmRedeploy() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_redeploy)
            .setMessage(R.string.settings_redeploy_confirm)
            .setPositiveButton("继续") { _, _ -> confirmRedeployAgain() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRedeployAgain() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_redeploy)
            .setMessage(R.string.settings_redeploy_again)
            .setPositiveButton("重建") { _, _ ->
                Bg.run({ repo.redeployBuiltinBooks() }) { result ->
                    result.onSuccess { summary ->
                        if (_binding == null || !isAdded) return@onSuccess
                        refresh()
                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.settings_redeploy)
                            .setMessage(summary)
                            .setPositiveButton("关闭", null)
                            .show()
                    }.onFailure {
                        if (_binding == null) return@onFailure
                        toast(getString(R.string.settings_redeploy_failed, it.message ?: "未知错误"))
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearLog() {
        AlertDialog.Builder(requireContext())
            .setTitle("清空练习记录")
            .setMessage("清空后所有单词计为未练习，星标与错题保留。")
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

    // ---------- 主题 / 备份 ----------

    private fun themeLabel(mode: Settings.ThemeMode): String = getString(
        when (mode) {
            Settings.ThemeMode.SYSTEM -> R.string.settings_theme_system
            Settings.ThemeMode.LIGHT -> R.string.settings_theme_light
            Settings.ThemeMode.DARK -> R.string.settings_theme_dark
        },
    )

    private fun nightModeOf(mode: Settings.ThemeMode): Int = when (mode) {
        Settings.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        Settings.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        Settings.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

    private fun pickTheme() {
        val context = requireContext()
        val modes = Settings.ThemeMode.entries
        val labels = modes.map { themeLabel(it) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(labels, modes.indexOf(Settings.themeMode(context))) { dialog, which ->
                Settings.setThemeMode(context, modes[which])
                AppCompatDelegate.setDefaultNightMode(nightModeOf(modes[which]))
                dialog.dismiss()
                refreshVoiceSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshBackupControls() {
        if (_binding == null) return
        val context = requireContext()
        val enabled = Settings.autoBackupEnabled(context)
        suppressSwitchEvent = true
        binding.swAutoBackup.isChecked = enabled
        suppressSwitchEvent = false
        binding.tvBackupInterval.text =
            getString(R.string.settings_interval_format, Settings.backupIntervalHours(context))
        binding.tvBackupKeep.text =
            getString(R.string.settings_keep_format, Settings.backupKeepCount(context))
        binding.rowBackupInterval.alpha = if (enabled) 1f else 0.45f
        binding.rowBackupKeep.alpha = if (enabled) 1f else 0.45f
        binding.rowBackupInterval.isEnabled = enabled
        binding.rowBackupKeep.isEnabled = enabled
    }

    private fun numberInput(value: Int): EditText = EditText(requireContext()).apply {
        setText(value.toString())
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun wrapInput(input: EditText): FrameLayout {
        val density = resources.displayMetrics.density
        return FrameLayout(requireContext()).apply {
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
            addView(input)
        }
    }

    private fun editBackupInterval() {
        val context = requireContext()
        val input = numberInput(Settings.backupIntervalHours(context))
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_backup_interval)
            .setMessage("0 表示不自动保存，只能手动存快照。")
            .setView(wrapInput(input))
            .setPositiveButton("保存") { _, _ ->
                val value = input.text?.toString()?.trim()?.toIntOrNull()
                if (value == null) {
                    toast("请输入数字")
                    return@setPositiveButton
                }
                Settings.setBackupIntervalHours(context, value)
                refreshBackupControls()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editBackupKeep() {
        val context = requireContext()
        val input = numberInput(Settings.backupKeepCount(context))
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_backup_keep)
            .setMessage("超出份数的旧快照会自动删除。")
            .setView(wrapInput(input))
            .setPositiveButton("保存") { _, _ ->
                val value = input.text?.toString()?.trim()?.toIntOrNull()
                if (value == null) {
                    toast("请输入数字")
                    return@setPositiveButton
                }
                Settings.setBackupKeepCount(context, value)
                refreshBackupControls()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun snapshotNow() {
        AutoBackup.snapshotNow(requireContext()) { ok ->
            if (_binding == null) return@snapshotNow
            toast(if (ok) "已保存快照" else "保存失败")
            refreshAutoBackupTime()
            refreshBackupControls()
        }
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_clear_cache)
            .setMessage("将删除全部自动备份快照，不影响学习数据。")
            .setPositiveButton("清除") { _, _ ->
                AutoBackup.clearAll(requireContext())
                refreshAutoBackupTime()
                refreshBackupControls()
                toast("已清除备份缓存")
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
