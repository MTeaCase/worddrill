package com.worddrill.app.data

/**
 * 词形变化题库：只做「能确定答案」的题。
 *
 * 取舍：
 * - 动词走不规则表 + 规则后缀，绝大多数动词都有确定的过去式；
 * - 名词只出**不规则复数**（child → children），规则复数不出，
 *   因为没法离线判断可数与不可数（information 加 s 就错了）；
 * - 形容词不出比较级，长形容词要用 more，容易教错。
 */
object WordForms {

    /** 一道词形题：题干是「过去式」「复数」这类标签，答案是应填的英文。 */
    data class FormQuestion(val label: String, val answer: String)

    /** 常见不规则动词过去式。只收高频词，宁可少出题也不教错。 */
    private val IRREGULAR_PAST = mapOf(
        "be" to "was", "bear" to "bore", "beat" to "beat", "become" to "became",
        "begin" to "began", "bite" to "bit", "blow" to "blew", "break" to "broke",
        "bring" to "brought", "build" to "built", "burn" to "burnt", "buy" to "bought",
        "catch" to "caught", "choose" to "chose", "come" to "came", "cost" to "cost",
        "cut" to "cut", "deal" to "dealt", "dig" to "dug", "do" to "did",
        "draw" to "drew", "drink" to "drank", "drive" to "drove", "eat" to "ate",
        "fall" to "fell", "feed" to "fed", "feel" to "felt", "fight" to "fought",
        "find" to "found", "fly" to "flew", "forget" to "forgot", "forgive" to "forgave",
        "freeze" to "froze", "get" to "got", "give" to "gave", "go" to "went",
        "grow" to "grew", "hang" to "hung", "have" to "had", "hear" to "heard",
        "hide" to "hid", "hit" to "hit", "hold" to "held", "hurt" to "hurt",
        "keep" to "kept", "know" to "knew", "lay" to "laid", "lead" to "led",
        "learn" to "learnt", "leave" to "left", "lend" to "lent", "let" to "let",
        "lie" to "lay", "light" to "lit", "lose" to "lost", "make" to "made",
        "mean" to "meant", "meet" to "met", "pay" to "paid", "put" to "put",
        "read" to "read", "ride" to "rode", "ring" to "rang", "rise" to "rose",
        "run" to "ran", "say" to "said", "see" to "saw", "seek" to "sought",
        "sell" to "sold", "send" to "sent", "set" to "set", "shake" to "shook",
        "shine" to "shone", "shoot" to "shot", "show" to "showed", "shut" to "shut",
        "sing" to "sang", "sink" to "sank", "sit" to "sat", "sleep" to "slept",
        "speak" to "spoke", "spend" to "spent", "split" to "split", "spread" to "spread",
        "stand" to "stood", "steal" to "stole", "stick" to "stuck", "strike" to "struck",
        "swim" to "swam", "take" to "took", "teach" to "taught", "tear" to "tore",
        "tell" to "told", "think" to "thought", "throw" to "threw", "understand" to "understood",
        "wake" to "woke", "wear" to "wore", "win" to "won", "write" to "wrote",
    )

    /** 名词只出这些确定的不规则复数。 */
    private val IRREGULAR_PLURAL = mapOf(
        "child" to "children", "man" to "men", "woman" to "women", "foot" to "feet",
        "tooth" to "teeth", "mouse" to "mice", "goose" to "geese", "person" to "people",
        "knife" to "knives", "leaf" to "leaves", "life" to "lives", "wife" to "wives",
        "half" to "halves", "shelf" to "shelves", "wolf" to "wolves",
        "potato" to "potatoes", "tomato" to "tomatoes", "hero" to "heroes",
    )

    /** 能出题就返回题目，否则 null（调用方据此跳过这个词）。 */
    fun questionFor(word: Word): FormQuestion? {
        val base = word.text.trim().lowercase()
        if (base.length < 3 || base.any { !it.isLetter() }) return null
        return when {
            word.pos.trim().lowercase().startsWith("v") ->
                pastForm(base)?.let { FormQuestion("过去式", it) }

            word.pos.trim().lowercase().startsWith("n") ->
                IRREGULAR_PLURAL[base]?.let { FormQuestion("复数", it) }

            else -> null
        }
    }

    /** 规则动词过去式；单音节「辅音+元音+辅音」要双写词尾。 */
    fun pastForm(base: String): String? {
        if (base.isEmpty() || base.any { !it.isLetter() }) return null
        IRREGULAR_PAST[base]?.let { return it }
        if (base.length < 3) return null
        return when {
            base.endsWith("e") -> base + "d"
            base.endsWith("y") && !isVowel(base[base.length - 2]) -> base.dropLast(1) + "ied"
            isDoubling(base) -> base + base.last() + "ed"
            else -> base + "ed"
        }
    }

    private fun isVowel(ch: Char): Boolean = ch in "aeiou"

    /** 单音节且以「辅音-元音-辅音」结尾时才双写（stop → stopped，visit 不双写）。 */
    private fun isDoubling(word: String): Boolean {
        if (word.length < 3) return false
        val last = word[word.length - 1]
        val middle = word[word.length - 2]
        val first = word[word.length - 3]
        if (last in "wxy") return false
        if (isVowel(last) || isVowel(middle) || !isVowel(first)) return false
        return vowelGroups(word) == 1
    }

    private fun vowelGroups(word: String): Int {
        var groups = 0
        var previous = false
        for (ch in word) {
            val vowel = isVowel(ch)
            if (vowel && !previous) groups++
            previous = vowel
        }
        return groups
    }
}