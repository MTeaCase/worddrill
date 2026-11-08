package com.worddrill.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.worddrill.app.R
import com.worddrill.app.data.WordRepository
import com.worddrill.app.parser.VocabEntry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 在没有设备/模拟器的情况下，用 Robolectric 原生渲染把界面画成 PNG，
 * 用于人工检查布局。输出到 build/screenshots/。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    private val outDir = File("build/screenshots").apply { mkdirs() }

    @Test
    fun renderMainScreen() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        waitForBooks(activity)
        capture(activity.window.decorView, "main.png")
        capture(activity.window.decorView, "tab_practice.png")
    }

    @Test
    fun renderMainTabs() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        waitForBooks(activity)

        switchTab(activity, R.id.tab_review, "tab_review.png")
        switchTab(activity, R.id.tab_library, "tab_library.png")
        switchTab(activity, R.id.tab_mine, "tab_mine.png")
    }

    /** 切到底部某个页签，等布局跑完再截图。 */
    private fun switchTab(activity: MainActivity, itemId: Int, name: String) {
        activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            R.id.bottomNav,
        )?.selectedItemId = itemId
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(60)
            if (activity.findViewById<android.view.View>(R.id.fragmentContainer)?.childCount ?: 0 > 0) break
        }
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        capture(activity.window.decorView, name)
    }

    /** 等内置词库播种完成、练习页的词库下拉填上数据。 */
    private fun waitForBooks(activity: MainActivity) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val dropdown = activity.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(
                R.id.etBook,
            )
            if (dropdown?.adapter != null && dropdown.adapter.count > 0) break
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun renderPracticeScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bookId = firstBookId(context)

        val intent = PracticeActivity.intent(
            context = context,
            bookId = bookId,
            bookName = "高中 3500 词",
            mode = com.worddrill.app.data.PracticeMode.EN_ZH,
            count = 5,
            startOrd = 1,
            endOrd = 50,
            filter = com.worddrill.app.data.WordFilter.ALL,
            allowRepeat = true,
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

        val activity = Robolectric.buildActivity(PracticeActivity::class.java, intent).setup().get()
        waitForPrompt(activity)
        capture(activity.window.decorView, "practice.png")
    }

    @Test
    fun renderSpellScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bookId = firstBookId(context)
        val intent = PracticeActivity.intent(
            context = context,
            bookId = bookId,
            bookName = "高中 3500 词",
            mode = com.worddrill.app.data.PracticeMode.ZH_EN_SPELL,
            count = 5,
            startOrd = 1,
            endOrd = 50,
            filter = com.worddrill.app.data.WordFilter.ALL,
            allowRepeat = true,
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

        val activity = Robolectric.buildActivity(PracticeActivity::class.java, intent).setup().get()
        waitForPrompt(activity)
        capture(activity.window.decorView, "practice_spell.png")
    }

    @Test
    fun renderResultScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val records = listOf(
            com.worddrill.app.data.AnswerRecord(
                1, "academic", "a./n. 学术的，教学的", "a./n. 学术的，教学的",
                "a./n. 学术的，教学的", true,
            ),
            com.worddrill.app.data.AnswerRecord(
                2, "signature", "n. 签名", "n. 签名", "电话亭", false,
            ),
        )
        val intent = ResultActivity.intent(
            context,
            com.worddrill.app.data.PracticeMode.EN_ZH,
            "高中 3500 词",
            records,
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = Robolectric.buildActivity(ResultActivity::class.java, intent).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        capture(activity.window.decorView, "result.png")
    }

    @Test
    fun renderResultPerfectScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val records = List(12) { i ->
            com.worddrill.app.data.AnswerRecord(
                (i + 1).toLong(),
                "word$i",
                "n. 释义 $i",
                "n. 释义 $i",
                "n. 释义 $i",
                true,
            )
        }
        val intent = ResultActivity.intent(
            context,
            com.worddrill.app.data.PracticeMode.EN_ZH,
            "高中 3500 词",
            records,
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = Robolectric.buildActivity(ResultActivity::class.java, intent).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        capture(activity.window.decorView, "result_perfect.png")
    }

    @Test
    fun renderWordListScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val book = seedBlockTestBook(context)
        val intent = WordListActivity.intent(
            context,
            book,
            "必修一 Unit 3",
            com.worddrill.app.data.WordFilter.ALL,
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = Robolectric.buildActivity(WordListActivity::class.java, intent).setup().get()
        waitForWords(activity)
        capture(activity.window.decorView, "word_list.png")
    }

    @Test
    fun renderBlockedListScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val book = seedBlockTestBook(context)
        val intent = WordListActivity.intent(
            context,
            book,
            "必修一 Unit 3",
            com.worddrill.app.data.WordFilter.BLOCKED,
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = Robolectric.buildActivity(WordListActivity::class.java, intent).setup().get()
        waitForWords(activity)
        capture(activity.window.decorView, "blocked_words.png")
    }

    @Test
    fun renderAboutPanel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val themed = ContextThemeWrapper(context, R.style.Theme_WordDrill)
        val content = LayoutInflater.from(themed).inflate(R.layout.dialog_about, null)
        AboutDialog.bind(themed, content, com.worddrill.app.util.AboutConfig.load(context, "1.0.0"))

        val host = FrameLayout(themed).apply {
            background = GradientDrawable().apply {
                color = android.content.res.ColorStateList.valueOf(context.getColor(R.color.surface))
                cornerRadius = 28 * context.resources.displayMetrics.density
            }
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val width = dp(context, 348)
        val height = dp(context, 560)
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, width, height)
        capture(host, "about.png")
    }

    @Test
    fun renderImportScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = ImportActivity.intent(context).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = Robolectric.buildActivity(ImportActivity::class.java, intent).setup().get()
        activity.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etName,
        )?.setText("必修一 Unit 3")
        activity.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etMarkdown,
        )?.setText(
            """
            | # | 英文 | 词性 | 汉语 |
            |---|------|------|------|
            | 1 | pick out | 动词短语 | 精心挑选；认出来 |
            | 2 | competence | n. | 能力；胜任 |
            | 3 | tricky | adj. | 难对付的，棘手的 |
            | 4 | mindset | n. | 思维模式；心态 |
            """.trimIndent(),
        )
        activity.findViewById<View>(R.id.btnPreview)?.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        capture(activity.window.decorView, "import.png")
    }

    /** 建一个自组词库，并屏蔽其中一个词，用来展示索引重排与屏蔽入口。 */
    private fun seedBlockTestBook(context: Context): Long {
        val repo = WordRepository(context)
        val latch = CountDownLatch(1)
        var bookId = -1L
        Thread {
            bookId = repo.importEntries(
                null,
                "必修一 Unit 3",
                listOf(
                    VocabEntry("competence", "n.", "能力；胜任"),
                    VocabEntry("tricky", "adj.", "难对付的，棘手的"),
                    VocabEntry("mindset", "n.", "思维模式；心态"),
                    VocabEntry("overcome", "v.", "克服；战胜"),
                    VocabEntry("patient", "adj.", "有耐心的"),
                    VocabEntry("achieve", "v.", "实现；达到"),
                ),
            ).bookId
            repo.words(bookId).getOrNull(2)?.let { repo.setBlocked(it.id, true) }
            latch.countDown()
        }.start()
        latch.await(30, TimeUnit.SECONDS)
        return bookId
    }

    private fun firstBookId(context: Context): Long {
        val repo = WordRepository(context)
        val latch = CountDownLatch(1)
        var bookId = -1L
        Thread {
            bookId = repo.books().first().id
            latch.countDown()
        }.start()
        latch.await(30, TimeUnit.SECONDS)
        return bookId
    }

    private fun waitForWords(activity: WordListActivity) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler)
            if ((recycler?.adapter?.itemCount ?: 0) > 0) break
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitForPrompt(activity: PracticeActivity) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val prompt = activity.findViewById<TextView>(R.id.tvPrompt)
            if (prompt != null && prompt.text.isNotEmpty()) break
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun capture(view: View, name: String) {
        val width = view.width.takeIf { it > 0 } ?: 1080
        val height = view.height.takeIf { it > 0 } ?: 2340
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (view.width == 0) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
        }
        view.draw(canvas)
        FileOutputStream(File(outDir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
