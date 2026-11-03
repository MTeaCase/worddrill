package com.worddrill.app.util

import com.worddrill.app.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 例句生成：调用自建服务的 /api/example，拿回英中对照例句。
 * 结果由调用方写进本地数据库，同一个词不会重复请求。
 */
object ExampleClient {

    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 30000

    data class Example(val en: String, val zh: String)

    /** 由智能解析地址推导例句地址：把结尾的 /smart-parse 换成 /example。 */
    fun url(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/smart-parse") -> trimmed.removeSuffix("/smart-parse") + "/example"
            trimmed.endsWith("/api") -> "$trimmed/example"
            else -> "$trimmed/example"
        }
    }

    /** 阻塞调用，必须在后台线程执行。 */
    fun fetch(base: String, word: String, meaning: String): Example {
        val target = url(base)
        if (target.isBlank()) throw IllegalStateException("没有配置智能解析服务地址")
        val payload = JSONObject().put("word", word).put("meaning", meaning).toString()
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
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
                val detail = runCatching { JSONObject(body).optString("error") }.getOrNull()
                throw IOException(if (!detail.isNullOrBlank()) detail else "服务返回 HTTP $code")
            }
            val json = runCatching { JSONObject(body) }
                .getOrElse { throw IllegalStateException("服务返回的不是合法 JSON") }
            if (!json.optBoolean("ok", true)) {
                throw IllegalStateException(json.optString("error").ifBlank { "生成失败" })
            }
            val en = json.optString("en").trim()
            val zh = json.optString("zh").trim()
            if (en.isEmpty()) throw IllegalStateException("没有生成出可用的例句")
            return Example(en, zh)
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
