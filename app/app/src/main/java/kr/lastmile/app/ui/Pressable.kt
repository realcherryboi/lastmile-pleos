package kr.lastmile.app.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/** Pleos Connect 인터랙션 가이드: 터치 가능한 요소는 Pressed 시 95%로 수축, 영역 밖으로 나가면 취소. 응답 지연 0 (즉시 애니메이션). */
object Pressable {
    @SuppressLint("ClickableViewAccessibility")
    fun attach(vararg views: View) {
        for (v in views) v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }
}
