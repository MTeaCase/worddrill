package com.worddrill.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 系统 TTS 的薄封装。
 *
 * 设计要点：
 * - 初始化是异步的，内部记住待读文本，引擎就绪后立刻朗读；
 * - 初始化失败 / 没有英语语音数据时，通过 onError 回调把原因交给 UI 显示，
 *   而不是静默失败；
 * - 提供 [diagnose] 输出引擎与语言可用性，方便排查「装了没声音」。
 */
object Tts {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var failed: String? = null
    private var pendingText: String? = null
    private var pendingError: ((String) -> Unit)? = null

    /** 朗读 [text]；失败时调用 [onError] 并给出可读原因。 */
    fun speak(context: Context, text: String, onError: ((String) -> Unit)? = null) {
        val app = context.applicationContext
        if (!Settings.ttsEnabled(app)) return
        val clean = text.trim()
        if (clean.isEmpty()) return

        failed?.let {
            onError?.invoke(it)
            return
        }

        val current = engine
        if (current == null) {
            pendingText = clean
            pendingError = onError
            createEngine(app)
            return
        }

        if (!ready) {
            // 个别设备会同步回调成功，这里补一次配置；已经初始化过也没关系，重复设置语言无害
            configure(current)
        }
        if (ready) {
            speakNow(current, clean, onError)
        } else {
            pendingText = clean
            pendingError = onError
        }
    }

    private fun createEngine(app: Context) {
        engine = TextToSpeech(app) { status ->
            if (status != TextToSpeech.SUCCESS) {
                fail("系统没有可用的语音引擎（初始化返回 $status），请在系统设置里安装或启用「文字转语音」")
                return@TextToSpeech
            }
            engine?.let { configure(it) }
        }
    }

    private fun configure(current: TextToSpeech) {
        var result = runCatching { current.setLanguage(Locale.US) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            result = runCatching { current.setLanguage(Locale.ENGLISH) }.getOrDefault(result)
        }
        runCatching { current.setSpeechRate(0.92f) }
        ready = true
        val text = pendingText
        val onError = pendingError
        pendingText = null
        pendingError = null
        if (text != null) speakNow(current, text, onError)
    }

    private fun speakNow(current: TextToSpeech, text: String, onError: ((String) -> Unit)?) {
        val code = runCatching {
            current.stop()
            current.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }.getOrElse { TextToSpeech.ERROR }
        if (code == TextToSpeech.ERROR) {
            onError?.invoke("语音播放失败：系统引擎没有英语发音（可在系统设置 → 文字转语音 里下载英语语音包）")
        }
    }

    private fun fail(reason: String) {
        failed = reason
        val callback = pendingError
        pendingText = null
        pendingError = null
        callback?.invoke(reason)
    }

    fun isReady(): Boolean = ready

    /** 排查用：返回引擎列表与语言可用性描述。 */
    fun diagnose(context: Context): String {
        val app = context.applicationContext
        val engines = runCatching { TextToSpeech.getEngines(app) }.getOrNull()
        val names = engines?.mapNotNull { it.name }?.joinToString(", ").orEmpty()
        val langCode = runCatching { engine?.isLanguageAvailable(Locale.US) }.getOrNull() ?: Int.MIN_VALUE
        val langText = when (langCode) {
            TextToSpeech.LANG_AVAILABLE -> "可用"
            TextToSpeech.LANG_COUNTRY_AVAILABLE -> "可用（国家变体）"
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "可用（国家/变体）"
            TextToSpeech.LANG_MISSING_DATA -> "缺少英语语音数据"
            TextToSpeech.LANG_NOT_SUPPORTED -> "不支持英语"
            Int.MIN_VALUE -> "引擎未初始化"
            else -> "未知($langCode)"
        }
        return buildString {
            append("已安装语音引擎：")
            append(if (names.isBlank()) "无" else names)
            append("\n英语语音：")
            append(langText)
            append("\n初始化状态：")
            append(
                when {
                    failed != null -> failed
                    ready -> "就绪"
                    engine == null -> "未创建"
                    else -> "初始化中"
                },
            )
        }
    }

    fun shutdown() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
        failed = null
        pendingText = null
        pendingError = null
    }

    private const val UTTERANCE_ID = "worddrill"
}
