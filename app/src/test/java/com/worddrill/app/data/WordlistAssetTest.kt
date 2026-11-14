package com.worddrill.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 内置词库资产的守门测试。
 *
 * 资产是离线脚本 `tools/build_words3500.py` 生成的，这里只校验「生成结果对不对」，
 * 不重复实现拆分逻辑——脚本可以重跑，断言只有这一处。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordlistAssetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun load(name: String): JSONArray {
        val text = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONArray(text)
    }

    private fun heads(array: JSONArray): List<String> =
        (0 until array.length()).map { array.getJSONObject(it).getString("w") }

    private fun coverage(array: JSONArray, field: String): Int =
        (0 until array.length()).count { array.getJSONObject(it).optString(field).isNotBlank() }

    @Test
    fun highSchoolListIsRepaired() {
        val array = load("words3500.json")
        assertEquals("高中 3500 应该是修完的 3846 条", 3846, array.length())

        val words = heads(array)
        val bad = listOf(
            "v]", "afte", "cance", "ad.", "energ", "federa", "kilometr", "woo",
            "shyv", "spaghettiv", "tiresomev", "Tibeta", "does doesnˈt",
            "soft sɔft;", "turning n",
        )
        for (broken in bad) {
            assertFalse("坏词头 $broken 应该已经修掉", words.contains(broken))
        }

        val mustHave = listOf(
            "best", "body", "child", "man", "woman", "foot", "leaf", "life",
            "mouse", "sheep", "tooth", "wolf", "sort", "after",
        )
        for (word in mustHave) {
            assertTrue(
                "修补后应该能搜到 $word",
                words.any { it.equals(word, ignoreCase = true) },
            )
        }

        assertTrue("词头不该有首尾空白", words.all { it == it.trim() })
        assertTrue("词头不该带方括号", words.none { it.contains('[') || it.contains(']') })
        assertTrue("连字符写法应该统一", words.none { it.contains("--") || it.contains(" -") || it.contains("- ") })
    }

    @Test
    fun posUsesAdjAndAdvInBothLists() {
        for (name in listOf("words3500.json", "words_cet4.json")) {
            val array = load(name)
            var adj = 0
            var adv = 0
            for (index in 0 until array.length()) {
                val pos = array.getJSONObject(index).optString("pos")
                assertFalse("$name 里还有旧写法 a.：${array.getJSONObject(index).optString("w")}", pos == "a.")
                assertFalse("$name 里还有旧写法 ad.：${array.getJSONObject(index).optString("w")}", pos == "ad.")
                if (pos.contains("adj.")) adj++
                if (pos.contains("adv.")) adv++
            }
            assertTrue("$name 的形容词数量异常：$adj", adj >= 600)
            assertTrue("$name 的副词数量异常：$adv", adv >= 100)
        }
    }

    @Test
    fun richFieldsKeepTheirCoverage() {
        val array = load("words3500.json")
        assertTrue("音标覆盖率不该下降：${coverage(array, "us")}", coverage(array, "us") >= 3543)
        assertTrue("例句覆盖率不该下降：${coverage(array, "ex")}", coverage(array, "ex") >= 3487)
        assertTrue("搭配覆盖率不该下降：${coverage(array, "ph")}", coverage(array, "ph") >= 3060)
    }

    @Test
    fun cet4KeepsAndroidDataAndSkipsWebTypo() {
        val array = load("words_cet4.json")
        assertEquals("四级词表条数不该变", 4543, array.length())

        val words = heads(array).map { it.lowercase() }
        assertFalse("网页版那条拼写错误 reservior 不该带进来", words.contains("reservior"))
        assertTrue("正常的 reservoir 应该还在", words.contains("reservoir"))
    }

    @Test
    fun everyEntryHasWordPosAndMeaning() {
        for (name in listOf("words3500.json", "words_cet4.json")) {
            val array = load(name)
            for (index in 0 until array.length()) {
                val entry = array.getJSONObject(index)
                val word = entry.getString("w")
                assertTrue("$name 第 $index 条词头为空", word.isNotBlank())
                assertTrue("$name 的 $word 缺释义", entry.optString("m").isNotBlank())
                assertTrue("$name 的 $word 缺词性字段", entry.has("pos"))
            }
        }
    }
}