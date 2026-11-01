package com.worddrill.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.worddrill.app.R
import com.worddrill.app.data.Book
import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.Word
import com.worddrill.app.data.WordFilter
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
    private var items: List<Word> = emptyList()
    private var wrongCounts: Map<Long, Int> = emptyMap()
    private var adapter: WordAdapter? = null
    private var book: Book? = null
    private var blockedTotal = 0
    private var menusReady = false

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
            WordFilter.STARRED -> "星标词汇"
            WordFilter.MISTAKES -> "错题本"
            WordFilter.BLOCKED -> getString(R.string.title_blocked)
            WordFilter.ALL -> bookName.ifBlank { "词库" }
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (filter == WordFilter.MISTAKES) {
            binding.tilSearch.isVisible = false
        } else {
            binding.tilSearch.isVisible = true
            binding.etSearch.doAfterTextChanged {
                query = it?.toString().orEmpty()
                load()
            }
        }

        adapter = WordAdapter(
            items = items,
            extraProvider = { word -> wrongCounts[word.id]?.let { "错 $it 次" } },
            onStar = { word -> toggleStar(word) },
            showIndex = filter != WordFilter.BLOCKED,
            onClick = if (filter == WordFilter.BLOCKED) {
                { word -> askUnblock(word) }
            } else {
                null
            },
            onLongClick = if (filter == WordFilter.ALL) {
                { word -> askBlock(word) }
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
        if (filter == WordFilter.ALL && custom) {
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
        } else if (filter == WordFilter.BLOCKED) {
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
        } else {
            binding.tvHint.isVisible = false
        }
    }

    private fun setupMenu(menuRes: Int, listener: (android.view.MenuItem) -> Boolean) {
        if (!menusReady) {
            binding.toolbar.inflateMenu(menuRes)
            binding.toolbar.setOnMenuItemClickListener { listener(it) }
            menusReady = true
        }
    }

    private fun load() {
        Bg.run({
            val list: List<Word>
            val counts: Map<Long, Int>
            if (filter == WordFilter.MISTAKES) {
                val mistakes = repo.mistakes(bookId)
                list = mistakes.map { it.word }
                counts = mistakes.associate { it.word.id to it.wrongCount }
            } else {
                list = repo.words(bookId, filter, query)
                counts = emptyMap()
            }
            Loaded(list, counts, repo.blockedCount(bookId))
        }) { result ->
            result.onSuccess { loaded ->
                if (isFinishing || isDestroyed) return@onSuccess
                items = loaded.words
                wrongCounts = loaded.counts
                blockedTotal = loaded.blocked
                adapter?.submit(loaded.words)
                binding.tvCount.text = countLabel(loaded.words.size, loaded.blocked)
                binding.toolbar.menu.findItem(R.id.action_blocked_words)?.title =
                    getString(R.string.blocked_words) + "（${loaded.blocked}）"
                val canPractice = loaded.words.isNotEmpty() && filter != WordFilter.BLOCKED
                binding.btnPractice.isEnabled = canPractice
                binding.btnPractice.text =
                    if (loaded.words.isEmpty()) "还没有单词" else getString(R.string.action_practice_these)
            }.onFailure { toast("加载失败：${it.message}") }
        }
    }

    private fun countLabel(count: Int, blocked: Int): String = when {
        filter == WordFilter.BLOCKED ->
            if (count == 0) "还没有屏蔽任何单词" else "共 $count 个已屏蔽单词"

        blocked > 0 -> "共 $count 个单词 · 屏蔽 $blocked 个（长按可屏蔽）"
        else -> "共 $count 个单词"
    }

    private fun toggleStar(word: Word) {
        Bg.run({ repo.setStar(word.id, !word.starred) }) { result ->
            result.onSuccess {
                if (filter == WordFilter.STARRED) {
                    load()
                } else {
                    val updated = items.map { if (it.id == word.id) it.copy(starred = !word.starred) else it }
                    items = updated
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

    private fun pickCount(mode: PracticeMode) {
        val total = items.size
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
                    PracticeActivity.intent(
                        context = this,
                        bookId = bookId,
                        bookName = binding.toolbar.title.toString(),
                        mode = mode,
                        count = count,
                        startOrd = 1,
                        endOrd = 1_000_000,
                        filter = filter,
                        allowRepeat = true,
                    ),
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private data class Loaded(val words: List<Word>, val counts: Map<Long, Int>, val blocked: Int)

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
