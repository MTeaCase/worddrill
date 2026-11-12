package com.worddrill.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.worddrill.app.R
import com.worddrill.app.data.AnswerRecord
import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.ReviewScheduler
import com.worddrill.app.data.WordProgress
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivityResultBinding
import com.worddrill.app.quiz.QuizEngine
import com.worddrill.app.ui.adapter.ResultAdapter
import com.worddrill.app.util.Bg

class ResultActivity : BaseActivity() {

    private lateinit var binding: ActivityResultBinding

    private var mode: PracticeMode = PracticeMode.EN_ZH
    private var bookName: String = ""
    private var records: List<AnswerRecord> = emptyList()

    private val repo by lazy { WordRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE)
            ?.let { runCatching { PracticeMode.valueOf(it) }.getOrNull() } ?: PracticeMode.EN_ZH
        bookName = intent.getStringExtra(EXTRA_BOOK_NAME).orEmpty()

        val words = intent.getStringArrayListExtra(EXTRA_WORDS) ?: arrayListOf()
        val meanings = intent.getStringArrayListExtra(EXTRA_MEANINGS) ?: arrayListOf()
        val answers = intent.getStringArrayListExtra(EXTRA_ANSWERS) ?: arrayListOf()
        val userAnswers = intent.getStringArrayListExtra(EXTRA_USER) ?: arrayListOf()
        val correctFlags = intent.getBooleanArrayExtra(EXTRA_CORRECT) ?: BooleanArray(0)
        val ids = intent.getLongArrayExtra(EXTRA_IDS) ?: LongArray(0)
        val elapsed = intent.getLongArrayExtra(EXTRA_ELAPSED) ?: LongArray(0)

        records = words.indices.map { i ->
            AnswerRecord(
                wordId = ids.getOrElse(i) { 0L },
                word = words[i],
                meaning = meanings.getOrElse(i) { "" },
                answer = answers.getOrElse(i) { "" },
                userAnswer = userAnswers.getOrElse(i) { "" },
                correct = correctFlags.getOrElse(i) { false },
                elapsedMs = elapsed.getOrElse(i) { 0L },
            )
        }

        val correctCount = records.count { it.correct }
        val wrong = records.filter { !it.correct }
        val percent = if (records.isEmpty()) 0 else correctCount * 100 / records.size
        val fast = records.count { QuizEngine.isFast(it.elapsedMs, it.correct) }

        binding.tvScore.text = "$percent%"
        binding.pbScore.setProgressCompat(percent, true)
        binding.tvScoreDetail.text = buildString {
            append("答对 $correctCount / ${records.size} 题 · ${mode.label} · $bookName")
            if (fast > 0) append("\n⚡ 3 秒内答对 $fast 题")
            if (records.isNotEmpty() && wrong.isEmpty()) append("\n全部答对，这一轮干净")
        }
        // 训练记录：总用时与配速，比只有百分比更像一张成绩单
        val totalMs = records.sumOf { it.elapsedMs }
        binding.tvSession.text = if (totalMs > 0) {
            val totalSec = totalMs / 1000.0
            val avgSec = totalSec / records.size
            buildString {
                append("用时 ")
                append(if (totalSec >= 60) "${(totalSec / 60).toInt()} 分 ${(totalSec % 60).toInt()} 秒" else "${totalSec.toInt()} 秒")
                append(" · 平均 ")
                append(String.format(java.util.Locale.US, "%.1f", avgSec))
                append(" 秒/题")
                if (bookName.isNotBlank()) append(" · $bookName")
            }
        } else {
            ""
        }
        binding.tvWrongLabel.text = if (wrong.isEmpty()) "全部答对，没有错题" else "错题 ${wrong.size}"

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = ResultAdapter(wrong)

        binding.btnRetry.visibility = if (wrong.isEmpty()) View.GONE else View.VISIBLE
        binding.btnRetry.setOnClickListener {
            startActivity(
                PracticeActivity.retryIntent(
                    context = this,
                    mode = mode,
                    bookName = bookName,
                    wordIds = wrong.map { it.wordId }.distinct().toLongArray(),
                ),
            )
            finish()
        }
        binding.btnHome.setOnClickListener { finish() }

        loadNextReview(records.map { it.wordId }.distinct().toLongArray())
    }

    /** 告诉用户这批词什么时候再来：复习算法一直在后台排期，不显示出来像个黑盒。 */
    private fun loadNextReview(ids: LongArray) {
        if (ids.isEmpty()) return
        Bg.run({ repo.progressFor(ids) }) { result ->
            result.onSuccess { progress ->
                if (progress.isEmpty() || isFinishing || isDestroyed) return@onSuccess
                val text = nextReviewText(progress.values)
                if (text.isEmpty()) return@onSuccess
                binding.tvNextReview.text = text
                binding.tvNextReview.visibility = View.VISIBLE
            }
        }
    }

    private fun nextReviewText(progress: Collection<WordProgress>): String {
        val now = System.currentTimeMillis()
        var today = 0
        var tomorrow = 0
        var later = 0
        var earliest = Long.MAX_VALUE
        progress.forEach { item ->
            if (item.dueAt in 1 until earliest) earliest = item.dueAt
            when (dayOffset(item.dueAt, now)) {
                0 -> today++
                1 -> tomorrow++
                else -> later++
            }
        }
        val parts = mutableListOf<String>()
        if (earliest != Long.MAX_VALUE) {
            val days = dayOffset(earliest, now)
            parts.add(if (days <= 0) "最早今天复习" else "最早 $days 天后复习")
        }
        if (today > 0) parts.add("今天 $today 个")
        if (tomorrow > 0) parts.add("明天 $tomorrow 个")
        if (later > 0) parts.add("更久 $later 个")
        if (parts.isEmpty()) return ""
        return parts.joinToString(" · ")
    }

    /** 距今天还有几天：已到期算 0，不足一天按一天算。 */
    private fun dayOffset(dueAt: Long, now: Long): Int {
        val diff = dueAt - now
        if (diff <= 0L) return 0
        return ((diff + ReviewScheduler.DAY_MS - 1) / ReviewScheduler.DAY_MS).toInt()
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_BOOK_NAME = "book_name"
        private const val EXTRA_WORDS = "words"
        private const val EXTRA_MEANINGS = "meanings"
        private const val EXTRA_ANSWERS = "answers"
        private const val EXTRA_USER = "user_answers"
        private const val EXTRA_CORRECT = "correct_flags"
        private const val EXTRA_IDS = "word_ids"
        private const val EXTRA_ELAPSED = "elapsed_ms"

        fun intent(
            context: Context,
            mode: PracticeMode,
            bookName: String,
            records: List<AnswerRecord>,
        ): Intent = Intent(context, ResultActivity::class.java).apply {
            putExtra(EXTRA_MODE, mode.name)
            putExtra(EXTRA_BOOK_NAME, bookName)
            putStringArrayListExtra(EXTRA_WORDS, ArrayList(records.map { it.word }))
            putStringArrayListExtra(EXTRA_MEANINGS, ArrayList(records.map { it.meaning }))
            putStringArrayListExtra(EXTRA_ANSWERS, ArrayList(records.map { it.answer }))
            putStringArrayListExtra(EXTRA_USER, ArrayList(records.map { it.userAnswer }))
            putExtra(EXTRA_CORRECT, BooleanArray(records.size) { records[it].correct })
            putExtra(EXTRA_IDS, records.map { it.wordId }.toLongArray())
            putExtra(EXTRA_ELAPSED, records.map { it.elapsedMs }.toLongArray())
        }
    }
}
