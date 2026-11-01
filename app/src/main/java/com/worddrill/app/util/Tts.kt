package com.worddrill.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 系统 TTS 的薄封装。首次调用时异步初始化：初始化完成前请求的文本会被记住，
 * 引擎就绪后立刻朗读。全部离线，不需要网络权限。
 */
object Tts {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(context: Context, text: String) {
        val app = context.applicationContext
        if (!Settings.ttsEnabled(app)) return
        val clean = text.trim()
        if (clean.isEmpty()) return

        val current = engine
        if (current == null) {
            pending = clean
            val created = TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    engine?.language = Locale.US
                    engine?.setSpeechRate(0.92f)
                    pending?.let { say(engine, it) }
                }
                pending = null
            }
            engine = created
            return
        }
        if (ready) {
            say(current, clean)
        } else {
            pending = clean
        }
    }

    private fun say(target: TextToSpeech?, text: String) {
        if (target == null) return
        target.stop()
        target.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
        pending = null
    }

    private const val UTTERANCE_ID = "worddrill"
}
