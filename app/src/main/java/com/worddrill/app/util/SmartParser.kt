package com.worddrill.app.util

import com.worddrill.app.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 智能解析：把用户乱糟糟的内容发给自建服务（Netlify Function → 商汤日日新），
 * 拿回规范化的 Markdown 表格，再交给 [com.worddrill.app.parser.VocabMarkdownParser] 导入。
 *
 * 具体发什么、LLM 怎么提示，都在仓库的 `netlify/functions/smart-parse.mjs` 里。
 */
object SmartParser {

    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 60000

    data class Result(
        val markdown: String,
        val count: Int,
    )

    /** 阻塞调用，必须在后台线程执行。 */
    fun request(url: String, text: String): Result {
        if (url.isBlank()) throw IllegalStateException("没有配置智能解析服务地址")
        if (text.isBlank()) throw IllegalStateException("内容为空")

        val payload = JSONObject().put("text", text).toString()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WordDrill/${BuildConfig.VERSION_NAME}")
        }
        try {
            conn.outputStream.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                // 服务端出错时也会返回 {ok:false,error:"..."}，优先用里面的说明
                val detail = runCatching { JSONObject(body).optString("error") }.getOrNull()
                throw IOException(
                    if (!detail.isNullOrBlank()) detail else "服务返回 HTTP $code",
                )
            }
            return parseResponse(body)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 解析服务返回的 JSON。抽出来方便单测，不碰网络。 */
    internal fun parseResponse(body: String): Result {
        val json = runCatching { JSONObject(body) }
            .getOrElse { throw IllegalStateException("服务返回的不是合法 JSON") }
        val ok = json.optBoolean("ok", json.has("markdown"))
        if (!ok) {
            val error = json.optString("error").ifBlank { "解析失败" }
            throw IllegalStateException(error)
        }
        val markdown = sanitize(json.optString("markdown", ""))
        val count = countRows(markdown)
        if (count <= 0) throw IllegalStateException("AI 没有返回可用的词条")
        return Result(markdown, count)
    }

    /**
     * 兜底清洗：模型偶尔会套上 ``` 代码块、或在表格前后加说明，
     * 这里只留表格行，并保证有表头。
     */
    internal fun sanitize(raw: String): String {
        val lines = raw.replace("\r", "")
            .split("\n")
            .map { it.trim() }
            .filter { it.startsWith("|") && it.indexOf('|', 1) > 0 }

        val isSeparator = { line: String -> Regex("^\\|[\\s:|-]+\\|$").matches(line) }
        val isHeader = { line: String -> line.contains("英文") && (line.contains("汉语") || line.contains("中文")) }

        val rows = lines.filter { !isSeparator(it) && !isHeader(it) }
        val header = lines.firstOrNull(isHeader) ?: "| 英文 | 词性 | 汉语 |"
        return buildList {
            add(header)
            add("| --- | --- | --- |")
            addAll(rows)
        }.joinToString("\n")
    }

    private fun countRows(markdown: String): Int = markdown.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("|") }
        .count { line ->
            val isSeparator = Regex("^\\|[\\s:|-]+\\|$").matches(line)
            val isHeader = line.contains("英文") && (line.contains("汉语") || line.contains("中文"))
            !isSeparator && !isHeader
        }
}
