package kr.lastmile.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kr.lastmile.app.R
import kr.lastmile.app.domain.Destination
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/** 도착 구역 지점의 실제 좌표. 정문 = 목적지 좌표, 나머지는 도로 방위(roadBearing)를 따라 m 오프셋. */
object ZoneGeo {
    data class Spot(val type: String, val label: String, val note: String, val lat: Double, val lng: Double)

    fun offset(lat: Double, lng: Double, meters: Double, bearingDeg: Double): Pair<Double, Double> {
        val b = Math.toRadians(bearingDeg)
        val dLat = meters * cos(b) / 111_320.0
        val dLng = meters * sin(b) / (111_320.0 * cos(Math.toRadians(lat)))
        return lat + dLat to lng + dLng
    }

    fun spots(d: Destination): List<Spot> = d.zone.map { z ->
        // 도로변(entrance·parking·noentry)은 정문에서 도로 쪽으로 6m, 만남 지점은 보도 쪽으로 8m
        val along = offset(d.latitude, d.longitude, z.offsetM.toDouble(), d.roadBearing)
        val side = when (z.type) { "meeting" -> -16.0; "entrance" -> 0.0; else -> 12.0 }
        val p = offset(along.first, along.second, side, d.roadBearing + 90)
        Spot(z.type, z.label, z.note, p.first, p.second)
    }
}

/**
 * 실제 지도(MapLibre + OpenFreeMap 벡터 타일, 키 불필요). 하나의 MapView 를 화면 슬롯 사이로 옮겨 쓴다.
 * 레이어: 경로선 → 도착 반경 → 정문 연석(허용 초록/금지 빨강) → 지점 심볼 → 차 마커.
 * 스타일 로드 실패(오프라인) 시 onAvailable(false) → 호출자가 스케치 폴백을 보여 준다.
 */
class ZoneMap(private val context: Context, private val onAvailable: (Boolean) -> Unit) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var holdUntil = 0L
    private val d = context.resources.displayMetrics.density

    // pending state (applied when style is ready)
    private var dest: Destination? = null
    private var car: LatLng? = null
    private var bearing = 0f
    private var arrived = false
    private var route: List<LatLng> = emptyList()
    private var includeCar = true

    init {
        val opts = MapLibreMapOptions.createFromAttributes(context).textureMode(true).logoEnabled(false).attributionEnabled(true)
            .compassEnabled(false).rotateGesturesEnabled(false).tiltGesturesEnabled(false)
        view = MapView(context, opts)
        view.onCreate(null)
        view.addOnDidFailLoadingMapListener { onAvailable(false) }
        view.getMapAsync { m ->
            map = m
            m.uiSettings.setAttributionMargins((12 * d).toInt(), 0, 0, (10 * d).toInt())
            m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) holdUntil = System.currentTimeMillis() + 8_000 }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { st -> style = st; localizeLabels(st); installLayers(st); onAvailable(true); apply() }
        }
    }

    fun attachTo(slot: FrameLayout) {
        if (view.parent === slot) return
        (view.parent as? ViewGroup)?.removeView(view)
        slot.addView(view, minOf(1, slot.childCount), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))   // 스케치 위, 유리 오버레이 아래
        holdUntil = 0L
        view.post { apply(force = true) }
    }

    /** "현재 위치로": 사용자 조작 유지 시간을 끝내고 차+도착 구역이 모두 보이게 되돌린다. */
    fun recenter() { holdUntil = 0L; apply(force = true) }

    fun update(dest: Destination?, carLat: Double, carLng: Double, bearing: Float, arrived: Boolean, includeCar: Boolean) {
        val changed = this.dest?.id != dest?.id
        this.dest = dest; this.arrived = arrived; this.bearing = bearing; this.includeCar = includeCar
        this.car = if (carLat.isNaN()) null else LatLng(carLat, carLng)
        if (changed) route = emptyList()
        apply(force = changed)
    }

    fun setRoute(points: List<LatLng>) { route = points; apply() }

    // ---------- lifecycle ----------
    fun onStart() = view.onStart(); fun onResume() = view.onResume(); fun onPause() = view.onPause()
    fun onStop() = view.onStop(); fun onDestroy() = view.onDestroy(); fun onLowMemory() = view.onLowMemory()

    /** 기본 스타일의 지명 라벨을 한국어(name:ko → name)만 표시하도록 바꾼다. */
    private fun localizeLabels(st: Style) {
        val ko = org.maplibre.android.style.expressions.Expression.coalesce(get("name:ko"), get("name"))
        for (l in st.layers) if (l is SymbolLayer) runCatching { l.setProperties(textField(ko)) }
    }

    // ---------- layers ----------
    private fun installLayers(st: Style) {
        for (id in listOf(SRC_ROUTE, SRC_RADIUS, SRC_CURB, SRC_SPOTS, SRC_CAR, SRC_DEST)) st.addSource(GeoJsonSource(id))
        st.addImage("lm-pin", circleIcon(R.drawable.ic_flag, c(R.color.text_primary)))
        st.addImage("lm-pin-ok", circleIcon(R.drawable.ic_flag, c(R.color.informative_active)))
        st.addImage("lm-pin-no", circleIcon(R.drawable.ic_flag, c(R.color.informative_negative)))
        st.addImage("lm-parking", circleIcon(R.drawable.ic_parking, c(R.color.informative_positive)))
        st.addImage("lm-meeting", circleIcon(R.drawable.ic_walk, c(R.color.text_primary)))
        st.addImage("lm-noentry", circleIcon(R.drawable.ic_block, c(R.color.informative_negative)))
        st.addImage("lm-car", carIcon())
        st.addLayer(LineLayer("lm-route-casing", SRC_ROUTE).withProperties(lineColor(Color.WHITE), lineWidth(12f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        st.addLayer(LineLayer("lm-route", SRC_ROUTE).withProperties(lineColor(c(R.color.informative_positive)), lineWidth(7f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        st.addLayer(FillLayer("lm-radius", SRC_RADIUS).withProperties(fillColor(c(R.color.informative_positive)), fillOpacity(0.10f)))
        st.addLayer(LineLayer("lm-curb-casing", SRC_CURB).withProperties(lineColor(Color.WHITE), lineWidth(14f), lineCap(Property.LINE_CAP_ROUND)))
        st.addLayer(LineLayer("lm-curb", SRC_CURB).withProperties(lineColor(get("color")), lineWidth(8f), lineCap(Property.LINE_CAP_ROUND)))
        st.addLayer(SymbolLayer("lm-car", SRC_CAR).withProperties(
            iconImage("lm-car"), iconAllowOverlap(true), iconIgnorePlacement(true), iconRotate(get("bearing")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)))
        st.addLayer(SymbolLayer("lm-dest", SRC_DEST).withProperties(
            iconImage("lm-pin"), iconAllowOverlap(true), iconIgnorePlacement(true),
            textField(get("label")), textFont(arrayOf("Noto Sans Bold")), textSize(14f), textOffset(arrayOf(0f, 1.9f)), textAnchor(Property.TEXT_ANCHOR_TOP),
            textColor(c(R.color.basic_100)), textHaloColor(Color.WHITE), textHaloWidth(1.6f), textAllowOverlap(true)).apply { maxZoom = 15.5f })
        st.addLayer(SymbolLayer("lm-spots", SRC_SPOTS).withProperties(
            iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true),
            textField(get("label")), textFont(arrayOf("Noto Sans Bold")), textSize(14f), textOffset(arrayOf(0f, 1.9f)), textAnchor(Property.TEXT_ANCHOR_TOP),
            textColor(c(R.color.basic_100)), textHaloColor(Color.WHITE), textHaloWidth(1.6f), textAllowOverlap(true)).apply { minZoom = 15.5f })
    }

    private fun apply(force: Boolean = false) {
        val st = style ?: return; val m = map ?: return
        val dest = this.dest
        val spots = dest?.let { ZoneGeo.spots(it) } ?: emptyList()
        st.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(if (route.size >= 2 && !arrived) FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(route.map { Point.fromLngLat(it.longitude, it.latitude) }))) else EMPTY)
        st.getSourceAs<GeoJsonSource>(SRC_RADIUS)?.setGeoJson(dest?.let { FeatureCollection.fromFeature(Feature.fromGeometry(circle(it.latitude, it.longitude, it.arrivalRadiusM.toDouble()))) } ?: EMPTY)
        // 정문 앞 연석 ±22m
        val curb = dest?.let { dd ->
            val e = spots.firstOrNull { it.type == "entrance" } ?: return@let null
            val a = ZoneGeo.offset(e.lat, e.lng, -22.0, dd.roadBearing); val b = ZoneGeo.offset(e.lat, e.lng, 22.0, dd.roadBearing)
            val road = { p: Pair<Double, Double> -> ZoneGeo.offset(p.first, p.second, 7.0, dd.roadBearing + 90) }
            val ra = road(a); val rb = road(b)
            Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(ra.second, ra.first), Point.fromLngLat(rb.second, rb.first)))).apply {
                addStringProperty("color", if (dd.curbsideAllowed) hex(R.color.informative_active) else hex(R.color.informative_negative))
            }
        }
        st.getSourceAs<GeoJsonSource>(SRC_CURB)?.setGeoJson(curb?.let { FeatureCollection.fromFeature(it) } ?: EMPTY)
        val feats = spots.map { s ->
            Feature.fromGeometry(Point.fromLngLat(s.lng, s.lat)).apply {
                addStringProperty("icon", when (s.type) { "parking" -> "lm-parking"; "meeting" -> "lm-meeting"; "noentry" -> "lm-noentry"; else -> if (dest?.curbsideAllowed == true) "lm-pin-ok" else "lm-pin-no" })
                addStringProperty("label", if (s.note.isNotBlank()) "${s.label}\n${s.note}" else s.label)
            }
        }
        st.getSourceAs<GeoJsonSource>(SRC_SPOTS)?.setGeoJson(FeatureCollection.fromFeatures(feats))
        st.getSourceAs<GeoJsonSource>(SRC_DEST)?.setGeoJson(dest?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).apply { addStringProperty("label", it.name) }) } ?: EMPTY)
        val carF = car?.takeIf { includeCar }?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).apply { addNumberProperty("bearing", bearing) } }
        st.getSourceAs<GeoJsonSource>(SRC_CAR)?.setGeoJson(carF?.let { FeatureCollection.fromFeature(it) } ?: EMPTY)

        // camera: 차 + 도착 구역 전부가 보이게. 사용자가 손으로 움직였으면 8초간 유지
        if (!force && System.currentTimeMillis() < holdUntil) return
        if (dest == null) return
        val b = LatLngBounds.Builder()
        for (s in spots) b.include(LatLng(s.lat, s.lng))
        val pad = ZoneGeo.offset(dest.latitude, dest.longitude, 60.0, 45.0); val pad2 = ZoneGeo.offset(dest.latitude, dest.longitude, 60.0, 225.0)
        b.include(LatLng(pad.first, pad.second)); b.include(LatLng(pad2.first, pad2.second))
        if (includeCar) car?.let { b.include(it) }
        if (view.width == 0 || view.height == 0) return
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), (70 * d).toInt(), (56 * d).toInt(), (70 * d).toInt(), (56 * d).toInt()))
        if (m.cameraPosition.zoom > 19.0) m.moveCamera(CameraUpdateFactory.zoomTo(19.0))
    }

    private fun circle(lat: Double, lng: Double, r: Double): Polygon {
        val pts = (0..36).map { i -> val p = ZoneGeo.offset(lat, lng, r, i * 10.0); Point.fromLngLat(p.second, p.first) }
        return Polygon.fromLngLats(listOf(pts))
    }

    private fun c(id: Int) = ContextCompat.getColor(context, id)
    private fun hex(id: Int) = String.format("#%06X", 0xFFFFFF and c(id))

    private fun circleIcon(drawableId: Int, bg: Int): Bitmap {
        val size = (44 * d).toInt(); val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; cv.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = bg; cv.drawCircle(size / 2f, size / 2f, size / 2f - 2.5f * d, p)
        val ic = ContextCompat.getDrawable(context, drawableId)!!.mutate(); ic.setTint(Color.WHITE)
        val r = (12 * d).toInt(); ic.setBounds(size / 2 - r, size / 2 - r, size / 2 + r, size / 2 + r); ic.draw(cv)
        return bmp
    }

    private fun carIcon(): Bitmap {
        val size = (52 * d).toInt(); val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x330082FF; cv.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = Color.WHITE; cv.drawCircle(size / 2f, size / 2f, 17f * d, p)
        p.color = c(R.color.informative_positive); cv.drawCircle(size / 2f, size / 2f, 14f * d, p)
        val ic = ContextCompat.getDrawable(context, R.drawable.ic_navigation)!!.mutate(); ic.setTint(Color.WHITE)
        val r = (9 * d).toInt(); ic.setBounds(size / 2 - r, size / 2 - r, size / 2 + r, size / 2 + r); ic.draw(cv)
        return bmp
    }

    companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
        private const val SRC_ROUTE = "lm-src-route"; private const val SRC_RADIUS = "lm-src-radius"; private const val SRC_CURB = "lm-src-curb"
        private const val SRC_SPOTS = "lm-src-spots"; private const val SRC_CAR = "lm-src-car"; private const val SRC_DEST = "lm-src-dest"
        private val EMPTY: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    }
}
