package com.worddrill.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** assets/about.txt 的解析规则。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutConfigTest {

    private val fallback = AboutInfo(
        name = "默认名称",
        version = "9.9.9",
        developer = "默认开发者",
        summary = "默认简介",
        rows = emptyList(),
        note = "默认备注",
    )

    @Test
    fun parsesFieldsCommentsAndMultipleRows() {
        val text = """
            # 这一行是注释
            名称 = 单词练习
            版本：2.0.0
            开发者 = 2816csj

            简介 = 第一行
                   第二行

            信息 = 词库 | 高中 3500 词
            信息 = 数据 | 不联网
            备注 = 仅用于练习
        """.trimIndent()

        val info = AboutConfig.parse(text, fallback)

        assertEquals("单词练习", info.name)
        assertEquals("2.0.0", info.version)
        assertEquals("2816csj", info.developer)
        assertEquals("第一行\n第二行", info.summary)
        assertEquals(2, info.rows.size)
        assertEquals("词库", info.rows[0].label)
        assertEquals("高中 3500 词", info.rows[0].value)
        assertEquals("数据", info.rows[1].label)
        assertEquals("仅用于练习", info.note)
    }

    @Test
    fun missingFieldsFallBackOnlyForNameAndVersion() {
        val info = AboutConfig.parse("名称 = 只有名称", fallback)

        assertEquals("只有名称", info.name)
        assertEquals("9.9.9", info.version)
        assertEquals("", info.developer)
        assertEquals("", info.summary)
        assertTrue(info.rows.isEmpty())
        assertEquals("", info.note)
    }

    @Test
    fun blankVersionUsesApkVersion() {
        val info = AboutConfig.parse("名称 = A\n版本 =\n开发者 = B", fallback)
        assertEquals("9.9.9", info.version)
        assertEquals("B", info.developer)
    }

    @Test
    fun rowWithoutPipeIsFullWidthValue() {
        val info = AboutConfig.parse("信息 = 只有内容", fallback)
        assertEquals(1, info.rows.size)
        assertEquals("", info.rows[0].label)
        assertEquals("只有内容", info.rows[0].value)
    }

    @Test
    fun packagedAssetIsReadable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = AboutConfig.load(context, "7.7.7")

        assertEquals("单词练习", info.name)
        assertEquals("1.3.4", info.version)
        assertEquals("zlt mtc", info.developer)
        assertEquals(2, info.rows.size)
        assertEquals("词库", info.rows[0].label)
        assertTrue(info.note.startsWith("仅供学习交流使用"))
    }
}

