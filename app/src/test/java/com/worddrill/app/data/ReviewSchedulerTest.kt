package com.worddrill.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 间隔重复曲线本身的纯逻辑测试。 */
class ReviewSchedulerTest {

    private val day = ReviewScheduler.DAY_MS

    @Test
    fun correctAnswerMovesUpOneLevel() {
        assertEquals(1, ReviewScheduler.nextLevel(0, correct = true))
        assertEquals(2, ReviewScheduler.nextLevel(1, correct = true))
        assertEquals(3, ReviewScheduler.nextLevel(2, correct = true))
    }

    @Test
    fun intervalsFollowTheCurve() {
        assertEquals(1, ReviewScheduler.intervalDays(1, correct = true))
        assertEquals(2, ReviewScheduler.intervalDays(2, correct = true))
        assertEquals(4, ReviewScheduler.intervalDays(3, correct = true))
        assertEquals(7, ReviewScheduler.intervalDays(4, correct = true))
        assertEquals(15, ReviewScheduler.intervalDays(5, correct = true))
        assertEquals(30, ReviewScheduler.intervalDays(6, correct = true))
        assertEquals(60, ReviewScheduler.intervalDays(7, correct = true))
    }

    @Test
    fun wrongAnswerResetsAndComesBackTomorrow() {
        assertEquals(0, ReviewScheduler.nextLevel(5, correct = false))
        val now = 1_700_000_000_000L
        assertEquals(now + day, ReviewScheduler.nextDueAt(5, correct = false, now = now))
    }

    @Test
    fun levelIsCappedAtMax() {
        assertEquals(ReviewScheduler.MAX_LEVEL, ReviewScheduler.nextLevel(ReviewScheduler.MAX_LEVEL, correct = true))
        val now = 1_700_000_000_000L
        assertEquals(now + 60 * day, ReviewScheduler.nextDueAt(ReviewScheduler.MAX_LEVEL, correct = true, now = now))
    }

    @Test
    fun firstCorrectAnswerIsDueTomorrow() {
        val now = 1_700_000_000_000L
        val due = ReviewScheduler.nextDueAt(0, correct = true, now = now)
        assertEquals(now + day, due)
        assertTrue(due > now)
    }
}
