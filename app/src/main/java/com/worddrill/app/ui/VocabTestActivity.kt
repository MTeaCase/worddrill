package com.worddrill.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import com.worddrill.app.R
import com.worddrill.app.data.Book
import com.worddrill.app.data.Word
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivityVocabTestBinding
import com.worddrill.app.util.Bg
import com.worddrill.app.util.Settings

/**
 * 词汇量自测：按 ord 高/中/低频三段各抽 10 个词，让用户自评「认识 / 不认识」，
 * 再用认识率外推整个词库的词汇量。纯本地，30 个词一轮。
 */
class VocabTestActivity : BaseActivity() {

    private lateinit var binding: ActivityVocabTestBinding
    private lateinit var repo: WordRepository

    private var book: Book? = null
    private var samples: List<Word> = emptyList()
    private var index = 0
    private var known = 0
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVocabTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        repo = WordRepository(this)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnKnown.setOnClickListener { answer(true) }
        binding.btnSkip.setOnClickListener { answer(false) }
        binding.progressBar.max = SAMPLE_SIZE
        binding.tvProgress.text = "准备中…"
        loadSamples()
    }

    private fun loadSamples() {
        val bookId = Settings.selectedBookId(this)
        Bg.run({
            val books = repo.books()
            val target = books.firstOrNull { it.id == bookId } ?: books.firstOrNull()
            val total = target?.let { repo.wordCount(it.id) } ?: 0
            val words = if (target == null || total == 0) {
                emptyList()
            } else {
                val third = total / 3
                val ranges = listOf(
                    1..third.coerceAtLeast(1),
                    (third + 1)..(third * 2).coerceAtLeast(third + 1),
                    (third * 2 + 1)..total,
                )
                ranges.filter { !it.isEmpty() }.flatMap { range ->
                    repo.pickWords(
                        bookId = target.id,
                        startOrd = range.first,
                        endOrd = range.last,
                        count = PER_BAND,
                        allowRepeat = true,
                    )
                }
            }
            target to words
        }) { result ->
            result.onSuccess { (target, words) ->
                if (isFinishing || isDestroyed) return@onSuccess
                book = target
                samples = words.distinctBy { it.id }
                if (samples.isEmpty()) {
                    binding.tvProgress.text = "这个词库还没有单词"
                    binding.answerRow.isVisible = false
                    return@onSuccess
                }
                started = true
                render()
            }.onFailure {
                toast("读取词库失败：${it.message}")
            }
        }
    }

    private fun render() {
        val word = samples.getOrNull(index) ?: return
        binding.tvProgress.text = "${index + 1} / ${samples.size}"
        binding.progressBar.setProgressCompat(index, true)
        binding.tvWord.text = word.text
        val phonetic = word.phonetic(Settings.accent(this))
        binding.tvPhonetic.text = if (phonetic.isBlank()) "" else "/$phonetic/"
        binding.answerRow.isVisible = true
    }

    private fun answer(isKnown: Boolean) {
        if (!started) return
        if (isKnown) known++
        index++
        if (index >= samples.size) {
            finishTest()
        } else {
            render()
        }
    }

    private fun finishTest() {
        started = false
        val total = book?.wordCount ?: 0
        val ratio = known.toDouble() / samples.size
        val estimate = (total * ratio).toInt()
        binding.answerRow.isVisible = false
        binding.progressBar.setProgressCompat(samples.size, true)
        binding.tvProgress.text = "自测完成"
        binding.tvWord.text = "≈ $estimate"
        binding.tvPhonetic.text = book?.name.orEmpty()
        binding.tvResult.isVisible = true
        binding.tvResult.text = buildString {
            append(getString(R.string.vocab_test_result, estimate, samples.size, known))
            append("\n词库总词数 $total，按认识率 ${(ratio * 100).toInt()}% 外推。")
            append("\n抽样的三段分别是词库前 / 中 / 后 1/3，越靠后越生僻。")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 高/中/低频各抽 10 个。 */
        private const val PER_BAND = 10
        private const val SAMPLE_SIZE = 30
    }
}