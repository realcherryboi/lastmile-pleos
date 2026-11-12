package kr.lastmile.app.ui

import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.VelocityTracker
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.roundToInt
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kr.lastmile.app.BuildConfig
import kr.lastmile.app.LastMileApp
import kr.lastmile.app.R
import kr.lastmile.app.databinding.ActivityMainBinding
import kr.lastmile.app.domain.Briefing
import kr.lastmile.app.domain.ChargerCandidate
import kr.lastmile.app.domain.ChargerPlanner
import kr.lastmile.app.domain.DropMemory
import kr.lastmile.app.domain.Destination
import kr.lastmile.app.domain.TripPurpose
import kr.lastmile.app.source.PublicChargerSource
import kr.lastmile.app.domain.GearPosition
import kr.lastmile.app.domain.TripEngine
import kr.lastmile.app.domain.Milestone
import kr.lastmile.app.domain.TripState
import kr.lastmile.app.domain.TurnSide
import kr.lastmile.app.domain.UiState
import kr.lastmile.app.service.LastMileService
import kr.lastmile.app.source.MockLocationSource
import kr.lastmile.app.source.MockNaviSource
import kr.lastmile.app.source.MockVehicleSignalSource
import kr.lastmile.app.speech.TextVoiceInput
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var engine: TripEngine
    private val rows = linkedMapOf<String, View>()
    private var shownPage: View? = null
    private var uxRestricted = false
    private var noKeyboard = false
    private var ux: UxRestrictions? = null
    private var versionTaps = 0
    private var lastState: TripState? = null
    private var query = ""
    private val clock = SimpleDateFormat("HH:mm", Locale.KOREA)
    private lateinit var zoneMap: ZoneMap
    private var mapOk = false
    private var prevCar: Pair<Double, Double>? = null
    private var carBearing = 0f
    private var routeFor: String? = null
    private val tick = object : Runnable { override fun run() { renderCurbTimer(engine.ui.value); b.root.postDelayed(this, 1000) } }
    private val dp get() = resources.displayMetrics.density
    // --- 모션 상태 ---
    private var lastSelId: String? = null
    private var lastNextTitle = ""
    private var lastSpokenText = ""
    private var voiceShown = false
    private var curbSnapAt = 0L
    private val enriched = HashSet<String>()      // 보강을 시도한 내비 목적지 id
    private val chargerSeeded = HashSet<String>(); private val chargerDone = HashSet<String>()
    private var enrichTries = 0
    private lateinit var remainNum: SpringValue      // 남은 거리 숫자: 값이 바뀌면 카운팅(스프링)으로 이어진다
    private lateinit var countdownNum: SpringValue
    private lateinit var curbNum: SpringValue
    /** 시트가 올라올 때 뒤 화면을 블러로 밀어낸다 (Apple: 모달은 배경을 뒤로 밀고 흐린다). API 31+ 에서만 블러, 그 외엔 스케일만. */
    private val blur = SpringValue(0f, Motion.Default) { r ->
        if (Build.VERSION.SDK_INT >= 31) b.content.setRenderEffect(if (r < 0.6f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
    }
    private fun pushBack(on: Boolean) { blur.animateTo(if (on) 26f else 0f); Motion.scale(b.content, if (on) 0.965f else 1f) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        engine = (application as LastMileApp).engine
        Motion.init(this)
        startForegroundService(Intent(this, LastMileService::class.java))

        zoneMap = ZoneMap(this) { ok -> runOnUiThread { mapOk = ok; zoneMap.view.visibility = if (ok) View.VISIBLE else View.GONE; engine.status("지도", if (ok) "OpenFreeMap 스타일 로드" else "지도 로드 실패 → 스케치 폴백") } }
        buildRows()
        bindIdle(); bindDrive(); bindArrived(); bindVoice()
        zoneMap.onIdle = { tryEnrich(engine.ui.value) }
        PublicChargerSource.codeLookup = { lat, lng -> OsmEnricher.sggCode(applicationContext, lat, lng) }
        for (slot in mapSlots()) { val rc = slot.findViewById<View>(R.id.recenterButton); rc.setOnClickListener { zoneMap.recenter() }; Pressable.attach(rc) }
        b.settingsButton.setOnClickListener { showSettings() }
        Pressable.attach(b.settingsButton)

        ux = UxRestrictions(this) { restricted, noKb -> runOnUiThread { uxRestricted = restricted; noKeyboard = noKb; render(engine.ui.value) } }.also { it.start() }

        lifecycleScope.launch { engine.ui.collect { render(it) } }
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleDebugIntent(intent) }
    /** adb shell am start ... --es debug_dest "이름,위도,경도" (디버그 빌드 전용) */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (intent?.getBooleanExtra("clear_drops", false) == true) { engine.clearDrops(); intent.removeExtra("clear_drops") }   // --ez clear_drops true (데모 반복용). 창 크기 변경으로 액티비티가 다시 만들어질 때 같은 인텐트가 또 오므로 한 번 처리하면 지운다
        val spec = intent?.getStringExtra("debug_dest") ?: return
        intent.removeExtra("debug_dest")
        val p = spec.split(","); if (p.size != 3) return
        runCatching { engine.debugDestination(p[0].trim(), p[1].trim().toDouble(), p[2].trim().toDouble()) }
    }

    override fun onStart() { super.onStart(); zoneMap.onStart() }
    override fun onResume() { super.onResume(); LastMileApp.activityResumed = true; zoneMap.onResume(); b.root.removeCallbacks(tick); b.root.post(tick) }
    override fun onPause() { LastMileApp.activityResumed = false; b.root.removeCallbacks(tick); zoneMap.onPause(); super.onPause() }
    override fun onStop() { zoneMap.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); zoneMap.onLowMemory() }
    override fun onDestroy() { ux?.stop(); zoneMap.onDestroy(); super.onDestroy() }

    private fun mapSlots() = listOf(b.pageIdle.root.findViewById<android.widget.FrameLayout>(R.id.mapSlotIdle), b.pageDrive.root.findViewById(R.id.mapSlotDrive), b.pageArrived.root.findViewById(R.id.mapSlotArrived))
    private fun glass(slot: View, icon: Int, text: String) {
        val t = slot.findViewById<TextView>(R.id.glassText); slot.findViewById<ImageView>(R.id.glassIcon).setImageResource(icon)
        if (t.text.toString() != text) { t.text = text; Motion.pop(slot.findViewById(R.id.glassTitle), 0.94f) }
    }

    /** 하나의 MapView 를 현재 페이지의 지도 슬롯으로 옮긴다 (스케치가 그 아래에 폴백으로 깔려 있다). */
    private fun placeMap(page: View) {
        val slot = when (page) {
            b.pageIdle.root -> page.findViewById<android.widget.FrameLayout>(R.id.mapSlotIdle)
            b.pageArrived.root -> page.findViewById(R.id.mapSlotArrived)
            else -> page.findViewById(R.id.mapSlotDrive)
        }
        slot?.let { zoneMap.attachTo(it) }
    }

    /** 경로선: 공개 OSRM 데모 서버(데모용). 실패해도 지도는 그대로 쓴다. 목적지당 1회. */
    private fun fetchRoute(s: UiState) {
        val d = s.destination ?: return
        if (s.hereLat.isNaN() || routeFor == d.id) return
        routeFor = d.id
        val url = "https://router.project-osrm.org/route/v1/driving/${s.hereLng},${s.hereLat};${d.longitude},${d.latitude}?overview=full&geometries=geojson"
        lifecycleScope.launch(Dispatchers.IO) {
            val pts = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection; conn.connectTimeout = 5000; conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "LastMile/${BuildConfig.VERSION_NAME} (Pleos Connect demo)")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val coords = JSONObject(body).getJSONArray("routes").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
                (0 until coords.length()).map { i -> val c = coords.getJSONArray(i); LatLng(c.getDouble(1), c.getDouble(0)) }
            }.getOrElse { android.util.Log.w("LastMile", "OSRM 실패: $it"); emptyList() }
            withContext(Dispatchers.Main) { if (pts.isNotEmpty() && engine.ui.value.destination?.id == d.id) { zoneMap.setRoute(pts); engine.status("경로선", "OSRM ${pts.size}점") } else if (pts.isEmpty()) engine.status("경로선", "OSRM 응답 없음") }
        }
    }

    // ---------- IDLE ----------
    private fun buildRows() {
        val list = b.pageIdle.root.findViewById<LinearLayout>(R.id.destList)
        for ((i, d) in engine.repository.all().withIndex()) {
            val v = LayoutInflater.from(this).inflate(R.layout.view_dest_row, list, false)
            v.findViewById<TextView>(R.id.rowName).text = d.name
            v.findViewById<TextView>(R.id.rowAddress).text = d.address.ifBlank { d.category }
            v.findViewById<ImageView>(R.id.rowIcon).setImageResource(iconFor(d.category))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.topMargin = 12
            list.addView(v, lp)
            v.setOnClickListener { engine.selectDestination(d) }
            Pressable.attach(v)
            rows[d.id] = v
        }
    }

    private fun iconFor(category: String) = when (category) {
        "식당" -> R.drawable.ic_restaurant; "병원" -> R.drawable.ic_hospital; "공항" -> R.drawable.ic_flight
        "학교" -> R.drawable.ic_school; "아파트" -> R.drawable.ic_home; else -> R.drawable.ic_pin
    }

    private fun bindIdle() {
        val root = b.pageIdle.root
        val start = root.findViewById<Button>(R.id.startButton)
        bindPurpose(root); bindPurpose(b.pageDrive.root)
        start.setOnClickListener { engine.startGuidance() }
        Pressable.attach(start)
        val search = root.findViewById<EditText>(R.id.searchField)
        getDrawable(R.drawable.ic_search)?.mutate()?.let { ic -> ic.setBounds(0, 0, 40, 40); ic.setTint(getColor(R.color.text_secondary)); search.setCompoundDrawablesRelative(ic, null, null, null) }
        search.doAfterTextChanged { query = it?.toString()?.trim() ?: ""; filterRows() }
        search.setOnEditorActionListener { v, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { v.clearFocus(); true } else false }
        val sw = root.findViewById<MaterialSwitch>(R.id.meetingSwitch)
        sw.setOnClickListener { engine.setMeetingMode(sw.isChecked) }
        root.findViewById<View>(R.id.meetingCard).setOnClickListener { if (engine.ui.value.recipientPhone.isBlank()) showSettings() else { sw.toggle(); engine.setMeetingMode(sw.isChecked) } }
        fact(root, R.id.detailCurb, R.drawable.ic_stop_sign, getString(R.string.parking_curb))
        fact(root, R.id.detailParking, R.drawable.ic_parking, getString(R.string.parking_lot))
        fact(root, R.id.detailMeet, R.drawable.ic_walk, getString(R.string.meeting_point))
        fact(root, R.id.detailDrop, R.drawable.ic_pin, getString(R.string.drop_label))
    }

    private fun filterRows() {
        val q = query.replace(" ", "")
        for (d in engine.repository.all()) {
            val hit = q.isEmpty() || listOf(d.name, d.address, d.category).any { it.replace(" ", "").contains(q, ignoreCase = true) }
            rows[d.id]?.visibility = if (hit) View.VISIBLE else View.GONE
        }
    }

    private fun distanceText(s: UiState, d: Destination): String {
        if (s.hereLat.isNaN()) return ""
        val dy = (d.latitude - s.hereLat) * 111_000; val dx = (d.longitude - s.hereLng) * 111_000 * Math.cos(Math.toRadians(s.hereLat))
        val m = Math.sqrt(dx * dx + dy * dy).toInt()
        return if (m >= 1000) getString(R.string.distance_fmt_km, m / 1000.0) else getString(R.string.distance_fmt_m, m)
    }

    // ---------- DRIVE ----------
    private fun bindDrive() {
        val root = b.pageDrive.root
        val ask = root.findViewById<Button>(R.id.askButton); val end = root.findViewById<Button>(R.id.endButton)
        ask.setOnClickListener { engine.askVoice() }
        end.setOnClickListener { engine.endGuidance() }
        Pressable.attach(ask, end)
        val remain = root.findViewById<TextView>(R.id.driveRemain); val cd = root.findViewById<TextView>(R.id.nextCountdown)
        remainNum = SpringValue(0f) { remain.text = fmtMeters(it.roundToInt().coerceAtLeast(0)) }
        countdownNum = SpringValue(0f) { cd.text = fmtMeters(it.roundToInt().coerceAtLeast(0)) }
        fact(root, R.id.factCurb, R.drawable.ic_stop_sign, getString(R.string.parking_curb))
        fact(root, R.id.factValet, R.drawable.ic_person, getString(R.string.parking_valet))
        fact(root, R.id.factParking, R.drawable.ic_parking, getString(R.string.parking_lot))
        fact(root, R.id.factBattery, R.drawable.ic_bolt, getString(R.string.battery_label))
        fact(root, R.id.factDrop, R.drawable.ic_pin, getString(R.string.drop_label))
    }

    private fun fact(root: View, id: Int, icon: Int, label: String) {
        val row = root.findViewById<View>(id)
        row.findViewById<ImageView>(R.id.factIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.factLabel).text = label
    }
    private fun setFact(root: View, id: Int, value: String) { root.findViewById<View>(id).findViewById<TextView>(R.id.factValue).text = value }

    /** "머무는 동안 충전" 카드: 배터리가 HINT_SOC 이하이고 추천 충전소가 있을 때만. 내비가 이미 충전 경유지를 잡았으면 그 사실을 먼저 보여 준다. */
    private fun renderChargerCard(dr: View, s: UiState, d: Destination) {
        val card = dr.findViewById<View>(R.id.chargerCard)
        val c = d.charger
        val naviStop = s.naviChargingStops.firstOrNull()
        val show = s.evSoc in 0..ChargerPlanner.HINT_SOC && (c != null || naviStop != null)
        Motion.setVisible(card, show, fromY = 12 * dp, fromScale = 0.97f)
        if (!show) return
        val title = dr.findViewById<TextView>(R.id.chargerTitle); val text = dr.findViewById<TextView>(R.id.chargerText); val btn = dr.findViewById<Button>(R.id.chargerButton)
        if (c != null) {
            title.text = if (s.evSoc <= Briefing.LOW_SOC) "도착해서 충전" else "머무는 동안 충전"
            text.text = "${c.name} · ${c.summary()}" + (naviStop?.let { "\n내비 충전 경유지: ${it.name} ${it.maxKw} kW" } ?: "")
            btn.text = if (s.chargerWaypointAdded) getString(R.string.charger_added) else getString(R.string.charger_add_waypoint)
            btn.isEnabled = !s.chargerWaypointAdded
            btn.setOnClickListener { engine.addChargerWaypoint() }
        } else {
            title.text = "내비 충전 경유지 · ${naviStop!!.name}"
            text.text = listOfNotNull(naviStop.maxKw.takeIf { it > 0 }?.let { "$it kW" }, naviStop.chargerCount.takeIf { it > 0 }?.let { "충전기 ${it}대" }, "${naviStop.startSoc}% → ${naviStop.endSoc}% · ${naviStop.chargeMin}분").joinToString(" · ")
            btn.isEnabled = false; btn.text = getString(R.string.charger_navi_planned)
        }
    }

    /** 목적 칩 3개: 선택된 것만 강조. 누르면 엔진에 반영. */
    private fun bindPurpose(root: View) {
        for ((id, p) in listOf(R.id.purposeStop to TripPurpose.STOP, R.id.purposePark to TripPurpose.PARK, R.id.purposeVia to TripPurpose.VIA)) {
            val v = root.findViewById<View>(id) ?: continue
            Pressable.attach(v); v.setOnClickListener { engine.setPurpose(p) }
        }
    }
    private fun renderPurpose(root: View, s: UiState) {
        for ((id, p) in listOf(R.id.purposeStop to TripPurpose.STOP, R.id.purposePark to TripPurpose.PARK, R.id.purposeVia to TripPurpose.VIA)) {
            val v = root.findViewById<TextView>(id) ?: continue
            val on = s.purpose == p
            if (v.tag != on) { v.tag = on; v.setBackgroundResource(if (on) R.drawable.bg_chip_accent else R.drawable.bg_chip); v.setTextColor(getColor(if (on) R.color.accent else R.color.text_primary)); if (on) Motion.pop(v, 0.94f) }
        }
    }

    private fun curbText(d: Destination) = when { !d.rulesKnown -> "정문 앞 5분 이내 · 일반 도로 기준"; d.curbsideAllowed -> "정문 앞 ${d.curbsideLimitMin}분까지"; else -> "정문 앞 정차 금지" } + if (d.curb.known) " · ${d.curb.label}" else ""
    /** "정문 지나서 20 m에서 내림 · 3회" — 승객석 문이 열렸던 자리 (기기 내 기억) */
    private fun dropText(d: Destination) = d.drop?.let { "${DropMemory.describe(d, it)}에서 내림 · ${d.dropCount}회" + if (d.dropNew) " · 방금 기억" else "" }
    private fun renderDropRow(root: View, id: Int, d: Destination) { val t = dropText(d); Motion.setVisible(root.findViewById(id), t != null, fromY = 8 * dp); if (t != null) setFact(root, id, t) }
    private fun parkingText(d: Destination) = when { d.parkingName.isBlank() -> if (d.isGeneric) "주변 주차장 찾는 중" else "정보 없음"; d.parkingFee.isNotBlank() -> "${d.parkingName} · ${d.parkingFee}"; d.parkingDistanceM >= 0 -> "${d.parkingName} · ${d.parkingDistanceM} m"; else -> d.parkingName }
    private fun fmtMeters(m: Int) = if (m >= 1000) getString(R.string.remain_fmt_km, m / 1000.0) else getString(R.string.remain_fmt_m, m)

    /** 다음 안내 카드: 어떤 이정표가 남았고(아이콘·제목), 몇 m 뒤에 오며, 무엇을 말할지(미리보기) */
    private data class Next(val icon: Int, val title: String, val preview: String, val inMeters: Int)
    private fun nextOf(s: UiState, d: Destination): Next {
        val m = s.remainMeters; val min = maxOf(1, s.remainSec / 60)
        if (s.state == TripState.STOPPING) return Next(R.drawable.ic_clock, "잠시 정차 중", when { s.purpose == TripPurpose.PARK -> "주차 목적 · ${d.parkingName.ifBlank { "주차장" }} 입구로 안내"; d.curbsideAllowed -> "${d.curbsideLimitMin}분 넘으면 주차장으로 안내해요"; else -> "정차 금지 구역 · ${d.parkingName}로 안내" }, -1)
        // 이정표 계획에서 아직 안 지난 첫 이정표가 '다음 안내'. 경로가 3 km 미만이면 첫 이정표가 "출발"(바로 나가는 브리핑)이고, 출발점과 겹치는 1 km·300 m 는 계획에서 빠져 있다.
        val plan = s.milestones.ifEmpty { listOf(Milestone("3 km", 3000, TripState.BRIEF_3KM), Milestone("1 km", 1000, TripState.BRIEF_1KM), Milestone("300 m", 300, TripState.BRIEF_300M), Milestone("도착", 0, TripState.ARRIVED)) }
        val next = plan.getOrNull(passedOf(s)) ?: return Next(R.drawable.ic_flag, "", "", -1)
        val inM = if (next.meters > 0 && m > next.meters) m - next.meters else -1
        return when (next.state) {
            TripState.BRIEF_3KM -> Next(R.drawable.ic_bell, if (next.label == "출발") "출발 · 도착 전 브리핑" else "3 km 지점 · 도착 전 브리핑", Briefing.at3km(d, if (s.remainSec > 0) min else 10, s.evSoc, s.purpose), inM)
            TripState.BRIEF_1KM -> Next(R.drawable.ic_flag, if (s.purpose == TripPurpose.PARK) "1 km 지점 · 주차장 안내" else "1 km 지점 · 정차 안내", Briefing.at1km(d, s.purpose), inM)
            TripState.BRIEF_300M -> Next(R.drawable.ic_parking, "300 m 지점 · 진입 안내", Briefing.at300m(d, s.purpose), inM)
            else -> Next(R.drawable.ic_check, "도착 · 기어 P", if (s.meetingMode && "arrived" !in s.messagesSent) "도착하면 ${s.recipientName.ifBlank { s.recipientPhone }}에게 알림을 보내요" else "정차 후 기어를 P로 두면 도착 처리돼요", if (m > 0) m else -1)
        }
    }
    /** 지난 이정표 수 = 계획에서 현재 상태(또는 그 앞 상태)의 위치 + 1. STOPPING 은 300 m 를 지난 것으로, DONE 은 도착으로 센다. */
    private fun passedOf(s: UiState): Int {
        val plan = s.milestones.ifEmpty { return when (s.state) { TripState.BRIEF_3KM -> 1; TripState.BRIEF_1KM -> 2; TripState.BRIEF_300M, TripState.STOPPING -> 3; TripState.ARRIVED, TripState.DONE -> 4; else -> 0 } }
        val st = when (s.state) { TripState.STOPPING -> plan.lastOrNull { it.state != TripState.ARRIVED }?.state ?: TripState.BRIEF_300M; TripState.DONE -> TripState.ARRIVED; else -> s.state }
        val i = plan.indexOfFirst { it.state == st }
        return if (i >= 0) i + 1 else 0
    }

    // ---------- ARRIVED ----------
    private fun bindArrived() {
        val done = b.pageArrived.root.findViewById<Button>(R.id.doneButton)
        done.setOnClickListener { engine.done() }
        Pressable.attach(done)
        val bar = b.pageArrived.root.findViewById<ProgressBar>(R.id.curbProgress)
        curbNum = SpringValue(1000f) { bar.progress = it.roundToInt() }
    }

    private fun renderCurbTimer(s: UiState) {
        if (s.state != TripState.ARRIVED && s.state != TripState.DONE) return
        if (s.purpose == TripPurpose.PARK) return   // 주차 목적: 정차 타이머 없음
        val d = s.destination ?: return
        val ar = b.pageArrived.root
        val timer = ar.findViewById<TextView>(R.id.curbTimer); val bar = ar.findViewById<ProgressBar>(R.id.curbProgress)
        if (s.state == TripState.DONE) { timer.visibility = View.VISIBLE; timer.text = "완료"; timer.setTextColor(getColor(R.color.success)); bar.visibility = View.GONE; return }
        if (!d.curbsideAllowed) { timer.visibility = View.VISIBLE; timer.text = "정차 금지"; timer.setTextColor(getColor(R.color.negative)); bar.visibility = View.GONE; return }
        timer.visibility = View.VISIBLE; bar.visibility = View.VISIBLE
        val total = d.curbsideLimitMin * 60
        val elapsed = if (s.arrivedAtMs > 0) ((System.currentTimeMillis() - s.arrivedAtMs) / 1000).toInt() else 0
        val left = (total - elapsed).coerceAtLeast(0)
        timer.text = "%d:%02d".format(left / 60, left % 60)
        timer.setTextColor(getColor(if (left <= 60) R.color.negative else R.color.text_primary))
        val target = (if (total > 0) (left * 1000f / total) else 0f)
        if (curbSnapAt != s.arrivedAtMs) { curbSnapAt = s.arrivedAtMs; curbNum.snapTo(target) } else curbNum.animateTo(target)
    }

    // ---------- VOICE ----------
    private fun bindVoice() {
        val v = b.voiceSheet.root
        v.findViewById<View>(R.id.voiceClose).setOnClickListener { engine.cancelVoice() }
        v.setOnClickListener { engine.cancelVoice() }
        val typed = v.findViewById<EditText>(R.id.voiceTyped)
        val send = v.findViewById<Button>(R.id.voiceSend)
        val submit = { val t = typed.text.toString(); if (t.isNotBlank()) { engine.submitUtterance(t); typed.setText("") } }
        send.setOnClickListener { submit() }
        typed.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        Pressable.attach(send, v.findViewById(R.id.voiceClose))

        // 시트 끌어내리기: 손가락과 1:1, 위로는 러버밴드, 놓으면 속도를 이어받아 투영 지점으로 결정(Apple Fluid Interfaces).
        val panel = v.findViewById<View>(R.id.voicePanel); val scrim = v.findViewById<View>(R.id.voiceScrim)
        var downY = 0f; var startTy = 0f; var tracker: VelocityTracker? = null
        panel.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(e) }; downY = e.rawY; startTy = panel.translationY; Motion.cancel(panel, DynamicAnimation.TRANSLATION_Y) }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val raw = startTy + (e.rawY - downY)
                    val ty = if (raw < 0f) Motion.rubberband(raw, panel.height.toFloat()) else raw
                    panel.translationY = ty; scrim.alpha = 1f - (ty / panel.height).coerceIn(0f, 1f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val vt = tracker; vt?.addMovement(e); vt?.computeCurrentVelocity(1000); val vy = vt?.yVelocity ?: 0f; vt?.recycle(); tracker = null
                    val projected = panel.translationY + Motion.project(vy)
                    if (e.actionMasked == MotionEvent.ACTION_UP && (projected > panel.height * 0.45f || vy > 1500f)) { hideVoice(velocity = vy); engine.cancelVoice() }
                    else { Motion.spring(panel, DynamicAnimation.TRANSLATION_Y, 0f, Motion.Sheet, velocity = vy); Motion.spring(scrim, DynamicAnimation.ALPHA, 1f) }
                }
            }
            true
        }
    }

    // ---------- RENDER ----------
    private fun render(s: UiState) {
        b.stateChip.text = getString(when (s.state) {
            TripState.IDLE -> R.string.state_idle; TripState.ARMED -> R.string.state_armed
            TripState.STOPPING -> R.string.state_stopping; TripState.ARRIVED, TripState.DONE -> R.string.state_arrived
            else -> R.string.state_driving
        })
        b.stateChip.setBackgroundResource(when (s.state) { TripState.IDLE -> R.drawable.bg_chip; TripState.ARRIVED, TripState.DONE -> R.drawable.bg_chip_success; else -> R.drawable.bg_chip_accent })
        b.stateChip.setTextColor(getColor(when (s.state) { TripState.IDLE -> R.color.text_primary; TripState.ARRIVED, TripState.DONE -> R.color.success; else -> R.color.accent }))

        val page = when (s.state) { TripState.IDLE -> b.pageIdle.root; TripState.ARRIVED, TripState.DONE -> b.pageArrived.root; else -> b.pageDrive.root }
        val changed = lastState != null && lastState != s.state
        showPage(page, animate = changed)
        if (changed) Motion.pop(b.stateChip, 0.86f)
        if (changed && (s.state == TripState.ARRIVED || s.state == TripState.DONE)) b.pageArrived.root.findViewById<View>(R.id.arrivedBadge).let { Motion.pop(it, 0.4f) }
        lastState = s.state

        renderIdle(s); renderDrive(s); renderArrived(s); renderVoice(s)
        renderMap(s, page)
    }

    private fun renderMap(s: UiState, page: View) {
        if (!s.hereLat.isNaN()) {
            prevCar?.let { (plat, plng) ->
                val dy = (s.hereLat - plat) * 111_000; val dx = (s.hereLng - plng) * 111_000 * Math.cos(Math.toRadians(s.hereLat))
                if (dx * dx + dy * dy > 25.0) carBearing = Math.toDegrees(Math.atan2(dx, dy)).toFloat()
            }
            prevCar = s.hereLat to s.hereLng
        }
        val idle = page === b.pageIdle.root
        val dest = if (idle) s.selected else s.destination
        val arrived = s.state == TripState.ARRIVED || s.state == TripState.DONE
        zoneMap.update(dest, s.hereLat, s.hereLng, carBearing, arrived, includeCar = !idle)
        val slots = mapSlots()
        glass(slots[0], R.drawable.ic_flag, "도착 구역 미리보기")
        glass(slots[1], R.drawable.ic_flag, s.destination?.let { "도착 구역 · ${it.name}" } ?: getString(R.string.zone_title))
        glass(slots[2], R.drawable.ic_walk, s.destination?.let { "만남 · ${it.meetingPoint}" } ?: getString(R.string.zone_title))
        if (!idle && !arrived) fetchRoute(s)
        if (s.state == TripState.IDLE) { routeFor = null; enrichTries = 0; chargerSeeded.clear(); chargerDone.clear() }
        tryEnrich(s)
    }

    /** 내비가 준 임의 목적지: 주소(Nominatim) + 타일(주차장·충전소·도로 방위)로 보강. 타일이 아직 없으면 지도가 멈출 때마다 최대 6회 재시도. */
    private fun tryEnrich(s: UiState) {
        val d = s.destination ?: return
        val nearby = if (mapOk) zoneMap.nearby(d.latitude, d.longitude) else ZoneMap.Nearby(null, null, null, false)
        // 충전소: 등록 데이터는 바로(타일 없어도), 타일이 오면 한 번 더 합쳐서 적용
        if (d.id !in chargerSeeded) { chargerSeeded += d.id; if (!nearby.tiles) engine.applyChargers(d, OsmEnricher.chargerCandidates(d, nearby)) }
        if (nearby.tiles && d.id !in chargerDone) { chargerDone += d.id; val c = OsmEnricher.chargerCandidates(d, nearby); engine.applyChargers(d, c); fetchLiveChargers(d, c) }
        if (d.id in enriched || !d.isGeneric) return   // 등록 장소는 규칙 데이터가 있으니 주소·주차장 보강은 안 한다
        if (!nearby.tiles && enrichTries < 6) { enrichTries++; return }
        enriched += d.id
        engine.status("보강", "타일 ${if (nearby.tiles) "조회" else "없음"} · 주차장 ${nearby.parking?.name ?: "없음"} · 충전소 ${nearby.chargers.size} · 도로 ${nearby.roadBearing?.toInt() ?: "-"}°")
        val chargers = OsmEnricher.chargerCandidates(d, nearby)
        lifecycleScope.launch {
            val addr = OsmEnricher.address(d.latitude, d.longitude)
            val merged = OsmEnricher.merge(d, addr, nearby)
            engine.enrich(merged); engine.applyChargers(merged, OsmEnricher.chargerCandidates(merged, nearby))
        }
    }

    /** 2단계(키 필요): 한국환경공단 실시간 충전기 상태로 후보를 덮어쓴다. 키가 없으면 아무것도 안 한다. */
    private fun fetchLiveChargers(d: Destination, base: List<ChargerCandidate>) {
        if (!PublicChargerSource.enabled) return
        lifecycleScope.launch {
            val live = PublicChargerSource.near(d.latitude, d.longitude, ChargerPlanner.RADIUS_M.toDouble())
            engine.status("충전 실시간", if (live.isEmpty()) "응답 없음" else "${live.size}곳 · 빈 충전기 ${live.sumOf { maxOf(it.available, 0) }}")
            if (live.isNotEmpty()) engine.applyChargers(d, ChargerPlanner.merge(base, live))
        }
    }

    private fun renderIdle(s: UiState) {
        val root = b.pageIdle.root
        for ((id, v) in rows) v.isSelected = s.selected?.id == id
        for (d in engine.repository.all()) rows[d.id]?.findViewById<TextView>(R.id.rowDistance)?.text = distanceText(s, d)
        val sel = s.selected
        val empty = root.findViewById<View>(R.id.detailEmpty)
        val land = empty is android.view.ViewGroup          // 가로: detailScroll 이 상세 카드만 담는다 / 세로: 카드가 목록 위에 끼어든다
        val container = if (land) root.findViewById(R.id.detailScroll) else root.findViewById<View>(R.id.detailCard)
        if (land) root.findViewById<View>(R.id.detailCard).visibility = View.VISIBLE
        if (sel == null) { lastSelId = null; Motion.setVisible(container, false, fromY = 40 * dp, fromScale = 0.98f); if (land) Motion.setVisible(empty, true) else empty.visibility = View.GONE }
        else if (sel.id != lastSelId) {
            lastSelId = sel.id
            if (land) Motion.setVisible(empty, false)
            if (container.visibility == View.VISIBLE && container.getTag(R.id.tag_hiding) != true) { container.alpha = 0.55f; container.translationY = 20 * dp; container.scaleX = 0.985f; container.scaleY = 0.985f }   // 다른 목적지로 바꿈: 카드가 다시 "도착"한다
            Motion.setVisible(container, true, fromY = 40 * dp, fromScale = 0.98f)
            Motion.appear(container, fromY = 40 * dp, fromScale = 0.98f)
        }
        if (sel != null) {
            root.findViewById<TextView>(R.id.detailCategory).text = sel.category
            root.findViewById<TextView>(R.id.detailName).text = sel.name
            root.findViewById<TextView>(R.id.detailAddress).text = sel.address
            setFact(root, R.id.detailCurb, curbText(sel) + if (sel.valet) " · 발렛 있음" else "")
            root.findViewById<View>(R.id.detailCurb).findViewById<ImageView>(R.id.factIcon).setImageResource(if (sel.curbsideAllowed) R.drawable.ic_clock else R.drawable.ic_stop_sign)
            setFact(root, R.id.detailParking, parkingText(sel))
            setFact(root, R.id.detailMeet, sel.meetingPoint)
            renderDropRow(root, R.id.detailDrop, sel)
            root.findViewById<ZoneSketchView>(R.id.idleSketch).apply { destination = sel; arrived = false }
            renderPurpose(root, s)
        }
        root.findViewById<Button>(R.id.startButton).isEnabled = sel != null
        val sw = root.findViewById<MaterialSwitch>(R.id.meetingSwitch)
        sw.isChecked = s.meetingMode; sw.isEnabled = s.recipientPhone.isNotBlank()
        root.findViewById<TextView>(R.id.meetingDesc).text = when {
            s.recipientPhone.isBlank() -> getString(R.string.meeting_no_recipient)
            s.meetingMode -> getString(R.string.meeting_on_fmt, s.recipientName.ifBlank { s.recipientPhone })
            else -> getString(R.string.meeting_off)
        }
    }

    private fun renderDrive(s: UiState) {
        val d = s.destination; val dr = b.pageDrive.root
        if (s.remainMeters < 0) { remainNum.snapTo(0f); dr.findViewById<TextView>(R.id.driveRemain).text = "—" }
        else if (remainNum.target <= 0f || s.state == TripState.ARMED && lastState != s.state) remainNum.snapTo(s.remainMeters.toFloat()) else remainNum.animateTo(s.remainMeters.toFloat())
        dr.findViewById<TextView>(R.id.driveEta).text = if (s.remainSec > 0) getString(R.string.eta_fmt, maxOf(1, s.remainSec / 60)) else "—"
        dr.findViewById<TextView>(R.id.driveClock).text = if (s.remainSec > 0) clock.format(Date(System.currentTimeMillis() + s.remainSec * 1000L)) else "—"
        dr.findViewById<RouteProgressView>(R.id.routeProgress).apply { milestones = s.milestones; remainMeters = s.remainMeters; passed = passedOf(s) }
        dr.findViewById<ZoneSketchView>(R.id.zoneSketch).apply { destination = d; arrived = false }

        val spoken = dr.findViewById<TextView>(R.id.driveGuidance)
        val spokenText = s.lastSpoken.ifBlank { if (s.state == TripState.ARMED) "내비 안내를 기다리고 있어요" else "" }
        val spokenRow = dr.findViewById<View>(R.id.spokenRow)
        if (spokenText != lastSpokenText) { lastSpokenText = spokenText; spoken.text = spokenText; if (spokenText.isNotBlank()) { spokenRow.alpha = 0f; spokenRow.translationY = 12 * dp; Motion.appear(spokenRow) } }
        if (spokenText.isBlank()) spokenRow.visibility = View.INVISIBLE

        val banner = dr.findViewById<View>(R.id.stoppingBanner)
        Motion.setVisible(banner, s.state == TripState.STOPPING, fromY = -20 * dp)
        if (d != null) {
            val n = nextOf(s, d)
            dr.findViewById<ImageView>(R.id.nextIcon).setImageResource(n.icon)
            dr.findViewById<TextView>(R.id.nextTitle).text = n.title
            dr.findViewById<TextView>(R.id.nextPreview).text = n.preview
            if (n.title != lastNextTitle) {   // 다음 이정표가 바뀐 순간: 카드가 다시 "도착"하고 아이콘이 팝
                val firstShow = lastNextTitle.isEmpty(); lastNextTitle = n.title
                if (!firstShow) { Motion.pop(dr.findViewById(R.id.nextCard), 0.965f); Motion.pop(dr.findViewById<View>(R.id.nextIcon).parent as View, 0.7f) }
            }
            val cd = dr.findViewById<TextView>(R.id.nextCountdown)
            Motion.setVisible(cd, n.inMeters >= 0, fromScale = 0.8f)
            if (n.inMeters >= 0) { if (cd.getTag(R.id.tag_hiding) == true || countdownNum.target <= 0f) countdownNum.snapTo(n.inMeters.toFloat()) else countdownNum.animateTo(n.inMeters.toFloat()) } else countdownNum.snapTo(0f)
            dr.findViewById<TextView>(R.id.stoppingHint).text = if (d.curbsideAllowed) "여기는 ${d.curbsideLimitMin}분까지 세울 수 있어요" else "정차 금지 구역이에요 · ${d.parkingName}로 안내"
            renderPurpose(dr, s)
            setFact(dr, R.id.factCurb, if (s.purpose == TripPurpose.PARK) (if (d.curb.known) "${d.curb.label} · 주차 목적이라 건너뜀" else "주차 목적이라 건너뜀") else curbText(d))
            dr.findViewById<View>(R.id.factCurb).findViewById<ImageView>(R.id.factIcon).setImageResource(if (s.purpose == TripPurpose.PARK) R.drawable.ic_parking else if (d.curbsideAllowed) R.drawable.ic_clock else R.drawable.ic_stop_sign)
            dr.findViewById<View>(R.id.factValet).visibility = View.GONE   // 시안: 사실 3개(정문·주차·지난번)만. 발렛은 브리핑 문장에서 말한다
            setFact(dr, R.id.factParking, parkingText(d))
            renderDropRow(dr, R.id.factDrop, d)
            val bat = dr.findViewById<View>(R.id.factBattery)
            Motion.setVisible(bat, s.evSoc in 0..ChargerPlanner.HINT_SOC, fromY = 8 * dp)   // 배터리가 낮을 때만 (시안)
            if (s.evSoc >= 0) setFact(dr, R.id.factBattery, "${s.evSoc}%" + when { s.evSoc > Briefing.LOW_SOC -> ""; d.charger != null -> " · ${d.charger.name} ${d.charger.distanceM} m"; d.parkingCharger -> " · ${d.parkingName.ifBlank { "주차장" }} 충전기 있음"; else -> " · 충전기 정보 없음" })
            renderChargerCard(dr, s, d)
        }
        dr.findViewById<TextView>(R.id.meetingStatusText).text = when {
            !s.meetingMode -> "꺼져 있어요"
            "arrived" in s.messagesSent -> "도착 메시지 보냄 · ${s.lastMessage}"
            "eta" in s.messagesSent -> "5분 전 알림 보냄 · 도착하면 한 번 더 보내요"
            else -> "${s.recipientName.ifBlank { s.recipientPhone }}에게 5분 전·도착 시 자동 전송"
        }
        dr.findViewById<ImageView>(R.id.meetingStatusIcon).setColorFilter(getColor(if (s.meetingMode) R.color.accent else R.color.text_tertiary))
    }

    private fun renderArrived(s: UiState) {
        val d = s.destination; val ar = b.pageArrived.root
        val boarded = s.state == TripState.DONE
        ar.findViewById<TextView>(R.id.arrivedTitle).text = getString(if (boarded) R.string.boarded_title else R.string.arrived_title)
        ar.findViewById<TextView>(R.id.arrivedDestination).text = if (boarded) getString(when (s.purpose) { TripPurpose.PARK -> R.string.boarded_sub_park; TripPurpose.VIA -> R.string.boarded_sub_via; else -> R.string.boarded_sub }) else d?.let { listOf(it.name, it.address).filter { f -> f.isNotBlank() }.joinToString(" · ") } ?: ""
        // 승하차 카드: 기다리는 중 → 끝(내린 자리 기억 안내)
        ar.findViewById<TextView>(R.id.boardTitle).text = getString(if (boarded) R.string.boarding_done_title else R.string.boarding_wait_title)
        ar.findViewById<TextView>(R.id.boardHint).text = if (boarded) {
            val where = d?.takeIf { it.dropNew }?.drop?.let { "내린 자리: ${DropMemory.describe(d, it)}. " } ?: ""
            where + getString(R.string.boarding_done_hint)
        } else getString(R.string.boarding_wait_hint)
        ar.findViewById<Button>(R.id.doneButton).text = getString(if (boarded) R.string.depart else R.string.done)
        if (d != null) {
            val curbTitle = ar.findViewById<TextView>(R.id.curbTitle); val curbIcon = ar.findViewById<ImageView>(R.id.curbIcon); val curbHint = ar.findViewById<TextView>(R.id.curbHint)
            val park = s.purpose == TripPurpose.PARK
            ar.findViewById<View>(R.id.curbTimer).visibility = if (park) View.GONE else View.VISIBLE
            ar.findViewById<View>(R.id.curbProgress).visibility = if (park) View.GONE else View.VISIBLE
            if (park) {
                curbTitle.text = "주차하면 끝"; curbIcon.setImageResource(R.drawable.ic_parking); curbIcon.setColorFilter(getColor(R.color.success))
                curbHint.text = (if (d.parkingName.isNotBlank()) "${d.parkingName}에 세우세요. " else "") + (if (d.curb.known) "정문 앞은 ${d.curb.label}, ${d.curb.rule}." else "정차 타이머는 없어요.")
            } else if (s.purpose == TripPurpose.VIA) {
                curbTitle.text = "경유 · 잠깐 멈춤"; curbIcon.setImageResource(R.drawable.ic_timer); curbIcon.setColorFilter(getColor(if (d.curbsideAllowed) R.color.success else R.color.negative))
                curbHint.text = if (d.curbsideAllowed) "${d.curbsideLimitMin}분 안에 내리고 출발하세요. 문이 닫히면 다음 목적지로 이어가요." else "정차 금지 구역이에요. 바로 내리고 출발하세요."
            } else if (d.curbsideAllowed) {
                curbTitle.text = getString(R.string.curb_allowed_title); curbIcon.setImageResource(R.drawable.ic_timer); curbIcon.setColorFilter(getColor(R.color.success))
                curbHint.text = "${d.meetingPoint}에서 ${d.curbsideLimitMin}분까지 기다릴 수 있어요. 넘으면 ${d.parkingName}로 이동하세요."
            } else {
                curbTitle.text = getString(R.string.curb_forbidden_title); curbIcon.setImageResource(R.drawable.ic_block); curbIcon.setColorFilter(getColor(R.color.negative))
                curbHint.text = "정문 앞은 단속 구역이에요. ${d.parkingName}에 세우고 ${d.meetingPoint}에서 만나세요."
            }
            ar.findViewById<TextView>(R.id.parkName).text = d.parkingName.ifBlank { "주차장 정보 없음" }
            ar.findViewById<TextView>(R.id.parkFee).text = d.parkingFee
            ar.findViewById<TextView>(R.id.parkHint).text = d.parkingHint
            ar.findViewById<ZoneSketchView>(R.id.arrivedSketch).apply { destination = d; arrived = true }
            if (!park) renderCurbTimer(s)
        }
        val card = ar.findViewById<View>(R.id.arrivedMessageCard)
        Motion.setVisible(card, "arrived" in s.messagesSent, fromY = 16 * dp)
        ar.findViewById<TextView>(R.id.arrivedMessageText).text = "${s.recipientName.ifBlank { s.recipientPhone }}에게 보냄 · ${s.lastMessage}"
    }

    private fun renderVoice(s: UiState) {
        val vs = b.voiceSheet.root
        val wantVoice = s.listening || (s.lastUtterance.isNotBlank() && voiceShown)
        if (wantVoice && !voiceShown) showVoice() else if (!wantVoice && voiceShown) hideVoice()
        vs.findViewById<TextView>(R.id.voiceTitle).text = if (s.listening) getString(R.string.listening) else "\"${s.lastUtterance}\""
        val ans = vs.findViewById<TextView>(R.id.voiceAnswer)
        ans.visibility = if (!s.listening && s.lastUtterance.isNotBlank()) View.VISIBLE else View.GONE
        ans.text = s.lastSpoken
        val typedRow = vs.findViewById<View>(R.id.voiceTypedRow)
        typedRow.visibility = if (s.listening && engine.voice is TextVoiceInput && !noKeyboard) View.VISIBLE else View.GONE
        if (!s.listening && s.lastUtterance.isNotBlank() && voiceShown) vs.postDelayed({ if (!engine.ui.value.listening && voiceShown) hideVoice() }, 5000)
    }

    /** 음성 시트: 아래에서 스프링(damping 0.82)으로 올라오고, 뒤 화면은 블러·축소로 밀려난다. 같은 경로로 내려간다. */
    private fun showVoice() {
        voiceShown = true
        val vs = b.voiceSheet.root; val panel = vs.findViewById<View>(R.id.voicePanel); val scrim = vs.findViewById<View>(R.id.voiceScrim)
        vs.visibility = View.VISIBLE; panel.alpha = 0f
        vs.post { if (!voiceShown) return@post; panel.alpha = 1f; panel.translationY = panel.height.toFloat(); scrim.alpha = 0f
            Motion.spring(panel, DynamicAnimation.TRANSLATION_Y, 0f, Motion.Sheet); Motion.spring(scrim, DynamicAnimation.ALPHA, 1f) }
        pushBack(true)
    }
    private fun hideVoice(velocity: Float? = null) {
        if (!voiceShown) return
        voiceShown = false
        val vs = b.voiceSheet.root; val panel = vs.findViewById<View>(R.id.voicePanel); val scrim = vs.findViewById<View>(R.id.voiceScrim)
        Motion.spring(scrim, DynamicAnimation.ALPHA, 0f)
        Motion.spring(panel, DynamicAnimation.TRANSLATION_Y, panel.height.toFloat(), Motion.Sheet, velocity) { if (!voiceShown) vs.visibility = View.GONE }
        pushBack(false)
    }

    /**
     * 페이지 전환. 앞으로(출발→안내→도착)는 새 화면이 아래에서 올라오고 이전 화면은 뒤로 물러난다.
     * 뒤로(→출발 전)는 그 역경로: 이전 화면이 아래로 내려가고 새 화면이 뒤에서 앞으로 온다. (같은 길로 들어오고 나간다)
     */
    private fun showPage(page: View, animate: Boolean) {
        if (shownPage === page) return
        val prev = shownPage; shownPage = page
        val forward = page !== b.pageIdle.root
        val dy = 56 * dp
        page.setTag(R.id.tag_hiding, null)
        for (p in listOf(b.pageIdle.root, b.pageDrive.root, b.pageArrived.root)) if (p !== page && p !== prev) { Motion.cancel(p); p.visibility = View.GONE }
        if (animate && Motion.enabled && prev != null) {
            page.visibility = View.VISIBLE; page.alpha = 0f
            page.translationY = if (forward) dy else 0f; val sc = if (forward) 0.985f else 0.955f; page.scaleX = sc; page.scaleY = sc
            Motion.appear(page)
            Motion.disappear(prev, toY = if (forward) -dy * 0.35f else dy, toScale = if (forward) 0.965f else 1f)
        } else {
            Motion.cancel(page); page.alpha = 1f; page.translationY = 0f; page.scaleX = 1f; page.scaleY = 1f; page.visibility = View.VISIBLE
            prev?.let { Motion.cancel(it); it.visibility = View.GONE; it.alpha = 1f; it.translationY = 0f; it.scaleX = 1f; it.scaleY = 1f }
        }
        placeMap(page)
    }

    // ---------- SETTINGS / DEV ----------
    private fun showSettings() {
        val dlg = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_settings, null)
        val name = v.findViewById<EditText>(R.id.recipientName); val phone = v.findViewById<EditText>(R.id.recipientPhone)
        name.setText(engine.ui.value.recipientName); phone.setText(engine.ui.value.recipientPhone)
        val locked = noKeyboard
        name.isEnabled = !locked; phone.isEnabled = !locked
        v.findViewById<View>(R.id.restrictedNote).visibility = if (locked) View.VISIBLE else View.GONE
        val save = v.findViewById<Button>(R.id.saveButton)
        save.setOnClickListener { engine.setRecipient(name.text.toString(), phone.text.toString()); dlg.dismiss() }
        val ver = v.findViewById<TextView>(R.id.versionText)
        ver.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME) + if (BuildConfig.USE_MOCK) " · mock" else ""
        ver.setOnClickListener { if (++versionTaps >= 5) { versionTaps = 0; dlg.dismiss(); showDev() } }
        Pressable.attach(save)
        dlg.setOnDismissListener { pushBack(false) }; pushBack(true)
        dlg.setContentView(v); dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED; dlg.show()
    }

    private fun showDev() {
        val dlg = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_dev, null)
        val navi = engine.navi as? MockNaviSource; val veh = engine.vehicle as? MockVehicleSignalSource; val loc = engine.location as? MockLocationSource
        v.findViewById<View>(R.id.devMockPanel).visibility = if (navi != null) View.VISIBLE else View.GONE
        val label = v.findViewById<TextView>(R.id.distanceLabel)
        v.findViewById<SeekBar>(R.id.distanceSeek).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { label.text = "남은 거리 ${p}m"; if (fromUser) navi?.setRemainDistance(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}; override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        v.findViewById<View>(R.id.btn3km).setOnClickListener { navi?.setRemainDistance(2900) }
        v.findViewById<View>(R.id.btn1km).setOnClickListener { navi?.setRemainDistance(950) }
        v.findViewById<View>(R.id.btn300m).setOnClickListener { navi?.setRemainDistance(250) }
        v.findViewById<View>(R.id.btnNaviArrived).setOnClickListener { navi?.arrive() }
        v.findViewById<View>(R.id.btnBrake).setOnClickListener { veh?.brake(true) }
        v.findViewById<View>(R.id.btnGearD).setOnClickListener { veh?.gear(GearPosition.D) }
        v.findViewById<View>(R.id.btnGearP).setOnClickListener { veh?.gear(GearPosition.P) }
        v.findViewById<View>(R.id.btnGeofence).setOnClickListener { loc?.enter() }
        v.findViewById<View>(R.id.btnTurnRight).setOnClickListener { veh?.turn(TurnSide.RIGHT); v.postDelayed({ veh?.turn(TurnSide.OFF) }, 2000) }
        v.findViewById<View>(R.id.btnDoor).setOnClickListener { veh?.door(true); v.postDelayed({ veh?.door(false) }, 1500) }
        v.findViewById<View>(R.id.btnSoc).setOnClickListener { veh?.soc(15) }
        v.findViewById<View>(R.id.btnChargeOps).setOnClickListener { engine.navi.requestChargerOperators() }
        v.findViewById<View>(R.id.btnClearDrops).setOnClickListener { engine.clearDrops() }
        v.findViewById<View>(R.id.btnNaviAny).setOnClickListener { engine.debugDestination("판교역", 37.3948, 127.1112) }
        val src = v.findViewById<TextView>(R.id.devSources); val log = v.findViewById<TextView>(R.id.devLog)
        val job = lifecycleScope.launch { engine.ui.collect { s -> src.text = s.sources.entries.joinToString("  ·  ") { "${it.key}: ${it.value}" }; log.text = s.log.joinToString("\n") } }
        dlg.setOnDismissListener { job.cancel(); pushBack(false) }; pushBack(true)
        dlg.setContentView(v); dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED; dlg.show()
    }
}
