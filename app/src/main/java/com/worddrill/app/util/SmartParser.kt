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
 * 服务端单次请求有 10 秒限制，所以这里会把长文本按行切成小块顺序请求，最后拼成一张表。
 * 服务端代码见 `netlify/functions/smart-parse.mjs`。
 */
object SmartParser {

    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 30000

    /** 单块字符上限，留足余量让模型在 10 秒内返回。 */
    internal const val MAX_CHUNK_CHARS = 1200

    private val separatorRegex = Regex("^\\|[\\s:|-]+\\|$")

    data class Result(
        val markdown: String,
        val count: Int,
    )

    /**
     * 阻塞调用，必须在后台线程执行。
     * [onProgress] 回调（已完成的块，总块数），用于显示进度。
     */
    fun request(
        url: String,
        text: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        if (url.isBlank()) throw IllegalStateException("没有配置智能解析服务地址")
        if (text.isBlank()) throw IllegalStateException("内容为空")

        val chunks = splitChunks(text)
        val rows = mutableListOf<String>()
        var header = "| 英文 | 词性 | 汉语 |"
        chunks.forEachIndexed { index, chunk ->
            onProgress(index + 1, chunks.size)
            val part = requestOnce(url, chunk)
            header = part.header
            rows += part.rows
        }
        if (rows.isEmpty()) throw IllegalStateException("AI 没有返回可用的词条")
        return Result(joinTable(header, rows), rows.size)
    }

    private data class Part(
        val header: String,
        val rows: List<String>,
    )

    private fun requestOnce(url: String, text: String): Part {
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
                throw IOException(if (!detail.isNullOrBlank()) detail else "服务返回 HTTP $code")
            }
            return parsePart(body)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 解析服务返回的 JSON。抽出来方便单测，不碰网络。 */
    internal fun parseResponse(body: String): Result {
        val part = parsePart(body)
        if (part.rows.isEmpty()) throw IllegalStateException("AI 没有返回可用的词条")
        return Result(joinTable(part.header, part.rows), part.rows.size)
    }

    private fun parsePart(body: String): Part {
        val json = runCatching { JSONObject(body) }
            .getOrElse { throw IllegalStateException("服务返回的不是合法 JSON") }
        val ok = json.optBoolean("ok", json.has("markdown"))
        if (!ok) {
            throw IllegalStateException(json.optString("error").ifBlank { "解析失败" })
        }
        return toPart(json.optString("markdown", ""))
    }

    /**
     * 兜底清洗：模型偶尔会套上 ``` 代码块、或在表格前后加说明，
     * 这里只留表格行，并保证有表头。
     */
    internal fun sanitize(raw: String): String = with(toPart(raw)) { joinTable(header, rows) }

    private fun toPart(raw: String): Part {
        val lines = raw.replace("\r", "")
            .split("\n")
            .map { it.trim() }
            .filter { it.startsWith("|") && it.indexOf('|', 1) > 0 }

        val header = lines.firstOrNull(::isHeader) ?: "| 英文 | 词性 | 汉语 |"
        val rows = lines.filter { !separatorRegex.matches(it) && !isHeader(it) }
        return Part(header, rows)
    }

    private fun isHeader(line: String): Boolean =
        line.contains("英文") && (line.contains("汉语") || line.contains("中文"))

    private fun joinTable(header: String, rows: List<String>): String =
        buildList {
            add(header)
            add("| --- | --- | --- |")
            addAll(rows)
        }.joinToString("\n")

    /** 按行切块，尽量不把一行拆开；单行超长时硬切。 */
    internal fun splitChunks(text: String, limit: Int = MAX_CHUNK_CHARS): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val piece = current.toString().trim()
            if (piece.isNotEmpty()) chunks.add(piece)
            current.setLength(0)
        }

        for (raw in text.split("\n")) {
            val line = raw.trimEnd()
            if (line.length > limit) {
                flush()
                line.chunked(limit).forEach { piece ->
                    val trimmed = piece.trim()
                    if (trimmed.isNotEmpty()) chunks.add(trimmed)
                }
                continue
            }
            if (current.isNotEmpty() && current.length + line.length + 1 > limit) flush()
            current.append(line).append('\n')
        }
        flush()
        return chunks.ifEmpty { listOf(text.trim()) }
    }
}
