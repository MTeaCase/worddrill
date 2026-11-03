package com.worddrill.app.quiz

import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.Word
import kotlin.random.Random

/**
 * 一道题。选择题的 options 已打乱，correctOption 是正确项下标；
 * 拼写题的 options 为空，answer 为应输入的英文。
 */
data class QuizItem(
    val word: Word,
    val prompt: String,
    val answer: String,
    val options: List<String> = emptyList(),
    val correctOption: Int = -1,
    val spell: Boolean = false,
)

/** 纯逻辑出题器，可在 JVM 单元测试中直接运行。 */
object QuizEngine {

    const val OPTION_COUNT = 4

    fun build(
        pool: List<Word>,
        allWords: List<Word>,
        count: Int,
        mode: PracticeMode,
        random: Random = Random.Default,
    ): List<QuizItem> {
        if (pool.isEmpty() || count <= 0) return emptyList()
        val picked = pool.shuffled(random).distinctBy { it.id }.take(count)
        return picked.map { makeItem(it, allWords, mode, random) }
    }

    fun makeItem(
        word: Word,
        allWords: List<Word>,
        mode: PracticeMode,
        random: Random = Random.Default,
    ): QuizItem = when (mode) {
        PracticeMode.ZH_EN_SPELL -> QuizItem(
            word = word,
            prompt = word.meaningWithPos,
            answer = word.text,
            spell = true,
        )

        // 听音拼写：不显示中文，靠发音回忆拼写；题面只放一句提示
        PracticeMode.LISTEN_SPELL -> QuizItem(
            word = word,
            prompt = "点这里再听一遍",
            answer = word.text,
            spell = true,
        )

        PracticeMode.EN_ZH -> {
            val correct = word.meaningWithPos
            val options = buildOptions(correct, allWords, random) { it.meaningWithPos }
            QuizItem(
                word = word,
                prompt = word.text,
                answer = correct,
                options = options,
                correctOption = options.indexOf(correct),
            )
        }

        PracticeMode.ZH_EN -> {
            val correct = word.text
            val options = buildOptions(correct, allWords, random) { it.text }
            QuizItem(
                word = word,
                prompt = word.meaningWithPos,
                answer = correct,
                options = options,
                correctOption = options.indexOf(correct),
            )
        }
    }

    /** 正确项 + 最多 3 个来自全词库的干扰项，全部打乱且去重。 */
    private fun buildOptions(
        correct: String,
        allWords: List<Word>,
        random: Random,
        selector: (Word) -> String,
    ): List<String> {
        val candidates = allWords.asSequence()
            .map(selector)
            .filter { it.isNotBlank() && it != correct }
            .distinct()
            .toList()
        val distractors = if (candidates.size <= OPTION_COUNT - 1) {
            candidates
        } else {
            candidates.shuffled(random).take(OPTION_COUNT - 1)
        }
        return (distractors + correct).shuffled(random)
    }

    fun normalize(input: String): String =
        input.trim().lowercase().replace(Regex("\\s+"), " ")

    fun isCorrect(input: String, answer: String): Boolean =
        normalize(input) == normalize(answer) && normalize(input).isNotEmpty()

    /** 拼写提示：每个单词的首字母 + 下划线，空格显示为 /，连字符保留。 */
    fun hint(answer: String): String {
        val sb = StringBuilder()
        answer.forEachIndexed { index, ch ->
            if (index > 0) sb.append(' ')
            val isWordStart = index == 0 || answer[index - 1] == ' ' || answer[index - 1] == '-'
            sb.append(
                when {
                    ch == ' ' -> "/"
                    ch == '-' -> "-"
                    isWordStart -> ch
                    else -> '_'
                },
            )
        }
        return sb.toString()
    }
}
