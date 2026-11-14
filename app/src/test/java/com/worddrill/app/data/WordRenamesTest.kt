package com.worddrill.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 改名表：修词库后，老备份里的旧词头要能换到新词头上。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordRenamesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun tableCoversEveryRenamedHeadword() {
        assertEquals("改名表条数变了要先更新构建脚本", 38, WordRenames.size(context))
    }

    @Test
    fun canonicalMapsOldHeadwordToNew() {
        assertEquals("sort", WordRenames.canonical(context, "v]"))
        assertEquals("either", WordRenames.canonical(context, "ad."))
        assertEquals("best-seller", WordRenames.canonical(context, "best--seller"))
        assertEquals("department store", WordRenames.canonical(context, "department -store"))
        assertEquals("does", WordRenames.canonical(context, "does doesnˈt"))
        assertEquals("may", WordRenames.canonical(context, "may modal"))
        assertEquals("o'clock", WordRenames.canonical(context, "oˈclock"))
    }

    @Test
    fun canonicalIgnoresCaseAndKeepsUnknownWords() {
        assertEquals("sort", WordRenames.canonical(context, "V]"))
        assertEquals("zzunknown", WordRenames.canonical(context, "zzunknown"))
        assertEquals("", WordRenames.canonical(context, "   "))
        assertTrue(WordRenames.size(context) > 0)
    }
}