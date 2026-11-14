package kr.lastmile.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kr.lastmile.app.LastMileApp

/**
 * 헤드업 안내 카드의 "물어보기" 버튼 (대표 결정 11/14).
 * 주행 중에는 내비가 화면 앞에 있어 앱의 물어보기 버튼에 손이 닿지 않는다. 그래서 내비 위에 뜨는 헤드업 카드에 버튼을 달고,
 * 누르면 앱 화면을 열지 않고(내비를 가리지 않고) 마이크만 한 번 연다. 답은 음성 + 헤드업 카드로 돌아온다.
 */
class AskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? LastMileApp)?.engine?.askFromHeadsUp()
    }
}
