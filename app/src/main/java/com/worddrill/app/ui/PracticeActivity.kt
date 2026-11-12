package com.worddrill.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import com.worddrill.app.R
import com.worddrill.app.data.AnswerRecord
import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.ReviewRating
import com.worddrill.app.data.WordFilter
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivityPracticeBinding
import com.worddrill.app.quiz.QuizEngine
import com.worddrill.app.quiz.QuizItem
import com.worddrill.app.util.Bg
import com.worddrill.app.util.Settings
import com.worddrill.app.util.Tts
import com.worddrill.app.util.WidgetUpdater
import kotlin.math.abs

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
    private lateinit var gestureDetector: GestureDetector

    /** 题面出现的时刻（单调时钟），用来算答题耗时。 */
    private var shownAt = 0L

    /** 当前反馈卡对应的词；点了自评就用它，避免翻页后错记到下一个词。 */
    private var ratingWordId: Long? = null

    /** 本轮原始题量，用来限制「错题补练」最多追加多少。 */
    /** 用户选了「重练已练过的词」，本轮强制允许重复。 */
    private var forceRepeat = false
    private var baseSize = 0
    private val retried = mutableSetOf<Long>()
    private var retryHintShown = false

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
        binding.btnSpeak.setOnClickListener {
            items.getOrNull(index)?.let { speakWord(it.word.text) }
        }
        binding.btnDontKnow.setOnClickListener { markUnknown() }
        binding.btnStar.setOnClickListener { toggleStar() }

        binding.btnRateKnow.setOnClickListener { applyRating(ReviewRating.KNOW) }
        binding.btnRateFuzzy.setOnClickListener { applyRating(ReviewRating.FUZZY) }
        binding.btnRateForgot.setOnClickListener { applyRating(ReviewRating.FORGOT) }
        binding.ratingRow.isVisible = Settings.selfRatingEnabled(this)

        // 手势只保留长按收藏。左滑「我不会」已移除：它不可见、无法撤销，
// 而且一次误滑就会记一次错并重置复习等级——代价和收益不成比例。
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(event: MotionEvent) {
                if (items.isNotEmpty()) toggleStar()
            }
        })
        binding.root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
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
        val allowRepeat = intent.getBooleanExtra(EXTRA_ALLOW_REPEAT, true) || forceRepeat
        val filter = intent.getStringExtra(EXTRA_FILTER)
            ?.let { runCatching { WordFilter.valueOf(it) }.getOrNull() } ?: WordFilter.ALL
        val ids = intent.getLongArrayExtra(EXTRA_WORD_IDS)
        val requested = if (ids != null && ids.isNotEmpty()) ids.size else count

        Bg.run({
            // 出不了题的词先筛掉，再抽题；否则先抽 20 个再过滤，一轮会只剩几道
            val require: (com.worddrill.app.data.Word) -> Boolean = when (mode) {
                PracticeMode.FORM -> { word -> com.worddrill.app.data.WordForms.questionFor(word) != null }
                PracticeMode.EX_SPELL -> { word -> QuizEngine.blankExample(word.exampleEn, word.text) != null }
                else -> { _ -> true }
            }
            val words = if (ids != null && ids.isNotEmpty()) {
                repo.wordsByIds(ids).filter(require)
            } else {
                repo.pickWords(bookId, start, end, count, allowRepeat, filter, require)
            }
            val all = repo.allWords()
            QuizEngine.build(
                pool = words,
                allWords = all,
                count = requested,
                mode = mode,
                ttsEnabled = Settings.ttsEnabled(this),
            )
        }) { result ->
            binding.progressBar.isIndeterminate = false
            result.onSuccess { quiz ->
                if (quiz.isEmpty()) {
                    showEmptyDialog(allowRepeat)
                    return@onSuccess
                }
                items = quiz
                baseSize = quiz.size
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
            builder.setPositiveButton("本轮重练已练过的词") { _, _ ->
                // 不删练习记录：把「允许重复」在本轮强制打开就够了
                forceRepeat = true
                loadSession()
            }
        } else {
            builder.setPositiveButton(null, null)
        }
        builder.show()
    }

    private fun render() {
        val item = items[index]
        answered = false
        shownAt = SystemClock.elapsedRealtime()
        val itemMode = item.mode
        binding.tvProgress.text = if (mode == PracticeMode.MIXED) {
            "第 ${index + 1} 题 / 共 ${items.size} 题 · ${itemMode.label}"
        } else {
            "第 ${index + 1} 题 / 共 ${items.size} 题"
        }
        binding.progressBar.max = items.size
        binding.progressBar.setProgressCompat(index, true)
        val isListen = itemMode == PracticeMode.LISTEN_SPELL || itemMode == PracticeMode.LISTEN_ZH
        val canHear = isListen || itemMode == PracticeMode.EN_ZH
        binding.tvPromptLabel.text = when {
            isListen -> "听 发 音"
            itemMode == PracticeMode.EN_ZH -> "英 文"
            itemMode == PracticeMode.EX_SPELL -> "例 句"
            else -> "中 文 释 义"
        }
        binding.tvPrompt.text = item.prompt
        binding.tvPrompt.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            when {
                item.longPrompt -> 17f
                // 单词是这一屏的主角，给它独立字阶；释义类题干留 30sp
                itemMode == PracticeMode.EN_ZH || itemMode == PracticeMode.LISTEN_SPELL -> 40f
                else -> 30f
            },
        )
        binding.btnSpeak.isVisible = canHear
        binding.btnDontKnow.isEnabled = true
        binding.tvPrompt.setOnClickListener {
            if (canHear) speakWord(item.word.text)
        }
        if (canHear && Settings.ttsEnabled(this)) {
            speakWord(item.word.text)
        }
        binding.feedbackCard.visibility = View.GONE
        binding.btnNext.visibility = View.GONE
        binding.ratingRow.isVisible = Settings.selfRatingEnabled(this)
        setRatingEnabled(true)
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
        binding.btnDontKnow.isEnabled = false
        val correct = option == item.correctOption
        val userAnswer = item.options.getOrElse(option) { "" }

        optionRows.forEachIndexed { i, _ ->
            when {
                i == item.correctOption -> markOption(i, R.drawable.bg_option_correct, R.color.correct)
                i == option && !correct -> markOption(i, R.drawable.bg_option_wrong, R.color.wrong)
            }
        }
        val elapsed = elapsedSinceShown()
        showFeedback(correct, item, userAnswer, elapsed)
        persist(correct, userAnswer, elapsed)
        holdForReading(correct)
    }

    /**
     * 答对可以自动翻页，**答错必须停下**：讲解是这个 App 唯一的教学时刻，
     * 1.6 秒读完一整段释义是不可能的，所以等用户点「下一题」。
     */
    private fun holdForReading(correct: Boolean) {
        if (correct) {
            scheduleAdvance(advanceDelay(true))
        } else {
            pendingNext?.let { binding.root.removeCallbacks(it) }
            pendingNext = null
            binding.btnNext.setText(R.string.action_next)
            binding.btnNext.visibility = View.VISIBLE
        }
    }

    private fun markOption(index: Int, background: Int, tone: Int) {
        optionRows[index].setBackgroundResource(background)
        optionTexts[index].setTextColor(color(tone))
        optionBadges[index].backgroundTintList = ColorStateList.valueOf(color(tone))
        optionBadges[index].setTextColor(color(R.color.white))
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    /** 「我不会」：直接记为答错，进错题本并进入下一题。 */
    private fun markUnknown() {
        if (answered || items.isEmpty()) return
        val item = items[index]
        answered = true
        binding.btnDontKnow.isEnabled = false
        binding.etAnswer.isEnabled = false
        binding.btnSubmit.isEnabled = false
        val elapsed = elapsedSinceShown()
        showFeedback(false, item, "我不会", elapsed)
        persist(false, "我不会", elapsed)
        holdForReading(correct = false)
    }

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
        val correct = QuizEngine.isAcceptable(input, item.answer, item.accept)
        val elapsed = elapsedSinceShown()
        showFeedback(correct, item, input, elapsed)
        persist(correct, input, elapsed)
        binding.btnNext.visibility = View.VISIBLE
    }

    private fun elapsedSinceShown(): Long =
        if (shownAt <= 0L) 0L else (SystemClock.elapsedRealtime() - shownAt).coerceAtLeast(0L)

    private fun showFeedback(correct: Boolean, item: QuizItem, userAnswer: String, elapsed: Long) {
        ratingWordId = item.word.id
        val word = item.word
        val phonetic = word.phonetic(Settings.accent(this))
        val head = if (phonetic.isBlank()) word.text else "${word.text}  /$phonetic/"

        val title = if (correct) {
            buildString {
                append("✓ 答对了")
                if (QuizEngine.isFast(elapsed, true)) {
                    append("  ⚡ ${(elapsed / 1000.0).toSecondsShort()} 秒")
                }
            }
        } else {
            "✗ 正确答案：${item.answer}"
        }

        val detail = buildString {
            if (!correct) {
                append("\n你作答的是：").append(userAnswer)
                if (item.spell) {
                    QuizEngine.diagnose(userAnswer, item.answer)?.let { append("\n提示：").append(it) }
                }
            }
            append("\n").append(head).append(" — ").append(word.meaningWithPos)
            if (word.hasPhrase) {
                append("\n搭配：").append(word.phraseEn).append(' ').append(word.phraseZh)
            }
        }

        // 第一行加重、其余行常规字重：原来的整块粗体没有层次
        val text = android.text.SpannableStringBuilder().apply {
            val start = length
            append(title)
            setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start,
                length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            append(detail)
            setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.NORMAL),
                start + title.length,
                length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        binding.feedbackCard.visibility = View.VISIBLE
        binding.feedbackCard.setCardBackgroundColor(color(if (correct) R.color.correct_bg else R.color.wrong_bg))
        binding.tvFeedback.setTextColor(color(if (correct) R.color.correct else R.color.wrong))
        binding.tvFeedback.text = text
        speakWord(word.text)
    }

    private fun Double.toSecondsShort(): String =
        if (this >= 10) "${this.toInt()}" else String.format(java.util.Locale.US, "%.1f", this)

    private fun speakWord(text: String) {
        Tts.speak(this, text) { reason ->
            if (!ttsWarned) {
                ttsWarned = true
                toast(reason)
            }
        }
    }

    private fun persist(correct: Boolean, userAnswer: String, elapsed: Long) {
        val item = items[index]
        records.add(
            AnswerRecord(
                wordId = item.word.id,
                word = item.word.text,
                meaning = item.word.meaningWithPos,
                answer = item.answer,
                userAnswer = userAnswer,
                correct = correct,
                elapsedMs = elapsed,
            ),
        )
        Bg.run({
            repo.recordAnswer(item.word.id, item.mode, correct, elapsed)
            repo.checkAchievements()
        }) { result ->
            result.onSuccess { fresh ->
                fresh.firstOrNull()?.let { toast("🏅 解锁成就：${it.title}") }
            }
        }
        if (!correct) requeueForRetry(item)
    }

    /**
     * 答错的词本轮末尾再出现一次，不用等到第二天。
     * 同一个词只补一次，追加总量不超过原题量，避免一直错一直加。
     */
    private fun requeueForRetry(item: QuizItem) {
        if (!retried.add(item.word.id)) return
        if (baseSize <= 0 || items.size >= baseSize * 2) return
        items = items + item
        if (!retryHintShown) {
            retryHintShown = true
            toast("答错的词会在本轮末尾再出现一次")
        }
    }

    /** 开启自评后把自动前进放慢一点，否则还没来得及点就翻页了。 */
    private fun advanceDelay(correct: Boolean): Long = when {
        !Settings.selfRatingEnabled(this) -> if (correct) 850L else 1600L
        correct -> 2_200L
        else -> 3_200L
    }

    private fun scheduleAdvance(delay: Long) {
        val runnable = Runnable { advance() }
        pendingNext = runnable
        binding.root.postDelayed(runnable, delay)
    }

    /** 三档自评：覆盖判分排期，点完立刻下一题。 */
    private fun applyRating(rating: ReviewRating) {
        val wordId = ratingWordId ?: return
        if (!answered) return
        ratingWordId = null
        setRatingEnabled(false)
        pendingNext?.let { binding.root.removeCallbacks(it) }
        pendingNext = null
        Bg.run({ repo.rateWord(wordId, rating) }) { }
        toast("记为「${rating.label}」，已重排复习时间")
        advance()
    }

    private fun setRatingEnabled(enabled: Boolean) {
        binding.btnRateKnow.isEnabled = enabled
        binding.btnRateFuzzy.isEnabled = enabled
        binding.btnRateForgot.isEnabled = enabled
    }

    private fun advance() {
        pendingNext?.let { binding.root.removeCallbacks(it) }
        pendingNext = null
        ratingWordId = null
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