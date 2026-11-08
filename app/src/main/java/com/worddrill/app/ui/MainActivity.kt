package com.worddrill.app.ui

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.worddrill.app.R
import com.worddrill.app.databinding.ActivityMainBinding
import com.worddrill.app.ui.library.LibraryFragment
import com.worddrill.app.ui.mine.MineFragment
import com.worddrill.app.ui.practice.PracticeFragment
import com.worddrill.app.ui.review.ReviewFragment
import com.worddrill.app.util.ApkInstaller
import com.worddrill.app.util.AutoBackup
import com.worddrill.app.util.ReviewReminder
import com.worddrill.app.util.Updater

/**
 * 主界面外壳：底部四个 tab（练习 / 复习 / 词库 / 我的），
 * 每个 tab 是一个独立 Fragment，用 show/hide 保留各自状态。
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTag: String = TAG_PRACTICE
    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyOuterInsets()

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(tagOf(item.itemId))
            true
        }

        val initial = when {
            // 从复习提醒的通知点进来，直接落到复习 tab
            intent?.getBooleanExtra(ReviewReminder.EXTRA_OPEN_REVIEW, false) == true -> TAG_REVIEW
            else -> savedInstanceState?.getString(KEY_TAB) ?: TAG_PRACTICE
        }
        binding.bottomNav.selectedItemId = idOf(initial)
        showTab(initial)

        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTag != TAG_PRACTICE) {
                    binding.bottomNav.selectedItemId = R.id.tab_practice
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        Updater.autoCheck(this)
        // 每 6 小时留一份本地快照，防止闪退/数据库异常导致进度全丢
        AutoBackup.maybeRun(this)
    }

    override fun onResume() {
        super.onResume()
        // 从「安装未知应用」设置页返回后，继续未完成的安装
        ApkInstaller.onActivityResume(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTag)
    }

    /** 切到指定 tab（词库页选完词库后跳回练习用）。 */
    fun selectTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    /** 切换 tab：第一次进来时创建，之后只 show/hide，滚动位置和输入都保留。 */
    private fun showTab(tag: String) {
        if (tag == currentTag && supportFragmentManager.findFragmentByTag(tag) != null) return
        val manager = supportFragmentManager
        val transaction = manager.beginTransaction()
        val target = manager.findFragmentByTag(tag) ?: createFragment(tag).also {
            transaction.add(R.id.fragmentContainer, it, tag)
        }
        manager.fragments.forEach { fragment ->
            if (fragment !== target && !fragment.isHidden) transaction.hide(fragment)
        }
        transaction.show(target)
        transaction.commit()
        currentTag = tag
        // 只要不是停在练习页，返回键都先回练习页，所以每次切换都重新启用
        if (::backCallback.isInitialized) backCallback.isEnabled = true
    }

    private fun createFragment(tag: String): Fragment = when (tag) {
        TAG_REVIEW -> ReviewFragment()
        TAG_LIBRARY -> LibraryFragment()
        TAG_MINE -> MineFragment()
        else -> PracticeFragment()
    }

    private fun tagOf(itemId: Int): String = when (itemId) {
        R.id.tab_review -> TAG_REVIEW
        R.id.tab_library -> TAG_LIBRARY
        R.id.tab_mine -> TAG_MINE
        else -> TAG_PRACTICE
    }

    private fun idOf(tag: String): Int = when (tag) {
        TAG_REVIEW -> R.id.tab_review
        TAG_LIBRARY -> R.id.tab_library
        TAG_MINE -> R.id.tab_mine
        else -> R.id.tab_practice
    }

    /** 状态栏和手势条由外壳处理，里面的 Fragment 就不必各自算了。 */
    private fun applyOuterInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    companion object {
        private const val TAG_PRACTICE = "practice"
        private const val TAG_REVIEW = "review"
        private const val TAG_LIBRARY = "library"
        private const val TAG_MINE = "mine"

        private const val KEY_TAB = "current_tab"
    }
}
