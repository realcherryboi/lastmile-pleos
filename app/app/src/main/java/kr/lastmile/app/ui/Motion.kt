package kr.lastmile.app.ui

import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kr.lastmile.app.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow

/**
 * 스프링 모션 유틸. Apple 의 Fluid Interfaces 원칙을 그대로 따른다:
 *  - 고정 시간(duration) 대신 스프링(response·damping) 으로 움직인다 → 항상 현재 값에서 이어지고, 중간에 목표가 바뀌어도 끊기지 않는다.
 *  - 기본은 임계감쇠(damping 1.0, 오버슈트 없음). 바운스는 제스처 에너지가 실린 경우(누름 해제·던지기)에만.
 *  - 시스템 "애니메이션 끄기"(animator duration scale 0) 면 즉시 최종값으로 간다.
 *
 * response(초) → stiffness 환산: k = (2π / response)² (질량 1).
 */
object Motion {
    class Spec(val response: Float, val damping: Float) {
        val stiffness get() = (2.0 * PI / response).pow(2).toFloat()
    }
    /** 이동·크기·투명도 기본값. Apple 기본(1.0 / 0.4)보다 살짝 빠르게 — 차량 화면은 글랜스 시간이 짧다. */
    val Default = Spec(0.38f, 1f)
    /** 누름(터치 다운) 반응: 지연 0 에 가깝게 */
    val Press = Spec(0.14f, 1f)
    /** 누름 해제: 손가락이 떠난 에너지만큼만 살짝 튄다 */
    val Release = Spec(0.32f, 0.62f)
    /** 시트·드로어 (Apple 값 0.8 / 0.3) */
    val Sheet = Spec(0.34f, 0.82f)
    /** 숫자·진행 바처럼 값이 자주 바뀌는 것: 느긋하게, 절대 튀지 않게 */
    val Value = Spec(0.55f, 1f)
    /** 도착·이정표 통과처럼 "순간"을 강조할 때 */
    val Pop = Spec(0.42f, 0.55f)

    var enabled = true
        private set

    fun init(context: Context) {
        enabled = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f
    }

    private fun springs(v: View): HashMap<Any, SpringAnimation> {
        @Suppress("UNCHECKED_CAST")
        return (v.getTag(R.id.tag_springs) as? HashMap<Any, SpringAnimation>) ?: HashMap<Any, SpringAnimation>().also { v.setTag(R.id.tag_springs, it) }
    }

    /** 뷰 속성 하나를 스프링으로 target 까지. 이미 움직이는 중이면 현재 값·속도에서 이어서 간다. */
    fun spring(v: View, prop: FloatPropertyCompat<View>, target: Float, spec: Spec = Default, velocity: Float? = null, end: (() -> Unit)? = null) {
        val map = springs(v)
        val anim = map.getOrPut(prop) { SpringAnimation(v, prop).apply { spring = SpringForce() } }
        anim.removeEndListener(ENDS.remove(anim) ?: NO_END)
        if (!enabled) { anim.cancel(); prop.setValue(v, target); end?.invoke(); return }
        anim.spring.stiffness = spec.stiffness; anim.spring.dampingRatio = spec.damping
        anim.minimumVisibleChange = when (prop) { DynamicAnimation.ALPHA -> 0.004f; DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y -> 0.002f; else -> 0.5f }
        if (velocity != null) anim.setStartVelocity(velocity)
        if (end != null) {
            val l = DynamicAnimation.OnAnimationEndListener { a, canceled, _, _ -> if (!canceled) { ENDS.remove(a); end() } }
            ENDS[anim] = l; anim.addEndListener(l)
        }
        anim.animateToFinalPosition(target)
    }
    private val ENDS = HashMap<SpringAnimation, DynamicAnimation.OnAnimationEndListener>()
    private val NO_END = DynamicAnimation.OnAnimationEndListener { _, _, _, _ -> }

    fun cancel(v: View) { springs(v).values.forEach { it.cancel() } }
    fun cancel(v: View, prop: FloatPropertyCompat<View>) { springs(v)[prop]?.cancel() }

    fun scale(v: View, target: Float, spec: Spec = Default, velocity: Float? = null) {
        spring(v, DynamicAnimation.SCALE_X, target, spec, velocity); spring(v, DynamicAnimation.SCALE_Y, target, spec, velocity)
    }

    /** 나타나기: 살짝 아래·작게·투명에서 제자리로 ("재질이 도착한다"). 같은 경로로 사라지므로 공간적으로 일관됨. */
    fun appear(v: View, fromY: Float = 0f, fromScale: Float = 1f, spec: Spec = Default) {
        if (v.visibility != View.VISIBLE) { v.visibility = View.VISIBLE; v.alpha = 0f; v.translationY = fromY; v.scaleX = fromScale; v.scaleY = fromScale }
        spring(v, DynamicAnimation.ALPHA, 1f, spec); spring(v, DynamicAnimation.TRANSLATION_Y, 0f, spec); scale(v, 1f, spec)
    }

    /** 사라지기: appear 의 역경로. 끝나면 GONE (그 사이 다시 appear 되면 GONE 하지 않음). */
    fun disappear(v: View, toY: Float = 0f, toScale: Float = 1f, spec: Spec = Default, gone: Boolean = true) {
        if (v.visibility != View.VISIBLE) return
        v.setTag(R.id.tag_hiding, true)
        spring(v, DynamicAnimation.TRANSLATION_Y, toY, spec); scale(v, toScale, spec)
        spring(v, DynamicAnimation.ALPHA, 0f, spec) { if (v.getTag(R.id.tag_hiding) == true) { v.visibility = if (gone) View.GONE else View.INVISIBLE; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; v.setTag(R.id.tag_hiding, null) } }
    }

    /** visible 토글을 appear/disappear 로. 상태가 같으면 아무것도 안 한다. */
    fun setVisible(v: View, visible: Boolean, fromY: Float = 0f, fromScale: Float = 0.97f) {
        val shown = v.visibility == View.VISIBLE && v.getTag(R.id.tag_hiding) != true
        if (visible && !shown) { v.setTag(R.id.tag_hiding, null); appear(v, fromY, fromScale) }
        else if (!visible && shown) disappear(v, fromY, fromScale)
    }

    /** 내용이 바뀐 순간을 알리는 작은 팝 (아이콘·칩). 제스처가 아니므로 바운스는 작게. */
    fun pop(v: View, from: Float = 0.9f) { v.scaleX = from; v.scaleY = from; scale(v, 1f, Pop) }

    /** 던지기 종착점 투영 (Apple 의 UIScrollView 감속 모델). v: px/s */
    fun project(velocity: Float, decelerationRate: Float = 0.998f): Float = (velocity / 1000f) * decelerationRate / (1f - decelerationRate)

    /** 경계 너머 러버밴드: 멀리 갈수록 덜 따라온다. */
    fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float = (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))
}

/** 뷰 속성이 아닌 "값" 하나를 스프링으로 움직인다 (숫자 카운팅, 진행 바, 블러 반경). */
class SpringValue(initial: Float, private val spec: Motion.Spec = Motion.Value, private val onUpdate: (Float) -> Unit) {
    private val holder = FloatValueHolder(initial)
    private val anim = SpringAnimation(holder).apply {
        spring = SpringForce().apply { stiffness = spec.stiffness; dampingRatio = spec.damping }
        minimumVisibleChange = 0.002f
        addUpdateListener { _, v, _ -> onUpdate(v) }
    }
    var target = initial; private set
    val value get() = holder.value
    val isRunning get() = anim.isRunning

    fun animateTo(t: Float, velocity: Float? = null, end: (() -> Unit)? = null) {
        target = t
        if (!Motion.enabled) { anim.cancel(); holder.value = t; onUpdate(t); end?.invoke(); return }
        if (velocity != null) anim.setStartVelocity(velocity)
        if (end != null) anim.addEndListener(object : DynamicAnimation.OnAnimationEndListener {
            override fun onAnimationEnd(a: DynamicAnimation<*>, canceled: Boolean, v: Float, vel: Float) { anim.removeEndListener(this); if (!canceled) end() }
        })
        anim.animateToFinalPosition(t)
    }
    fun snapTo(t: Float) { target = t; anim.cancel(); holder.value = t; onUpdate(t) }
}
