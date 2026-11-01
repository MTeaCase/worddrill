package com.worddrill.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class TextFilesTest {

    @Test
    fun decodesUtf8() {
        val text = "| 英文 | 词性 | 汉语 |\n| apple | n. | 苹果 |"
        assertEquals(text, TextFiles.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun stripsUtf8Bom() {
        val text = "apple,n.,苹果"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals(text, TextFiles.decode(bytes))
    }

    @Test
    fun fallsBackToGb18030() {
        val text = "| remote | adj. | 偏远的；远程的 |"
        val gbk = text.toByteArray(Charset.forName("GB18030"))
        assertEquals(text, TextFiles.decode(gbk))
    }

    @Test
    fun decodesUtf16LittleEndianWithBom() {
        val text = "| run | v. | 跑 |"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, TextFiles.decode(bytes))
    }
}
