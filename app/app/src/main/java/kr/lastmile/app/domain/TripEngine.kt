package kr.lastmile.app.domain

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.lastmile.app.BuildConfig
import kr.lastmile.app.messaging.MessageSender
import kr.lastmile.app.source.LocationSource
import kr.lastmile.app.source.MockLocationSource
import kr.lastmile.app.source.MockNaviSource
import kr.lastmile.app.source.MockVehicleSignalSource
import kr.lastmile.app.source.NaviSource
import kr.lastmile.app.source.VehicleSignalSource
import kr.lastmile.app.speech.AndroidTtsSpeaker
import kr.lastmile.app.speech.Speaker
import kr.lastmile.app.speech.TextVoiceInput
import kr.lastmile.app.speech.VoiceInput
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val state: TripState = TripState.IDLE,
    val destination: Destination? = null,
    val selected: Destination? = null,          // 출발 전 선택(아직 안내 시작 전)
    val meetingMode: Boolean = false,
    val recipientName: String = "",
    val recipientPhone: String = "",
    val remainMeters: Int = -1,
    val remainSec: Int = -1,
    val lastSpoken: String = "",
    val lastMessage: String = "",
    val messagesSent: List<String> = emptyList(),   // "eta", "arrived"
    val listening: Boolean = false,
    val lastUtterance: String = "",
    val sources: Map<String, String> = emptyMap(),
    val log: List<String> = emptyList(),
    val hereLat: Double = Double.NaN,            // 마지막 위치 (목록 거리 표시용)
    val hereLng: Double = Double.NaN,
    val arrivedAtMs: Long = 0L,                  // ARRIVED 진입 시각 (정차 타이머)
    val evSoc: Int = -1,                         // EV 배터리 잔량 % (-1 = 모름)
    val doorOpen: Boolean = false,
    val chargerOperators: List<String> = emptyList(),   // 내비가 아는 충전 사업자 목록 (카탈로그)
    val naviChargingStops: List<NaviChargingStop> = emptyList(),   // 내비 EV 플래너 충전 경유지
    val chargerWaypointAdded: Boolean = false,   // 추천 충전소를 경유지로 넣음
    val purpose: TripPurpose = TripPurpose.STOP, // 이번 목적지에서 할 일
)

/**
 * 앱의 심장. 소스(내비·차량·위치) → 상태머신 → 행동(TTS·메시지).
 * 모든 콜백을 메인 스레드로 모아 상태머신을 단일 스레드에서 돌린다.
 */
class TripEngine private constructor(
    private val context: Context,
    val repository: DestinationRepository,
    val navi: NaviSource,
    val vehicle: VehicleSignalSource,
    val location: LocationSource,
    private val speaker: Speaker,
    val voice: VoiceInput,
    val sender: MessageSender,
) {
    private val main = Handler(Looper.getMainLooper())
    private val machine = TripStateMachine()
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private val sourceStatus = linkedMapOf<String, String>()
    private val log = ArrayDeque<String>()
    private var started = false
    /** 완료한 목적지 id. 내비가 같은 목적지를 다시 알려도(앱 재시작 포함) 재시작하지 않도록 저장한다. */
    private var finishedId: String?
        get() = prefs.getString("finished", null)
        set(v) { prefs.edit().putString("finished", v).apply() }
    private var lastNaviRemainAt = 0L        // 내비 남은 거리 "값이 바뀐" 시각
    private var lastNaviRemain = -1
    private var insideRadius = false
    private var lastLocLogAt = 0L
    private val brakeHold = Runnable { addLog("브레이크 유지"); dispatch(TripEvent.BrakeHeld(true)) }
    private val BRAKE_HOLD_MS = 3_000L
    private val NAVI_STALE_MS = 15_000L
    val drops by lazy { DropSpotStore(context) }   // 승하차 위치 기억 (기기 내)

    fun start() {
        if (started) return; started = true
        runCatching { navi.start(object : NaviSource.Listener {
            init { naviListener = this }
            override fun onDestinationSet(name: String, lat: Double, lng: Double) = post {
                val d = repository.all().firstOrNull { it.name == name }
                    ?: repository.all().filter { dist(it, lat, lng) < 300.0 }.minByOrNull { dist(it, lat, lng) }
                // 등록 장소가 아니면 내비 좌표 그대로 "일반 규칙 목적지"로 안내한다 (주소·주차장은 지도 타일에서 보강)
                val target = DropMemory.attach(d ?: Destination.generic(name, lat, lng), drops.of((d ?: Destination.generic(name, lat, lng)).id))
                when {
                    target.id == finishedId -> Unit   // 방금 완료한 목적지 (내비 경로 취소 전 재통지)
                    target.id == machine.destination?.id -> Unit   // 같은 목적지 재통지는 무시
                    else -> {
                        finishedId = null
                        // 출발 전에 고른 목적이 이 목적지 것이면 유지, 아니면 정차 규칙으로 기본값
                        val chosen = pendingPurpose?.takeIf { it.first == target.id }?.second ?: TripPurpose.defaultFor(target)
                        pendingPurpose = null; machine.purpose = chosen; _ui.value = _ui.value.copy(purpose = chosen)
                        dispatch(TripEvent.DestinationSet(target)); location.watch(target)
                        addLog((if (d == null) "내비 목적지 '${target.name}' — 등록 데이터 없음 → 일반 규칙" else "목적지: ${d.name}") + " · 목적 ${chosen.short}" + (target.drop?.let { " · 지난번 내린 자리 ${DropMemory.describe(target, it)} (${target.dropCount}회)" } ?: ""))
                    }
                }
            }
            override fun onRemainDistance(meters: Int, remainSec: Int) = post {
                if (meters != lastNaviRemain) { lastNaviRemain = meters; lastNaviRemainAt = System.currentTimeMillis() }
                else if (System.currentTimeMillis() - lastNaviRemainAt > NAVI_STALE_MS) return@post   // 값이 안 변하면 위치 폴백에 맡긴다
                _ui.value = _ui.value.copy(remainMeters = meters, remainSec = remainSec); dispatch(TripEvent.RemainDistance(meters, remainSec))
            }
            override fun onArrived() = post { addLog("내비: 도착"); dispatch(TripEvent.NaviArrived) }
            override fun onChargerOperators(names: List<String>) = post { _ui.value = _ui.value.copy(chargerOperators = names); machine.destination?.let { d -> if (d.chargers.isNotEmpty()) applyChargers(d, d.chargers) } }
            override fun onTripEstimate(lat: Double, lng: Double, distanceM: Int, etaSec: Int, socAtDest: Int) = post {
                val d = machine.destination ?: return@post
                val c = d.charger ?: return@post
                if (dist(c.lat, c.lng, lat, lng) > 50.0) return@post
                // 에뮬레이터 내비는 차량 잔량과 무관하게 자기 에너지 모델로 계산한다 → 현재 잔량보다 크면 믿지 않는다
                if (_ui.value.evSoc in 0 until socAtDest) { addLog("충전소 '${c.name}' 내비 예상 잔량 $socAtDest% > 현재 ${_ui.value.evSoc}% → 무시"); return@post }
                val upd = c.copy(socAtArrival = socAtDest)
                machine.updateDestination(d.copy(charger = upd, chargers = d.chargers.map { if (it.lat == c.lat && it.lng == c.lng) upd else it }))
                _ui.value = _ui.value.copy(destination = machine.destination); addLog("충전소 '${c.name}' 도착 예상 잔량 $socAtDest%")
            }
            override fun onNaviChargingStops(stops: List<NaviChargingStop>) = post {
                if (stops != _ui.value.naviChargingStops) { _ui.value = _ui.value.copy(naviChargingStops = stops); if (stops.isNotEmpty()) addLog("내비 충전 경유지: ${stops.joinToString { "${it.name} ${it.maxKw}kW" }}") }
            }
            override fun onStatus(text: String) = status("내비", text)
        }) }.onFailure { status("내비", "시작 실패 ${it.message}") }
        runCatching { vehicle.start(object : VehicleSignalSource.Listener {
            override fun onGear(position: GearPosition) = post { addLog("기어: $position"); dispatch(TripEvent.Gear(position)) }
            override fun onHazard(on: Boolean) = post { addLog("비상등: $on"); dispatch(TripEvent.Hazard(on)) }
            override fun onParkingBrake(on: Boolean) = post { addLog("주차브레이크: $on"); dispatch(TripEvent.ParkingBrake(on)) }
            override fun onBrakePedal(pressed: Boolean) = post {
                main.removeCallbacks(brakeHold)
                if (pressed) main.postDelayed(brakeHold, BRAKE_HOLD_MS)
            }
            override fun onTurnSignal(side: TurnSide) = post { if (side != TurnSide.OFF) addLog("방향지시등: $side"); dispatch(TripEvent.TurnSignal(side)) }
            override fun onDoor(open: Boolean) = post { if (open != _ui.value.doorOpen) { addLog("승객석 문: ${if (open) "열림" else "닫힘"}"); _ui.value = _ui.value.copy(doorOpen = open); if (open) rememberDrop(); dispatch(TripEvent.Door(open)) } }
            override fun onEvSoc(percent: Int) = post { if (percent != _ui.value.evSoc) { addLog("배터리: $percent%"); _ui.value = _ui.value.copy(evSoc = percent); dispatch(TripEvent.EvSoc(percent)) } }
            override fun onStatus(text: String) = status("차량", text)
        }) }.onFailure { status("차량", "시작 실패 ${it.message}") }
        runCatching { location.start(object : LocationSource.Listener {
            override fun onGeofence(entered: Boolean) = post { addLog("지오펜스: ${if (entered) "진입" else "이탈"}"); dispatch(TripEvent.GeofenceEnter(entered)) }
            override fun onLocation(lat: Double, lng: Double) = post {
                _ui.value = _ui.value.copy(hereLat = lat, hereLng = lng)
                val d = machine.destination ?: return@post
                val now = System.currentTimeMillis()
                val meters = dist(d, lat, lng).toInt()
                if (now - lastLocLogAt > 5_000) { lastLocLogAt = now; addLog("위치 ${"%.4f".format(lat)},${"%.4f".format(lng)} → ${meters}m") }
                // 지오펜스 ENTER 이벤트를 놓쳐도(등록 시점에 이미 반경 안) 위치로 반경 진입을 확정
                if (meters <= d.arrivalRadiusM && !insideRadius) { insideRadius = true; dispatch(TripEvent.GeofenceEnter(true)) }
                if (meters > d.arrivalRadiusM * 2) insideRadius = false
                // 내비 남은 거리가 15초 이상 그대로면(에뮬레이터 내비는 GPS 미연동) 위치 기반 직선거리로 대체
                if (now - lastNaviRemainAt > NAVI_STALE_MS) {
                    _ui.value = _ui.value.copy(remainMeters = meters, remainSec = meters / 10); dispatch(TripEvent.RemainDistance(meters, meters / 10))
                }
            }
            override fun onStatus(text: String) = status("위치", text)
        }) }.onFailure { status("위치", "시작 실패 ${it.message}") }
        runCatching { voice.start { text -> post { onUtterance(text) } } }.onFailure { status("STT", "시작 실패 ${it.message}") }
        // 저장된 수신자·마중 설정 복원
        val rn = prefs.getString("rname", "") ?: ""; val rp = prefs.getString("rphone", "") ?: ""
        sender.recipient = rp; _ui.value = _ui.value.copy(recipientName = rn, recipientPhone = rp)
        if (prefs.getBoolean("meeting", false) && rp.isNotBlank()) { machine.meetingMode = true; _ui.value = _ui.value.copy(meetingMode = true) }
        addLog("엔진 시작 (${if (BuildConfig.USE_MOCK) "모의" else "SDK"} 모드)")
    }

    private val prefs by lazy { context.getSharedPreferences("lastmile", Context.MODE_PRIVATE) }

    /**
     * 승객석 문이 열린 순간의 위치를 "내린 자리"로 기억한다 (Door + FusedLocation). 도착(ARRIVED)이나 잠시 정차(STOPPING) 중,
     * 목적지에서 150 m 안일 때만. 기억은 기기 안 SharedPreferences, 목적지 id 별 최근 10건.
     */
    private fun rememberDrop() {
        val d = machine.destination ?: return
        if (machine.state != TripState.ARRIVED && machine.state != TripState.STOPPING) return
        val lat = _ui.value.hereLat; val lng = _ui.value.hereLng
        if (lat.isNaN() || lng.isNaN()) { addLog("내린 자리: 위치 없음 → 기억 안 함"); return }
        val m = dist(d, lat, lng)
        if (m > DropMemory.NEAR_M) { addLog("내린 자리: 목적지에서 ${m.toInt()} m → 기억 안 함"); return }
        val list = drops.add(d.id, DropSpot(lat, lng, System.currentTimeMillis(), machine.purpose.name))
        val nd = DropMemory.attach(d, list, justSaved = true)
        machine.updateDestination(nd); _ui.value = _ui.value.copy(destination = nd)
        addLog("내린 자리 기억: ${DropMemory.describe(nd, nd.drop!!)} · ${list.size}회")
    }

    /** 개발용: 기억 전부 삭제 (데모 반복용). 현재 목적지에서도 뗀다. */
    fun clearDrops() = post {
        drops.clear(); addLog("내린 자리 기억 삭제")
        machine.destination?.let { d -> val nd = DropMemory.attach(d, emptyList()); machine.updateDestination(nd); _ui.value = _ui.value.copy(destination = nd) }
        _ui.value.selected?.let { s -> _ui.value = _ui.value.copy(selected = DropMemory.attach(s, emptyList())) }
    }

    /** 지도 타일·역지오코딩으로 보강된 목적지(주소·주차장·도로 방위)로 교체. 현재 목적지일 때만. */
    fun enrich(d: Destination) = post {
        if (machine.destination?.id != d.id) return@post
        machine.updateDestination(d); _ui.value = _ui.value.copy(destination = d); location.watch(d)
        addLog("목적지 보강: ${listOfNotNull(d.address.takeIf { it.isNotBlank() }, d.parkingName.takeIf { it.isNotBlank() }?.let { "$it ${d.parkingDistanceM}m" }).joinToString(" · ").ifBlank { "추가 정보 없음" }}")
    }

    /**
     * 충전소 후보(타일·등록 데이터·실시간)를 목적지에 붙이고 1곳을 추천한다. 추천이 바뀌면 내비에 가상 경로를 물어 도착 예상 잔량을 받는다.
     * 내비 EV 플래너가 이미 충전 경유지를 잡았으면(장거리) 우리 추천은 "도착 후" 보조로만 남긴다.
     */
    fun applyChargers(target: Destination, cands: List<ChargerCandidate>) = post {
        val d = machine.destination ?: return@post
        if (d.id != target.id) return@post
        val parking = d.zone.firstOrNull { it.type == "parking" && it.absolute }
        // 내비 사업자 목록은 카탈로그(선호 설정 아님)라 가산점에는 안 쓴다. 선호 사업자는 키 단계에서 설정으로 받는다.
        val pick = ChargerPlanner.pick(cands, emptyList(), parking?.lat ?: Double.NaN, parking?.lng ?: Double.NaN)
        val keepSoc = d.charger?.takeIf { old -> pick != null && dist(old.lat, old.lng, pick.lat, pick.lng) < 40.0 }?.socAtArrival ?: -1
        val nd = d.copy(chargers = cands, charger = pick?.copy(socAtArrival = if (pick.socAtArrival >= 0) pick.socAtArrival else keepSoc), parkingCharger = d.parkingCharger || (pick != null && parking != null && dist(pick.lat, pick.lng, parking.lat, parking.lng) < 60.0))
        machine.updateDestination(nd); _ui.value = _ui.value.copy(destination = nd)
        if (pick == null) { addLog("충전소 후보 없음 (반경 ${ChargerPlanner.RADIUS_M} m)"); return@post }
        addLog("충전 추천: ${pick.name} · ${pick.summary()}")
        if (keepSoc < 0 && pick.socAtArrival < 0) navi.requestTripEstimate(pick.name, pick.lat, pick.lng)
    }

    /** 추천 충전소를 내비 경유지로 추가 (운전자가 카드에서 누름). */
    fun addChargerWaypoint() {
        val c = machine.destination?.charger ?: return
        navi.addWaypoint(c.name, c.lat, c.lng); _ui.value = _ui.value.copy(chargerWaypointAdded = true)
        say("정문 대신 ${c.name} 충전기 자리로 안내할게요."); addLog("충전기 경유지 요청: ${c.name}")
    }

    /** 개발용: 내비 콜백과 같은 경로로 임의 목적지를 넣는다 (에뮬레이터 내비 검색 없이 A 시나리오 재현). */
    fun debugDestination(name: String, lat: Double, lng: Double) { naviListener?.onDestinationSet(name, lat, lng) }
    private var naviListener: NaviSource.Listener? = null

    /** 출발 전 선택만 (안내는 startGuidance 에서). 목적은 정차 규칙 기본값으로 돌아간다. */
    fun selectDestination(d0: Destination) { val d = DropMemory.attach(d0, drops.of(d0.id)); val p = TripPurpose.defaultFor(d); pendingPurpose = d.id to p; _ui.value = _ui.value.copy(selected = d, purpose = p) }

    private var pendingPurpose: Pair<String, TripPurpose>? = null   // 출발 전 고른 목적 (목적지 id, 목적)

    /** 목적 변경: 출발 전이면 기억해 뒀다가 내비가 목적지를 돌려줄 때 적용, 안내 중이면 바로 적용(이후 브리핑부터 반영). */
    fun setPurpose(p: TripPurpose) = post {
        val d = machine.destination
        if (d != null && machine.state != TripState.IDLE) { machine.purpose = p; addLog("목적 변경: ${p.short}") }
        else _ui.value.selected?.let { pendingPurpose = it.id to p }
        _ui.value = _ui.value.copy(purpose = p)
    }

    /** 내비에 경로 요청. 모의 소스는 즉시, NaviHelper 는 내비가 DestinationInfo 로 되돌려 줄 때 ARMED 가 된다. */
    fun startGuidance() { val d = _ui.value.selected ?: return; finishedId = null; addLog("안내 시작 요청: ${d.name}"); navi.requestRoute(d) }

    fun setMeetingMode(on: Boolean) {
        if (on && sender.recipient.isBlank()) { addLog("마중 모드: 수신자 없음"); _ui.value = _ui.value.copy(meetingMode = false); return }
        machine.meetingMode = on; _ui.value = _ui.value.copy(meetingMode = on); prefs.edit().putBoolean("meeting", on).apply(); addLog("마중 모드: $on")
    }

    fun setRecipient(name: String, phone: String) {
        sender.recipient = phone.trim()
        prefs.edit().putString("rname", name.trim()).putString("rphone", phone.trim()).apply()
        _ui.value = _ui.value.copy(recipientName = name.trim(), recipientPhone = phone.trim())
        if (phone.isBlank() && _ui.value.meetingMode) setMeetingMode(false)
    }

    fun askVoice() { _ui.value = _ui.value.copy(listening = true, lastUtterance = ""); voice.listenOnce() }
    fun cancelVoice() { _ui.value = _ui.value.copy(listening = false) }
    /** 음성 인식이 없는 환경(에뮬레이터)용 텍스트 경로 */
    fun submitUtterance(text: String) = post { onUtterance(text) }

    fun reset() = post { insideRadius = false; dispatch(TripEvent.Reset); _ui.value = _ui.value.copy(remainMeters = -1, remainSec = -1, selected = null, messagesSent = emptyList(), lastMessage = "", lastSpoken = "", arrivedAtMs = 0L, chargerWaypointAdded = false) }
    fun endGuidance() { finishedId = machine.destination?.id; navi.cancelRoute(); reset() }
    /** 도착 화면의 완료: 내비 경로는 건드리지 않고(내비가 앞으로 튀어나오지 않게) 같은 목적지 재통지만 무시 */
    fun done() { finishedId = machine.destination?.id; reset() }

    private fun onUtterance(text: String) {
        val d = machine.destination
        _ui.value = _ui.value.copy(listening = false, lastUtterance = text)
        addLog("운전자: $text")
        val t = text.replace(" ", "")
        when {
            t.contains("마중") && (t.contains("켜") || t.contains("시작")) -> { setMeetingMode(true); say("마중 모드 켰어요.") }
            t.contains("마중") && (t.contains("꺼") || t.contains("해제")) -> { setMeetingMode(false); say("마중 모드 껐어요.") }
            t.contains("충전") && (t.contains("안내") || t.contains("가자") || t.contains("가줘") || t.contains("넣어") || t.contains("들러")) -> {
                // "충전기 자리로 안내해" — 카드 버튼과 같은 요청을 음성으로 (주행 중 터치 0회)
                val c = machine.destination?.charger
                when {
                    c == null -> say("근처 충전기 정보가 없어요.")
                    _ui.value.chargerWaypointAdded -> say("이미 ${c.name}으로 안내 중이에요.")
                    else -> addChargerWaypoint()
                }
            }
            d == null -> say("먼저 목적지를 설정해 주세요.")
            else -> say(Briefing.answer(d, text, machine.purpose))
        }
    }

    private fun dispatch(event: TripEvent) {
        for (a in machine.handle(event)) when (a) {
            is TripAction.Speak -> say(a.text)
            is TripAction.Notify -> sender.send(a.text) { addLog("메시지(${a.kind}): $it") }.also { _ui.value = _ui.value.copy(lastMessage = a.text, messagesSent = _ui.value.messagesSent + a.kind) }
            is TripAction.StateChanged -> { addLog("상태 ${a.from} → ${a.to}"); if (a.to == TripState.ARRIVED) { _ui.value = _ui.value.copy(arrivedAtMs = System.currentTimeMillis()); bringToFront() } }
        }
        _ui.value = _ui.value.copy(state = machine.state, destination = machine.destination)
    }

    /**
     * 주행 중에는 내비가 앞(또는 분할 창 왼쪽)에 있고 이 앱은 오른쪽 반 창이나 뒤에서 말한다(화면 터치 0회 원칙).
     * 뒤에 있을 때만 차가 멈춘 순간(ARRIVED)에 앱을 앞으로 가져와 정차 타이머·주차장·만남 지점을 보여 준다. 정차 중이라 시선 분산 규칙에 걸리지 않는다.
     * 분할 창으로 이미 보이는 중이면 그대로 둔다(startActivity 를 또 부르면 왼쪽 창으로 옮겨져 "내비 왼쪽 · 라스트마일 오른쪽" 이 뒤집힌다).
     */
    private fun bringToFront() {
        if (kr.lastmile.app.LastMileApp.activityResumed) { addLog("도착 → 이미 화면에 떠 있음(분할 창), 그대로 둠"); return }
        runCatching {
            val i = android.content.Intent(context, Class.forName("kr.lastmile.app.ui.MainActivity"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(i); addLog("도착 → 앱을 앞으로")
        }.onFailure { addLog("앱 전면 전환 실패: ${it.message}") }
    }

    private fun say(text: String) { speaker.speak(text); _ui.value = _ui.value.copy(lastSpoken = text); addLog("🔊 $text") }

    /** 소스·어댑터가 상태를 보고하는 창구 (화면 상단 상태줄 + 로그). */
    fun status(key: String, text: String) = post { sourceStatus[key] = text; _ui.value = _ui.value.copy(sources = sourceStatus.toMap()); addLog("[$key] $text") }

    private fun addLog(line: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("LastMile", line)   // 디버그 빌드: logcat 에도 남긴다 (에뮬레이터 검증용)
        log.addFirst("${TIME.format(Date())} $line"); while (log.size > 60) log.removeLast()
        _ui.value = _ui.value.copy(log = log.toList())
    }

    private fun post(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    private fun dist(d: Destination, lat: Double, lng: Double): Double = dist(d.latitude, d.longitude, lat, lng)
    private fun dist(lat1: Double, lng1: Double, lat: Double, lng: Double): Double {
        val dy = (lat1 - lat) * 111_000; val dx = (lng1 - lng) * 111_000 * Math.cos(Math.toRadians(lat)); return Math.sqrt(dx * dx + dy * dy)
    }

    companion object {
        private val TIME = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

        fun create(context: Context): TripEngine {
            val app = context.applicationContext
            val repo = DestinationRepository(app)
            val sender = MessageSender(app)
            val engine: TripEngine
            if (BuildConfig.USE_MOCK) {
                engine = TripEngine(app, repo, MockNaviSource(), MockVehicleSignalSource(), MockLocationSource(),
                    AndroidTtsSpeaker(app) { }, TextVoiceInput(), sender)
            } else {
                engine = PleosWiring.create(app, repo, sender)
            }
            return engine
        }

        /** SDK 모드 생성자 (PleosWiring 에서 호출). */
        internal fun build(context: Context, repo: DestinationRepository, navi: NaviSource, vehicle: VehicleSignalSource,
                           location: LocationSource, speaker: Speaker, voice: VoiceInput, sender: MessageSender) =
            TripEngine(context, repo, navi, vehicle, location, speaker, voice, sender)
    }
}
