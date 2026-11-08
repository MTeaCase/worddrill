package com.worddrill.app.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.worddrill.app.R
import com.worddrill.app.data.Book
import com.worddrill.app.data.WordFilter
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.FragmentLibraryBinding
import com.worddrill.app.ui.DictionaryActivity
import com.worddrill.app.ui.ImportActivity
import com.worddrill.app.ui.MainActivity
import com.worddrill.app.ui.WordListActivity
import com.worddrill.app.ui.adapter.LibraryBookAdapter
import com.worddrill.app.util.Bg
import com.worddrill.app.util.Settings

/** 词库 tab：顶部词典搜索条，下面是词库列表，点卡片切换当前词库。 */
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: WordRepository
    private var adapter: LibraryBookAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = WordRepository(requireContext())

        adapter = LibraryBookAdapter(
            items = emptyList(),
            onSelect = { item -> selectBook(item.book) },
            onWords = { item -> openWords(item.book) },
            onDelete = { book -> confirmDelete(book) },
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.searchBar.setOnClickListener {
            startActivity(DictionaryActivity.intent(requireContext()))
        }
        binding.btnImport.setOnClickListener {
            startActivity(ImportActivity.intent(requireContext()))
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) load()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun load() {
        val current = Settings.selectedBookId(requireContext())
        Bg.run({
            val books = repo.books()
            books.map { book ->
                LibraryBookAdapter.LibraryBook(
                    book = book,
                    practiced = repo.practicedCount(book.id),
                    selected = book.id == current,
                )
            }
        }) { result ->
            result.onSuccess { list ->
                if (_binding == null || !isAdded) return@onSuccess
                adapter?.submit(list)
            }.onFailure {
                if (_binding != null) toast("读取词库失败：${it.message}")
            }
        }
    }

    private fun selectBook(book: Book) {
        Settings.setSelectedBookId(requireContext(), book.id)
        load()
        // 选完直接回练习页，省得用户再点一次底部导航
        (activity as? MainActivity)?.selectTab(R.id.tab_practice)
        toast("已切换到「${book.name}」")
    }

    private fun openWords(book: Book) {
        startActivity(WordListActivity.intent(requireContext(), book.id, book.name, WordFilter.ALL))
    }

    private fun confirmDelete(book: Book) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除「${book.name}」？")
            .setMessage("词库里的 ${book.wordCount} 个单词会一起删除，无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                Bg.run({ repo.deleteBook(book.id) }) { result ->
                    result.onSuccess {
                        if (_binding == null) return@onSuccess
                        val current = Settings.selectedBookId(requireContext())
                        if (current == book.id) Settings.setSelectedBookId(requireContext(), -1L)
                        toast("已删除")
                        load()
                    }.onFailure { toast("删除失败：${it.message}") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
