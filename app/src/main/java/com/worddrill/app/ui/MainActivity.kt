package com.worddrill.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.worddrill.app.R
import com.worddrill.app.data.Book
import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.WordFilter
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivityMainBinding
import com.worddrill.app.util.Bg
import com.worddrill.app.util.Settings
import com.worddrill.app.util.ApkInstaller
import com.worddrill.app.util.ReviewReminder
import com.worddrill.app.util.Updater
import com.worddrill.app.util.WidgetUpdater

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: WordRepository

    private var books: List<Book> = emptyList()
    private var selectedBookId = -1L
    private var lastAppliedBookId = -1L
    private var mode: PracticeMode = PracticeMode.EN_ZH
    private var ordRange: IntRange? = null
    private var pendingOpenReview = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        repo = WordRepository(this)
        savedInstanceState?.let {
            selectedBookId = it.getLong(KEY_BOOK, -1L)
            mode = runCatching { PracticeMode.valueOf(it.getString(KEY_MODE) ?: "") }.getOrDefault(PracticeMode.EN_ZH)
        }

        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(SettingsActivity.intent(this))
                    true
                }

                R.id.action_clear_log -> {
                    confirmClearLog()
                    true
                }

                R.id.action_dictionary -> {
                    startActivity(DictionaryActivity.intent(this))
                    true
                }

                R.id.action_report -> {
                    startActivity(ReportActivity.intent(this))
                    true
                }

                else -> false
            }
        }

        binding.etBook.setOnItemClickListener { _, _, position, _ ->
            onBookPicked(position)
        }
        binding.etBook.setOnClickListener { binding.etBook.showDropDown() }

        binding.cardEnZh.setOnClickListener { selectMode(PracticeMode.EN_ZH) }
        binding.cardZhEn.setOnClickListener { selectMode(PracticeMode.ZH_EN) }
        binding.cardSpell.setOnClickListener { selectMode(PracticeMode.ZH_EN_SPELL) }
        binding.cardListen.setOnClickListener { selectMode(PracticeMode.LISTEN_SPELL) }

        binding.btnStart.setOnClickListener { startPractice() }
        binding.btnReview.setOnClickListener { startReview() }
        binding.cardHero.setOnClickListener { startActivity(ReportActivity.intent(this)) }
        binding.btnWordList.setOnClickListener { openList(WordFilter.ALL) }
        binding.btnStarred.setOnClickListener { openList(WordFilter.STARRED) }
        binding.btnMistakes.setOnClickListener { openList(WordFilter.MISTAKES) }
        binding.btnManageBooks.setOnClickListener {
            startActivity(Intent(this, BookManageActivity::class.java))
        }

        selectMode(mode)
        Updater.autoCheck(this)
        // 从复习提醒的通知点进来时，加载完词库直接弹复习模式选择
        pendingOpenReview = intent?.getBooleanExtra(ReviewReminder.EXTRA_OPEN_REVIEW, false) == true
    }

    override fun onResume() {
        super.onResume()
        // 从「安装未知应用」设置页返回后，继续未完成的安装
        ApkInstaller.onActivityResume(this)
        loadBooks()
        WidgetUpdater.refresh(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_BOOK, selectedBookId)
        outState.putString(KEY_MODE, mode.name)
    }

    private fun selectMode(target: PracticeMode) {
        mode = target
        val rows = listOf(
            Triple(PracticeMode.EN_ZH, binding.cardEnZh, binding.ivCheckEnZh),
            Triple(PracticeMode.ZH_EN, binding.cardZhEn, binding.ivCheckZhEn),
            Triple(PracticeMode.ZH_EN_SPELL, binding.cardSpell, binding.ivCheckSpell),
            Triple(PracticeMode.LISTEN_SPELL, binding.cardListen, binding.ivCheckListen),
        )
        rows.forEach { (rowMode, card, check) ->
            val selected = rowMode == target
            card.strokeWidth = dp(if (selected) 2 else 1)
            card.strokeColor = color(if (selected) R.color.brand else R.color.divider)
            card.setCardBackgroundColor(color(if (selected) R.color.brand_soft else R.color.surface))
            check.setImageResource(
                if (selected) R.drawable.ic_check_circle else R.drawable.ic_radio_unchecked,
            )
            check.imageTintList =
                ColorStateList.valueOf(color(if (selected) R.color.brand else R.color.text_hint))
        }
    }

    private fun loadBooks() {
        Bg.run({ repo.books() }) { result ->
            result.onSuccess { list ->
                if (isFinishing || isDestroyed) return@onSuccess
                books = list
                if (list.isEmpty()) {
                    binding.tvHeroTitle.text = "还没有词库"
                    binding.tvHeroSub.text = "去「词库管理」导入自组词汇库"
                    return@onSuccess
                }
                val labels = list.map { it.name }
                binding.etBook.setAdapter(
                    ArrayAdapter(this, android.R.layout.simple_list_item_1, labels),
                )
                val index = list.indexOfFirst { it.id == selectedBookId }.takeIf { it >= 0 } ?: 0
                val book = list[index]
                val changed = book.id != lastAppliedBookId
                selectedBookId = book.id
                lastAppliedBookId = book.id
                binding.etBook.setText(book.name, false)
                applyBook(book, resetRange = changed)
            }.onFailure {
                toast("读取词库失败：${it.message}")
            }
        }
    }

    private fun onBookPicked(position: Int) {
        val book = books.getOrNull(position) ?: return
        if (book.id == selectedBookId) return
        selectedBookId = book.id
        lastAppliedBookId = book.id
        binding.etBook.setText(book.name, false)
        applyBook(book, resetRange = true)
    }

    private fun applyBook(book: Book, resetRange: Boolean) {
        Bg.run({
            val range = repo.ordRange(book.id)
            val practiced = repo.practicedCount(book.id)
            val blocked = repo.blockedCount(book.id)
            val summary = repo.studySummary(7, Settings.dailyGoal(this@MainActivity))
            BookStats(
                range,
                practiced,
                blocked,
                summary.todayAnswered,
                summary.dailyGoal,
                summary.streak,
                summary.dueReview,
            )
        }) { result ->
            result.onSuccess { stats ->
                if (isFinishing || isDestroyed) return@onSuccess
                val range = stats.range
                ordRange = range
                val max = range?.last ?: 0
                val total = book.wordCount
                binding.tvHeroTitle.text = "已练 ${stats.practiced} / $total 词"
                binding.pbHero.max = maxOf(total, 1)
                binding.pbHero.setProgressCompat(stats.practiced.coerceAtMost(total), true)
                binding.tvHeroSub.text = buildString {
                    if (stats.todayAnswered > 0 || stats.streak > 0) {
                        append("今日 ${stats.todayAnswered}/${stats.dailyGoal} 题")
                        if (stats.streak > 0) append(" · 连续 ${stats.streak} 天")
                        append("\n")
                    }
                    if (max > 0) {
                        append("索引 1 – $max · 还有 ${(total - stats.practiced).coerceAtLeast(0)} 词未练")
                    } else if (stats.blocked > 0) {
                        append("${stats.blocked} 个单词都被屏蔽了，取消屏蔽后才会出现")
                    } else {
                        append("这个词库还没有单词")
                    }
                    if (stats.blocked > 0 && max > 0) append(" · 屏蔽 ${stats.blocked} 个")
                }
                binding.tvBookInfo.text = buildString {
                    append("共 $total 词")
                    if (stats.blocked > 0) append(" · 屏蔽 ${stats.blocked}")
                }
                binding.btnReview.isEnabled = stats.dueReview > 0
                binding.btnReview.text = if (stats.dueReview > 0) {
                    "复习今日到期的 ${stats.dueReview} 个单词"
                } else {
                    getString(R.string.review_none)
                }
                binding.tvRangeHint.text = buildString {
                    if (max > 0) {
                        append("可填 1 – $max；留空表示整本词库")
                        if (stats.blocked > 0) append("（不含屏蔽词）")
                    } else if (stats.blocked > 0) {
                        append("没有可输入的索引：单词都被屏蔽了")
                    } else {
                        append("这个词库还没有单词")
                    }
                }
                if (resetRange) {
                    binding.etStart.setText(if (max > 0) "1" else "")
                    binding.etEnd.setText(if (max > 0) max.toString() else "")
                } else if (max > 0) {
                    // 屏蔽/恢复单词后索引范围会变，把超出上限的输入夹回来
                    val start = binding.etStart.text?.toString()?.toIntOrNull()
                    val end = binding.etEnd.text?.toString()?.toIntOrNull()
                    if (end != null && end > max) binding.etEnd.setText(max.toString())
                    if (start != null && start > max) binding.etStart.setText("1")
                    val startNow = binding.etStart.text?.toString()?.toIntOrNull() ?: 1
                    val endNow = binding.etEnd.text?.toString()?.toIntOrNull() ?: max
                    if (startNow > endNow) binding.etStart.setText("1")
                }
                if (pendingOpenReview) {
                    pendingOpenReview = false
                    startReview()
                }
            }.onFailure { toast("读取词库信息失败：${it.message}") }
        }
    }

    private fun startPractice() {
        val book = books.firstOrNull { it.id == selectedBookId } ?: run {
            toast("还没有可用的词库")
            return
        }
        val count = binding.etCount.text?.toString()?.trim()?.toIntOrNull() ?: 0
        if (count !in 1..200) {
            toast("题量请填 1 – 200")
            return
        }
        val max = ordRange?.last ?: 0
        if (max == 0) {
            toast("这个词库里没有可练习的单词")
            return
        }
        val startText = binding.etStart.text?.toString()?.trim().orEmpty()
        val endText = binding.etEnd.text?.toString()?.trim().orEmpty()
        val start = if (startText.isEmpty()) 1 else startText.toIntOrNull() ?: run {
            toast("起始索引无效")
            return
        }
        val end = if (endText.isEmpty()) max else endText.toIntOrNull() ?: run {
            toast("结束索引无效")
            return
        }
        if (start < 1 || end > max || start > end) {
            toast("索引范围应在 1 – $max 之间，且起始不大于结束")
            return
        }

        startActivity(
            PracticeActivity.intent(
                context = this,
                bookId = book.id,
                bookName = book.name,
                mode = mode,
                count = count,
                startOrd = start,
                endOrd = end,
                filter = WordFilter.ALL,
                allowRepeat = binding.cbAllowRepeat.isChecked,
            ),
        )
    }

    /** 取出今天到期的单词，选个模式直接开练（间隔重复复习）。 */
    private fun startReview() {
        Bg.run({ repo.dueReviewWords(200) }) { result ->
            result.onSuccess { words ->
                if (isFinishing || isDestroyed) return@onSuccess
                if (words.isEmpty()) {
                    toast(getString(R.string.review_none))
                    loadBooks()
                    return@onSuccess
                }
                val labels = PracticeMode.entries.map { it.label }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("复习 ${words.size} 个单词")
                    .setItems(labels) { _, which ->
                        startActivity(
                            PracticeActivity.retryIntent(
                                context = this,
                                mode = PracticeMode.entries[which],
                                bookName = "复习",
                                wordIds = words.map { it.id }.toLongArray(),
                            ),
                        )
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }.onFailure { toast("读取复习列表失败：${it.message}") }
        }
    }

    private fun openList(filter: WordFilter) {
        val book = books.firstOrNull { it.id == selectedBookId } ?: run {
            toast("还没有可用的词库")
            return
        }
        startActivity(WordListActivity.intent(this, book.id, book.name, filter))
    }

    private fun confirmClearLog() {
        AlertDialog.Builder(this)
            .setTitle("清空练习记录？")
            .setMessage("清空后所有单词都会重新被视为「没练过」，星标和错题不受影响。")
            .setPositiveButton("清空") { _, _ ->
                Bg.run({ repo.clearPracticeLog() }) { result ->
                    result.onSuccess {
                        toast("已清空练习记录")
                        loadBooks()
                    }.onFailure { toast("清空失败：${it.message}") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private data class BookStats(
        val range: IntRange?,
        val practiced: Int,
        val blocked: Int,
        val todayAnswered: Int,
        val dailyGoal: Int,
        val streak: Int,
        val dueReview: Int,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_BOOK = "selected_book"
        private const val KEY_MODE = "selected_mode"
    }
}
