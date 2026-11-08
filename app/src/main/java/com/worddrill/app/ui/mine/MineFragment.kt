package com.worddrill.app.ui.mine

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.worddrill.app.BuildConfig
import com.worddrill.app.R
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.FragmentMineBinding
import com.worddrill.app.databinding.ItemMineRowBinding
import com.worddrill.app.ui.AboutDialog
import com.worddrill.app.ui.DictionaryActivity
import com.worddrill.app.ui.PrivacyActivity
import com.worddrill.app.ui.ReportActivity
import com.worddrill.app.ui.SettingsActivity
import com.worddrill.app.util.Bg
import com.worddrill.app.util.Settings
import com.worddrill.app.util.Updater

/** 我的 tab：今日概览 + 设置入口。具体开关都还在设置页，这里只做导航和状态展示。 */
class MineFragment : Fragment() {

    private var _binding: FragmentMineBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: WordRepository

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

        binding.cardSummary.setOnClickListener {
            startActivity(ReportActivity.intent(requireContext()))
        }
        buildRows()
        loadSummary()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            buildRows()
            loadSummary()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun settings(): AppCompatActivity = requireActivity() as AppCompatActivity

    private fun buildRows() {
        binding.studyRows.removeAllViews()
        binding.dataRows.removeAllViews()
        binding.otherRows.removeAllViews()

        addRow(binding.studyRows, getString(R.string.title_report), "看打卡、正确率和掌握度") {
            startActivity(ReportActivity.intent(requireContext()))
        }
        addRow(binding.studyRows, getString(R.string.settings_tts_title), stateLabel(Settings.ttsEnabled(requireContext()))) {
            openSettings()
        }
        addRow(binding.studyRows, getString(R.string.settings_reminder), reminderLabel()) {
            openSettings()
        }
        addRow(binding.studyRows, getString(R.string.settings_example_ai), stateLabel(Settings.exampleAiEnabled(requireContext()))) {
            openSettings()
        }

        addRow(binding.dataRows, getString(R.string.settings_backup), getString(R.string.settings_export_desc)) {
            openSettings()
        }
        addRow(binding.dataRows, getString(R.string.settings_check_update), getString(R.string.settings_update_desc)) {
            Updater.check(settings(), manual = true)
        }
        addRow(binding.dataRows, getString(R.string.action_clear_log), null) {
            confirmClearLog()
        }

        addRow(binding.otherRows, getString(R.string.title_dictionary), getString(R.string.shortcut_dict_long)) {
            startActivity(DictionaryActivity.intent(requireContext()))
        }
        addRow(binding.otherRows, getString(R.string.settings_smart_parse), getString(R.string.settings_smart_parse_desc)) {
            openSettings()
        }
        addRow(binding.otherRows, getString(R.string.settings_privacy), getString(R.string.settings_privacy_desc)) {
            startActivity(PrivacyActivity.intent(requireContext()))
        }
        addRow(binding.otherRows, getString(R.string.about), getString(R.string.about_version, BuildConfig.VERSION_NAME)) {
            AboutDialog.show(settings())
        }
    }

    private fun addRow(container: LinearLayout, title: String, sub: String?, onClick: () -> Unit) {
        val row = ItemMineRowBinding.inflate(layoutInflater, container, false)
        row.tvTitle.text = title
        row.tvSub.isVisible = !sub.isNullOrBlank()
        row.tvSub.text = sub.orEmpty()
        row.root.setOnClickListener { onClick() }
        container.addView(row.root)
    }

    private fun stateLabel(enabled: Boolean): String =
        if (enabled) "已开启" else "已关闭"

    private fun reminderLabel(): String =
        if (Settings.reminderEnabled(requireContext())) {
            String.format(java.util.Locale.US, "每天 %02d:00 提醒", Settings.reminderHour(requireContext()))
        } else {
            "未开启"
        }

    private fun loadSummary() {
        val goal = Settings.dailyGoal(requireContext())
        Bg.run({ repo.studySummary(7, goal) }) { result ->
            result.onSuccess { summary ->
                if (_binding == null || !isAdded) return@onSuccess
                binding.tvToday.text = "${summary.todayAnswered} / ${summary.dailyGoal} 题"
                binding.pbToday.max = summary.dailyGoal
                binding.pbToday.setProgressCompat(summary.todayAnswered.coerceAtMost(summary.dailyGoal), true)
                val accuracy = if (summary.totalAnswered == 0) {
                    0
                } else {
                    summary.totalCorrect * 100 / summary.totalAnswered
                }
                binding.tvSummarySub.text =
                    "连续 ${summary.streak} 天 · 累计正确率 $accuracy% · 待复习 ${summary.dueReview} 词"
            }.onFailure {
                if (_binding != null) toast("读取统计失败：${it.message}")
            }
        }
    }

    private fun openSettings() {
        startActivity(SettingsActivity.intent(requireContext()))
    }

    private fun confirmClearLog() {
        AlertDialog.Builder(requireContext())
            .setTitle("清空练习记录？")
            .setMessage("清空后所有单词都会重新被视为「没练过」，星标和错题不受影响。")
            .setPositiveButton("清空") { _, _ ->
                Bg.run({ repo.clearPracticeLog() }) { result ->
                    result.onSuccess {
                        toast("已清空练习记录")
                        loadSummary()
                    }.onFailure { toast("清空失败：${it.message}") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
