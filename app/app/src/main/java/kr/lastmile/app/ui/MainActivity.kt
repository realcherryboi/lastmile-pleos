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
import kr.lastmile.app.domain.Destination
import kr.lastmile.app.domain.GearPosition
import kr.lastmile.app.domain.TripEngine
import kr.lastmile.app.domain.TripState
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
        for (slot in mapSlots()) { val rc = slot.findViewById<View>(R.id.recenterButton); rc.setOnClickListener { zoneMap.recenter() }; Pressable.attach(rc) }
        b.settingsButton.setOnClickListener { showSettings() }
        Pressable.attach(b.settingsButton)

        ux = UxRestrictions(this) { restricted, noKb -> runOnUiThread { uxRestricted = restricted; noKeyboard = noKb; render(engine.ui.value) } }.also { it.start() }

        lifecycleScope.launch { engine.ui.collect { render(it) } }
    }

    override fun onStart() { super.onStart(); zoneMap.onStart() }
    override fun onResume() { super.onResume(); zoneMap.onResume(); b.root.removeCallbacks(tick); b.root.post(tick) }
    override fun onPause() { b.root.removeCallbacks(tick); zoneMap.onPause(); super.onPause() }
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
    }

    private fun fact(root: View, id: Int, icon: Int, label: String) {
        val row = root.findViewById<View>(id)
        row.findViewById<ImageView>(R.id.factIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.factLabel).text = label
    }
    private fun setFact(root: View, id: Int, value: String) { root.findViewById<View>(id).findViewById<TextView>(R.id.factValue).text = value }

    private fun curbText(d: Destination) = if (d.curbsideAllowed) "정문 앞 ${d.curbsideLimitMin}분까지" else "정문 앞 정차 금지"
    private fun parkingText(d: Destination) = listOf(d.parkingName, d.parkingFee).filter { it.isNotBlank() }.joinToString(" · ")
    private fun fmtMeters(m: Int) = if (m >= 1000) getString(R.string.remain_fmt_km, m / 1000.0) else getString(R.string.remain_fmt_m, m)

    /** 다음 안내 카드: 어떤 이정표가 남았고(아이콘·제목), 몇 m 뒤에 오며, 무엇을 말할지(미리보기) */
    private data class Next(val icon: Int, val title: String, val preview: String, val inMeters: Int)
    private fun nextOf(s: UiState, d: Destination): Next {
        val m = s.remainMeters; val min = maxOf(1, s.remainSec / 60)
        return when (s.state) {
            TripState.ARMED -> Next(R.drawable.ic_bell, "3 km 지점 · 도착 전 브리핑", Briefing.at3km(d, if (s.remainSec > 0) min else 10), if (m > 3000) m - 3000 else -1)
            TripState.BRIEF_3KM -> Next(R.drawable.ic_flag, "1 km 지점 · 정차 안내", Briefing.at1km(d), if (m > 1000) m - 1000 else -1)
            TripState.BRIEF_1KM -> Next(R.drawable.ic_parking, "300 m 지점 · 진입 안내", Briefing.at300m(d), if (m > 300) m - 300 else -1)
            TripState.BRIEF_300M -> Next(R.drawable.ic_check, "도착 · 기어 P", if (s.meetingMode && "arrived" !in s.messagesSent) "도착하면 ${s.recipientName.ifBlank { s.recipientPhone }}에게 알림을 보내요" else "정차 후 기어를 P로 두면 도착 처리돼요", if (m > 0) m else -1)
            TripState.STOPPING -> Next(R.drawable.ic_clock, "잠시 정차 중", if (d.curbsideAllowed) "${d.curbsideLimitMin}분 넘으면 주차장으로 안내해요" else "정차 금지 구역 · ${d.parkingName}로 안내", -1)
            else -> Next(R.drawable.ic_flag, "", "", -1)
        }
    }
    private fun passedOf(s: TripState) = when (s) { TripState.BRIEF_3KM -> 1; TripState.BRIEF_1KM -> 2; TripState.BRIEF_300M, TripState.STOPPING -> 3; TripState.ARRIVED, TripState.DONE -> 4; else -> 0 }

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
        val d = s.destination ?: return
        val ar = b.pageArrived.root
        val timer = ar.findViewById<TextView>(R.id.curbTimer); val bar = ar.findViewById<ProgressBar>(R.id.curbProgress)
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
        if (changed && s.state == TripState.ARRIVED) b.pageArrived.root.findViewById<View>(R.id.arrivedBadge).let { Motion.pop(it, 0.4f) }
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
        if (s.state == TripState.IDLE) routeFor = null
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
            root.findViewById<ZoneSketchView>(R.id.idleSketch).apply { destination = sel; arrived = false }
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
        dr.findViewById<RouteProgressView>(R.id.routeProgress).apply { remainMeters = s.remainMeters; passed = passedOf(s.state) }
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
            setFact(dr, R.id.factCurb, curbText(d))
            dr.findViewById<View>(R.id.factCurb).findViewById<ImageView>(R.id.factIcon).setImageResource(if (d.curbsideAllowed) R.drawable.ic_clock else R.drawable.ic_stop_sign)
            setFact(dr, R.id.factValet, if (d.valet) "있음" else "없음")
            setFact(dr, R.id.factParking, parkingText(d))
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
        ar.findViewById<TextView>(R.id.arrivedDestination).text = d?.let { listOf(it.name, it.address).filter { f -> f.isNotBlank() }.joinToString(" · ") } ?: ""
        if (d != null) {
            val curbTitle = ar.findViewById<TextView>(R.id.curbTitle); val curbIcon = ar.findViewById<ImageView>(R.id.curbIcon); val curbHint = ar.findViewById<TextView>(R.id.curbHint)
            if (d.curbsideAllowed) {
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
            renderCurbTimer(s)
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
        val src = v.findViewById<TextView>(R.id.devSources); val log = v.findViewById<TextView>(R.id.devLog)
        val job = lifecycleScope.launch { engine.ui.collect { s -> src.text = s.sources.entries.joinToString("  ·  ") { "${it.key}: ${it.value}" }; log.text = s.log.joinToString("\n") } }
        dlg.setOnDismissListener { job.cancel(); pushBack(false) }; pushBack(true)
        dlg.setContentView(v); dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED; dlg.show()
    }
}
