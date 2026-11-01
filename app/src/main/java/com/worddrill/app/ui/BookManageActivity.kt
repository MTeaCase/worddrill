package com.worddrill.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.worddrill.app.data.Book
import com.worddrill.app.data.WordFilter
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivityBooksBinding
import com.worddrill.app.ui.adapter.BookAdapter
import com.worddrill.app.util.Bg

class BookManageActivity : BaseActivity() {

    private lateinit var binding: ActivityBooksBinding
    private lateinit var repo: WordRepository
    private var adapter: BookAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBooksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        repo = WordRepository(this)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)

        adapter = BookAdapter(
            items = emptyList(),
            onOpen = { book ->
                startActivity(WordListActivity.intent(this, book.id, book.name, WordFilter.ALL))
            },
            onDelete = { book -> confirmDelete(book) },
        )
        binding.recycler.adapter = adapter

        binding.btnImport.setOnClickListener {
            startActivity(ImportActivity.intent(this))
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        Bg.run({ repo.books() }) { result ->
            result.onSuccess { list ->
                if (isFinishing || isDestroyed) return@onSuccess
                adapter?.submit(list)
            }.onFailure { toast("读取失败：${it.message}") }
        }
    }

    private fun confirmDelete(book: Book) {
        AlertDialog.Builder(this)
            .setTitle("删除「${book.name}」？")
            .setMessage("词库里的 ${book.wordCount} 个单词会一起删除，无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                Bg.run({ repo.deleteBook(book.id) }) { result ->
                    result.onSuccess {
                        toast("已删除")
                        load()
                    }.onFailure { toast("删除失败：${it.message}") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
