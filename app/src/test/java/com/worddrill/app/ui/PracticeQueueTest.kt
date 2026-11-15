package com.worddrill.app.ui

import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.Word
import com.worddrill.app.quiz.QuizItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「不再练习此单词」之后卡在「正在准备下一题…」的回归测试。
 *
 * 当时的两个原因：
 * 1. 界面用 `items = items.toMutableList()` 换成了新列表，后台算好的题还追加在旧列表里；
 * 2. 删题只减了列表、没减计划题数，走到末尾永远满足不了「index >= 总题数」。
 */
class PracticeQueueTest {

    private fun item(id: Long, mode: PracticeMode = PracticeMode.EN_ZH): QuizItem {
        val word = Word(
            id = id,
            bookId = 1,
            ord = id.toInt(),
            text = "w$id",
            pos = "n.",
            meaning = "释义$id",
            starred = false,
        )
        return QuizItem(word = word, prompt = word.text, answer = word.meaning)
    }

    private fun queueOf(vararg ids: Long): PracticeQueue = PracticeQueue().apply {
        reset(ids.map { item(it) }.toMutableList(), plannedTotal = ids.size)
    }

    @Test
    fun resetKeepsTheSharedListInsteadOfCopyingIt() {
        val shared = listOf(item(1), item(2), item(3)).toMutableList()
        val queue = PracticeQueue()
        queue.reset(shared, plannedTotal = 20)
        // 后台线程拿着的就是这一个列表，换对象等于把后面算好的题丢掉
        assertSame(shared, queue.items)
    }

    @Test
    fun skippingBeforeBuildDoesNotSpinAtTheEnd() {
        val queue = PracticeQueue()
        val built = mutableListOf(item(1), item(2), item(3))
        queue.reset(built, plannedTotal = 20)

        // 第 1 题点「不再练习此单词」：计划题数和列表同步减 1
        queue.removeAt(0)
        assertEquals(2, queue.items.size)
        assertEquals(19, queue.plannedTotal)

        // 后台把剩下的 17 题算完，仍然追加在同一个列表上
        queue.append((4L..20L).map { item(it) })
        queue.markBuilt()

        assertEquals(19, queue.items.size)
        assertEquals(19, queue.plannedTotal)
        // 答完最后一道就应该结束，而不是继续等永远不来的第 20 题
        assertEquals(PracticeQueue.Next.Finish, queue.next(18))
    }

    @Test
    fun waitsWhileBackgroundBatchIsStillRunning() {
        val queue = queueOf(1, 2, 3)
        queue.reset(queue.items, plannedTotal = 20)
        assertEquals(PracticeQueue.Next.Wait, queue.next(2))

        queue.append(listOf(item(4), item(5)))
        queue.markBuilt()
        assertEquals(PracticeQueue.Next.Show(3), queue.next(2))
    }

    @Test
    fun skippingTheLastBuiltItemFinishesInsteadOfShowingThePreviousQuestion() {
        val queue = PracticeQueue()
        queue.reset(mutableListOf(item(1), item(2), item(3)), plannedTotal = 3)
        queue.markBuilt()

        queue.removeAt(2)
        assertEquals(PracticeQueue.Next.Finish, queue.next(1))
    }

    @Test
    fun skippingTheLastBuiltItemWaitsForTheRestWhenBuildIsPending() {
        val queue = PracticeQueue()
        queue.reset(mutableListOf(item(1), item(2), item(3)), plannedTotal = 5)

        queue.removeAt(2)
        assertEquals(PracticeQueue.Next.Wait, queue.next(1))

        queue.append(listOf(item(4), item(5)))
        queue.markBuilt()
        assertEquals(PracticeQueue.Next.Show(2), queue.next(1))
    }

    @Test
    fun requeueBeforeBuildDoesNotShortenTheSession() {
        val queue = PracticeQueue()
        queue.reset(mutableListOf(item(1), item(2), item(3)), plannedTotal = 20)

        // 答错补练：这时后台还有 17 题没算，总题数不能被压成 4
        queue.requeue(item(1))
        assertEquals(20, queue.plannedTotal)
        assertEquals(4, queue.items.size)

        queue.append((4L..20L).map { item(it) })
        queue.markBuilt()
        assertEquals(21, queue.plannedTotal)
        assertEquals(21, queue.items.size)
    }

    @Test
    fun truncateStopsTheWaitingSpinner() {
        val queue = PracticeQueue()
        queue.reset(mutableListOf(item(1), item(2), item(3)), plannedTotal = 20)
        assertEquals(PracticeQueue.Next.Wait, queue.next(2))

        // 后台算失败：没有第 4 题了，必须收尾
        queue.truncate()
        assertTrue(queue.builtAll)
        assertEquals(3, queue.plannedTotal)
        assertEquals(PracticeQueue.Next.Finish, queue.next(2))
    }

    @Test
    fun removingEveryWordLeavesAnEmptyQueueThatFinishes() {
        val queue = queueOf(1)
        queue.markBuilt()
        queue.removeAt(0)
        assertTrue(queue.items.isEmpty())
        assertEquals(0, queue.plannedTotal)
        assertEquals(PracticeQueue.Next.Finish, queue.next(-1))
    }
}