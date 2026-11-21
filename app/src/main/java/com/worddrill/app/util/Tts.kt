package com.worddrill.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import com.worddrill.app.data.Accent
import java.util.Locale

/**
 * 语音朗读入口，两种引擎二选一：
 *
 * - **系统 TTS**（默认）：`TextToSpeech(context, listener)`，用设备自带的语音引擎；
 * - **离线语音包**（可选）：用户下载安装 com.worddrill.tts 后，用
 *   `TextToSpeech(context, listener, "com.worddrill.tts")` 指名调用它。
 *
 * 选哪个由设置里的开关决定，但**永远兜底**：离线引擎初始化失败（被卸载、数据损坏、
 * ROM 拦截）就自动切回系统语音，同时把开关关掉，并在回调里告诉用户为什么。
 *
 * 其它设计沿用之前：初始化异步、口音/语速跟随设置、失败原因交给 UI、diagnose 排查。
 */
object Tts {

    private var engine: TextToSpeech? = null

    /** 当前实例实际用的引擎包名；null 表示系统默认引擎。 */
    private var enginePackage: String? = null

    private var ready = false
    private var failed: String? = null
    private var pendingText: String? = null
    private var pendingError: ((String) -> Unit)? = null
    private var app: Context? = null

    /** 已经应用到引擎上的口音和语速，用来判断设置变了没有。 */
    private var appliedAccent: Accent? = null
    private var appliedRate: Float = -1f

    /** 上一次设置语言时是否因为设备缺语音包而回退过，给 UI 提示用。 */
    private var missingAccent = false

    /** 最近一次自动回退的原因，diagnose 里能看到。 */
    private var lastFallback: String? = null

    /** 朗读 [text]；失败时调用 [onError] 并给出可读原因。 */
    fun speak(context: Context, text: String, onError: ((String) -> Unit)? = null) {
        val application = context.applicationContext
        app = application
        if (!Settings.ttsEnabled(application)) return
        val clean = text.trim()
        if (clean.isEmpty()) return

        // 设置里换了引擎、或者离线包被卸载了：把旧引擎丢掉重建
        if (engine != null && enginePackage != desiredPackage(application)) resetEngine()

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

    /** 设置里切换「系统语音 / 离线语音」后调用，下一次朗读就用新引擎。 */
    fun refreshEngine() {
        resetEngine()
        failed = null
        ready = false
    }

    private fun desiredPackage(context: Context): String? {
        if (!Settings.offlineTtsEnabled(context)) return null
        if (!TtsPack.isSupported()) return null
        return if (TtsPack.isInstalled(context)) TtsPack.ENGINE_PACKAGE else null
    }

    private fun createEngine(application: Context) {
        val wanted = desiredPackage(application)
        enginePackage = wanted
        val listener = TextToSpeech.OnInitListener { status -> onInit(application, status) }
        engine = if (wanted == null) {
            TextToSpeech(application, listener)
        } else {
            TextToSpeech(application, listener, wanted)
        }
    }

    private fun onInit(application: Context, status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            engine?.let { configure(it) }
            return
        }

        // 离线引擎起不来：切回系统语音，别让用户「点了没声音」
        if (enginePackage != null) {
            Settings.setOfflineTtsEnabled(application, false)
            lastFallback = "离线语音初始化失败（$status），已切回系统语音"
            resetEngine()
            val callback = pendingError
            pendingError = null
            callback?.invoke(lastFallback.orEmpty())
            createEngine(application)
            return
        }

        fail("系统未提供语音引擎（初始化返回 $status），请在系统设置中安装或启用「文字转语音」")
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
            val preferred = if (accent == Accent.UK) Locale.UK else Locale.US
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

    fun accentFallbackWarning(): String? =
        if (missingAccent) "未安装对应英语语音包，暂用通用发音" else null

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
            onError?.invoke("播放失败：语音引擎没有发出声音，可在设置里换用离线语音包")
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

    /** 当前用的是哪个引擎，给设置页和排查用。 */
    fun engineLabel(): String = when (enginePackage) {
        null -> "系统语音"
        TtsPack.ENGINE_PACKAGE -> "离线语音包"
        else -> enginePackage.orEmpty()
    }

    /** 排查用：返回语言可用性与初始化状态描述。 */
    fun diagnose(context: Context): String {
        val accent = Settings.accent(context)
        val locale = if (accent == Accent.UK) Locale.UK else Locale.US
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
        val packVersion = TtsPack.installedVersion(context)
        return buildString {
            append("当前引擎：").append(if (ready) engineLabel() else "未就绪")
            append("\n离线语音包：")
            append(if (packVersion > 0) "已安装（versionCode $packVersion）" else "未安装")
            append("\n当前口音：").append(accent.label)
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
            lastFallback?.let { append("\n最近一次回退：").append(it) }
            append("\n如显示「缺少语音数据」，可在设置里下载离线语音包，或在系统「文字转语音」中安装语音数据")
        }
    }

    /** 只拆掉当前引擎实例，保留待朗读的文本和错误回调。 */
    private fun resetEngine() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        enginePackage = null
        ready = false
        appliedAccent = null
        appliedRate = -1f
    }

    fun shutdown() {
        resetEngine()
        failed = null
        pendingText = null
        pendingError = null
        app = null
        missingAccent = false
    }

    private const val UTTERANCE_ID = "worddrill"
}