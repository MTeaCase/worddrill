package com.worddrill.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import com.worddrill.app.R
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivityAchievementsBinding
import com.worddrill.app.ui.adapter.AchievementAdapter
import com.worddrill.app.util.Bg

/** 成就徽章页：一个个列出，得到的显示获得日期，没得到的整行压灰。 */
class AchievementsActivity : BaseActivity() {

    private lateinit var binding: ActivityAchievementsBinding
    private lateinit var repo: WordRepository
    private val adapter = AchievementAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAchievementsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        repo = WordRepository(this)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvAchievements.layoutManager = LinearLayoutManager(this)
        binding.rvAchievements.adapter = adapter
        load()
    }

    private fun load() {
        Bg.run({ repo.checkAchievements(); repo.achievementState() }) { result ->
            if (isFinishing || isDestroyed) return@run
            result.onSuccess { rows ->
                adapter.submit(rows)
                val unlocked = rows.count { it.second > 0L }
                binding.tvSummary.text = getString(R.string.ach_summary, unlocked, rows.size)
                val percent = if (rows.isEmpty()) 0 else unlocked * 100 / rows.size
                binding.pbAchievements.setProgressCompat(percent, true)
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AchievementsActivity::class.java)
    }
}