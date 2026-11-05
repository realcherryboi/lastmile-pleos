package kr.lastmile.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kr.lastmile.app.R

/**
 * 내비 앱의 경로 진행 바. 4개 이정표(3km·1km·300m·도착) 위를 차 마커가 이동한다.
 * 거리 축은 선형이 아니라 구간별(>3km / 3~1km / 1km~300m / 300m~0)로 나눠 라스트마일을 넓게 보여 준다.
 * 차 마커 위치는 스프링(임계감쇠)으로 이어지고, 이정표를 지나는 순간 그 노드가 살짝 튀며 채워진다.
 */
class RouteProgressView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var remainMeters: Int = -1
        set(v) {
            val first = field < 0
            field = v
            val p = progress()
            if (first || v < 0) pos.snapTo(p) else pos.animateTo(p)
            invalidate()
        }
    var passed: Int = 0            // 지난 이정표 수 (0~4)
        set(v) {
            if (v > field) for (i in field until v) nodeScale[i].let { it.snapTo(1.6f); it.animateTo(1f) }
            if (v < field) for (i in v until field) nodeScale[i].snapTo(1f)
            field = v; invalidate()
        }

    private val labels = listOf("3 km", "1 km", "300 m", "도착")
    private val xs = listOf(0.12f, 0.45f, 0.75f, 1f)
    private val pos = SpringValue(0f, Motion.Value) { invalidate() }
    private val nodeScale = List(4) { SpringValue(1f, Motion.Pop) { invalidate() } }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.outline); strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.accent); strokeCap = Paint.Cap.ROUND }
    private val node = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f * density(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val carBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.text_primary) }
    private val carIcon = ContextCompat.getDrawable(context, R.drawable.ic_navigation)!!.mutate().apply { setTint(c(R.color.text_on_primary)) }

    private fun c(id: Int) = ContextCompat.getColor(context, id)
    private fun density() = resources.displayMetrics.density

    /** 남은 거리 → 0..1 (구간별 선형) */
    private fun progress(): Float {
        val m = remainMeters
        if (m < 0) return 0f
        return when {
            m > 3000 -> (1f - ((m - 3000f) / 7000f).coerceIn(0f, 1f)) * 0.12f
            m > 1000 -> 0.12f + (1f - (m - 1000f) / 2000f) * 0.33f
            m > 300 -> 0.45f + (1f - (m - 300f) / 700f) * 0.30f
            else -> 0.75f + (1f - m / 300f) * 0.25f
        }.coerceIn(0f, 1f)
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

        for ((i, fx) in xs.withIndex()) {
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
