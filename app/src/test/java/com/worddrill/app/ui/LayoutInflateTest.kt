package com.worddrill.app.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.worddrill.app.R

/** 确保所有布局都能在主题下正常 inflate（没有设备时的主要 UI 校验手段）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayoutInflateTest {

    private val context: Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_WordDrill)
    private val inflater: LayoutInflater = LayoutInflater.from(context)

    private val layouts = listOf(
        R.layout.activity_main,
        R.layout.fragment_practice,
        R.layout.fragment_review,
        R.layout.fragment_library,
        R.layout.fragment_mine,
        R.layout.activity_practice,
        R.layout.activity_result,
        R.layout.activity_word_list,
        R.layout.activity_import,
        R.layout.activity_dictionary,
        R.layout.activity_report,
        R.layout.activity_privacy,
        R.layout.widget_worddrill,
        R.layout.widget_word,
        R.layout.widget_due,
        R.layout.widget_streak,
        R.layout.item_word,
        R.layout.item_book_card,
        R.layout.item_result,
        R.layout.dialog_about,
        R.layout.dialog_update_progress,
        R.layout.item_about_row,
    )

    @Test
    fun everyLayoutInflates() {
        layouts.forEach { id ->
            val view = inflater.inflate(id, null, false)
            assertNotNull("layout $id", view)
        }
    }
}
