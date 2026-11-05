package kr.lastmile.app.messaging

import android.content.Context
import android.content.Intent
import android.net.Uri

/** 마중 메시지. 데모는 미리보기 + SMS 인텐트(수신 앱이 없으면 미리보기만). 서버 없음. */
class MessageSender(private val context: Context) {
    var recipient: String = ""          // 출발 전 운전자가 등록 (데모: 임의 번호)
    var lastPreview: String = ""
        private set

    fun send(text: String, onStatus: (String) -> Unit) {
        lastPreview = text
        if (recipient.isBlank()) { onStatus("수신자 미등록 — 미리보기만"); return }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$recipient"))
            .putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) { context.startActivity(intent); onStatus("SMS 인텐트 전송") }
        else onStatus("SMS 앱 없음 — 미리보기만")
    }
}
