package com.worddrill.app.data

/**
 * 词性写法统一。规则对齐网页版 `scripts/normalize-pos.mjs`：
 * `a.` → `adj.`、`ad.` → `adv.`、`interj` → `int.`、`vt/vi` 保留，多词性用 `&` 连接。
 *
 * 认不出来的写法（用户导入的「动词短语」之类）原样保留——不猜、不丢，
 * 只是把已知的几种旧写法收敛掉。
 */
object Pos {

    private val MODAL_V = Regex("^modal\\s*v\\.?$", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")

    /** 旧写法 / 同义写法 → 统一写法（不带点）。 */
    private val UNITS = mapOf(
        "a" to "adj",
        "adj" to "adj",
        "ad" to "adv",
        "adv" to "adv",
        "n" to "n",
        "v" to "v",
        "vt" to "vt",
        "vi" to "vi",
        "prep" to "prep",
        "conj" to "conj",
        "pron" to "pron",
        "num" to "num",
        "art" to "art",
        "int" to "int",
        "interj" to "int",
        "interjection" to "int",
        "aux" to "aux",
        "modal" to "modal v",
    )

    /** 统一词性写法；空串仍然是空串。 */
    fun normalize(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if (MODAL_V.matches(text)) return "modal v."

        val parts = text.split('&', '/', ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return text

        val out = LinkedHashSet<String>()
        var recognized = false
        for (part in parts) {
            val unit = UNITS[key(part)]
            if (unit == null) {
                out.add(part)
            } else {
                recognized = true
                out.add("$unit.")
            }
        }
        if (!recognized) return text
        return out.joinToString("&")
    }

    /** 释义里残留的词性前缀（「a. 好看的」）一起改掉，对齐网页版 normalizeMeaning。 */
    fun normalizeMeaning(meaning: String): String {
        var text = meaning
        text = Regex("(^|[\\s,，;；/])a\\.").replace(text) { m -> m.groupValues[1] + "adj." }
        text = Regex("(^|[\\s,，;；/])ad\\.").replace(text) { m -> m.groupValues[1] + "adv." }
        text = Regex("\\bvnum\\.", RegexOption.IGNORE_CASE).replace(text, "num.")
        return text
    }

    /** 拆成统一后的词性单位集合，用于筛选。 */
    fun units(raw: String): Set<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (part in text.split('&', '/', ',')) {
            val unitKey = key(part)
            if (unitKey.isEmpty()) continue
            out.add(UNITS[unitKey] ?: unitKey)
        }
        return out
    }

    /**
     * 词表筛选：`v` 同时包含 `vt` / `vi`；旧数据里的 `a.` / `ad.` 也能按
     * 新的 `adj` / `adv` 筛出来（迁移前后的词库行为一致）。
     */
    fun matchesFilter(raw: String, filter: String): Boolean {
        if (filter.isBlank()) return true
        val target = UNITS[key(filter)] ?: return false
        val set = units(raw)
        return if (target == "v") {
            set.any { it == "v" || it == "vt" || it == "vi" }
        } else {
            target in set
        }
    }

    private fun key(part: String): String =
        part.trim().lowercase().removeSuffix(".").replace(SPACES, " ").trim()
}