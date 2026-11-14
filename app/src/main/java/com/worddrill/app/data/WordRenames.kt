package com.worddrill.app.data

import android.content.Context
import org.json.JSONObject

/**
 * 内置词表的改名表：修词库时旧词头 → 新词头。
 *
 * 备份按「词文本」记录星标 / 错题 / 复习 / 练习，改名（`v]` → `sort`、`ad.` → `either`）
 * 之后老备份一条都匹配不上，用户会以为「恢复成功但进度没了」。
 * 所以导入前先在这里换算一次。
 *
 * 表放在 `assets/word_renames.json`，离线构建脚本和 App 共用同一份，避免两处漂移。
 */
object WordRenames {

    private const val ASSET = "word_renames.json"

    /** 查表用的小写键表 + 表里原始条数（同一条会同时挂「原样键」和「折叠键」，不是条数）。 */
    private class Table(val lookup: Map<String, String>, val count: Int)

    @Volatile
    private var cache: Table? = null

    /** 旧词头换算成新词头；不在表里就原样返回（只去掉首尾空白）。 */
    fun canonical(context: Context, word: String): String {
        val raw = word.trim()
        if (raw.isEmpty()) return raw
        val table = load(context).lookup
        if (table.isEmpty()) return raw
        val lower = raw.lowercase()
        table[lower]?.let { return it }
        table[fold(lower)]?.let { return it }
        return raw
    }

    /** 表里有多少条，测试和「数据版本」提示用。 */
    fun size(context: Context): Int = load(context).count

    /** 规范化词头，用于新旧词表对齐（大小写、星号、音标撇号、连字符、空白）。 */
    fun normalizeKey(word: String): String = fold(word.trim())

    private fun load(context: Context): Table {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val table = readAsset(context)
            cache = table
            return table
        }
    }

    private fun readAsset(context: Context): Table = runCatching {
        val text = context.applicationContext.assets
            .open(ASSET)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val root = JSONObject(text)
        val pairs = buildMap {
            for (key in root.keys()) {
                val target = root.optString(key).trim()
                if (target.isEmpty()) continue
                val normalizedKey = key.trim().lowercase()
                put(normalizedKey, target)
                put(fold(normalizedKey), target)
            }
        }
        Table(pairs, root.length())
    }.getOrDefault(Table(emptyMap(), 0))

    /** 折叠写法差异：重音符、双连字符、连字符两侧空格、多余空白。 */
    private fun fold(word: String): String = word
        .lowercase()
        .replace("*", "")
        .replace('ˈ', '\'')
        .replace('ˌ', '\'')
        .replace("--", "-")
        .replace(Regex("\\s*-\\s*"), "-")
        .replace(Regex("\\s+"), " ")
        .trim()
}