package kr.lastmile.app.domain

import android.content.Context
import kr.lastmile.app.messaging.MessageSender
import kr.lastmile.app.source.pleos.PleosLocationSource
import kr.lastmile.app.source.pleos.PleosNaviSource
import kr.lastmile.app.source.pleos.PleosSpeaker
import kr.lastmile.app.source.pleos.PleosVehicleSignalSource
import kr.lastmile.app.source.pleos.PleosVoiceInput
import kr.lastmile.app.speech.AndroidTtsSpeaker

/** SDK 모드 배선 (BuildConfig.USE_MOCK = false). 각 어댑터는 실패 시 상태 문자열만 남기고 앱은 계속 동작한다. */
object PleosWiring {
    fun create(context: Context, repo: DestinationRepository, sender: MessageSender): TripEngine {
        var engine: TripEngine? = null
        val tts: (String) -> Unit = { engine?.status("TTS", it) }
        val stt: (String) -> Unit = { engine?.status("STT", it) }
        return TripEngine.build(
            context, repo,
            PleosNaviSource(context), PleosVehicleSignalSource(context), PleosLocationSource(context),
            PleosSpeaker(context, tts, AndroidTtsSpeaker(context) { engine?.status("TTS", it) }), PleosVoiceInput(context, stt), sender,
        ).also { engine = it }
    }
}
