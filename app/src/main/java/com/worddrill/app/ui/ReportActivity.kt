package com.worddrill.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.worddrill.app.R
import com.worddrill.app.data.StudySummary
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivityReportBinding
import com.worddrill.app.util.Bg
import com.worddrill.app.util.Settings
import com.worddrill.app.util.WidgetUpdater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/** 学习报告：今日进度、连续打卡、累计正确率、近 7 天柱状图、每日目标。 */
class ReportActivity : BaseActivity() {

    private lateinit var binding: ActivityReportBinding
    private lateinit var repo: WordRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        repo = WordRepository(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnGoal.setOnClickListener { askGoal() }
        binding.swTts.isChecked = Settings.ttsEnabled(this)
        binding.swTts.setOnCheckedChangeListener { _, checked ->
            Settings.setTtsEnabled(this, checked)
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val goal = Settings.dailyGoal(this)
        Bg.run({ repo.studySummary(DAYS, goal) }) { result ->
            result.onSuccess { summary ->
                if (isFinishing || isDestroyed) return@onSuccess
                render(summary)
            }.onFailure { toast("读取统计失败：${it.message}") }
        }
    }

    private fun render(summary: StudySummary) {
        val goal = summary.dailyGoal
        binding.tvToday.text = "${summary.todayAnswered} / $goal 题"
        binding.pbToday.max = goal
        binding.pbToday.setProgressCompat(summary.todayAnswered.coerceAtMost(goal), true)
        binding.tvTodaySub.text = when {
            summary.todayAnswered >= goal -> "今天的目标已经完成，继续保持"
            summary.todayAnswered == 0 -> "今天还没开始，先来 10 道题？"
            else -> "还差 ${goal - summary.todayAnswered} 题完成今日目标"
        }
        binding.tvStreak.text = "${summary.streak} 天"
        binding.tvTotal.text = "${summary.totalAnswered} 题"
        val accuracy = if (summary.totalAnswered == 0) 0 else summary.totalCorrect * 100 / summary.totalAnswered
        binding.tvAccuracy.text = "累计正确率 $accuracy% · 答对 ${summary.totalCorrect} / ${summary.totalAnswered} 题"
        binding.chart.setStats(summary.days, goal)
    }

    private fun askGoal() {
        val input = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(Settings.dailyGoal(this@ReportActivity).toString())
            setSelection(text?.length ?: 0)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_set_goal)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val value = input.text?.toString()?.trim()?.toIntOrNull()
                if (value == null || value !in Settings.MIN_GOAL..Settings.MAX_GOAL) {
                    toast("请输入 ${Settings.MIN_GOAL} – ${Settings.MAX_GOAL} 之间的数字")
                    return@setPositiveButton
                }
                Settings.setDailyGoal(this, value)
                WidgetUpdater.refresh(this)
                load()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DAYS = 7

        fun intent(context: Context): Intent = Intent(context, ReportActivity::class.java)
    }
}
