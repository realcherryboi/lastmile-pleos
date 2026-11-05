package kr.lastmile.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

interface Speaker {
    fun speak(text: String)
    fun release()
}

/** AAOS 기본 TTS 폴백. Pleos TextToSpeech SDK 어댑터는 PleosSpeaker 에서. */
class AndroidTtsSpeaker(context: Context, private val onStatus: (String) -> Unit) : Speaker {
    private var ready = false
    private val pending = ArrayDeque<String>()
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status: Int ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) { tts.setLanguage(Locale.KOREAN); onStatus("Android TTS 준비"); while (pending.isNotEmpty()) speak(pending.removeFirst()) }
            else onStatus("Android TTS 실패($status)")
        }
    }
    override fun speak(text: String) {
        if (!ready) { pending += text; return }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "lastmile-${System.nanoTime()}")
    }
    override fun release() = tts.shutdown()
}

interface VoiceInput {
    fun start(onResult: (String) -> Unit)
    fun listenOnce()
    fun release()
}

/** 화면 텍스트 입력으로 STT를 흉내 낸다. */
class TextVoiceInput : VoiceInput {
    private var cb: ((String) -> Unit)? = null
    override fun start(onResult: (String) -> Unit) { cb = onResult }
    override fun listenOnce() {}
    fun submit(text: String) = cb?.invoke(text)
    override fun release() { cb = null }
}
