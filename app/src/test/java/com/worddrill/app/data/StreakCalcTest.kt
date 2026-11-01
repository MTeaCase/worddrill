package com.worddrill.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 连续打卡的纯逻辑测试，不依赖 Android。 */
class StreakCalcTest {

    @Test
    fun countsConsecutiveDaysEndingToday() {
        val studied = setOf("2026-10-30", "2026-10-31", "2026-11-01")
        assertEquals(3, StreakCalc.current("2026-11-01", studied))
    }

    @Test
    fun todayWithoutStudyDoesNotBreakStreak() {
        val studied = setOf("2026-10-30", "2026-10-31")
        assertEquals(2, StreakCalc.current("2026-11-01", studied))
    }

    @Test
    fun gapBreaksStreak() {
        val studied = setOf("2026-10-28", "2026-10-31", "2026-11-01")
        assertEquals(2, StreakCalc.current("2026-11-01", studied))
    }

    @Test
    fun emptyHistoryIsZero() {
        assertEquals(0, StreakCalc.current("2026-11-01", emptySet()))
    }

    @Test
    fun ignoredBrokenDate() {
        assertEquals(0, StreakCalc.current("not-a-date", setOf("2026-11-01")))
    }
}
