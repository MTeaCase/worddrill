package com.worddrill.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 隐私政策要真的打进包里，且能被加载出来。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyAssetTest {

    @Test
    fun privacyPolicyIsPackaged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = context.assets.open("privacy.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue("隐私政策文件不应为空", text.length > 1000)
        assertTrue(text.contains("隐私政策"))
        assertTrue(text.contains("不收集"))
        // 关键条款要在
        assertTrue(text.contains("POST_NOTIFICATIONS"))
        assertTrue(text.contains("例句生成"))
    }
}
