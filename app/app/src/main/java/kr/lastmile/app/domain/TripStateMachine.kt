package kr.lastmile.app.domain

enum class TripState { IDLE, ARMED, BRIEF_3KM, BRIEF_1KM, BRIEF_300M, STOPPING, ARRIVED, DONE }

enum class GearPosition { P, R, N, D, UNKNOWN }


/**
 * 이번 경로의 이정표 하나. 경로가 짧으면(3 km 미만) 첫 이정표는 "출발"이 되고, 출발점과 300 m 안에 겹치는 이정표는 빠진다.
 * state = 그 이정표를 지나면 들어가는 상태 (도착은 ARRIVED).
 */
data class Milestone(val label: String, val meters: Int, val state: TripState)

/** 외부(내비·차량·위치)에서 들어오는 사실. 상태머신은 이것만 본다. */
sealed class TripEvent {
    data class DestinationSet(val destination: Destination) : TripEvent()
    data class RemainDistance(val meters: Int, val remainSec: Int) : TripEvent()
    object NaviArrived : TripEvent()
    data class GeofenceEnter(val entered: Boolean) : TripEvent()
    data class Hazard(val on: Boolean) : TripEvent()
    data class BrakeHeld(val held: Boolean) : TripEvent()      // 브레이크 페달 N초 유지 (비상등 대체)
    data class Gear(val position: GearPosition) : TripEvent()
    data class ParkingBrake(val on: Boolean) : TripEvent()
    data class Door(val open: Boolean) : TripEvent()           // 승객석 문 (열림 → 닫힘 = 승하차 완료)
    data class EvSoc(val percent: Int) : TripEvent()           // EV 배터리 잔량 %
    object CurbTimeUp : TripEvent()                            // 도착 후 정차 허용 시간이 다 됨 (엔진 타이머)
    object Reset : TripEvent()
}

/** 상태 전이 결과로 나가는 행동. 엔진이 TTS·메시지로 바꾼다. */
sealed class TripAction {
    /** headline = 헤드업 알림 제목(짧게, "1 km · 주차장 안내"). 비면 앱 이름. */
    data class Speak(val text: String, val headline: String = "") : TripAction()
    data class Notify(val kind: String, val text: String) : TripAction()   // 마중 메시지 (kind: eta / arrived)
    data class StateChanged(val from: TripState, val to: TripState) : TripAction()
}

/**
 * 순수 함수형 상태머신. 속도·기어 외에 "차가 멈췄다"를 증명하는 신호 조합:
 *   도착 = (내비 도착 이벤트 또는 지오펜스 진입) AND (기어 P 또는 주차 브레이크 ON)
 *   잠시 정차 = 목적지 100 m 안에서 브레이크 3초 유지(또는 비상등 ON — 서드파티에 열리면)
 *     방향지시등은 쓰지 않는다: 300 m 안 우회전마다 안내가 떠 번거롭다는 대표 판단(11/13). 브레이크도 신호 대기와 헷갈리지 않게 100 m 안에서만.
 *   승하차 완료 = 도착 후 승객석 문이 열렸다 닫힘 → DONE
 */
class TripStateMachine {
    companion object { const val STOP_INTENT_M = 100 }   // 이 거리 안에서 멈춰 서면 "여기 세우려는구나"로 본다
    var state: TripState = TripState.IDLE
        private set
    var destination: Destination? = null
        private set
    var meetingMode: Boolean = false
    var purpose: TripPurpose = TripPurpose.STOP   // 이번 목적지에서 할 일 (엔진이 목적지 설정 때 정한다)
        set(v) { if (state == TripState.BRIEF_3KM && v != field) purposeChangedSince3km = true; field = v }
    private var purposeChangedSince3km = false      // 3 km 브리핑 뒤 목적을 바꿨으면 1 km 에서 규칙을 한 번 더 말한다
    var evSoc: Int = -1                      // 마지막 배터리 잔량 (-1 = 모름)
        private set

    /** 경로 전체 거리: 목적지 설정 뒤 처음 들어온 남은 거리 (-1 = 아직 모름). 3 km 미만이면 첫 브리핑이 "출발 브리핑"이 된다. */
    var routeMeters: Int = -1
        private set
    /** 출발점과 이만큼도 안 떨어진 이정표는 따로 말하지 않는다 (첫 브리핑 직후에 또 말하게 되므로). */
    private val mergeGapM = 300
    val skip1km: Boolean get() = routeMeters in 0 until 1000 + mergeGapM
    val skip300m: Boolean get() = routeMeters in 0 until 300 + mergeGapM

    /** 이번 경로의 이정표 목록 (진행 바·다음 안내 카드용). 경로 거리를 모르면 기본 3 km·1 km·300 m·도착. */
    val milestones: List<Milestone>
        get() {
            val out = mutableListOf<Milestone>()
            out += if (routeMeters in 0..3000) Milestone("출발", routeMeters, TripState.BRIEF_3KM) else Milestone("3 km", 3000, TripState.BRIEF_3KM)
            if (!skip1km) out += Milestone("1 km", 1000, TripState.BRIEF_1KM)
            if (!skip300m) out += Milestone("300 m", 300, TripState.BRIEF_300M)
            out += Milestone("도착", 0, TripState.ARRIVED)
            return out
        }

    private var nearDestination = false      // navi arrived or geofence enter
    private var parked = false               // gear P or parking brake
    private var doorOpened = false           // ARRIVED 이후 문이 열린 적 있음
    private var overstaySaid = false         // 정차 시간 초과 안내는 한 번만
    private var lastRemainMin = 0
    private var lastRemainM = -1             // 마지막 내비 남은 거리(m), -1 = 모름

    /** 보강된 목적지 데이터(주소·주차장)로 교체. 같은 id 일 때만. */
    fun updateDestination(d: Destination) { if (destination?.id == d.id) destination = d }

    fun handle(event: TripEvent): List<TripAction> {
        val d = destination
        val out = mutableListOf<TripAction>()
        when (event) {
            is TripEvent.Reset -> { reset(); transition(TripState.IDLE, out) }
            is TripEvent.DestinationSet -> {
                reset(); destination = event.destination; transition(TripState.ARMED, out)
            }
            is TripEvent.RemainDistance -> if (d != null) {
                lastRemainMin = maxOf(1, event.remainSec / 60); lastRemainM = event.meters
                if (routeMeters < 0 && event.meters > 0) routeMeters = event.meters   // 첫 남은 거리 = 경로 길이 → 이정표 계획 확정
                when {
                    // 3 km 지점 브리핑. 경로가 3 km 미만이면 출발 직후 바로 나간다(출발 브리핑) — 문장은 "○○까지 N분 남았어요" 전이에요"라 그대로 맞는다.
                    state == TripState.ARMED && event.meters <= 3000 -> {
                        purposeChangedSince3km = false
                        transition(TripState.BRIEF_3KM, out)
                        out += TripAction.Speak(Briefing.at3km(d, lastRemainMin, evSoc, purpose), if (routeMeters in 0..3000) "출발 · ${d.name}까지 ${lastRemainMin}분" else "3 km · ${d.name}까지 ${lastRemainMin}분")
                        if (meetingMode) out += TripAction.Notify("eta", Briefing.meetingEta(d, lastRemainMin))
                    }
                    // 1 km. 출발점이 1.3 km 안이면 건너뛴다(첫 브리핑에서 막 규칙을 말했는데 곧바로 또 말하게 되므로).
                    state == TripState.BRIEF_3KM && !skip1km && event.meters <= 1000 -> {
                        transition(TripState.BRIEF_1KM, out); out += TripAction.Speak(Briefing.at1km(d, purpose, purposeChangedSince3km), if (purpose == TripPurpose.PARK) "1 km · 주차장 안내" else "1 km · 정차 안내")
                    }
                    // 300 m. 1 km 를 건너뛰었으면 첫 브리핑 상태에서 바로 온다. 출발점이 600 m 안이면 이것도 건너뛴다.
                    (state == TripState.BRIEF_1KM || state == TripState.BRIEF_3KM && skip1km) && !skip300m && event.meters <= 300 -> {
                        transition(TripState.BRIEF_300M, out); out += TripAction.Speak(Briefing.at300m(d, purpose), "300 m · 잠시 후 도착")
                    }
                }
            }
            is TripEvent.NaviArrived -> { nearDestination = true; checkArrival(d, out) }
            is TripEvent.GeofenceEnter -> { nearDestination = event.entered || nearDestination; checkArrival(d, out) }
            is TripEvent.Hazard -> if (d != null && event.on && inLastStretch()) stopping(d, out)
            is TripEvent.BrakeHeld -> if (d != null && event.held && lastRemainM in 0..STOP_INTENT_M) stopping(d, out)
            is TripEvent.Gear -> { parked = event.position == GearPosition.P; checkArrival(d, out) }
            is TripEvent.ParkingBrake -> { if (event.on) parked = true; checkArrival(d, out) }
            is TripEvent.EvSoc -> evSoc = event.percent
            is TripEvent.CurbTimeUp -> if (d != null && state == TripState.ARRIVED && purpose != TripPurpose.PARK && d.curbsideAllowed && !overstaySaid) {
                overstaySaid = true; out += TripAction.Speak(Briefing.overstay(d), "정차 시간 초과")
            }
            is TripEvent.Door -> if (d != null && state == TripState.ARRIVED) {
                if (event.open) doorOpened = true
                else if (doorOpened) { transition(TripState.DONE, out); out += TripAction.Speak(Briefing.boarded(d, meetingMode, purpose), "승하차 확인") }
            }
        }
        return out
    }

    /** 마지막 300 m 구간인가 — 300 m 이정표를 지났거나, 경로가 짧아 그 이정표가 아예 없는 경우 */
    private fun inLastStretch() = state == TripState.BRIEF_300M || skip300m && state in listOf(TripState.BRIEF_3KM, TripState.BRIEF_1KM)

    private fun stopping(d: Destination, out: MutableList<TripAction>) {
        if (state in listOf(TripState.BRIEF_3KM, TripState.BRIEF_1KM, TripState.BRIEF_300M)) { transition(TripState.STOPPING, out); out += TripAction.Speak(Briefing.stopping(d, purpose), if (purpose == TripPurpose.PARK) "주차장 안내" else "잠시 정차") }
    }

    private fun checkArrival(d: Destination?, out: MutableList<TripAction>) {
        if (d == null || state == TripState.ARRIVED || state == TripState.DONE || state == TripState.IDLE) return
        if (nearDestination && parked) {
            transition(TripState.ARRIVED, out)
            if (meetingMode) out += TripAction.Notify("arrived", Briefing.meetingArrived(d, purpose))
            out += TripAction.Speak(Briefing.arrived(d, meetingMode, purpose), "도착 · ${d.name}")
        }
    }

    private fun transition(to: TripState, out: MutableList<TripAction>) {
        val from = state; state = to
        if (from != to) out += TripAction.StateChanged(from, to)
    }

    /** 기어 상태(parked)·배터리는 물리 상태라 목적지 변경에도 유지한다. 출발 시 P→D 이벤트가 오면 자연히 해제된다. */
    private fun reset() { destination = null; nearDestination = false; doorOpened = false; overstaySaid = false; lastRemainMin = 0; routeMeters = -1 }
}
