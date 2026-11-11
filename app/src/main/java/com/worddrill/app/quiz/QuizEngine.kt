package com.worddrill.app.quiz

import com.worddrill.app.data.PracticeMode
import com.worddrill.app.data.Word
import kotlin.random.Random

/**
 * 一道题。选择题的 options 已打乱，correctOption 是正确项下标；
 * 拼写题的 options 为空，answer 应输入的英文。
 *
 * [mode] 记录这一题实际用的题型——混合模式下同一轮里每道题可能都不一样。
 */
data class QuizItem(
    val word: Word,
    val prompt: String,
    val answer: String,
    val options: List<String> = emptyList(),
    val correctOption: Int = -1,
    val spell: Boolean = false,
    val mode: PracticeMode = PracticeMode.EN_ZH,
    /** 除 [answer] 之外还算对的写法（例句填词时原形和变形都认）。 */
    val accept: List<String> = emptyList(),
    /** 长题面（整句例句）用小字号显示。 */
    val longPrompt: Boolean = false,
)

/** 纯逻辑出题器，可在 JVM 单元测试中直接运行。 */
object QuizEngine {

    const val OPTION_COUNT = 4

    /** 例句里挖掉的空。 */
    const val BLANK = "____"

    /** 3 秒内答对算「秒答」，用于结果页统计和成就判定。 */
    const val FAST_ANSWER_MS = 3_000L

    /** 混合模式里会轮到的题型（顺序固定，配合单词 id 哈希保证同一个词每次同一题）。 */
    val MIXED_MODES = listOf(
        PracticeMode.EN_ZH,
        PracticeMode.ZH_EN,
        PracticeMode.ZH_EN_SPELL,
        PracticeMode.LISTEN_SPELL,
        PracticeMode.LISTEN_ZH,
        PracticeMode.EX_SPELL,
    )

    /** 关掉发音时不出听力题。 */
    val MIXED_MODES_NO_TTS = listOf(
        PracticeMode.EN_ZH,
        PracticeMode.ZH_EN,
        PracticeMode.ZH_EN_SPELL,
        PracticeMode.EX_SPELL,
    )

    fun build(
        pool: List<Word>,
        allWords: List<Word>,
        count: Int,
        mode: PracticeMode,
        random: Random = Random.Default,
        ttsEnabled: Boolean = true,
    ): List<QuizItem> {
        if (pool.isEmpty() || count <= 0) return emptyList()
        // 例句填词和词形变化有前提条件，先按条件筛一遍，宁可这轮题少也别出空题
        val usable = when (mode) {
            PracticeMode.EX_SPELL -> pool.filter { blankExample(it.exampleEn, it.text) != null }
            PracticeMode.FORM -> pool.filter { com.worddrill.app.data.WordForms.questionFor(it) != null }
            else -> pool
        }
        if (usable.isEmpty()) return emptyList()
        val picked = usable.shuffled(random).distinctBy { it.id }.take(count)
        return picked.map { word ->
            val itemMode = if (mode == PracticeMode.MIXED) pickMixed(word, ttsEnabled) else mode
            makeItem(word, allWords, itemMode, random)
        }
    }

    /**
     * 混合模式给某个词分配题型：按 id 的哈希取模，同一个词永远落到同一档，
     * 不会出现「这次会、下次又抽到最难的形态」这种运气问题。
     * 词本身不具备条件的题型（没例句 / 没词形）会先从候选里剔除，
     * 所以同一个词在不同词库里的落点可能不同，但在同一个库里是固定的。
     */
    fun pickMixed(word: Word, ttsEnabled: Boolean): PracticeMode {
        var modes = if (ttsEnabled) MIXED_MODES else MIXED_MODES_NO_TTS
        if (blankExample(word.exampleEn, word.text) == null) {
            modes = modes.filter { it != PracticeMode.EX_SPELL }
        }
        if (modes.isEmpty()) return PracticeMode.EN_ZH
        val index = Math.floorMod(word.id.hashCode(), modes.size)
        return modes[index]
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
            mode = mode,
        )

        // 听音拼写：不显示中文，靠发音回忆拼写；题面只放一句提示
        PracticeMode.LISTEN_SPELL -> QuizItem(
            word = word,
            prompt = "点这里再听一遍",
            answer = word.text,
            spell = true,
            mode = mode,
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
                mode = mode,
            )
        }

        // 听音选义：题面不能露出单词，只给一个可以重复播放的提示
        PracticeMode.LISTEN_ZH -> {
            val correct = word.meaningWithPos
            val options = buildOptions(correct, allWords, random) { it.meaningWithPos }
            QuizItem(
                word = word,
                prompt = "点这里再听一遍",
                answer = correct,
                options = options,
                correctOption = options.indexOf(correct),
                mode = mode,
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
                mode = mode,
            )
        }

        // 只有 build() 会把 MIXED 拆成具体题型；直接调到这里时按默认规则拆一次
        PracticeMode.MIXED -> makeItem(word, allWords, pickMixed(word, true), random)

        // 例句填词：中文句意 + 英文挖空；没有例句就退回英译汉，不让出题器空转
        PracticeMode.EX_SPELL -> {
            val blank = blankExample(word.exampleEn, word.text)
            if (blank == null) {
                makeItem(word, allWords, PracticeMode.EN_ZH, random)
            } else {
                val zh = word.exampleZh.ifBlank { "把这句话补完整" }
                QuizItem(
                    word = word,
                    prompt = "$zh\n${blank.line}",
                    answer = blank.token,
                    spell = true,
                    mode = mode,
                    accept = listOf(word.text),
                    longPrompt = true,
                )
            }
        }

        // 词形变化：题干给释义和原词，答案是变位后的形式
        PracticeMode.FORM -> {
            val form = com.worddrill.app.data.WordForms.questionFor(word)
            if (form == null) {
                makeItem(word, allWords, PracticeMode.ZH_EN_SPELL, random)
            } else {
                QuizItem(
                    word = word,
                    prompt = "「${word.text}」的${form.label}",
                    answer = form.answer,
                    spell = true,
                    mode = mode,
                )
            }
        }
    }

    // ---------- 例句挖空 ----------

    /** 挖空后的句子 + 被挖掉的那个词形（可能是 abandoned 这种变形）。 */
    data class ExampleBlank(val line: String, val token: String)

    /**
     * 把例句里第一次出现的目标词（含变形）替换成下划线。
     * 太短的词不挖（a / an 满篇都是），返回 null 让调用方跳过。
     */
    fun blankExample(sentence: String, word: String): ExampleBlank? {
        val base = word.trim().lowercase()
        if (base.length < 3 || sentence.isBlank()) return null
        val regex = Regex("\\b${Regex.escape(base)}[a-z]*\\b", RegexOption.IGNORE_CASE)
        val match = regex.find(sentence) ?: return null
        val token = match.value
        val line = sentence.replaceRange(match.range, BLANK)
        return ExampleBlank(line = line, token = token)
    }

    /** 例句填词时原形和变形都算对。 */
    fun isAcceptable(input: String, answer: String, accept: List<String>): Boolean {
        if (isCorrect(input, answer)) return true
        return accept.any { isCorrect(input, it) }
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

    /** 答对且够快。 */
    fun isFast(elapsedMs: Long, correct: Boolean): Boolean =
        correct && elapsedMs in 0..FAST_ANSWER_MS

    // ---------- 拼写诊断 ----------

    /**
     * 答错时给出具体错因；答对或没输入返回 null。
     * 纯函数，方便单测：只说「差几个字母 / 少写了什么 / 哪个字母容易混」。
     */
    fun diagnose(input: String, answer: String): String? {
        val given = normalize(input)
        val target = normalize(answer)
        if (given.isEmpty() || given == target) return null

        val hints = mutableListOf<String>()
        if (given.replace(" ", "") == target.replace(" ", "")) {
            hints.add("空格位置不对")
        }

        val distance = editDistance(given, target)
        hints.add(
            when {
                distance == 1 -> "只差 1 个字母"
                distance <= 3 -> "差 $distance 个字母"
                else -> "和正确答案差 $distance 个字母"
            },
        )

        if (target.startsWith(given)) {
            hints.add("后面还差 ${target.length - given.length} 个字母")
        } else if (given.startsWith(target)) {
            hints.add("多写了 ${given.length - target.length} 个字母")
        } else if (target == given + "e" || target == given + "d") {
            hints.add("结尾少了一个字母")
        }

        confusionHint(target, given)?.let { hints.add(it) }

        // 只保留前两条，反馈卡一行放得下
        return hints.take(2).joinToString("；")
    }

    /** 常见拼写混淆：ie/ei、-tion/-sion、双写字母、ph。 */
    private fun confusionHint(target: String, given: String): String? {
        fun has(word: String, token: String) = word.contains(token)

        if (has(target, "ie") && has(given, "ei") && !has(target, "ei")) return "ie 和 ei 记混了"
        if (has(target, "ei") && has(given, "ie") && !has(target, "ie")) return "ei 和 ie 记混了"
        if (has(target, "tion") && has(given, "sion")) return "是 -tion 不是 -sion"
        if (has(target, "sion") && has(given, "tion")) return "是 -sion 不是 -tion"
        if (has(target, "ph") && has(given, "f") && !has(given, "ph")) return "ph 发 /f/ 音，别写成 f"
        doubleLetter(target)?.let { letter ->
            if (!has(given, letter.toString() + letter)) return "$letter 要双写"
        }
        return null
    }

    /** 目标词里的双写字母（ll / ss / ee / oo / tt / pp / nn / rr），没有返回 null。 */
    private fun doubleLetter(word: String): Char? {
        for (i in 0 until word.length - 1) {
            val ch = word[i]
            if (ch == word[i + 1] && ch in "lseotpnr") return ch
        }
        return null
    }

    /** Levenshtein 距离，两次滚动数组，单词长度量级足够快。 */
    fun editDistance(a: String, b: String): Int = com.worddrill.app.data.TextDistance.editDistance(a, b)

    /** 两个词形是否只差一个后缀（诊断用不到时保留接口，测试里用到）。 */
    fun similarLength(a: String, b: String, tolerance: Int = 2): Boolean =
        com.worddrill.app.data.TextDistance.similarLength(a, b, tolerance)
}