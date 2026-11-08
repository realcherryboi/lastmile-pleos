package kr.lastmile.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kr.lastmile.app.R
import kr.lastmile.app.domain.Milestone
import kr.lastmile.app.domain.TripState

/**
 * 내비 앱의 경로 진행 바. 이정표(기본 3km·1km·300m·도착) 위를 차 마커가 이동한다.
 * 이정표는 경로마다 다르다(`TripStateMachine.milestones`): 경로가 3 km 미만이면 첫 이정표가 "출발"이고, 출발점과 겹치는 1 km·300 m 는 빠진다.
 * 거리 축은 선형이 아니라 이정표 구간별 선형이라 라스트마일을 넓게 보여 준다.
 * 차 마커 위치는 스프링(임계감쇠)으로 이어지고, 이정표를 지나는 순간 그 노드가 살짝 튀며 채워진다.
 */
class RouteProgressView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** 이정표 계획. 비어 있으면 기본 3 km·1 km·300 m·도착. */
    var milestones: List<Milestone> = emptyList()
        set(v) {
            if (v == field) return
            field = v
            val n = plan().size
            while (nodeScale.size < n) nodeScale += SpringValue(1f, Motion.Pop) { invalidate() }
            if (remainMeters >= 0) pos.snapTo(progress())
            invalidate()
        }
    var remainMeters: Int = -1
        set(v) {
            val first = field < 0
            field = v
            val p = progress()
            if (first || v < 0) pos.snapTo(p) else pos.animateTo(p)
            invalidate()
        }
    var passed: Int = 0            // 지난 이정표 수 (0~n)
        set(v) {
            val n = plan().size
            if (v > field) for (i in field until minOf(v, n)) nodeScale[i].let { it.snapTo(1.6f); it.animateTo(1f) }
            if (v < field) for (i in v until minOf(field, n)) nodeScale[i].snapTo(1f)
            field = v; invalidate()
        }

    private val default = listOf(Milestone("3 km", 3000, TripState.BRIEF_3KM), Milestone("1 km", 1000, TripState.BRIEF_1KM), Milestone("300 m", 300, TripState.BRIEF_300M), Milestone("도착", 0, TripState.ARRIVED))
    private fun plan() = milestones.ifEmpty { default }
    /** 노드 x 위치(0..1). 첫 노드가 "3 km"면 그 앞(>3 km 구간)에 12%를 남기고, "출발"이면 맨 왼쪽. 뒤로 갈수록 구간을 좁혀 라스트마일을 넓게. */
    private fun xs(): List<Float> {
        val p = plan(); val n = p.size
        val x0 = if (p.first().label == "출발") 0f else 0.12f
        if (n == 1) return listOf(1f)
        val weights = listOf(4f, 3.5f, 2.5f).take(n - 1).let { w -> if (w.size < n - 1) w + List(n - 1 - w.size) { 2.5f } else w }
        val total = weights.sum(); var acc = x0
        return listOf(x0) + weights.map { acc += (1f - x0) * it / total; acc }
    }
    private val pos = SpringValue(0f, Motion.Value) { invalidate() }
    private val nodeScale = MutableList(4) { SpringValue(1f, Motion.Pop) { invalidate() } }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.outline); strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.accent); strokeCap = Paint.Cap.ROUND }
    private val node = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f * density(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val carBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.text_primary) }
    private val carIcon = ContextCompat.getDrawable(context, R.drawable.ic_navigation)!!.mutate().apply { setTint(c(R.color.text_on_primary)) }

    private fun c(id: Int) = ContextCompat.getColor(context, id)
    private fun density() = resources.displayMetrics.density

    /** 남은 거리 → 0..1 (이정표 구간별 선형) */
    private fun progress(): Float {
        val m = remainMeters
        if (m < 0) return 0f
        val p = plan(); val x = xs()
        if (m >= p.first().meters) {
            // 첫 이정표 전: "3 km"면 10 km→3 km 를 0..x0 에 펼친다. "출발"이면 x0 = 0.
            return if (x[0] <= 0f) 0f else (1f - ((m - p.first().meters) / 7000f).coerceIn(0f, 1f)) * x[0]
        }
        for (i in 0 until p.size - 1) {
            val a = p[i].meters; val b = p[i + 1].meters
            if (m <= a && m > b) return x[i] + (a - m).toFloat() / (a - b).toFloat() * (x[i + 1] - x[i])
        }
        return 1f
    }

    override fun onDraw(canvas: Canvas) {
        val d = density()
        val padX = 36f * d
        val y = 34f * d
        val w = width - padX * 2
        val stroke = 10f * d
        track.strokeWidth = stroke; fill.strokeWidth = stroke
        canvas.drawLine(padX, y, padX + w, y, track)
        val p = pos.value.coerceIn(0f, 1f)
        val px = padX + w * p
        if (p > 0f) canvas.drawLine(padX, y, px, y, fill)

        val labels = plan().map { it.label }
        for ((i, fx) in xs().withIndex()) {
            val x = padX + w * fx
            val done = i < passed
            val s = nodeScale[i].value
            node.color = if (done) c(R.color.accent) else c(R.color.surface)
            canvas.drawCircle(x, y, 14f * d * s, node)
            if (!done) { node.color = c(R.color.outline); canvas.drawCircle(x, y, 14f * d, node); node.color = c(R.color.surface); canvas.drawCircle(x, y, 9f * d, node) }
            text.color = if (done) c(R.color.text_primary) else c(R.color.text_secondary)
            canvas.drawText(labels[i], x, y + 52f * d, text)
        }
        if (remainMeters >= 0) {
            canvas.drawCircle(px, y, 24f * d, carBg)
            val r = 14f * d
            canvas.save(); canvas.rotate(90f, px, y)
            carIcon.setBounds((px - r).toInt(), (y - r).toInt(), (px + r).toInt(), (y + r).toInt()); carIcon.draw(canvas)
            canvas.restore()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (96f * density()).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }
}
