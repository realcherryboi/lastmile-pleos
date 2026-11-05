package kr.lastmile.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kr.lastmile.app.R
import kr.lastmile.app.domain.Destination

/**
 * 도착 구역 스케치. 지도 대신 "마지막 300m"를 도식으로 보여 준다:
 * 건물(목적지) — 보도(만남 지점) — 도로(정문·주차장 입구·진입 금지) — 거리 표기.
 * 데이터는 Destination.zone (정문 기준 m 오프셋). 자동 애니메이션 없음(주행 규제).
 */
class ZoneSketchView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var destination: Destination? = null
        set(v) { field = v; invalidate() }
    var arrived: Boolean = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private fun c(id: Int) = ContextCompat.getColor(context, id)
    private fun icon(id: Int, tint: Int): Drawable = ContextCompat.getDrawable(context, id)!!.mutate().apply { setTint(tint) }

    private val building = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.surface_variant) }
    private val buildingEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.outline); style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.basic_200) }
    private val lane = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.basic_400); strokeWidth = 3f * d; pathEffect = DashPathEffect(floatArrayOf(22f * d, 16f * d), 0f) }
    private val curbOk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.informative_active) }
    private val curbNo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.informative_negative) }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; color = c(R.color.text_primary) }
    private val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f * d; textAlign = Paint.Align.CENTER; color = c(R.color.text_secondary) }
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f * d; typeface = Typeface.DEFAULT_BOLD; color = c(R.color.text_secondary) }
    private val dist = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f * d; textAlign = Paint.Align.CENTER; color = c(R.color.text_tertiary) }
    private val distLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.text_tertiary); strokeWidth = 2f * d }
    private val rect = RectF()

    private val icParking = icon(R.drawable.ic_parking, c(R.color.text_on_primary))
    private val icWalk = icon(R.drawable.ic_walk, c(R.color.text_on_primary))
    private val icBlock = icon(R.drawable.ic_block, c(R.color.text_on_primary))
    private val icCar = icon(R.drawable.ic_car, c(R.color.text_on_primary))
    private val carBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(R.color.text_primary) }

    override fun onDraw(canvas: Canvas) {
        val dest = destination ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val spots = dest.zone
        // 축척이 아니라 "순서"로 배치: 같은 오프셋은 같은 x, 서로 다른 오프셋은 균등 간격 (라벨이 겹치지 않게)
        val offsets = spots.map { it.offsetM }.distinct().sorted()
        val left = 210f * d; val right = minOf(w - 90f * d, left + 520f * d * maxOf(1, offsets.size - 1))
        fun x(off: Int): Float { val i = offsets.indexOf(off); return if (offsets.size <= 1) (left + right) / 2 else left + (right - left) * i / (offsets.size - 1) }

        val bTop = 10f * d; val bBottom = h * 0.36f
        val walkY = h * 0.44f
        val roadTop = h * 0.52f; val roadBottom = h * 0.72f
        // building
        rect.set(40f * d, bTop, w - 40f * d, bBottom)
        canvas.drawRoundRect(rect, 20f * d, 20f * d, building); canvas.drawRoundRect(rect, 20f * d, 20f * d, buildingEdge)
        canvas.drawText(dest.name, rect.left + 24f * d, bTop + 38f * d, name)
        // road
        canvas.drawRect(0f, roadTop, w, roadBottom, road)
        canvas.drawLine(0f, (roadTop + roadBottom) / 2, w, (roadTop + roadBottom) / 2, lane)
        val entrance = spots.firstOrNull { it.type == "entrance" }
        if (entrance != null) {
            val cx = x(entrance.offsetM)
            rect.set(cx - 64f * d, roadTop - 5f * d, cx + 64f * d, roadTop + 5f * d)
            canvas.drawRoundRect(rect, 5f * d, 5f * d, if (dest.curbsideAllowed) curbOk else curbNo)
        }
        val labelY = roadBottom + 34f * d; val noteY = roadBottom + 60f * d
        for (s in spots) {
            val sx = x(s.offsetM)
            when (s.type) {
                "entrance", "noentry" -> {
                    val bad = s.type == "noentry" || !dest.curbsideAllowed
                    marker.color = if (bad) c(R.color.informative_negative) else c(R.color.informative_active)
                    rect.set(sx - 26f * d, bBottom - 30f * d, sx + 26f * d, bBottom + 2f * d)
                    canvas.drawRoundRect(rect, 8f * d, 8f * d, marker)
                    if (s.type == "noentry") { val cy = bBottom - 14f * d; val r = 12f * d; icBlock.setBounds((sx - r).toInt(), (cy - r).toInt(), (sx + r).toInt(), (cy + r).toInt()); icBlock.draw(canvas) }
                    canvas.drawText(s.label, sx, labelY, label)
                    if (s.note.isNotBlank()) { note.color = marker.color; canvas.drawText(s.note, sx, noteY, note); note.color = c(R.color.text_secondary) }
                }
                "parking" -> {
                    marker.color = c(R.color.informative_positive)
                    val cy = roadTop - 30f * d
                    canvas.drawCircle(sx, cy, 26f * d, marker)
                    val r = 16f * d; icParking.setBounds((sx - r).toInt(), (cy - r).toInt(), (sx + r).toInt(), (cy + r).toInt()); icParking.draw(canvas)
                    canvas.drawText(s.label, sx, labelY, label)
                    if (s.note.isNotBlank()) canvas.drawText(s.note, sx, noteY, note)
                    if (entrance != null && s.offsetM != entrance.offsetM) {
                        val ex = x(entrance.offsetM); val ly = walkY
                        val meetStart = if (entrance.offsetM == offsets.first()) 48f * d else ex + 58f * d
                        val meetEnd = spots.firstOrNull { it.type == "meeting" }?.let { meetStart + 30f * d + label.measureText(it.label) + 24f * d } ?: 0f
                        val a = maxOf(minOf(ex, sx) + 40f * d, meetEnd); val b = maxOf(ex, sx) - 36f * d
                        if (b - a > 60f * d) {
                            canvas.drawLine(a, ly, b, ly, distLine)
                            canvas.drawLine(b, ly, b - 8f * d, ly - 6f * d, distLine); canvas.drawLine(b, ly, b - 8f * d, ly + 6f * d, distLine)
                            dist.color = c(R.color.surface); dist.style = Paint.Style.FILL
                            val t = "${Math.abs(s.offsetM - entrance.offsetM)} m"; val tw = dist.measureText(t)
                            rect.set((a + b) / 2 - tw / 2 - 10f * d, ly - 14f * d, (a + b) / 2 + tw / 2 + 10f * d, ly + 14f * d)
                            canvas.drawRoundRect(rect, 14f * d, 14f * d, dist)
                            dist.color = c(R.color.text_secondary); canvas.drawText(t, (a + b) / 2, ly + 7f * d, dist)
                        }
                    }
                }
                "meeting" -> {
                    marker.color = c(R.color.text_primary)
                    val ex = entrance?.let { x(it.offsetM) } ?: left
                    val leftmost = entrance == null || entrance.offsetM == offsets.first()
                    val mx = if (leftmost) 48f * d else ex + 58f * d; val cy = walkY      // 정문 옆 보도
                    canvas.drawCircle(mx, cy, 20f * d, marker)
                    val r = 12f * d; icWalk.setBounds((mx - r).toInt(), (cy - r).toInt(), (mx + r).toInt(), (cy + r).toInt()); icWalk.draw(canvas)
                    label.textAlign = Paint.Align.LEFT
                    canvas.drawText(s.label, mx + 30f * d, cy + 8f * d, label)
                    label.textAlign = Paint.Align.CENTER
                }
            }
        }
        // car: approaching from the left, or at the entrance when arrived
        val cy = (roadTop + roadBottom) / 2
        val cxCar = if (arrived && entrance != null) x(entrance.offsetM) else 48f * d
        canvas.drawCircle(cxCar, cy, 26f * d, carBg)
        val r = 15f * d; icCar.setBounds((cxCar - r).toInt(), (cy - r).toInt(), (cxCar + r).toInt(), (cy + r).toInt()); icCar.draw(canvas)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize((330f * d).toInt(), heightMeasureSpec))
    }
}
