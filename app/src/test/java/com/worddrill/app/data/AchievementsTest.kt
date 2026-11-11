package com.worddrill.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementsTest {

    @Test
    fun firstWordAndStreakUnlockAtTheirThresholds() {
        val snapshot = AchievementSnapshot(
            practicedWords = 120,
            streak = 7,
            masteredWords = 10,
            fastCorrect = 0,
        )
        val keys = Achievements.reached(snapshot).map { it.key }.toSet()
        assertTrue(keys.contains("first_word"))
        assertTrue(keys.contains("words_100"))
        assertFalse(keys.contains("words_500"))
        assertTrue(keys.contains("streak_3"))
        assertTrue(keys.contains("streak_7"))
        assertFalse(keys.contains("streak_30"))
        assertFalse(keys.contains("mastered_50"))
        assertFalse(keys.contains("fast_20"))
    }

    @Test
    fun everythingUnlocksForAVeteran() {
        val snapshot = AchievementSnapshot(
            practicedWords = 900,
            streak = 40,
            masteredWords = 300,
            fastCorrect = 99,
        )
        assertEquals(Achievements.ALL.size, Achievements.reached(snapshot).size)
    }

    @Test
    fun progressIsCappedAtOne() {
        val def = Achievements.ALL.first { it.key == "words_100" }
        assertEquals(0.5f, Achievements.progressOf(def, AchievementSnapshot(practicedWords = 50)), 0.001f)
        assertEquals(1f, Achievements.progressOf(def, AchievementSnapshot(practicedWords = 200)), 0.001f)
    }

    @Test
    fun everyMetricIsCovered() {
        // 新增成就时忘了接指标会在这里露出来
        val snapshot = AchievementSnapshot(1, 1, 1, 1)
        Achievements.ALL.forEach { def ->
            Achievements.valueOf(def.metric, snapshot)
        }
        assertEquals(AchievementMetric.entries.size, Achievements.ALL.map { it.metric }.toSet().size)
    }
}