package com.worddrill.app.ui

import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import com.worddrill.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** 首页右上角「关于」要能打开，内容来自 assets/about.txt。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutDialogTest {

    @Test
    fun aboutMenuShowsConfiguredContent() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        assertNotNull(toolbar.menu.findItem(R.id.action_about))

        toolbar.menu.performIdentifierAction(R.id.action_about, 0)
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("关于对话框没有弹出", dialog)

        assertEquals("单词练习", dialog.findViewById<TextView>(R.id.tvAboutName).text.toString())
        assertEquals("版本 1.0.0", dialog.findViewById<TextView>(R.id.tvAboutVersion).text.toString())

        val rows = dialog.findViewById<LinearLayout>(R.id.rowsContainer)
        assertEquals(3, rows.childCount)
        val developer = rows.getChildAt(0)
        assertEquals("开发者", developer.findViewById<TextView>(R.id.tvRowLabel).text.toString())
        assertEquals("2816 .", developer.findViewById<TextView>(R.id.tvRowValue).text.toString())
        val vocab = rows.getChildAt(1)
        assertEquals("词库", vocab.findViewById<TextView>(R.id.tvRowLabel).text.toString())

        assertTrue(dialog.isShowing)
    }
}
