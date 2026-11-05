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

    fun start() {
        if (started) return; started = true
        runCatching { navi.start(object : NaviSource.Listener {
            init { naviListener = this }
            override fun onDestinationSet(name: String, lat: Double, lng: Double) = post {
                val d = repository.all().firstOrNull { it.name == name }
                    ?: repository.all().filter { dist(it, lat, lng) < 300.0 }.minByOrNull { dist(it, lat, lng) }
                // 등록 장소가 아니면 내비 좌표 그대로 "일반 규칙 목적지"로 안내한다 (주소·주차장은 지도 타일에서 보강)
                val target = d ?: Destination.generic(name, lat, lng)
                when {
                    target.id == finishedId -> Unit   // 방금 완료한 목적지 (내비 경로 취소 전 재통지)
                    target.id == machine.destination?.id -> Unit   // 같은 목적지 재통지는 무시
                    else -> { finishedId = null; dispatch(TripEvent.DestinationSet(target)); location.watch(target); addLog(if (d == null) "내비 목적지 '${target.name}' — 등록 데이터 없음 → 일반 규칙" else "목적지: ${d.name}") }
                }
            }
            override fun onRemainDistance(meters: Int, remainSec: Int) = post {
                if (meters != lastNaviRemain) { lastNaviRemain = meters; lastNaviRemainAt = System.currentTimeMillis() }
                else if (System.currentTimeMillis() - lastNaviRemainAt > NAVI_STALE_MS) return@post   // 값이 안 변하면 위치 폴백에 맡긴다
                _ui.value = _ui.value.copy(remainMeters = meters, remainSec = remainSec); dispatch(TripEvent.RemainDistance(meters, remainSec))
            }
            override fun onArrived() = post { addLog("내비: 도착"); dispatch(TripEvent.NaviArrived) }
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
            override fun onDoor(open: Boolean) = post { if (open != _ui.value.doorOpen) { addLog("승객석 문: ${if (open) "열림" else "닫힘"}"); _ui.value = _ui.value.copy(doorOpen = open); dispatch(TripEvent.Door(open)) } }
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

    /** 지도 타일·역지오코딩으로 보강된 목적지(주소·주차장·도로 방위)로 교체. 현재 목적지일 때만. */
    fun enrich(d: Destination) = post {
        if (machine.destination?.id != d.id) return@post
        machine.updateDestination(d); _ui.value = _ui.value.copy(destination = d); location.watch(d)
        addLog("목적지 보강: ${listOfNotNull(d.address.takeIf { it.isNotBlank() }, d.parkingName.takeIf { it.isNotBlank() }?.let { "$it ${d.parkingDistanceM}m" }).joinToString(" · ").ifBlank { "추가 정보 없음" }}")
    }

    /** 개발용: 내비 콜백과 같은 경로로 임의 목적지를 넣는다 (에뮬레이터 내비 검색 없이 A 시나리오 재현). */
    fun debugDestination(name: String, lat: Double, lng: Double) { naviListener?.onDestinationSet(name, lat, lng) }
    private var naviListener: NaviSource.Listener? = null

    /** 출발 전 선택만 (안내는 startGuidance 에서). */
    fun selectDestination(d: Destination) { _ui.value = _ui.value.copy(selected = d) }

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

    fun reset() = post { insideRadius = false; dispatch(TripEvent.Reset); _ui.value = _ui.value.copy(remainMeters = -1, remainSec = -1, selected = null, messagesSent = emptyList(), lastMessage = "", lastSpoken = "", arrivedAtMs = 0L) }
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
            d == null -> say("먼저 목적지를 설정해 주세요.")
            else -> say(Briefing.answer(d, text))
        }
    }

    private fun dispatch(event: TripEvent) {
        for (a in machine.handle(event)) when (a) {
            is TripAction.Speak -> say(a.text)
            is TripAction.Notify -> sender.send(a.text) { addLog("메시지(${a.kind}): $it") }.also { _ui.value = _ui.value.copy(lastMessage = a.text, messagesSent = _ui.value.messagesSent + a.kind) }
            is TripAction.StateChanged -> { addLog("상태 ${a.from} → ${a.to}"); if (a.to == TripState.ARRIVED) _ui.value = _ui.value.copy(arrivedAtMs = System.currentTimeMillis()) }
        }
        _ui.value = _ui.value.copy(state = machine.state, destination = machine.destination)
    }

    private fun say(text: String) { speaker.speak(text); _ui.value = _ui.value.copy(lastSpoken = text); addLog("🔊 $text") }

    /** 소스·어댑터가 상태를 보고하는 창구 (화면 상단 상태줄 + 로그). */
    fun status(key: String, text: String) = post { sourceStatus[key] = text; _ui.value = _ui.value.copy(sources = sourceStatus.toMap()); addLog("[$key] $text") }

    private fun addLog(line: String) {
        log.addFirst("${TIME.format(Date())} $line"); while (log.size > 60) log.removeLast()
        _ui.value = _ui.value.copy(log = log.toList())
    }

    private fun post(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    private fun dist(d: Destination, lat: Double, lng: Double): Double {
        val dy = (d.latitude - lat) * 111_000; val dx = (d.longitude - lng) * 111_000 * Math.cos(Math.toRadians(lat)); return Math.sqrt(dx * dx + dy * dy)
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
