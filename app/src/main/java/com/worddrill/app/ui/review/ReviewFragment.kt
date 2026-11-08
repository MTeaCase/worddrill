package com.worddrill.app.ui.review

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.worddrill.app.R
import com.worddrill.app.data.Book
import com.worddrill.app.data.MasteryStats
import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.WordFilter
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.FragmentReviewBinding
import com.worddrill.app.ui.PracticeActivity
import com.worddrill.app.ui.ReportActivity
import com.worddrill.app.ui.WordListActivity
import com.worddrill.app.util.Bg
import com.worddrill.app.util.Settings

/** 复习 tab：今天到期多少、未来几天任务量、以及三个词表入口。 */
class ReviewFragment : Fragment() {

    private var _binding: FragmentReviewBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: WordRepository
    private var bookId = -1L
    private var bookName = ""
    private var bookCount = 0
    private var allCount = 0
    private var multipleBooks = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = WordRepository(requireContext())

        binding.btnReview.setOnClickListener { startReview() }
        binding.cardLearned.setOnClickListener { openList(WordFilter.LEARNED) }
        binding.cardMistakes.setOnClickListener { openList(WordFilter.MISTAKES) }
        binding.cardStarred.setOnClickListener { openList(WordFilter.STARRED) }
        binding.cardMastery.setOnClickListener {
            startActivity(ReportActivity.intent(requireContext()))
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
        val saved = Settings.selectedBookId(requireContext())
        Bg.run({
            val books: List<Book> = repo.books()
            val book = books.firstOrNull { it.id == saved } ?: books.firstOrNull()
            val id = book?.id ?: -1L
            ReviewData(
                bookId = id,
                bookName = book?.name.orEmpty(),
                multipleBooks = books.size > 1,
                bookDue = if (id > 0) repo.dueReviewCount(bookId = id) else 0,
                allDue = repo.dueReviewCount(),
                forecast = repo.reviewForecast(FORECAST_DAYS),
                learned = repo.learnedStats(id),
                mistakes = repo.mistakeCount(id),
                starred = repo.starredCount(id),
                mastery = repo.masteryStats(id),
            )
        }) { result ->
            result.onSuccess { data ->
                if (_binding == null || !isAdded) return@onSuccess
                bookId = data.bookId
                bookName = data.bookName
                bookCount = data.bookDue
                allCount = data.allDue
                multipleBooks = data.multipleBooks
                render(data)
            }.onFailure {
                if (_binding != null) toast("读取复习数据失败：${it.message}")
            }
        }
    }

    private fun render(data: ReviewData) {
        binding.tvDue.text = "${data.bookDue} 个单词"
        binding.tvDueSub.text = buildString {
            if (data.bookName.isNotBlank()) append(data.bookName)
            if (data.multipleBooks && data.allDue != data.bookDue) {
                if (isNotEmpty()) append(" · ")
                append("全部词库共 ${data.allDue} 个")
            }
            if (isEmpty()) append("选一个词库开始练习后这里会显示复习任务")
        }
        binding.btnReview.isEnabled = data.allDue > 0
        binding.btnReview.text =
            if (data.allDue > 0) getString(R.string.action_review) else getString(R.string.review_none)

        binding.chartForecast.setForecast(data.forecast)
        val total = data.forecast.sum()
        binding.tvForecast.text = if (total == 0) {
            getString(R.string.report_forecast_empty)
        } else {
            "未来 7 天共 $total 个复习任务，今天 ${data.forecast.getOrElse(0) { 0 }} 个（含逾期）"
        }

        binding.tvLearnedCount.text = data.learned.learned.toString()
        binding.tvMistakeCount.text = data.mistakes.toString()
        binding.tvStarredCount.text = data.starred.toString()

        val mastery: MasteryStats = data.mastery
        binding.tvMasteryTitle.text =
            "已掌握 ${mastery.mastered} / ${mastery.total} 词（${mastery.masteredPercent}%）"
        binding.pbMastery.setProgressCompat(mastery.masteredPercent, true)
        binding.tvMasteryDetail.text = buildString {
            append("没学过 ${mastery.fresh} · 学习中 ${mastery.learning} · 已掌握 ${mastery.mastered}")
            if (mastery.weak > 0) append(" · 易忘 ${mastery.weak}")
            append("\n复习等级到 5 级（约 30 天）算掌握；答对升级，答错归零重来")
        }
    }

    private fun openList(filter: WordFilter) {
        if (bookId <= 0) {
            toast("还没有可用的词库")
            return
        }
        startActivity(WordListActivity.intent(requireContext(), bookId, bookName, filter))
    }

    /** 先选范围（当前词库 / 全部词库），再选题量，最后选题型。 */
    private fun startReview() {
        if (allCount == 0) {
            toast(getString(R.string.review_none))
            return
        }
        if (!multipleBooks || bookCount == allCount || bookCount == 0) {
            if (bookCount == 0) {
                pickCount("复习", allCount, null)
            } else {
                pickCount(bookName, bookCount, bookId)
            }
            return
        }
        val options = arrayOf("$bookName（$bookCount 个）", "全部词库（$allCount 个）")
        AlertDialog.Builder(requireContext())
            .setTitle("复习哪个词库？")
            .setItems(options) { _, which ->
                if (which == 0) {
                    pickCount(bookName, bookCount, bookId)
                } else {
                    pickCount("复习", allCount, null)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickCount(label: String, count: Int, scopedBookId: Long?) {
        val options = linkedMapOf(
            "全部 $count 个" to count,
            "只练 20 个" to minOf(20, count),
            "只练 10 个" to minOf(10, count),
        )
        val labels = options.keys.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.action_review))
            .setItems(labels) { _, which ->
                val take = options[labels[which]] ?: return@setItems
                pickMode(label, take, scopedBookId)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickMode(label: String, take: Int, scopedBookId: Long?) {
        Bg.run({ repo.dueReviewWords(limit = take, bookId = scopedBookId) }) { result ->
            result.onSuccess { words ->
                if (_binding == null || !isAdded) return@onSuccess
                if (words.isEmpty()) {
                    toast(getString(R.string.review_none))
                    return@onSuccess
                }
                val modes = PracticeMode.entries.map { it.label }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle("复习 ${words.size} 个单词")
                    .setItems(modes) { _, which ->
                        startActivity(
                            PracticeActivity.retryIntent(
                                context = requireContext(),
                                mode = PracticeMode.entries[which],
                                bookName = label.ifBlank { "复习" },
                                wordIds = words.map { it.id }.toLongArray(),
                            ),
                        )
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }.onFailure { toast("读取复习列表失败：${it.message}") }
        }
    }

    private data class ReviewData(
        val bookId: Long,
        val bookName: String,
        val multipleBooks: Boolean,
        val bookDue: Int,
        val allDue: Int,
        val forecast: List<Int>,
        val learned: com.worddrill.app.data.LearnedStats,
        val mistakes: Int,
        val starred: Int,
        val mastery: MasteryStats,
    )

    private companion object {
        const val FORECAST_DAYS = 7
    }
}
