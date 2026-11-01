package com.worddrill.app.util

import android.content.Context
import com.worddrill.app.R

/** 「关于」页里的一行信息：左边标签 + 右边内容。 */
data class AboutRow(val label: String, val value: String)

/** 「关于」页的全部可配置内容。 */
data class AboutInfo(
    val name: String,
    val version: String,
    val developer: String,
    val summary: String,
    val rows: List<AboutRow>,
    val note: String,
)

/**
 * 读取 assets/about.txt 里的键值对，让「关于」页内容不用改代码。
 * 文件不存在或读不出来时使用内置文案。
 */
object AboutConfig {

    const val ASSET_NAME = "about.txt"

    private const val KEY_NAME = "名称"
    private const val KEY_VERSION = "版本"
    private const val KEY_DEVELOPER = "开发者"
    private const val KEY_SUMMARY = "简介"
    private const val KEY_NOTE = "备注"
    private const val KEY_ROW = "信息"

    fun load(context: Context, apkVersion: String): AboutInfo {
        val text = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
        val fallback = builtIn(context, apkVersion)
        return if (text == null) fallback else parse(text, fallback)
    }

    /** 解析文本；缺字段时：名称/版本退回默认，其余不显示。 */
    fun parse(text: String, fallback: AboutInfo): AboutInfo {
        val values = LinkedHashMap<String, String>()
        val rows = mutableListOf<AboutRow>()
        for ((key, value) in pairs(text)) {
            when (key) {
                KEY_ROW, "资料", "信息行" -> rows.add(toRow(value))
                else -> values[key] = value
            }
        }
        return AboutInfo(
            name = values[KEY_NAME].orEmpty().ifBlank { fallback.name },
            version = values[KEY_VERSION].orEmpty().ifBlank { fallback.version },
            developer = values[KEY_DEVELOPER].orEmpty().trim(),
            summary = values[KEY_SUMMARY].orEmpty().trim(),
            rows = rows.filter { it.label.isNotBlank() || it.value.isNotBlank() },
            note = values[KEY_NOTE].orEmpty().trim(),
        )
    }

    private fun builtIn(context: Context, apkVersion: String): AboutInfo = AboutInfo(
        name = context.getString(R.string.app_name),
        version = apkVersion,
        developer = context.getString(R.string.about_developer_value),
        summary = context.getString(R.string.about_summary),
        rows = listOf(
            AboutRow(
                context.getString(R.string.about_vocab_label),
                context.getString(R.string.about_vocab_value),
            ),
            AboutRow(
                context.getString(R.string.about_data_label),
                context.getString(R.string.about_data_value),
            ),
        ),
        note = context.getString(R.string.about_note),
    )

    /** 把文本切成有序的「字段 → 内容」，缩进行接到上一条内容后面。 */
    private fun pairs(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        text.removePrefix("\uFEFF").lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach
            val indented = raw.firstOrNull()?.isWhitespace() == true
            if (indented && out.isNotEmpty()) {
                val last = out.removeAt(out.lastIndex)
                out.add(last.first to if (last.second.isEmpty()) line else last.second + "\n" + line)
                return@forEach
            }
            val split = line.indexOfFirst { it == '=' || it == '：' || it == ':' }
            if (split <= 0) return@forEach
            out.add(line.substring(0, split).trim() to line.substring(split + 1).trim())
        }
        return out
    }

    private fun toRow(value: String): AboutRow {
        val split = value.indexOf('|')
        return if (split < 0) {
            AboutRow("", value.trim())
        } else {
            AboutRow(value.substring(0, split).trim(), value.substring(split + 1).trim())
        }
    }
}
