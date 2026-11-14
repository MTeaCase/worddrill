package com.worddrill.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词性归一化与筛选。
 *
 * 重点是两件事：把旧写法 `a.` / `ad.` 收敛成 `adj.` / `adv.`；
 * 迁移前后的数据都能被同一个筛选条件筛出来（老库里还是 `a.` 也要能按 `adj` 筛到）。
 */
class PosTest {

    @Test
    fun normalizesLegacyAdjectiveAndAdverb() {
        assertEquals("adj.", Pos.normalize("a."))
        assertEquals("adv.", Pos.normalize("ad."))
        assertEquals("adj.&n.", Pos.normalize("a.&n."))
        assertEquals("adv.&prep.&conj.", Pos.normalize("ad.&prep.&conj."))
    }

    @Test
    fun keepsRecognizedUnitsAndJoinsWithAmpersand() {
        assertEquals("vt.&vi.&n.", Pos.normalize("vt&vi&n."))
        assertEquals("int.", Pos.normalize("interj"))
        assertEquals("modal v.", Pos.normalize("modal v."))
        assertEquals("n.&v.", Pos.normalize("n. & v."))
    }

    @Test
    fun leavesUnknownAndEmptyAlone() {
        assertEquals("", Pos.normalize("   "))
        assertEquals("动词短语", Pos.normalize("动词短语"))
        assertEquals("phr.", Pos.normalize("phr."))
    }

    @Test
    fun fixesPosPrefixesInsideMeaning() {
        assertEquals("adj. 好看的", Pos.normalizeMeaning("a. 好看的"))
        assertEquals("adv. 快速地", Pos.normalizeMeaning("ad. 快速地"))
        assertEquals("正常释义", Pos.normalizeMeaning("正常释义"))
        assertEquals("num. 数字", Pos.normalizeMeaning("vnum. 数字"))
    }

    @Test
    fun filterMatchesLegacyAdjectiveData() {
        assertTrue("老库里的 a. 要能被 adj 筛到", Pos.matchesFilter("a.", "adj"))
        assertTrue(Pos.matchesFilter("adj.", "adj"))
        assertFalse("ad. 是副词，不该被 adj 筛出来", Pos.matchesFilter("ad.", "adj"))
        assertTrue(Pos.matchesFilter("ad.", "adv"))
    }

    @Test
    fun filterVIncludesTransitiveAndIntransitive() {
        assertTrue(Pos.matchesFilter("vt.", "v"))
        assertTrue(Pos.matchesFilter("vi.&n.", "v"))
        assertFalse(Pos.matchesFilter("vt.", "n"))
        assertTrue(Pos.matchesFilter("n.&vt.", "n"))
    }

    @Test
    fun blankFilterMatchesEverything() {
        assertTrue(Pos.matchesFilter("", ""))
        assertTrue(Pos.matchesFilter("动词短语", ""))
    }

    @Test
    fun unitsAreDedupedAndMapped() {
        assertEquals(setOf("adj", "n"), Pos.units("a.&adj.&n."))
        assertEquals(setOf("adv"), Pos.units("ad."))
        assertEquals(emptySet<String>(), Pos.units("  "))
    }
}