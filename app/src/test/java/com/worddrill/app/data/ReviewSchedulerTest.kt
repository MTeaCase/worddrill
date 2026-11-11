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

    // ---------- 三档自评 ----------

    @Test
    fun knowRatingUpsTheLevel() {
        assertEquals(3, ReviewScheduler.nextLevel(2, ReviewRating.KNOW))
        assertEquals(ReviewScheduler.MAX_LEVEL, ReviewScheduler.nextLevel(ReviewScheduler.MAX_LEVEL, ReviewRating.KNOW))
    }

    @Test
    fun fuzzyRatingKeepsLevelButNotBelowOne() {
        assertEquals(2, ReviewScheduler.nextLevel(2, ReviewRating.FUZZY))
        assertEquals(1, ReviewScheduler.nextLevel(0, ReviewRating.FUZZY))
    }

    @Test
    fun forgotRatingResetsToZero() {
        assertEquals(0, ReviewScheduler.nextLevel(5, ReviewRating.FORGOT))
    }

    @Test
    fun fuzzyAndForgotBothComeBackTomorrow() {
        val now = 1_700_000_000_000L
        assertEquals(now + day, ReviewScheduler.nextDueAt(4, ReviewRating.FUZZY, now = now))
        assertEquals(now + day, ReviewScheduler.nextDueAt(4, ReviewRating.FORGOT, now = now))
        assertEquals(now + 15 * day, ReviewScheduler.nextDueAt(4, ReviewRating.KNOW, now = now))
    }

    @Test
    fun onlyForgotGoesIntoTheMistakeBook() {
        assertTrue(ReviewScheduler.addsToMistakes(ReviewRating.FORGOT))
        assertTrue(!ReviewScheduler.addsToMistakes(ReviewRating.KNOW))
        assertTrue(!ReviewScheduler.addsToMistakes(ReviewRating.FUZZY))
    }

    // ---------- ease（SM-2） ----------

    @Test
    fun easeMovesWithTheRatingAndStaysInRange() {
        assertEquals(2.60, ReviewScheduler.nextEase(2.5, ReviewRating.KNOW), 0.001)
        assertEquals(2.36, ReviewScheduler.nextEase(2.5, ReviewRating.FUZZY), 0.001)
        assertEquals(2.30, ReviewScheduler.nextEase(2.5, ReviewRating.FORGOT), 0.001)
        assertEquals(ReviewScheduler.MIN_EASE, ReviewScheduler.nextEase(1.3, ReviewRating.FORGOT), 0.001)
        assertEquals(ReviewScheduler.MAX_EASE, ReviewScheduler.nextEase(3.0, ReviewRating.KNOW), 0.001)
    }

    @Test
    fun defaultEaseKeepsTheOldCurve() {
        // ease 2.5 时「认识」的间隔与原曲线完全一致
        for (level in 1..ReviewScheduler.MAX_LEVEL) {
            assertEquals(
                ReviewScheduler.intervalDays(level, correct = true),
                ReviewScheduler.intervalDays(level, ReviewRating.KNOW, ReviewScheduler.DEFAULT_EASE),
            )
        }
    }

    @Test
    fun higherEaseStretchesLowerEaseShrinks() {
        assertEquals(5, ReviewScheduler.intervalDays(3, ReviewRating.KNOW, 3.0))
        assertEquals(2, ReviewScheduler.intervalDays(3, ReviewRating.KNOW, 1.3))
        assertEquals(1, ReviewScheduler.intervalDays(3, ReviewRating.FUZZY, 3.0))
        assertEquals(1, ReviewScheduler.intervalDays(3, ReviewRating.FORGOT, 3.0))
    }
}
