package kr.lastmile.app.domain

enum class TripState { IDLE, ARMED, BRIEF_3KM, BRIEF_1KM, BRIEF_300M, STOPPING, ARRIVED, DONE }

enum class GearPosition { P, R, N, D, UNKNOWN }

enum class TurnSide { OFF, LEFT, RIGHT }

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
    data class TurnSignal(val side: TurnSide) : TripEvent()    // 우측 깜빡이 = 길가에 세울 의도 (우측 통행)
    data class Door(val open: Boolean) : TripEvent()           // 승객석 문 (열림 → 닫힘 = 승하차 완료)
    data class EvSoc(val percent: Int) : TripEvent()           // EV 배터리 잔량 %
    object Reset : TripEvent()
}

/** 상태 전이 결과로 나가는 행동. 엔진이 TTS·메시지로 바꾼다. */
sealed class TripAction {
    data class Speak(val text: String) : TripAction()
    data class Notify(val kind: String, val text: String) : TripAction()   // 마중 메시지 (kind: eta / arrived)
    data class StateChanged(val from: TripState, val to: TripState) : TripAction()
}

/**
 * 순수 함수형 상태머신. 속도·기어 외에 "차가 멈췄다"를 증명하는 신호 조합:
 *   도착 = (내비 도착 이벤트 또는 지오펜스 진입) AND (기어 P 또는 주차 브레이크 ON)
 *   잠시 정차 = 비상등 ON · 브레이크 3초 유지 · 우측 방향지시등 (300m 이내)
 *   승하차 완료 = 도착 후 승객석 문이 열렸다 닫힘 → DONE
 */
class TripStateMachine {
    var state: TripState = TripState.IDLE
        private set
    var destination: Destination? = null
        private set
    var meetingMode: Boolean = false
    var evSoc: Int = -1                      // 마지막 배터리 잔량 (-1 = 모름)
        private set

    private var nearDestination = false      // navi arrived or geofence enter
    private var parked = false               // gear P or parking brake
    private var doorOpened = false           // ARRIVED 이후 문이 열린 적 있음
    private var lastRemainMin = 0

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
                lastRemainMin = maxOf(1, event.remainSec / 60)
                when {
                    state == TripState.ARMED && event.meters <= 3000 -> {
                        transition(TripState.BRIEF_3KM, out)
                        out += TripAction.Speak(Briefing.at3km(d, lastRemainMin, evSoc))
                        if (meetingMode) out += TripAction.Notify("eta", Briefing.meetingEta(d, lastRemainMin))
                    }
                    state == TripState.BRIEF_3KM && event.meters <= 1000 -> {
                        transition(TripState.BRIEF_1KM, out); out += TripAction.Speak(Briefing.at1km(d))
                    }
                    state == TripState.BRIEF_1KM && event.meters <= 300 -> {
                        transition(TripState.BRIEF_300M, out); out += TripAction.Speak(Briefing.at300m(d))
                    }
                }
            }
            is TripEvent.NaviArrived -> { nearDestination = true; checkArrival(d, out) }
            is TripEvent.GeofenceEnter -> { nearDestination = event.entered || nearDestination; checkArrival(d, out) }
            is TripEvent.Hazard -> if (d != null && event.on) stopping(d, out)
            is TripEvent.BrakeHeld -> if (d != null && event.held) stopping(d, out)
            is TripEvent.TurnSignal -> if (d != null && event.side == TurnSide.RIGHT && state == TripState.BRIEF_300M) {
                stopping(d, out)
            }
            is TripEvent.Gear -> { parked = event.position == GearPosition.P; checkArrival(d, out) }
            is TripEvent.ParkingBrake -> { if (event.on) parked = true; checkArrival(d, out) }
            is TripEvent.EvSoc -> evSoc = event.percent
            is TripEvent.Door -> if (d != null && state == TripState.ARRIVED) {
                if (event.open) doorOpened = true
                else if (doorOpened) { transition(TripState.DONE, out); out += TripAction.Speak(Briefing.boarded(d, meetingMode)) }
            }
        }
        return out
    }

    private fun stopping(d: Destination, out: MutableList<TripAction>) {
        if (state in listOf(TripState.BRIEF_300M, TripState.BRIEF_1KM)) { transition(TripState.STOPPING, out); out += TripAction.Speak(Briefing.stopping(d)) }
    }

    private fun checkArrival(d: Destination?, out: MutableList<TripAction>) {
        if (d == null || state == TripState.ARRIVED || state == TripState.DONE || state == TripState.IDLE) return
        if (nearDestination && parked) {
            transition(TripState.ARRIVED, out)
            if (meetingMode) out += TripAction.Notify("arrived", Briefing.meetingArrived(d))
            out += TripAction.Speak(Briefing.arrived(d, meetingMode))
        }
    }

    private fun transition(to: TripState, out: MutableList<TripAction>) {
        val from = state; state = to
        if (from != to) out += TripAction.StateChanged(from, to)
    }

    /** 기어 상태(parked)·배터리는 물리 상태라 목적지 변경에도 유지한다. 출발 시 P→D 이벤트가 오면 자연히 해제된다. */
    private fun reset() { destination = null; nearDestination = false; doorOpened = false; lastRemainMin = 0 }
}
