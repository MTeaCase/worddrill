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
 * - 口音（美式 / 英式）和语速都从设置里读，改完设置调用 [refreshVoice] 立即生效；
 * - 提供 [diagnose] 输出引擎与语言可用性，方便排查「装了没声音」。
 */
object Tts {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var failed: String? = null
    private var pendingText: String? = null
    private var pendingError: ((String) -> Unit)? = null
    private var app: Context? = null

    /** 已经应用到引擎上的口音和语速，用来判断设置变了没有。 */
    private var appliedAccent: Settings.Accent? = null
    private var appliedRate: Float = -1f

    /** 朗读 [text]；失败时调用 [onError] 并给出可读原因。 */
    fun speak(context: Context, text: String, onError: ((String) -> Unit)? = null) {
        val application = context.applicationContext
        app = application
        if (!Settings.ttsEnabled(application)) return
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
            createEngine(application)
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

    private fun createEngine(application: Context) {
        engine = TextToSpeech(application) { status ->
            if (status != TextToSpeech.SUCCESS) {
                fail("系统没有可用的语音引擎（初始化返回 $status），请在系统设置里安装或启用「文字转语音」")
                return@TextToSpeech
            }
            engine?.let { configure(it) }
        }
    }

    private fun configure(current: TextToSpeech) {
        applyVoice(current)
        ready = true
        val text = pendingText
        val onError = pendingError
        pendingText = null
        pendingError = null
        if (text != null) speakNow(current, text, onError)
    }

    /** 把设置里的口音和语速写进引擎；值没变就跳过，避免每次朗读都重设语言。 */
    private fun applyVoice(current: TextToSpeech) {
        val application = app ?: return
        val accent = Settings.accent(application)
        val rate = Settings.speechRate(application)

        if (accent != appliedAccent) {
            val preferred = if (accent == Settings.Accent.UK) Locale.UK else Locale.US
            var result = runCatching { current.setLanguage(preferred) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // 设备没装英式语音包时回退到通用英语，至少还能读出来
                result = runCatching { current.setLanguage(Locale.ENGLISH) }.getOrDefault(result)
            }
            missingAccent = result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            appliedAccent = accent
        }
        if (kotlin.math.abs(rate - appliedRate) > 0.001f) {
            runCatching { current.setSpeechRate(rate) }
            appliedRate = rate
        }
    }

    /** 上一次设置语言时是否因为设备缺语音包而回退过，给 UI 提示用。 */
    private var missingAccent = false

    fun accentFallbackWarning(): String? =
        if (missingAccent) "设备没有装对应的英语语音包，暂时用的是通用英语发音" else null

    /** 设置里改了口音或语速后调用，下一次朗读就按新设置走。 */
    fun refreshVoice() {
        appliedAccent = null
        appliedRate = -1f
        val current = engine
        if (current != null && ready) runCatching { applyVoice(current) }
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

    /** 排查用：返回语言可用性与初始化状态描述。 */
    fun diagnose(context: Context): String {
        val accent = Settings.accent(context)
        val locale = if (accent == Settings.Accent.UK) Locale.UK else Locale.US
        val langCode = runCatching { engine?.isLanguageAvailable(locale) }.getOrNull() ?: Int.MIN_VALUE
        val langText = when (langCode) {
            TextToSpeech.LANG_AVAILABLE -> "可用"
            TextToSpeech.LANG_COUNTRY_AVAILABLE -> "可用（国家变体）"
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "可用（国家/变体）"
            TextToSpeech.LANG_MISSING_DATA -> "缺少语音数据"
            TextToSpeech.LANG_NOT_SUPPORTED -> "不支持"
            Int.MIN_VALUE -> "引擎未初始化"
            else -> "未知($langCode)"
        }
        return buildString {
            append("当前口音：").append(accent.label)
            append("\n${accent.label}英语：").append(langText)
            append("\n语速：").append(Settings.speechRate(context)).append("×")
            append("\n初始化状态：")
            append(
                when {
                    failed != null -> failed
                    ready -> "就绪"
                    engine == null -> "未创建"
                    else -> "初始化中"
                },
            )
            append("\n提示：若显示「缺少语音数据」，请到 系统设置 → 文字转语音(TTS) → 安装对应英语语音包")
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
        app = null
        appliedAccent = null
        appliedRate = -1f
        missingAccent = false
    }

    private const val UTTERANCE_ID = "worddrill"
}