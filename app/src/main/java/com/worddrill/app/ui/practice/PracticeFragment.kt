package com.worddrill.app.ui.practice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.worddrill.app.R
import com.worddrill.app.data.Book
import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.WordFilter
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.FragmentPracticeBinding
import com.worddrill.app.ui.PracticeActivity
import com.worddrill.app.ui.WordListActivity
import com.worddrill.app.util.Bg
import com.worddrill.app.util.Settings

/** 练习 tab：选词库、选模式、开练。题量这类不常改的东西收进折叠区。 */
class PracticeFragment : Fragment() {

    private var _binding: FragmentPracticeBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: WordRepository

    private var books: List<Book> = emptyList()
    private var selectedBookId = -1L
    private var mode: PracticeMode = PracticeMode.EN_ZH
    private var ordRange: IntRange? = null
    private var expanded = false
    private var loadedBookId = -1L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPracticeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = WordRepository(requireContext())

        binding.etBook.setOnItemClickListener { _, _, position, _ -> onBookPicked(position) }
        binding.etBook.setOnClickListener { binding.etBook.showDropDown() }

        binding.cardEnZh.setOnClickListener { selectMode(PracticeMode.EN_ZH) }
        binding.cardZhEn.setOnClickListener { selectMode(PracticeMode.ZH_EN) }
        binding.cardSpell.setOnClickListener { selectMode(PracticeMode.ZH_EN_SPELL) }
        binding.cardListen.setOnClickListener { selectMode(PracticeMode.LISTEN_SPELL) }

        binding.rowSettings.setOnClickListener { setExpanded(!expanded) }
        binding.etCount.doAfterTextChanged { refreshSettingsSummary() }
        binding.etStart.doAfterTextChanged { refreshSettingsSummary() }
        binding.etEnd.doAfterTextChanged { refreshSettingsSummary() }

        binding.btnStart.setOnClickListener { startPractice() }
        binding.btnOpenList.setOnClickListener { openList() }

        selectMode(mode)
        setExpanded(false)
        loadBooks()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) loadBooks()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        binding.settingsBody.isVisible = value
        binding.ivExpand.rotation = if (value) 270f else 90f
    }

    private fun selectMode(target: PracticeMode) {
        mode = target
        val rows = listOf(
            Triple(PracticeMode.EN_ZH, binding.cardEnZh, null),
            Triple(PracticeMode.ZH_EN, binding.cardZhEn, null),
            Triple(PracticeMode.ZH_EN_SPELL, binding.cardSpell, null),
            Triple(PracticeMode.LISTEN_SPELL, binding.cardListen, null),
        )
        rows.forEach { (rowMode, card, _) ->
            val selected = rowMode == target
            card.strokeWidth = dp(if (selected) 2 else 1)
            card.strokeColor = color(if (selected) R.color.brand else R.color.divider)
            card.setCardBackgroundColor(color(if (selected) R.color.brand_soft else R.color.surface))
        }
    }

    private fun loadBooks() {
        Bg.run({ repo.books() }) { result ->
            result.onSuccess { list ->
                if (_binding == null || !isAdded) return@onSuccess
                books = list
                if (list.isEmpty()) {
                    binding.tvBookProgress.text = "还没有词库"
                    binding.btnStart.isEnabled = false
                    return@onSuccess
                }
                binding.etBook.setAdapter(
                    ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, list.map { it.name }),
                )
                val saved = Settings.selectedBookId(requireContext())
                val index = list.indexOfFirst { it.id == saved }.takeIf { it >= 0 } ?: 0
                val book = list[index]
                if (book.id != saved) Settings.setSelectedBookId(requireContext(), book.id)
                val changed = book.id != loadedBookId
                selectedBookId = book.id
                loadedBookId = book.id
                binding.etBook.setText(book.name, false)
                applyBook(book, resetRange = changed)
            }.onFailure {
                if (_binding != null) toast("读取词库失败：${it.message}")
            }
        }
    }

    private fun onBookPicked(position: Int) {
        val book = books.getOrNull(position) ?: return
        if (book.id == selectedBookId) return
        selectedBookId = book.id
        loadedBookId = book.id
        Settings.setSelectedBookId(requireContext(), book.id)
        binding.etBook.setText(book.name, false)
        applyBook(book, resetRange = true)
    }

    private fun applyBook(book: Book, resetRange: Boolean) {
        Bg.run({
            BookStats(
                range = repo.ordRange(book.id),
                practiced = repo.practicedCount(book.id),
                blocked = repo.blockedCount(book.id),
                total = book.wordCount,
            )
        }) { result ->
            result.onSuccess { stats ->
                if (_binding == null || !isAdded) return@onSuccess
                ordRange = stats.range
                val max = stats.range?.last ?: 0
                val percent = if (stats.total > 0) stats.practiced * 100 / stats.total else 0
                binding.pbBook.setProgressCompat(percent.coerceIn(0, 100), true)
                binding.tvBookProgress.text = buildString {
                    append("已练 ${stats.practiced} / ${stats.total} 词")
                    if (stats.blocked > 0) append(" · 屏蔽 ${stats.blocked}")
                    if (max == 0) append(" · 没有可练习的单词")
                }
                binding.tvRangeSub.text = buildString {
                    if (max > 0) {
                        append("可填 1 – $max；留空表示整本词库")
                        if (stats.blocked > 0) append("（不含屏蔽词）")
                    } else {
                        append("这个词库还没有可练习的单词")
                    }
                }
                binding.btnStart.isEnabled = max > 0
                if (resetRange) {
                    binding.etStart.setText(if (max > 0) "1" else "")
                    binding.etEnd.setText(if (max > 0) max.toString() else "")
                } else if (max > 0) {
                    val start = binding.etStart.text?.toString()?.toIntOrNull()
                    val end = binding.etEnd.text?.toString()?.toIntOrNull()
                    if (end != null && end > max) binding.etEnd.setText(max.toString())
                    if (start != null && start > max) binding.etStart.setText("1")
                }
                refreshSettingsSummary()
            }.onFailure {
                if (_binding != null) toast("读取词库信息失败：${it.message}")
            }
        }
    }

    private fun refreshSettingsSummary() {
        val count = binding.etCount.text?.toString()?.trim()?.toIntOrNull()
        val start = binding.etStart.text?.toString()?.trim()?.toIntOrNull()
        val end = binding.etEnd.text?.toString()?.trim()?.toIntOrNull()
        val countLabel = if (count != null && count > 0) "$count 题" else "未设定题量"
        val rangeLabel = when {
            start == null && end == null -> "全部范围"
            start != null && end != null && start == end -> "第 $start 个"
            start != null && end != null -> "第 $start–$end 个"
            start != null -> "从第 $start 个开始"
            else -> "到第 $end 个"
        }
        binding.tvSettingsSummary.text = "$countLabel · $rangeLabel"
    }

    private fun openList() {
        val book = books.firstOrNull { it.id == selectedBookId }
        if (book == null) {
            toast("还没有可用的词库")
            return
        }
        startActivity(WordListActivity.intent(requireContext(), book.id, book.name, WordFilter.ALL))
    }

    private fun startPractice() {
        val book = books.firstOrNull { it.id == selectedBookId } ?: run {
            toast("还没有可用的词库")
            return
        }
        val count = binding.etCount.text?.toString()?.trim()?.toIntOrNull() ?: 0
        if (count !in 1..200) {
            toast("题量请填 1 – 200")
            setExpanded(true)
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
            setExpanded(true)
            return
        }

        startActivity(
            PracticeActivity.intent(
                context = requireContext(),
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun color(id: Int): Int = ContextCompat.getColor(requireContext(), id)

    private data class BookStats(
        val range: IntRange?,
        val practiced: Int,
        val blocked: Int,
        val total: Int,
    )
}
