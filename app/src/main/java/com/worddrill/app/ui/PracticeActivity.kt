package com.worddrill.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.worddrill.app.R
import com.worddrill.app.data.AnswerRecord
import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.WordFilter
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivityPracticeBinding
import com.worddrill.app.quiz.QuizEngine
import com.worddrill.app.quiz.QuizItem
import com.worddrill.app.util.Bg
import com.worddrill.app.util.Settings
import com.worddrill.app.util.Tts
import com.worddrill.app.util.WidgetUpdater

class PracticeActivity : BaseActivity() {

    private lateinit var binding: ActivityPracticeBinding
    private lateinit var repo: WordRepository

    private var mode: PracticeMode = PracticeMode.EN_ZH
    private var bookName: String = ""
    private var items: List<QuizItem> = emptyList()
    private var index = 0
    private var answered = false
    private val records = mutableListOf<AnswerRecord>()
    private var pendingNext: Runnable? = null
    private var ttsWarned = false

    private val optionRows get() = listOf(binding.option1, binding.option2, binding.option3, binding.option4)
    private val optionTexts
        get() = listOf(
            binding.optionText1,
            binding.optionText2,
            binding.optionText3,
            binding.optionText4,
        )
    private val optionBadges get() = listOf(binding.badge1, binding.badge2, binding.badge3, binding.badge4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPracticeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        repo = WordRepository(this)
        mode = intent.getStringExtra(EXTRA_MODE)?.let { runCatching { PracticeMode.valueOf(it) }.getOrNull() }
            ?: PracticeMode.EN_ZH
        bookName = intent.getStringExtra(EXTRA_BOOK_NAME).orEmpty()

        binding.toolbar.title = "${mode.label} · $bookName"
        binding.toolbar.setNavigationOnClickListener { confirmExit() }
        binding.btnStar.setOnClickListener { toggleStar() }
        binding.btnSubmit.setOnClickListener { submitSpelling() }
        binding.btnNext.setOnClickListener { advance() }
        binding.etAnswer.setOnEditorActionListener { _, _, _ ->
            submitSpelling()
            true
        }
        optionRows.forEachIndexed { i, row ->
            row.setOnClickListener { chooseOption(i) }
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })

        loadSession()
    }

    override fun onDestroy() {
        pendingNext?.let { binding.root.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun loadSession() {
        binding.progressBar.isIndeterminate = true
        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        val count = intent.getIntExtra(EXTRA_COUNT, 20)
        val start = intent.getIntExtra(EXTRA_START, 1)
        val end = intent.getIntExtra(EXTRA_END, 0)
        val allowRepeat = intent.getBooleanExtra(EXTRA_ALLOW_REPEAT, true)
        val filter = intent.getStringExtra(EXTRA_FILTER)
            ?.let { runCatching { WordFilter.valueOf(it) }.getOrNull() } ?: WordFilter.ALL
        val ids = intent.getLongArrayExtra(EXTRA_WORD_IDS)
        val requested = if (ids != null && ids.isNotEmpty()) ids.size else count

        Bg.run({
            val words = if (ids != null && ids.isNotEmpty()) {
                repo.wordsByIds(ids)
            } else {
                repo.pickWords(bookId, start, end, count, allowRepeat, filter)
            }
            val all = repo.allWords()
            QuizEngine.build(words, all, requested, mode)
        }) { result ->
            binding.progressBar.isIndeterminate = false
            result.onSuccess { quiz ->
                if (quiz.isEmpty()) {
                    showEmptyDialog(allowRepeat)
                    return@onSuccess
                }
                items = quiz
                index = 0
                render()
                if (quiz.size < requested) {
                    toast("可抽的单词不足，本轮只有 ${quiz.size} 题")
                }
            }.onFailure {
                toast("加载失败：${it.message}")
                finish()
            }
        }
    }

    private fun showEmptyDialog(allowRepeat: Boolean) {
        val builder = AlertDialog.Builder(this)
            .setTitle("没有可练习的单词")
            .setMessage(
                if (!allowRepeat) {
                    "范围内的单词都练过了。可以清空练习记录后重新抽题，或换一个范围。"
                } else {
                    "当前词库或范围里没有单词，请返回调整。"
                },
            )
            .setCancelable(false)
            .setNegativeButton("返回") { _, _ -> finish() }

        if (!allowRepeat) {
            builder.setPositiveButton("清空记录并重试") { _, _ ->
                Bg.run({ repo.clearPracticeLog() }) { r ->
                    r.onSuccess { loadSession() }
                        .onFailure { toast("清空失败：${it.message}") }
                }
            }
        } else {
            builder.setPositiveButton(null, null)
        }
        builder.show()
    }

    private fun render() {
        val item = items[index]
        answered = false
        binding.tvProgress.text = "第 ${index + 1} 题 / 共 ${items.size} 题"
        binding.progressBar.max = items.size
        binding.progressBar.setProgressCompat(index, true)
        binding.tvPromptLabel.text = if (mode == PracticeMode.EN_ZH) "英 文" else "中 文 释 义"
        binding.tvPrompt.text = item.prompt
        binding.tvPrompt.setOnClickListener {
            if (mode == PracticeMode.EN_ZH) speakWord(item.word.text)
        }
        if (mode == PracticeMode.EN_ZH && Settings.ttsEnabled(this)) {
            speakWord(item.word.text)
        }
        binding.feedbackCard.visibility = View.GONE
        binding.btnNext.visibility = View.GONE
        updateStar(item)
        binding.etAnswer.isEnabled = true
        binding.btnSubmit.isEnabled = true
        binding.etAnswer.setText("")
        resetOptionStyles()

        if (item.spell) {
            binding.optionContainer.visibility = View.GONE
            binding.spellContainer.visibility = View.VISIBLE
            binding.tvHint.text = "${QuizEngine.hint(item.answer)}   (${item.answer.length} 个字母)"
            binding.etAnswer.post {
                binding.etAnswer.requestFocus()
                getSystemService<InputMethodManager>()?.showSoftInput(binding.etAnswer, 0)
            }
        } else {
            binding.optionContainer.visibility = View.VISIBLE
            binding.spellContainer.visibility = View.GONE
            hideKeyboard()
            optionRows.forEachIndexed { i, row ->
                val visible = i < item.options.size
                row.visibility = if (visible) View.VISIBLE else View.GONE
                if (visible) optionTexts[i].text = item.options[i]
            }
        }
    }

    private fun resetOptionStyles() {
        optionRows.forEachIndexed { i, row ->
            row.setBackgroundResource(R.drawable.bg_option)
            optionTexts[i].setTextColor(color(R.color.text_primary))
            optionBadges[i].backgroundTintList = ColorStateList.valueOf(color(R.color.brand_soft))
            optionBadges[i].setTextColor(color(R.color.brand))
        }
    }

    private fun chooseOption(option: Int) {
        if (answered) return
        val item = items[index]
        answered = true
        val correct = option == item.correctOption
        val userAnswer = item.options.getOrElse(option) { "" }

        optionRows.forEachIndexed { i, _ ->
            when {
                i == item.correctOption -> markOption(i, R.drawable.bg_option_correct, R.color.correct)
                i == option && !correct -> markOption(i, R.drawable.bg_option_wrong, R.color.wrong)
            }
        }
        showFeedback(correct, item, userAnswer)
        persist(correct, userAnswer)
        scheduleAdvance(if (correct) 850L else 1600L)
    }

    private fun markOption(index: Int, background: Int, tone: Int) {
        optionRows[index].setBackgroundResource(background)
        optionTexts[index].setTextColor(color(tone))
        optionBadges[index].backgroundTintList = ColorStateList.valueOf(color(tone))
        optionBadges[index].setTextColor(color(R.color.white))
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun submitSpelling() {
        if (answered || items.isEmpty()) return
        val item = items[index]
        val input = binding.etAnswer.text?.toString().orEmpty()
        if (input.isBlank()) {
            toast("先输入你记得的英文")
            return
        }
        answered = true
        hideKeyboard()
        binding.etAnswer.isEnabled = false
        binding.btnSubmit.isEnabled = false
        val correct = QuizEngine.isCorrect(input, item.answer)
        showFeedback(correct, item, input)
        persist(correct, input)
        binding.btnNext.visibility = View.VISIBLE
    }

    private fun showFeedback(correct: Boolean, item: QuizItem, userAnswer: String) {
        binding.feedbackCard.visibility = View.VISIBLE
        binding.feedbackCard.setCardBackgroundColor(color(if (correct) R.color.correct_bg else R.color.wrong_bg))
        binding.tvFeedback.setTextColor(color(if (correct) R.color.correct else R.color.wrong))
        val example = if (item.word.hasExample) {
            buildString {
                append("\n\n")
                append(item.word.exampleEn)
                if (item.word.exampleZh.isNotBlank()) {
                    append("\n")
                    append(item.word.exampleZh)
                }
            }
        } else {
            ""
        }
        binding.tvFeedback.text = if (correct) {
            "✓ 答对了\n${item.word.text} — ${item.word.meaningWithPos}$example"
        } else {
            "✗ 正确答案：${item.answer}\n你作答的是：$userAnswer\n${item.word.text} — ${item.word.meaningWithPos}$example"
        }
        speakWord(item.word.text)
    }

    private fun speakWord(text: String) {
        Tts.speak(this, text) { reason ->
            if (!ttsWarned) {
                ttsWarned = true
                toast(reason)
            }
        }
    }

    private fun persist(correct: Boolean, userAnswer: String) {
        val item = items[index]
        records.add(
            AnswerRecord(
                wordId = item.word.id,
                word = item.word.text,
                meaning = item.word.meaningWithPos,
                answer = item.answer,
                userAnswer = userAnswer,
                correct = correct,
            ),
        )
        Bg.run({ repo.recordAnswer(item.word.id, mode, correct) }) { }
    }

    private fun scheduleAdvance(delay: Long) {
        val runnable = Runnable { advance() }
        pendingNext = runnable
        binding.root.postDelayed(runnable, delay)
    }

    private fun advance() {
        pendingNext?.let { binding.root.removeCallbacks(it) }
        pendingNext = null
        index++
        if (index >= items.size) {
            finishSession()
        } else {
            render()
        }
    }

    private fun finishSession() {
        WidgetUpdater.refresh(this)
        startActivity(ResultActivity.intent(this, mode, bookName, records.toList()))
        finish()
    }

    private fun toggleStar() {
        if (items.isEmpty()) return
        val item = items[index]
        val target = !item.word.starred
        Bg.run({ repo.setStar(item.word.id, target) }) { result ->
            result.onSuccess {
                val updated = item.copy(word = item.word.copy(starred = target))
                items = items.toMutableList().also { it[index] = updated }
                updateStar(updated)
            }.onFailure { toast("操作失败：${it.message}") }
        }
    }

    private fun updateStar(item: QuizItem) {
        binding.btnStar.setImageResource(
            if (item.word.starred) R.drawable.ic_star_filled else R.drawable.ic_star_outline,
        )
    }

    private fun confirmExit() {
        if (records.isEmpty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("退出练习？")
            .setMessage("已答的 ${records.size} 道题会记入错题与练习记录。")
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("继续练习", null)
            .show()
    }

    private fun hideKeyboard() {
        getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_BOOK_ID = "book_id"
        private const val EXTRA_BOOK_NAME = "book_name"
        private const val EXTRA_COUNT = "count"
        private const val EXTRA_START = "start"
        private const val EXTRA_END = "end"
        private const val EXTRA_FILTER = "filter"
        private const val EXTRA_ALLOW_REPEAT = "allow_repeat"
        private const val EXTRA_WORD_IDS = "word_ids"

        fun intent(
            context: Context,
            bookId: Long,
            bookName: String,
            mode: PracticeMode,
            count: Int,
            startOrd: Int,
            endOrd: Int,
            filter: WordFilter,
            allowRepeat: Boolean,
        ): Intent = Intent(context, PracticeActivity::class.java).apply {
            putExtra(EXTRA_MODE, mode.name)
            putExtra(EXTRA_BOOK_ID, bookId)
            putExtra(EXTRA_BOOK_NAME, bookName)
            putExtra(EXTRA_COUNT, count)
            putExtra(EXTRA_START, startOrd)
            putExtra(EXTRA_END, endOrd)
            putExtra(EXTRA_FILTER, filter.name)
            putExtra(EXTRA_ALLOW_REPEAT, allowRepeat)
        }

        fun retryIntent(
            context: Context,
            mode: PracticeMode,
            bookName: String,
            wordIds: LongArray,
        ): Intent = Intent(context, PracticeActivity::class.java).apply {
            putExtra(EXTRA_MODE, mode.name)
            putExtra(EXTRA_BOOK_NAME, bookName)
            putExtra(EXTRA_COUNT, wordIds.size)
            putExtra(EXTRA_WORD_IDS, wordIds)
            putExtra(EXTRA_ALLOW_REPEAT, true)
        }
    }
}
