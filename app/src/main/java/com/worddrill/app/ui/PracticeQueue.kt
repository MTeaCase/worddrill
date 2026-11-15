package com.worddrill.app.ui

import com.worddrill.app.quiz.QuizItem

/**
 * 一轮练习的题目队列。界面和后台算题线程共用**同一个可变列表**。
 *
 * 这是踩过的坑：后台分批算题时拿着列表引用，如果界面上用
 * `items = items + x` / `items.toMutableList()` 换成新列表，后面算好的题
 * 就都追加到了已经没人看的旧列表上，用户答完最后一道会一直停在
 * 「正在准备下一题…」。所以这里的增、删、改一律原地进行。
 *
 * 同时负责「走到头了没有」的判断：只有后台已经算完（或算失败被截断），
 * 前面又没有题了，才算真正结束；否则就是还在等下一题。
 */
internal class PracticeQueue {

    var items: MutableList<QuizItem> = mutableListOf()
        private set

    /** 本轮计划题数：词在开始时就定死了，后台还没算完的部分也算进去。 */
    var plannedTotal: Int = 0
        private set

    /** 后台把剩下的题全部算完（或算失败、不再有题）后置为 true。 */
    var builtAll: Boolean = false
        private set

    fun reset(items: MutableList<QuizItem>, plannedTotal: Int) {
        this.items = items
        this.plannedTotal = plannedTotal
        builtAll = false
    }

    /** 后台算好的一批题接在后面（批次可能只有一部分，不能改 plannedTotal 的语义）。 */
    fun append(more: List<QuizItem>) {
        items += more
        plannedTotal = maxOf(plannedTotal, items.size)
    }

    /** 后台的题全部到齐。 */
    fun markBuilt() {
        builtAll = true
        plannedTotal = maxOf(plannedTotal, items.size)
    }

    /** 后台算失败：后面不会再有题了，按现有题数收尾，别让界面一直等。 */
    fun truncate() {
        builtAll = true
        plannedTotal = items.size
    }

    /** 「不再练习此单词」：原地删掉，并从计划题数里减去这一个。 */
    fun removeAt(index: Int) {
        if (index !in items.indices) return
        items.removeAt(index)
        if (plannedTotal > items.size) plannedTotal--
    }

    fun replaceAt(index: Int, item: QuizItem) {
        if (index in items.indices) items[index] = item
    }

    /** 答错的词在本轮末尾再出现一次（同一个词只补一次由调用方控制）。 */
    fun requeue(item: QuizItem) {
        items += item
        plannedTotal = maxOf(plannedTotal, items.size)
    }

    /** 站在第 [index] 题上，下一步该干什么。 */
    fun next(index: Int): Next {
        val next = index + 1
        return when {
            next < items.size -> Next.Show(next)
            builtAll -> Next.Finish
            else -> Next.Wait
        }
    }

    sealed interface Next {
        data class Show(val index: Int) : Next

        data object Wait : Next

        data object Finish : Next
    }
}