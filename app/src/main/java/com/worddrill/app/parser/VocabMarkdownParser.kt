package com.worddrill.app.parser

/** 一条词汇：英文、词性、中文释义。 */
data class VocabEntry(
    val word: String,
    val pos: String,
    val meaning: String,
)

/**
 * 解析 word-vocab-md 技能输出的 Markdown 词汇表（三列或四列表格）。
 *
 * 支持：
 * ```
 * | # | 英文 | 词性 | 汉语 |
 * |---|------|------|------|
 * | 1 | pick out | 动词短语 | 精心挑选；认出来 |
 * ```
 * 也支持无索引列的三列表格、以及 `英文 | 词性 | 汉语` 这种不带首尾竖线的行。
 * 表格里的序号会被忽略——入库顺序完全按本文件的出现顺序。
 */
object VocabMarkdownParser {

    private val separator = Regex("^:?-{2,}:?$")
    private val numeric = Regex("^\\d+[.、]?$")

    fun parse(markdown: String): List<VocabEntry> {
        val out = LinkedHashMap<String, VocabEntry>()
        for (rawLine in markdown.lineSequence()) {
            val line = rawLine.trim()
            if (!line.contains('|')) continue
            val cells = line.trim('|').split('|').map { clean(it) }
            if (cells.isEmpty()) continue
            if (cells.all { it.isEmpty() }) continue
            if (cells.all { separator.matches(it) }) continue
            if (isHeader(cells)) continue

            val body = if (cells.firstOrNull()?.let { numeric.matches(it) } == true) cells.drop(1) else cells
            val word: String
            val pos: String
            val meaning: String
            when {
                body.size >= 3 -> {
                    word = body[0]
                    pos = body[1]
                    meaning = body[2]
                }
                body.size == 2 -> {
                    word = body[0]
                    pos = ""
                    meaning = body[1]
                }
                else -> continue
            }
            if (!isValidWord(word) || meaning.isBlank()) continue
            val key = word.lowercase()
            if (out.containsKey(key)) continue
            out[key] = VocabEntry(word = word, pos = pos, meaning = meaning)
        }
        return out.values.toList()
    }

    private fun clean(cell: String): String =
        cell.trim()
            .removeSurrounding("**")
            .removeSurrounding("`")
            .trim()

    private fun isHeader(cells: List<String>): Boolean {
        val joined = cells.joinToString("")
        return joined.contains("英文") && (joined.contains("汉语") || joined.contains("中文"))
    }

    private fun isValidWord(word: String): Boolean =
        word.isNotBlank() && word.any { it.isLetter() } && !word.contains("|")
}
