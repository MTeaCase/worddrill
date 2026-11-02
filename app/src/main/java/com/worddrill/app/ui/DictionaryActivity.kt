package com.worddrill.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.worddrill.app.R
import com.worddrill.app.data.Book
import com.worddrill.app.data.Word
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivityDictionaryBinding
import com.worddrill.app.ui.adapter.WordAdapter
import com.worddrill.app.util.Bg
import com.worddrill.app.util.Tts

/**
 * 词典：跨词库搜索，中英文都支持 LIKE 模糊匹配。
 * 点条目弹出详情（释义 + 发音）。
 */
class DictionaryActivity : BaseActivity() {

    private lateinit var binding: ActivityDictionaryBinding
    private lateinit var repo: WordRepository
    private lateinit var adapter: WordAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var bookNames: Map<Long, String> = emptyMap()
    private var items: List<Word> = emptyList()
    private var searchSeq = 0
    private var ttsWarned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDictionaryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        repo = WordRepository(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = WordAdapter(
            items = emptyList(),
            extraProvider = { word -> extraFor(word) },
            onStar = { word -> toggleStar(word) },
            showIndex = false,
            onClick = { word -> showDetail(word) },
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.etSearch.doAfterTextChanged { editable ->
            scheduleSearch(editable?.toString().orEmpty())
        }

        Bg.run({ repo.books() }) { result ->
            result.onSuccess { list: List<Book> ->
                bookNames = list.associate { it.id to it.name }
            }
        }
        renderEmpty()
    }

    override fun onResume() {
        super.onResume()
        if (binding.etSearch.text?.isNotBlank() == true) {
            scheduleSearch(binding.etSearch.text.toString(), immediate = true)
        }
    }

    private fun scheduleSearch(query: String, immediate: Boolean = false) {
        pendingSearch?.let { handler.removeCallbacks(it) }
        if (query.isBlank()) {
            adapter.submit(emptyList())
            items = emptyList()
            renderEmpty()
            return
        }
        val runnable = Runnable { runSearch(query) }
        pendingSearch = runnable
        handler.postDelayed(runnable, if (immediate) 0L else 250L)
    }

    private fun runSearch(query: String) {
        val seq = ++searchSeq
        Bg.run({ repo.searchWords(query) }) { result ->
            result.onSuccess { found ->
                if (seq != searchSeq || isFinishing || isDestroyed) return@onSuccess
                items = found
                adapter.submit(found)
                binding.tvEmpty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
                binding.tvEmpty.text = if (found.isEmpty()) {
                    "没有找到「$query」\n试试只输入几个字母，或直接搜中文释义"
                } else {
                    ""
                }
                binding.tvCount.text = if (found.isEmpty()) "" else "找到 ${found.size} 个单词"
            }.onFailure {
                toast("搜索失败：${it.message}")
            }
        }
    }

    private fun renderEmpty() {
        binding.tvEmpty.visibility = View.VISIBLE
        binding.tvEmpty.text = "输入英文或中文都能搜\n比如 abandon、放弃、抛弃"
        binding.tvCount.text = ""
    }

    private fun extraFor(word: Word): String? = bookNames[word.bookId]

    private fun showDetail(word: Word) {
        AlertDialog.Builder(this)
            .setTitle(word.text)
            .setMessage(word.meaningWithPos)
            .setPositiveButton(R.string.action_pronounce) { _, _ -> speakWord(word.text) }
            .setNeutralButton(R.string.action_practice_this) { _, _ ->
                startActivity(
                    PracticeActivity.retryIntent(
                        context = this,
                        mode = com.worddrill.app.data.PracticeMode.EN_ZH,
                        bookName = bookNames[word.bookId].orEmpty(),
                        wordIds = longArrayOf(word.id),
                    ),
                )
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun toggleStar(word: Word) {
        val target = !word.starred
        Bg.run({ repo.setStar(word.id, target) }) { result ->
            result.onSuccess {
                val updated = items.map { if (it.id == word.id) it.copy(starred = target) else it }
                items = updated
                adapter.submit(updated)
            }.onFailure { toast("操作失败：${it.message}") }
        }
    }

    private fun speakWord(text: String) {
        Tts.speak(this, text) { reason ->
            if (!ttsWarned) {
                ttsWarned = true
                toast(reason)
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, DictionaryActivity::class.java)
    }
}
