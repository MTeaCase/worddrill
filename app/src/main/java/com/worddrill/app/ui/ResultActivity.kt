package com.worddrill.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.worddrill.app.R
import com.worddrill.app.data.AnswerRecord
import com.worddrill.app.data.PracticeMode
import com.worddrill.app.databinding.ActivityResultBinding
import com.worddrill.app.ui.adapter.ResultAdapter

class ResultActivity : BaseActivity() {

    private lateinit var binding: ActivityResultBinding

    private var mode: PracticeMode = PracticeMode.EN_ZH
    private var bookName: String = ""
    private var records: List<AnswerRecord> = emptyList()

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

        records = words.indices.map { i ->
            AnswerRecord(
                wordId = ids.getOrElse(i) { 0L },
                word = words[i],
                meaning = meanings.getOrElse(i) { "" },
                answer = answers.getOrElse(i) { "" },
                userAnswer = userAnswers.getOrElse(i) { "" },
                correct = correctFlags.getOrElse(i) { false },
            )
        }

        val correctCount = records.count { it.correct }
        val wrong = records.filter { !it.correct }
        val percent = if (records.isEmpty()) 0 else correctCount * 100 / records.size

        binding.tvScore.text = "$percent%"
        binding.pbScore.setProgressCompat(percent, true)
        binding.tvScoreDetail.text = "答对 $correctCount / ${records.size} 题 · ${mode.label} · $bookName"
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
                    wordIds = wrong.map { it.wordId }.toLongArray(),
                ),
            )
            finish()
        }
        binding.btnHome.setOnClickListener { finish() }
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
        }
    }
}
