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
import com.worddrill.app.data.DailyPack
import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.WordFilter
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.FragmentPracticeBinding
import com.worddrill.app.ui.PracticeActivity
import com.worddrill.app.ui.MainActivity
import com.worddrill.app.ui.ReportActivity
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
    private var pack: DailyPack? = null
    private var currentBookName = ""

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
        binding.cardListenZh.setOnClickListener { selectMode(PracticeMode.LISTEN_ZH) }
        binding.cardExample.setOnClickListener { selectMode(PracticeMode.EX_SPELL) }
        binding.cardForm.setOnClickListener { selectMode(PracticeMode.FORM) }
        binding.cardMixed.setOnClickListener { selectMode(PracticeMode.MIXED) }

        // 学习包：每天一个「复习 + 错题 + 新词」的组合，点一下直接开练
        binding.cardDailyPack.setOnClickListener { startDailyPack() }

        binding.rowSettings.setOnClickListener { setExpanded(!expanded) }
        binding.etCount.doAfterTextChanged { refreshSettingsSummary() }
        binding.etStart.doAfterTextChanged { refreshSettingsSummary() }
        binding.etEnd.doAfterTextChanged { refreshSettingsSummary() }

        binding.btnStart.setOnClickListener { startPractice() }
        binding.btnOpenList.setOnClickListener { openList(WordFilter.ALL) }
        binding.cardProgress.setOnClickListener {
            startActivity(ReportActivity.intent(requireContext()))
        }
        // 有到期词时练习页直接给个入口，省得切到复习页
        binding.btnReviewNow.setOnClickListener {
            (activity as? MainActivity)?.selectTab(R.id.tab_review)
        }

        selectMode(mode)
        setExpanded(false)
        loadBooks()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) loadBooks()
    }

    /** 从别的 tab 切回来时 onResume 不会再走一遍，这里补上刷新。 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) loadBooks()
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
            PracticeMode.EN_ZH to binding.cardEnZh,
            PracticeMode.ZH_EN to binding.cardZhEn,
            PracticeMode.ZH_EN_SPELL to binding.cardSpell,
            PracticeMode.LISTEN_SPELL to binding.cardListen,
            PracticeMode.LISTEN_ZH to binding.cardListenZh,
            PracticeMode.EX_SPELL to binding.cardExample,
            PracticeMode.FORM to binding.cardForm,
            PracticeMode.MIXED to binding.cardMixed,
        )
        rows.forEach { (rowMode, card) ->
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
                    binding.tvHeroTitle.text = "0 / 0 题"
                    binding.tvHeroSub.text = "去「词库」页导入自组词汇库"
                    binding.btnStart.isEnabled = false
                    binding.btnReviewNow.isVisible = false
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
        currentBookName = book.name
        val goal = Settings.dailyGoal(requireContext())
        val newQuota = Settings.newQuota(requireContext())
        val reviewQuota = Settings.reviewQuota(requireContext())
        val mistakeQuota = Settings.mistakeQuota(requireContext())
        Bg.run({
            BookStats(
                range = repo.ordRange(book.id),
                practiced = repo.practicedCount(book.id),
                blocked = repo.blockedCount(book.id),
                total = book.wordCount,
                summary = repo.studySummary(7, goal, book.id),
                pack = repo.dailyPack(book.id, newQuota, reviewQuota, mistakeQuota),
            )
        }) { result ->
            result.onSuccess { stats ->
                if (_binding == null || !isAdded) return@onSuccess
                ordRange = stats.range
                pack = stats.pack
                binding.cardDailyPack.isVisible = !stats.pack.isEmpty
                binding.tvPackSub.text = buildString {
                    append("复习 ").append(stats.pack.due.size)
                    append(" · 错题 ").append(stats.pack.mistakes.size)
                    append(" · 新词 ").append(stats.pack.fresh.size)
                }
                val max = stats.range?.last ?: 0
                val goal = stats.summary.dailyGoal.coerceAtLeast(1)
                val todayPercent = stats.summary.todayAnswered * 100 / goal
                binding.pbHero.setProgressCompat(todayPercent.coerceIn(0, 100), true)
                binding.tvHeroTitle.text = "${stats.summary.todayAnswered} / $goal 题"
                binding.tvHeroSub.text = buildString {
                    append("已练 ${stats.practiced} / ${stats.total} 词")
                    if (stats.summary.streak > 0) append(" · 连续 ${stats.summary.streak} 天")
                    if (stats.summary.dueReview > 0) append(" · 待复习 ${stats.summary.dueReview} 词")
                    if (stats.blocked > 0) append(" · 屏蔽 ${stats.blocked}")
                    if (max == 0) append(" · 没有可练习的单词")
                    examHint(stats.practiced, stats.total)?.let { append(" · ").append(it) }
                }
                val due = stats.summary.dueReview
                binding.btnReviewNow.isVisible = due > 0
                binding.btnReviewNow.text = "复习今日到期的 $due 个单词"
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

    private fun openList(filter: WordFilter) {
        val book = books.firstOrNull { it.id == selectedBookId }
        if (book == null) {
            toast("还没有可用的词库")
            return
        }
        startActivity(WordListActivity.intent(requireContext(), book.id, book.name, filter))
    }

    /** 学习包：把「复习 + 错题 + 新词」拼成一轮混合题直接开练。 */
    private fun startDailyPack() {
        val current = pack ?: return
        if (current.isEmpty) {
            toast(getString(R.string.daily_pack_empty))
            return
        }
        startActivity(
            PracticeActivity.retryIntent(
                context = requireContext(),
                mode = PracticeMode.MIXED,
                bookName = currentBookName,
                wordIds = current.wordIds.toLongArray(),
            ),
        )
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

    /** 考试倒计时：按剩余未练词数和剩余天数反推每天该练多少。 */
    private fun examHint(practiced: Int, total: Int): String? {
        val date = Settings.examDate(requireContext())
        if (date <= 0L) return null
        val days = ((date - System.currentTimeMillis()) / (24L * 60 * 60 * 1000)).toInt()
        val name = Settings.examName(requireContext()).ifBlank { "考试" }
        if (days <= 0) return "$name 倒计时已到"
        val remaining = (total - practiced).coerceAtLeast(0)
        val perDay = (remaining + days - 1) / days
        return "距$name $days 天 · 每天 $perDay 词"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun color(id: Int): Int = ContextCompat.getColor(requireContext(), id)

    private data class BookStats(
        val range: IntRange?,
        val practiced: Int,
        val blocked: Int,
        val total: Int,
        val summary: com.worddrill.app.data.StudySummary,
        val pack: DailyPack,
    )
}
