package kr.lastmile.app.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/**
 * Pleos Connect 인터랙션 가이드(Pressed 시 95% 수축, 영역 밖으로 나가면 취소) 를 스프링으로.
 *  - 터치 다운 즉시 반응(response 0.14, 오버슈트 없음) — 응답 지연 0.
 *  - 해제 시에는 손가락이 떠난 에너지만큼 살짝 튀어 돌아온다(damping 0.62). 중간에 다시 누르면 현재 크기에서 이어진다.
 */
object Pressable {
    private const val PRESSED = 0.95f

    @SuppressLint("ClickableViewAccessibility")
    fun attach(vararg views: View) {
        for (v in views) v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> Motion.scale(view, PRESSED, Motion.Press)
                MotionEvent.ACTION_MOVE -> {
                    val slop = 24f
                    val inside = e.x >= -slop && e.y >= -slop && e.x <= view.width + slop && e.y <= view.height + slop
                    Motion.scale(view, if (inside) PRESSED else 1f, if (inside) Motion.Press else Motion.Default)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Motion.scale(view, 1f, Motion.Release)
            }
            false
        }
    }
}
