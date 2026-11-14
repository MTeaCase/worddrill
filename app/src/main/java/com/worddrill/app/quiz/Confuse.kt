package com.worddrill.app.quiz

import com.worddrill.app.data.Word
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 选择题干扰项：优先挑「长得像」的词（adapt / adopt / adept），并保证不会出现两个正确答案。
 *
 * 规则对齐网页版 `confuse.js`：
 * - 形近分 = 编辑距离 + 公共前后缀 + 长度差 + 词性一致 + 同词族；
 * - 同义词、同释义、释义互相包含的词一律不能当干扰项；
 * - 同词族的派生词（adapt / adaptation）也不能当干扰项；
 * - 数量不够再按「同词性 → 同首字母 → 长度接近」兜底。
 */
object Confuse {

    enum class Mode { EN_ZH, ZH_EN }

    /** 一轮练习里词表固定，把这些预处理结果缓存起来，避免每题重算几千次。 */
    class Pool(words: List<Word>) {
        class Entry(val word: Word, val norm: String, val core: String, val pos: Set<String>) {
            val chars: Set<Char> = core.filter { !it.isWhitespace() }.toSet()
            val bigrams: Set<String> = buildSet {
                val text = core
                for (i in 0 until text.length - 1) add(text.substring(i, i + 2))
            }
        }

        val entries: List<Entry> = words.map { Entry(it, normalizeWord(it.text), coreGloss(it.meaning), posUnits(it.pos)) }
        private val byId: Map<Long, Entry> = entries.associateBy { it.word.id }
        private val byNorm: Map<String, Entry> = entries.associateBy { it.norm }

        fun entryOf(word: Word): Entry? = byId[word.id] ?: byNorm[normalizeWord(word.text)]
    }

    private val IPA = Regex("[ˈˌəɔɪʊʃʒθðŋæɑɜːɒʌɚɝ]")
    private val BAD_CHARS = Regex("[*\\[\\]{}<>\\\\|+=^%$#@!?;:\",`~]")
    private val WORD = Regex("^[A-Za-zÀ-ÖØ-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ0-9'’.\\-/&\\s]*$")
    private val POS_PREFIX = Regex("^(?:modal(?:\\s+v)?|interj|adj|adv|prep|conj|pron|art|num|int|aux|vt|vi|ad|a|n|v)\\.?(?:\\s*[&/]\\s*(?:modal(?:\\s+v)?|interj|adj|adv|prep|conj|pron|art|num|int|aux|vt|vi|ad|a|n|v)\\.?)*\\s*", RegexOption.IGNORE_CASE)
    private val PAREN = Regex("[（(][^）)]{0,80}[）)]")
    private val PUNCT = Regex("[，。；、：,.!?;:()（）\\[\\]“”\"'…·\\s]")
    private val STOP = Regex("[的地得了个些等或与和及]")
    private val SUFFIX = Regex("^(?:s|es|ed|ing|er|est|ly|tion|sion|ation|ition|ment|ness|ity|ive|al|ous|ic|ist|ism|ize|ise|y)$")

    fun normalizeWord(word: String): String = word
        .lowercase()
        .replace("*", "")
        .replace('ˈ', '\'')
        .replace('ˌ', '\'')
        .replace(Regex("\\s+"), " ")
        .trim()

    /** 释义去掉词性前缀、括号注释、标点和虚词，只留核心字/字母。 */
    fun coreGloss(meaning: String): String = meaning
        .replace(POS_PREFIX, "")
        .replace(PAREN, "")
        .replace(PUNCT, "")
        .replace(STOP, "")
        .trim()

    fun posUnits(pos: String): Set<String> = Regex("(?:interj|modal(?:\\s+v)?|adj|adv|prep|conj|pron|art|num|int|aux|vt|vi|ad|a|n|v)(?![a-z])")
        .findAll(pos.lowercase())
        .map { match ->
            when {
                match.value.startsWith("modal") -> "modal"
                match.value == "vt" || match.value == "vi" -> "v"
                match.value == "adv" || match.value == "adj" -> "ad"
                match.value == "interj" -> "int"
                else -> match.value
            }
        }
        .toSet()

    fun posMatch(a: Set<String>, b: Set<String>): Double = when {
        a.isEmpty() && b.isEmpty() -> 0.5
        a.isEmpty() || b.isEmpty() -> 0.35
        a.any { it in b } -> 1.0
        else -> 0.0
    }

    fun damerauLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val s = a.toCharArray()
        val t = b.toCharArray()
        val dp = Array(s.size + 1) { IntArray(t.size + 1) }
        for (i in 0..s.size) dp[i][0] = i
        for (j in 0..t.size) dp[0][j] = j
        for (i in 1..s.size) {
            for (j in 1..t.size) {
                val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                dp[i][j] = min(dp[i - 1][j] + 1, min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost))
                if (i > 1 && j > 1 && s[i - 1] == t[j - 2] && s[i - 2] == t[j - 1]) {
                    dp[i][j] = min(dp[i][j], dp[i - 2][j - 2] + 1)
                }
            }
        }
        return dp[s.size][t.size]
    }

    fun sameFamily(a: String, b: String): Boolean {
        if (a.length == b.length) return false
        val short = if (a.length < b.length) a else b
        val long = if (a.length < b.length) b else a
        if (short.length < 4 || !long.startsWith(short)) return false
        val suffix = long.substring(short.length)
        return suffix.length <= 3 || SUFFIX.matches(suffix)
    }

    fun orthoScore(a: Pool.Entry, b: Pool.Entry): Double {
        val x = a.norm
        val y = b.norm
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val maxLen = max(x.length, y.length)
        val edit = 1.0 - damerauLevenshtein(x, y).toDouble() / maxLen
        val prefix = min(commonPrefix(x, y), 4) / 4.0
        val suffix = min(commonSuffix(x, y), 3) / 3.0
        val length = 1.0 - abs(x.length - y.length).toDouble() / maxLen
        val family = if (sameFamily(x, y)) 1.0 else 0.0
        val score = 0.55 * edit + 0.2 * prefix + 0.1 * suffix + 0.15 * length +
            0.15 * posMatch(a.pos, b.pos) + 0.15 * family
        return score.coerceIn(0.0, 1.0)
    }

    fun glossScore(a: Pool.Entry, b: Pool.Entry): Double {
        if (a.core.isEmpty() || b.core.isEmpty()) return 0.0
        if (a.core == b.core) return 1.0
        val chars = jaccard(a.chars, b.chars)
        val bigrams = dice(a.bigrams, b.bigrams)
        return (0.7 * chars + 0.3 * bigrams).coerceIn(0.0, 1.0)
    }

    /** 两条释义是否会构成两个正确答案。 */
    fun isAmbiguous(a: Pool.Entry, b: Pool.Entry): Boolean {
        if (a.core.isEmpty() || b.core.isEmpty()) return false
        if (a.core == b.core) return true
        if (a.core.length >= 2 && b.core.length >= 2 && (a.core.contains(b.core) || b.core.contains(a.core))) return true
        var shared = 0
        for (ch in a.chars) if (ch in b.chars) shared += 1
        if (shared >= 2 && min(a.chars.size, b.chars.size) <= 8) return true
        return glossScore(a, b) >= 0.6
    }

    fun isValidWord(text: String): Boolean {
        if (text.isEmpty() || text != text.trim()) return false
        if (!WORD.matches(text)) return false
        if (IPA.containsMatchIn(text)) return false
        if (BAD_CHARS.containsMatchIn(text)) return false
        return true
    }

    fun isValidEntry(entry: Pool.Entry): Boolean =
        isValidWord(entry.word.text) && entry.core.isNotEmpty()

    private fun optionKey(mode: Mode, entry: Pool.Entry): String =
        if (mode == Mode.EN_ZH) entry.core else entry.norm

    /**
     * 选 [count] 个干扰项。`vary = false` 时固定取形近分最高的几个；`true` 时在前 9 个里随机，
     * 同一个词每次的选项就不再完全固定。
     */
    fun pickDistractors(
        correct: Word,
        pool: Pool,
        mode: Mode,
        count: Int = 3,
        random: Random = Random.Default,
        vary: Boolean = false,
    ): List<Word> {
        if (count <= 0) return emptyList()
        val target = pool.entryOf(correct) ?: return emptyList()
        if (target.norm.isEmpty() || !isValidEntry(target)) return emptyList()

        val basic = pool.entries.filter { entry ->
            entry.word.id != correct.id &&
                entry.norm != target.norm &&
                isValidEntry(entry)
        }
        if (basic.isEmpty()) return emptyList()

        val correctLen = correct.text.length
        val ortho = basic
            .filter { abs(it.word.text.length - correctLen) <= 4 }
            .map { it to orthoScore(target, it) }
            .filter { it.second >= 0.4 }
            .sortedByDescending { it.second }
            .take(max(9, count * 3))

        val picked = ArrayList<Pool.Entry>()
        val taken = HashSet<String>()

        fun take(entry: Pool.Entry): Boolean {
            val key = optionKey(mode, entry)
            if (key.isEmpty() || key in taken) return false
            if (mode == Mode.EN_ZH) {
                for (prev in picked) if (glossScore(prev, entry) >= 0.7) return false
            }
            picked.add(entry)
            taken.add(key)
            return true
        }

        fun safe(entry: Pool.Entry): Boolean {
            if (isAmbiguous(target, entry)) return false
            // 同词族的派生词不当干扰项：adapt 与 adaptation 摆在一起只会让人怀疑题目
            if (sameFamily(target.norm, entry.norm)) return false
            return true
        }

        val safeOrtho = ortho.map { it.first }.filter(::safe)
        val orthoCandidates = if (vary) safeOrtho.shuffled(random) else safeOrtho
        val orthoTarget = if (mode == Mode.EN_ZH) min(2, count) else count
        for (entry in orthoCandidates) {
            if (picked.size >= orthoTarget) break
            take(entry)
        }

        if (mode == Mode.EN_ZH && picked.size < count) {
            var best: Pool.Entry? = null
            var bestDistance = Double.MAX_VALUE
            for (entry in basic) {
                if (!safe(entry)) continue
                val score = glossScore(target, entry)
                if (score < 0.2 || score > 0.5) continue
                var shared = 0
                for (ch in target.chars) {
                    if (ch in entry.chars) {
                        shared += 1
                        if (shared >= 2) break
                    }
                }
                if (shared < 2) continue
                val distance = abs(score - 0.35)
                if (distance < bestDistance) {
                    best = entry
                    bestDistance = distance
                }
            }
            best?.let { take(it) }
        }

        if (picked.size < count) {
            val fallback = basic
                .filter(::safe)
                .sortedWith(
                    compareByDescending<Pool.Entry> { posMatch(target.pos, it.pos) }
                        .thenByDescending { firstLetterSame(target.norm, it.norm) }
                        .thenBy { abs(it.word.text.length - correctLen) },
                )
            for (entry in fallback) {
                if (picked.size >= count) break
                take(entry)
            }
        }
        return picked.map { it.word }
    }

    private fun firstLetterSame(a: String, b: String): Int =
        if (a.isNotEmpty() && b.isNotEmpty() && a[0].equals(b[0], ignoreCase = true)) 1 else 0

    private fun commonPrefix(a: String, b: String): Int {
        val n = min(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i += 1
        return i
    }

    private fun commonSuffix(a: String, b: String): Int {
        val n = min(a.length, b.length)
        var i = 0
        while (i < n && a[a.length - 1 - i] == b[b.length - 1 - i]) i += 1
        return i
    }

    private fun jaccard(a: Set<Char>, b: Set<Char>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var overlap = 0
        for (item in a) if (item in b) overlap += 1
        return overlap.toDouble() / (a.size + b.size - overlap)
    }

    private fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var overlap = 0
        for (item in a) if (item in b) overlap += 1
        return 2.0 * overlap / (a.size + b.size)
    }
}