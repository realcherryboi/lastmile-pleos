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
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineGradient
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.VectorSource
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
        if (z.absolute) return@map Spot(z.type, z.label, z.note, z.lat, z.lng)     // 타일에서 찾은 실제 좌표
        // 도로변(entrance·parking·noentry)은 정문에서 도로 쪽으로 6m, 만남 지점은 보도 쪽으로 8m
        val along = offset(d.latitude, d.longitude, z.offsetM.toDouble(), d.roadBearing)
        val side = when (z.type) { "meeting" -> -16.0; "entrance" -> 0.0; else -> 12.0 }
        val p = offset(along.first, along.second, side, d.roadBearing + 90)
        Spot(z.type, z.label, z.note, p.first, p.second)
    }

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0; val dx = (lng2 - lng1) * 111_320.0 * cos(Math.toRadians(lat1)); return Math.sqrt(dx * dx + dy * dy)
    }
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0; val dx = (lng2 - lng1) * 111_320.0 * cos(Math.toRadians(lat1)); return (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0
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
    /** 타일 로드가 끝나 지도가 멈췄을 때 (타일 조회 가능 시점) */
    var onIdle: (() -> Unit)? = null

    // --- 모션 상태 (스프링) ---
    private var originLat = Double.NaN; private var originLng = Double.NaN      // 차 위치 스프링의 기준점 (m 오프셋으로 정밀도 유지)
    private val carX = SpringValue(0f, Motion.Value) { pushCar() }
    private val carY = SpringValue(0f, Motion.Value) { pushCar() }
    private val carHeading = SpringValue(0f, Motion.Value) { pushCar() }
    private val reveal = SpringValue(0f, Motion.Spec(0.9f, 1f)) { pushRoute() }   // 경로선 그려지기 0→1
    private val pulse = SpringValue(0.10f, Motion.Spec(1.1f, 1f)) { v -> style?.getLayer("lm-radius")?.setProperties(fillOpacity(v)); style?.getLayer("lm-radius-line")?.setProperties(lineOpacity((v * 2.5f).coerceAtMost(1f))) }
    private var routeProgress = 0f                 // 경로 중 지나온 비율
    private var routeCum: FloatArray = FloatArray(0)
    private var lastArrived = false
    private var destBuildingFor: String? = null

    init {
        val opts = MapLibreMapOptions.createFromAttributes(context).textureMode(true).logoEnabled(false).attributionEnabled(true)
            .compassEnabled(false).rotateGesturesEnabled(false).tiltGesturesEnabled(false)
        view = MapView(context, opts)
        view.onCreate(null)
        view.addOnDidFailLoadingMapListener { onAvailable(false) }
        view.addOnDidBecomeIdleListener { pickDestBuilding(); onIdle?.invoke() }
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
        if (changed) { route = emptyList(); routeCum = FloatArray(0); routeProgress = 0f; destBuildingFor = null; style?.getSourceAs<GeoJsonSource>(SRC_DESTB)?.setGeoJson(EMPTY) }
        if (arrived && !lastArrived) { pulse.snapTo(0.38f); pulse.animateTo(0.10f) }   // 도착 순간 반경이 한 번 퍼진다
        lastArrived = arrived
        springCar(carLat, carLng, bearing, snap = changed || originLat.isNaN())
        apply(force = changed)
    }

    fun setRoute(points: List<LatLng>) {
        route = points
        // 누적 거리 (진행 비율 계산용)
        routeCum = FloatArray(points.size); var acc = 0f
        for (i in 1 until points.size) { acc += ZoneGeo.distanceM(points[i - 1].latitude, points[i - 1].longitude, points[i].latitude, points[i].longitude).toFloat(); routeCum[i] = acc }
        reveal.snapTo(0f); reveal.animateTo(1f)     // 출발지에서 목적지로 한 번 그려진다
        apply()
    }

    /** 차 마커: GPS 갱신마다 점프하지 않고 스프링으로 이어진다 (위치는 기준점 기준 m 오프셋, 방향은 최단 회전). */
    private fun springCar(lat: Double, lng: Double, bearing: Float, snap: Boolean) {
        if (lat.isNaN()) return
        if (originLat.isNaN() || snap) { originLat = lat; originLng = lng }
        val y = ((lat - originLat) * 111_320.0).toFloat(); val x = ((lng - originLng) * 111_320.0 * cos(Math.toRadians(originLat))).toFloat()
        var h = carHeading.target; val delta = ((bearing - h) % 360f + 540f) % 360f - 180f; h += delta
        if (snap) { carX.snapTo(x); carY.snapTo(y); carHeading.snapTo(h) } else { carX.animateTo(x); carY.animateTo(y); carHeading.animateTo(h) }
    }
    private fun carNow(): LatLng? { if (originLat.isNaN() || car == null) return null; return LatLng(originLat + carY.value / 111_320.0, originLng + carX.value / (111_320.0 * cos(Math.toRadians(originLat)))) }
    private fun pushCar() {
        val st = style ?: return
        val p = carNow()?.takeIf { includeCar }
        st.getSourceAs<GeoJsonSource>(SRC_CAR)?.setGeoJson(p?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).apply { addNumberProperty("bearing", carHeading.value) }) } ?: EMPTY)
        updateProgress(p)
    }

    /** 경로 위 가장 가까운 점까지의 누적 거리 → 지나온 비율. 지나온 구간은 회색, 남은 구간은 파랑. */
    private fun updateProgress(p: LatLng?) {
        if (p == null || route.size < 2 || routeCum.isEmpty()) return
        var best = Double.MAX_VALUE; var idx = 0
        for (i in route.indices) { val dm = ZoneGeo.distanceM(p.latitude, p.longitude, route[i].latitude, route[i].longitude); if (dm < best) { best = dm; idx = i } }
        val total = routeCum.last().takeIf { it > 0f } ?: return
        val prog = (routeCum[idx] / total).coerceIn(0f, 1f)
        if (kotlin.math.abs(prog - routeProgress) > 0.002f) { routeProgress = prog; pushRoute() }
    }
    private fun pushRoute() {
        val st = style ?: return
        val r = reveal.value.coerceIn(0f, 1f); val p = routeProgress.coerceIn(0f, r)
        fun grad(passed: Int, ahead: Int): Expression {
            val stops = ArrayList<Expression.Stop>()
            val eps = 0.0005f
            stops += Expression.stop(0f, Expression.color(passed))
            if (p > eps) stops += Expression.stop(p, Expression.color(passed))
            if (p + eps < r) { stops += Expression.stop(p + eps, Expression.color(ahead)); stops += Expression.stop(r, Expression.color(ahead)) }
            if (r + eps < 1f) { stops += Expression.stop(r + eps, Expression.color(Color.TRANSPARENT)); stops += Expression.stop(1f, Expression.color(Color.TRANSPARENT)) }
            return Expression.interpolate(Expression.linear(), Expression.lineProgress(), *stops.toTypedArray())
        }
        st.getLayer("lm-route")?.setProperties(lineGradient(grad(c(R.color.basic_400), c(R.color.informative_positive))))
        st.getLayer("lm-route-casing")?.setProperties(lineGradient(grad(Color.WHITE, Color.WHITE)))
    }

    /** 목적지 좌표 위에 그려진 건물 폴리곤을 타일에서 찾아 파랑 3D 로 강조한다 (타일이 로드된 뒤 idle 시점). */
    private fun pickDestBuilding() {
        val m = map ?: return; val st = style ?: return; val d = dest ?: return
        if (destBuildingFor == d.id) return
        val ids = st.layers.filter { (it is FillLayer && it.sourceLayer == "building") || (it is FillExtrusionLayer && it.sourceLayer == "building") }.map { it.id }.toTypedArray()
        if (ids.isEmpty()) return
        val pt = m.projection.toScreenLocation(LatLng(d.latitude, d.longitude))
        val f = m.queryRenderedFeatures(pt, *ids).firstOrNull { it.geometry() is Polygon || it.geometry() is org.maplibre.geojson.MultiPolygon } ?: return
        destBuildingFor = d.id
        val h = f.getNumberProperty("render_height")?.toFloat() ?: 10f
        st.getSourceAs<GeoJsonSource>(SRC_DESTB)?.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(f.geometry()).apply { addNumberProperty("h", h) }))
    }

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
        for (id in listOf(SRC_RADIUS, SRC_CURB, SRC_SPOTS, SRC_CAR, SRC_DEST, SRC_DESTB)) st.addSource(GeoJsonSource(id))
        st.addSource(GeoJsonSource(SRC_ROUTE, GeoJsonOptions().withLineMetrics(true)))   // line-progress 그라데이션용
        // 3D 건물 (타일의 building 레이어 render_height). 목적지 건물은 별도 소스에 파랑으로 강조.
        (st.sources.firstOrNull { it is VectorSource } as? VectorSource)?.let { vs ->
            st.addLayer(FillExtrusionLayer("lm-3d", vs.id).withSourceLayer("building").withProperties(
                fillExtrusionColor(c(R.color.basic_600)), fillExtrusionOpacity(0.72f),
                fillExtrusionHeight(Expression.coalesce(get("render_height"), Expression.literal(8f))), fillExtrusionBase(Expression.coalesce(get("render_min_height"), Expression.literal(0f)))).apply { minZoom = 15f })
        }
        st.addLayer(FillExtrusionLayer("lm-3d-dest", SRC_DESTB).withProperties(
            fillExtrusionColor(c(R.color.informative_positive)), fillExtrusionOpacity(0.85f),
            fillExtrusionHeight(Expression.coalesce(get("h"), Expression.literal(10f))), fillExtrusionBase(Expression.literal(0f))))
        st.addImage("lm-pin", circleIcon(R.drawable.ic_flag, c(R.color.text_primary)))
        st.addImage("lm-pin-ok", circleIcon(R.drawable.ic_flag, c(R.color.informative_active)))
        st.addImage("lm-pin-no", circleIcon(R.drawable.ic_flag, c(R.color.informative_negative)))
        st.addImage("lm-parking", circleIcon(R.drawable.ic_parking, c(R.color.informative_positive)))
        st.addImage("lm-meeting", circleIcon(R.drawable.ic_walk, c(R.color.text_primary)))
        st.addImage("lm-noentry", circleIcon(R.drawable.ic_block, c(R.color.informative_negative)))
        st.addImage("lm-charger", circleIcon(R.drawable.ic_bolt, c(R.color.informative_active)))
        st.addImage("lm-drop", circleIcon(R.drawable.ic_pin, c(R.color.informative_active)))
        st.addImage("lm-car", carIcon())
        st.addLayer(LineLayer("lm-route-casing", SRC_ROUTE).withProperties(lineColor(Color.WHITE), lineWidth(12f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        st.addLayer(LineLayer("lm-route", SRC_ROUTE).withProperties(lineColor(c(R.color.informative_positive)), lineWidth(7f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        st.addLayer(FillLayer("lm-radius", SRC_RADIUS).withProperties(fillColor(c(R.color.informative_positive)), fillOpacity(0.10f)))
        st.addLayer(LineLayer("lm-radius-line", SRC_RADIUS).withProperties(lineColor(c(R.color.informative_positive)), lineWidth(2f), lineOpacity(0.25f)))
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
        pushRoute()
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
                addStringProperty("icon", when (s.type) { "parking" -> "lm-parking"; "meeting" -> "lm-meeting"; "noentry" -> "lm-noentry"; "charger" -> "lm-charger"; "drop" -> "lm-drop"; else -> if (dest?.curbsideAllowed == true) "lm-pin-ok" else "lm-pin-no" })
                addStringProperty("label", if (s.note.isNotBlank()) "${s.label}\n${s.note}" else s.label)
            }
        }
        st.getSourceAs<GeoJsonSource>(SRC_SPOTS)?.setGeoJson(FeatureCollection.fromFeatures(feats))
        st.getSourceAs<GeoJsonSource>(SRC_DEST)?.setGeoJson(dest?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).apply { addStringProperty("label", it.name) }) } ?: EMPTY)
        pushCar()

        // camera: 차 + 도착 구역 전부가 보이게. 사용자가 손으로 움직였으면 8초간 유지
        if (!force && System.currentTimeMillis() < holdUntil) return
        if (dest == null) return
        val b = LatLngBounds.Builder()
        for (s in spots) b.include(LatLng(s.lat, s.lng))
        val pad = ZoneGeo.offset(dest.latitude, dest.longitude, 60.0, 45.0); val pad2 = ZoneGeo.offset(dest.latitude, dest.longitude, 60.0, 225.0)
        b.include(LatLng(pad.first, pad.second)); b.include(LatLng(pad2.first, pad2.second))
        if (includeCar) car?.let { b.include(it) }
        if (view.width == 0 || view.height == 0) return
        // 주행 중엔 진행 방향이 위(헤딩업), 기울여서 3D 건물이 보이게. 도착·미리보기는 북쪽 위.
        val headingUp = includeCar && car != null && !arrived
        val camBearing = if (headingUp) ((carHeading.target % 360f + 360f) % 360f).toDouble() else 0.0
        val tilt = if (arrived) 38.0 else if (includeCar) 45.0 else 32.0
        val pos = m.getCameraForLatLngBounds(b.build(), intArrayOf((80 * d).toInt(), (96 * d).toInt(), (80 * d).toInt(), (96 * d).toInt()), camBearing, tilt) ?: return
        val target = CameraPosition.Builder(pos).zoom(minOf(pos.zoom, if (arrived) 17.8 else 18.3)).tilt(tilt).bearing(camBearing).build()
        if (force || m.cameraPosition.target == null) m.moveCamera(CameraUpdateFactory.newCameraPosition(target))
        else m.easeCamera(CameraUpdateFactory.newCameraPosition(target), 900)
    }

    // ---------- 타일 조회 (키 없는 OSM 데이터) ----------
    data class Place(val name: String, val lat: Double, val lng: Double, val distanceM: Int, val operator: String = "")
    data class Nearby(val parking: Place?, val charger: Place?, val roadBearing: Double?, val tiles: Boolean, val chargers: List<Place> = emptyList())

    /**
     * 이미 내려받은 벡터 타일에서 목적지 주변의 주차장(poi class=parking)·충전소(charging_station)·가장 가까운 도로(transportation) 방위를 찾는다.
     * 서버 호출 없음. 타일이 아직 없으면 tiles=false.
     */
    fun nearby(lat: Double, lng: Double, radiusM: Double = 400.0): Nearby {
        if (map == null) return Nearby(null, null, null, false)
        val st = style ?: return Nearby(null, null, null, false)
        val chargerRadiusM = 600.0
        val src = st.sources.firstOrNull { it is org.maplibre.android.style.sources.VectorSource } as? org.maplibre.android.style.sources.VectorSource ?: return Nearby(null, null, null, false)
        fun centroid(g: org.maplibre.geojson.Geometry?): Pair<Double, Double>? {
            val pts: List<Point> = when (g) {
                is Point -> listOf(g)
                is Polygon -> g.coordinates().firstOrNull() ?: emptyList()
                is org.maplibre.geojson.MultiPolygon -> g.coordinates().flatMap { it.firstOrNull() ?: emptyList() }
                is LineString -> g.coordinates()
                is org.maplibre.geojson.MultiLineString -> g.coordinates().flatten()
                else -> emptyList()
            }
            if (pts.isEmpty()) return null
            return pts.map { it.latitude() }.average() to pts.map { it.longitude() }.average()
        }
        val pois: List<Feature> = runCatching { src.querySourceFeatures(arrayOf("poi"), null) }.getOrDefault(emptyList())
        val roads: List<Feature> = runCatching { src.querySourceFeatures(arrayOf("transportation"), null) }.getOrDefault(emptyList())
        val tiles = pois.isNotEmpty() || roads.isNotEmpty()
        fun places(cls: Set<String>, fallback: String, r: Double): List<Place> = pois.mapNotNull { f ->
            val c = f.getStringProperty("class") ?: return@mapNotNull null
            if (c !in cls) return@mapNotNull null
            val p = centroid(f.geometry()) ?: return@mapNotNull null
            val dm = ZoneGeo.distanceM(lat, lng, p.first, p.second)
            if (dm > r) return@mapNotNull null
            val name = listOf("name:ko", "name").firstNotNullOfOrNull { k -> f.getStringProperty(k)?.takeIf { it.isNotBlank() } } ?: fallback
            val op = listOf("operator", "brand").firstNotNullOfOrNull { k -> runCatching { f.getStringProperty(k) }.getOrNull()?.takeIf { it.isNotBlank() } } ?: ""
            Place(name, p.first, p.second, dm.toInt(), op)
        }.distinctBy { "${"%.5f".format(it.lat)},${"%.5f".format(it.lng)}" }.sortedBy { it.distanceM }
        val parking = places(setOf("parking"), "인근 주차장", radiusM).firstOrNull()
        val chargers = places(setOf("charging_station"), "EV 충전소", chargerRadiusM).take(6)
        val charger = chargers.firstOrNull { it.distanceM <= radiusM }
        // 가장 가까운 도로 구간의 방위 (정문 앞 연석 방향)
        var best = Double.MAX_VALUE; var bearing: Double? = null
        for (f in roads) {
            val cls = f.getStringProperty("class") ?: ""
            if (cls in setOf("path", "rail", "transit", "aerialway", "ferry")) continue
            val lines: List<List<Point>> = when (val g = f.geometry()) { is LineString -> listOf(g.coordinates()); is org.maplibre.geojson.MultiLineString -> g.coordinates(); else -> emptyList() }
            for (line in lines) for (i in 0 until line.size - 1) {
                val a = line[i]; val b = line[i + 1]
                val dm = minOf(ZoneGeo.distanceM(lat, lng, a.latitude(), a.longitude()), ZoneGeo.distanceM(lat, lng, b.latitude(), b.longitude()), ZoneGeo.distanceM(lat, lng, (a.latitude() + b.latitude()) / 2, (a.longitude() + b.longitude()) / 2))
                if (dm < best && dm < 80.0) { best = dm; bearing = ZoneGeo.bearing(a.latitude(), a.longitude(), b.latitude(), b.longitude()) }
            }
        }
        return Nearby(parking, charger, bearing, tiles, chargers)
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
        private const val SRC_SPOTS = "lm-src-spots"; private const val SRC_CAR = "lm-src-car"; private const val SRC_DEST = "lm-src-dest"; private const val SRC_DESTB = "lm-src-destb"
        private val EMPTY: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    }
}
