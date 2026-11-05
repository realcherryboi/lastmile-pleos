package kr.lastmile.app.source.pleos

import android.content.Context
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import kr.lastmile.app.speech.Speaker
import kr.lastmile.app.speech.VoiceInput
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode

/** TextToSpeech SDK v3.3.1, ON_DEVICE 모드(앱 등록 불필요). 1문장씩 순차 재생. */
class PleosSpeaker(context: Context, private val onStatus: (String) -> Unit, private val fallback: Speaker? = null) : Speaker {
    private var ready = false
    private var useFallback = false
    private val queue = ArrayDeque<String>()
    private var speaking = false
    private var tts: TextToSpeech? = null
    private var current: String? = null
    private val events = object : EventListener {
        override fun onStart() { speaking = true }
        override fun onReady() {}
        override fun onDone() { speaking = false; next() }
        override fun onStop() { speaking = false; next() }
        override fun onError(e: TtsError) {
            onStatus("TTS 오류 $e${if (fallback != null) " → Android TTS 폴백" else ""}"); speaking = false
            // 온디바이스 엔진 오류: 방금 문장을 폴백으로 다시 말하고 이후에도 폴백 사용
            if (fallback != null) { useFallback = true; current?.let { fallback.speak(it) } }
            next()
        }
        override fun onUpdatedRms(rms: Double) {}
    }

    init {
        try {
            if (!PleosPermissions.has(context, PleosPermissions.TTS)) onStatus("권한 없음 TTS_SERVICE")
            val t = TextToSpeech(context, TtsMode.ON_DEVICE); tts = t
            // SDK 순서: 생성 → initialize → (성공 후) addEventListener → setLocale → speak
            t.initialize(object : TtsInit {
                override fun onSuccess() {
                    runCatching { t.addEventListener(events); t.setLocale(Locale.KOREAN) }.onFailure { onStatus("TTS 설정 실패 ${it.message}") }
                    ready = true; onStatus("Pleos TTS 준비"); next()
                }
                override fun onFailure(e: TtsError) { onStatus("Pleos TTS 실패 $e") }
            })
        } catch (e: Throwable) { onStatus("Pleos TTS 생성 실패 ${e.message}") }
    }

    override fun speak(text: String) {
        if (useFallback || tts == null) { fallback?.speak(text); return }
        queue += text; next()
    }
    private fun next() {
        val t = tts ?: return
        if (!ready || speaking) return
        val line = queue.removeFirstOrNull() ?: return
        speaking = true; current = line
        runCatching { t.speak(line, false) }.onFailure { speaking = false; onStatus("speak 실패 ${it.message}"); fallback?.speak(line) }
    }
    override fun release() { runCatching { tts?.removeEventListener(events); tts?.release() }; fallback?.release() }
}

/** SpeechToText SDK v3.3.1, ON_DEVICE 모드. listenOnce() 로 1회 인식, 최종 결과만 전달. */
class PleosVoiceInput(private val context: Context, private val onStatus: (String) -> Unit) : VoiceInput {
    private var cb: ((String) -> Unit)? = null
    private var ready = false
    private var stt: SpeechToText? = null
    private val results = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) { if (isFinal && text.isNotBlank()) cb?.invoke(text) }
        override fun onUpdatedEpdData(start: Long, end: Long) {}
        override fun onStartedRecognition() { onStatus("듣는 중") }
        override fun onEndedRecognition() { onStatus("인식 종료") }
        override fun onError(e: SttError) { onStatus("STT 오류 $e") }
        override fun onReady() {}
        override fun onUpdatedRms(rms: Double) {}
    }

    override fun start(onResult: (String) -> Unit) {
        cb = onResult
        try {
            if (!PleosPermissions.has(context, PleosPermissions.STT)) onStatus("권한 없음 STT_SERVICE")
            val s = SpeechToText(context, SttMode.ON_DEVICE); stt = s
            s.initialize(object : SttInit {
                override fun onSuccess() {
                    runCatching { s.addListener(results); s.setLocale(Locale.KOREAN) }.onFailure { onStatus("STT 설정 실패 ${it.message}") }
                    ready = true; onStatus("Pleos STT 준비")
                }
                override fun onFailure(e: SttError) { onStatus("Pleos STT 실패 $e") }
            })
        } catch (e: Throwable) { onStatus("Pleos STT 생성 실패 ${e.message}") }
    }

    override fun listenOnce() { val s = stt; if (ready && s != null) runCatching { s.request() }.onFailure { onStatus("request 실패 ${it.message}") } else onStatus("STT 준비 안 됨") }
    override fun release() { runCatching { stt?.removeListener(results); stt?.release() }; cb = null }
}
