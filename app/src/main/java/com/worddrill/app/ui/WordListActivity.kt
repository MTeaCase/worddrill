package com.worddrill.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.worddrill.app.R
import com.worddrill.app.data.Book
import com.worddrill.app.data.LearnedStats
import com.worddrill.app.data.MistakeSort
import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.ReviewScheduler
import com.worddrill.app.data.Word
import com.worddrill.app.data.WordFilter
import com.worddrill.app.data.WordProgress
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivityWordListBinding
import com.worddrill.app.ui.adapter.WordAdapter
import com.worddrill.app.util.Bg

class WordListActivity : BaseActivity() {

    private lateinit var binding: ActivityWordListBinding
    private lateinit var repo: WordRepository

    private var filter = WordFilter.ALL
    private var bookId = -1L
    private var bookName = ""
    private var query = ""
    private var learnedScope = LearnedScope.ALL
    private var mistakeSort = MistakeSort.RECENT

    /** 数据库里的完整结果，筛选（chip）在内存里做，避免每点一下都查库。 */
    private var rawItems: List<Word> = emptyList()
    private var items: List<Word> = emptyList()
    private var progress: Map<Long, WordProgress> = emptyMap()
    private var learnedStats: LearnedStats? = null

    private var adapter: WordAdapter? = null
    private var book: Book? = null
    private var blockedTotal = 0
    private var menusReady = false

    private enum class LearnedScope { ALL, LEARNING, MASTERED, WEAK }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWordListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        repo = WordRepository(this)
        bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        bookName = intent.getStringExtra(EXTRA_BOOK_NAME).orEmpty()
        filter = intent.getStringExtra(EXTRA_FILTER)
            ?.let { runCatching { WordFilter.valueOf(it) }.getOrNull() } ?: WordFilter.ALL

        binding.toolbar.title = when (filter) {
            WordFilter.STARRED -> getString(R.string.starred_words)
            WordFilter.MISTAKES -> getString(R.string.mistake_book)
            WordFilter.LEARNED -> getString(R.string.title_learned)
            WordFilter.BLOCKED -> getString(R.string.title_blocked)
            WordFilter.ALL -> bookName.ifBlank { "词库" }
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 错题本以前不给搜，攒到几百条就没法找了
        binding.tilSearch.isVisible = filter != WordFilter.BLOCKED
        if (filter != WordFilter.BLOCKED) {
            binding.etSearch.doAfterTextChanged {
                query = it?.toString().orEmpty()
                load()
            }
        }

        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            learnedScope = when (checkedIds.first()) {
                R.id.chipMastered -> LearnedScope.MASTERED
                R.id.chipWeak -> LearnedScope.WEAK
                R.id.chipLearning -> LearnedScope.LEARNING
                else -> LearnedScope.ALL
            }
            applyScope()
        }

        adapter = WordAdapter(
            items = items,
            extraProvider = { word -> extraFor(word) },
            onStar = { word -> toggleStar(word) },
            showIndex = filter != WordFilter.BLOCKED,
            onClick = if (filter == WordFilter.BLOCKED) {
                { word -> askUnblock(word) }
            } else {
                null
            },
            onLongClick = when (filter) {
                WordFilter.ALL, WordFilter.LEARNED -> { word -> askBlock(word) }
                WordFilter.MISTAKES -> { word -> askRemoveMistake(word) }
                else -> null
            },
            onRemove = if (filter == WordFilter.MISTAKES) {
                { word -> askRemoveMistake(word) }
            } else {
                null
            },
        )

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnPractice.setOnClickListener { pickModeAndCount() }
        binding.btnPractice.isVisible = filter != WordFilter.BLOCKED

        loadBook()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun loadBook() {
        Bg.run({ repo.book(bookId) }) { result ->
            result.onSuccess { loaded ->
                if (isFinishing || isDestroyed) return@onSuccess
                book = loaded
                applyChrome()
            }.onFailure { toast("读取词库失败：${it.message}") }
        }
    }

    private fun applyChrome() {
        val custom = book?.let { !it.builtin } == true
        when {
            filter == WordFilter.MISTAKES -> setupMistakeMenu()
            filter == WordFilter.LEARNED -> setupLearnedChrome(custom)
            filter == WordFilter.BLOCKED -> setupBlockedMenu()
            filter == WordFilter.ALL && custom -> setupAllMenu()
            else -> binding.tvHint.isVisible = false
        }
    }

    private fun setupMistakeMenu() {
        setupMenu(R.menu.menu_word_list) { item ->
            if (item.itemId == R.id.action_sort) {
                showSortDialog()
                true
            } else {
                false
            }
        }
        binding.tvHint.isVisible = true
        binding.tvHint.text = getString(R.string.mistake_cleared_after_two)
        binding.btnClearMistakes.isVisible = true
        binding.btnClearMistakes.setOnClickListener { confirmClearMistakes() }
    }

    /** 汉堡按钮：选排序方式，当前用的那种会打勾。 */
    private fun showSortDialog() {
        val sorts = listOf(
            MistakeSort.RECENT to getString(R.string.mistake_sort_recent),
            MistakeSort.WRONG to getString(R.string.mistake_sort_wrong),
            MistakeSort.DUE to getString(R.string.mistake_sort_due),
            MistakeSort.ALPHABET to getString(R.string.mistake_sort_alphabet),
        )
        val labels = sorts.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.mistake_sort_label)
            .setSingleChoiceItems(labels, sorts.indexOfFirst { it.first == mistakeSort }) { dialog, which ->
                setMistakeSort(sorts[which].first)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupLearnedChrome(custom: Boolean) {
        setupMenu(R.menu.menu_learned) { item ->
            if (item.itemId == R.id.action_blocked_words) {
                startActivity(WordListActivity.intent(this, bookId, bookName, WordFilter.BLOCKED))
                true
            } else {
                false
            }
        }
        binding.chipGroup.isVisible = true
        binding.tvHint.isVisible = true
        binding.tvHint.text = if (custom) {
            getString(R.string.learned_hint)
        } else {
            "按最近练过的时间排序。点「练习这些」直接练这批词"
        }
    }

    private fun setupBlockedMenu() {
        setupMenu(R.menu.menu_blocked) { item ->
            if (item.itemId == R.id.action_unblock_all) {
                confirmUnblockAll()
                true
            } else {
                false
            }
        }
        binding.tvHint.isVisible = true
        binding.tvHint.text = getString(R.string.hint_unblock)
    }

    private fun setupAllMenu() {
        setupMenu(R.menu.menu_book_words) { item ->
            when (item.itemId) {
                R.id.action_append_words -> {
                    startActivity(
                        ImportActivity.intent(this, bookId, binding.toolbar.title?.toString().orEmpty()),
                    )
                    true
                }

                R.id.action_blocked_words -> {
                    startActivity(WordListActivity.intent(this, bookId, bookName, WordFilter.BLOCKED))
                    true
                }

                else -> false
            }
        }
        binding.tvHint.isVisible = true
        binding.tvHint.text = getString(R.string.hint_block)
    }

    private fun setupMenu(menuRes: Int, listener: (android.view.MenuItem) -> Boolean) {
        if (!menusReady) {
            binding.toolbar.inflateMenu(menuRes)
            binding.toolbar.setOnMenuItemClickListener { listener(it) }
            menusReady = true
        }
    }

    private fun setMistakeSort(sort: MistakeSort) {
        if (mistakeSort == sort) return
        mistakeSort = sort
        load()
    }

    private fun load() {
        val sort = mistakeSort
        val currentFilter = filter
        Bg.run({
            val list = repo.words(bookId, currentFilter, query, mistakeSort = sort)
            Loaded(
                words = list,
                progress = repo.progressFor(list.map { it.id }.toLongArray()),
                stats = if (currentFilter == WordFilter.LEARNED) repo.learnedStats(bookId) else null,
                blocked = repo.blockedCount(bookId),
            )
        }) { result ->
            result.onSuccess { loaded ->
                if (isFinishing || isDestroyed) return@onSuccess
                rawItems = loaded.words
                progress = loaded.progress
                learnedStats = loaded.stats
                blockedTotal = loaded.blocked
                refreshChipLabels()
                applyScope()
            }.onFailure { toast("加载失败：${it.message}") }
        }
    }

    private fun refreshChipLabels() {
        val stats = learnedStats ?: return
        binding.chipAll.text = "${getString(R.string.learned_scope_all)} ${stats.learned}"
        binding.chipLearning.text = "${getString(R.string.learned_scope_learning)} ${stats.learning}"
        binding.chipMastered.text = "${getString(R.string.learned_scope_mastered)} ${stats.mastered}"
        binding.chipWeak.text = "${getString(R.string.learned_scope_weak)} ${stats.weak}"
    }

    private fun applyScope() {
        val filtered = if (filter == WordFilter.LEARNED) {
            rawItems.filter { word ->
                val p = progress[word.id] ?: WordProgress()
                when (learnedScope) {
                    LearnedScope.ALL -> true
                    LearnedScope.MASTERED -> p.level >= 5
                    LearnedScope.LEARNING -> p.level in 1..4
                    // 易忘：答错过，或者复习时归零过 2 次以上
                    LearnedScope.WEAK -> p.wrong > 0 || p.lapses >= 2
                }
            }
        } else {
            rawItems
        }
        items = filtered
        adapter?.submit(filtered)
        updateCounts()
    }

    private fun updateCounts() {
        binding.tvCount.text = when (filter) {
            WordFilter.LEARNED -> {
                val stats = learnedStats
                if (stats != null && learnedScope == LearnedScope.ALL && query.isBlank()) {
                    getString(
                        R.string.learned_summary,
                        stats.learned,
                        stats.total,
                        stats.mastered,
                        stats.weak,
                    )
                } else if (items.isEmpty()) {
                    getString(R.string.learned_empty)
                } else {
                    "共 ${items.size} 个"
                }
            }

            WordFilter.MISTAKES -> when {
                items.isEmpty() -> getString(R.string.mistake_empty)
                blockedTotal > 0 -> "共 ${items.size} 个错题 · 答对 2 次后自动移出"
                else -> "共 ${items.size} 个错题"
            }

            WordFilter.BLOCKED ->
                if (items.isEmpty()) "还没有屏蔽任何单词" else "共 ${items.size} 个已屏蔽单词"

            else -> if (blockedTotal > 0) {
                "共 ${items.size} 个单词 · 屏蔽 $blockedTotal 个（长按可屏蔽）"
            } else {
                "共 ${items.size} 个单词"
            }
        }

        binding.toolbar.menu.findItem(R.id.action_blocked_words)?.title =
            getString(R.string.blocked_words) + "（$blockedTotal）"

        val canPractice = items.isNotEmpty() && filter != WordFilter.BLOCKED
        binding.btnPractice.isEnabled = canPractice
        binding.btnPractice.text = when {
            items.isEmpty() -> "还没有单词"
            filter == WordFilter.LEARNED -> "练习这些单词（${items.size}）"
            else -> getString(R.string.action_practice_these)
        }
    }

    /** 每行下面那行小字：错题本看错误次数，已学过看练习次数和下次复习时间。 */
    private fun extraFor(word: Word): String? = when (filter) {
        WordFilter.MISTAKES -> {
            val p = progress[word.id] ?: WordProgress()
            val parts = mutableListOf<String>()
            if (p.wrong > 0) parts.add("错 ${p.wrong} 次")
            if (p.streak > 0) parts.add("已连对 ${p.streak} 次")
            if (p.lastAt > 0) parts.add("上次 ${agoLabel(p.lastAt)}")
            modeLabel(p.lastMode).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            if (p.level >= 5) parts.add("已掌握")
            parts.joinToString(" · ").ifEmpty { null }
        }

        WordFilter.LEARNED -> {
            val p = progress[word.id]
            if (p == null) {
                null
            } else {
                val parts = mutableListOf<String>()
                parts.add("${p.level} 级")
                parts.add("练过 ${p.times} 次")
                if (p.wrong > 0) parts.add("错 ${p.wrong} 次")
                parts.add(dueLabel(p.dueAt))
                parts.joinToString(" · ")
            }
        }

        else -> null
    }

    private fun modeLabel(modeName: String): String =
        if (modeName.isBlank()) {
            ""
        } else {
            runCatching { PracticeMode.valueOf(modeName).label }.getOrDefault("")
        }

    private fun agoLabel(at: Long): String {
        if (at <= 0L) return "—"
        val diff = (System.currentTimeMillis() - at).coerceAtLeast(0L)
        val days = (diff / ReviewScheduler.DAY_MS).toInt()
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            days == 0 -> "今天"
            days == 1 -> "昨天"
            days < 30 -> "$days 天前"
            else -> "${days / 30} 个月前"
        }
    }

    private fun dueLabel(dueAt: Long): String {
        val diff = dueAt - System.currentTimeMillis()
        if (diff <= 0L) return "今天该复习"
        val days = ((diff + ReviewScheduler.DAY_MS - 1) / ReviewScheduler.DAY_MS).toInt()
        return when {
            days <= 1 -> "明天复习"
            days <= 30 -> "$days 天后复习"
            else -> "很久以后复习"
        }
    }

    private fun toggleStar(word: Word) {
        Bg.run({ repo.setStar(word.id, !word.starred) }) { result ->
            result.onSuccess {
                if (filter == WordFilter.STARRED) {
                    load()
                } else {
                    val updated = items.map { if (it.id == word.id) it.copy(starred = !word.starred) else it }
                    items = updated
                    rawItems = rawItems.map { if (it.id == word.id) it.copy(starred = !word.starred) else it }
                    adapter?.submit(updated)
                }
            }.onFailure { toast("操作失败：${it.message}") }
        }
    }

    private fun askBlock(word: Word) {
        if (book?.builtin != false) return
        AlertDialog.Builder(this)
            .setTitle(word.text)
            .setMessage("${word.meaningWithPos}\n\n屏蔽后这个词不会再出现在词表和练习里，后面的单词索引会自动前移。")
            .setPositiveButton(R.string.action_block) { _, _ ->
                setBlocked(word, true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askUnblock(word: Word) {
        AlertDialog.Builder(this)
            .setTitle(word.text)
            .setMessage("${word.meaningWithPos}\n\n恢复后回到它在词库里的原位置，索引会重新排。")
            .setPositiveButton(R.string.action_unblock) { _, _ ->
                setBlocked(word, false)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askRemoveMistake(word: Word) {
        AlertDialog.Builder(this)
            .setTitle(word.text)
            .setMessage("${word.meaningWithPos}\n\n${getString(R.string.mistake_remove_confirm)}")
            .setPositiveButton(R.string.action_remove_mistake) { _, _ ->
                Bg.run({ repo.removeMistake(word.id) }) { result ->
                    result.onSuccess {
                        toast("已移出错题本")
                        load()
                    }.onFailure { toast("操作失败：${it.message}") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearMistakes() {
        if (items.isEmpty()) {
            toast("错题本是空的")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_clear_mistakes)
            .setMessage(getString(R.string.mistake_clear_confirm))
            .setPositiveButton(R.string.action_clear_mistakes) { _, _ ->
                Bg.run({ repo.clearMistakes(bookId) }) { result ->
                    result.onSuccess {
                        toast("已清空错题本")
                        load()
                    }.onFailure { toast("操作失败：${it.message}") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setBlocked(word: Word, blocked: Boolean) {
        Bg.run({ repo.setBlocked(word.id, blocked) }) { result ->
            result.onSuccess {
                toast(if (blocked) "已屏蔽 ${word.text}" else "已恢复 ${word.text}")
                load()
            }.onFailure { toast("操作失败：${it.message}") }
        }
    }

    private fun confirmUnblockAll() {
        if (blockedTotal == 0) {
            toast("没有被屏蔽的单词")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("恢复全部屏蔽单词？")
            .setMessage("会把 ${bookName.ifBlank { "本词库" }} 里被屏蔽的 $blockedTotal 个单词全部恢复。")
            .setPositiveButton(R.string.action_unblock_all) { _, _ ->
                Bg.run({ repo.unblockAll(bookId) }) { result ->
                    result.onSuccess {
                        toast("已全部恢复")
                        load()
                    }.onFailure { toast("操作失败：${it.message}") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickModeAndCount() {
        if (items.isEmpty()) {
            toast("还没有可练习的单词")
            return
        }
        val modes = PracticeMode.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择练习方式")
            .setItems(modes) { _, which ->
                pickCount(PracticeMode.entries[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 直接把这批词的 id 传过去，不再让练习页重新随机抽一遍——
     * 否则「练这些」练到的和列表里看到的可能不是同一批。
     */
    private fun pickCount(mode: PracticeMode) {
        val total = items.size
        if (total == 0) return
        val options = linkedMapOf(
            "10 题" to minOf(10, total),
            "20 题" to minOf(20, total),
            "全部（$total 题）" to total,
        )
        val labels = options.keys.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("本轮题量")
            .setItems(labels) { _, which ->
                val count = options[labels[which]] ?: return@setItems
                startActivity(
                    PracticeActivity.retryIntent(
                        context = this,
                        mode = mode,
                        bookName = binding.toolbar.title?.toString().orEmpty(),
                        wordIds = items.take(count).map { it.id }.toLongArray(),
                    ),
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private data class Loaded(
        val words: List<Word>,
        val progress: Map<Long, WordProgress>,
        val stats: LearnedStats?,
        val blocked: Int,
    )

    companion object {
        private const val EXTRA_BOOK_ID = "book_id"
        private const val EXTRA_BOOK_NAME = "book_name"
        private const val EXTRA_FILTER = "filter"

        fun intent(context: Context, bookId: Long, bookName: String, filter: WordFilter): Intent =
            Intent(context, WordListActivity::class.java).apply {
                putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_BOOK_NAME, bookName)
                putExtra(EXTRA_FILTER, filter.name)
            }
    }
}
