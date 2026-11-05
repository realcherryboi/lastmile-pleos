package kr.lastmile.app.domain

enum class TripState { IDLE, ARMED, BRIEF_3KM, BRIEF_1KM, BRIEF_300M, STOPPING, ARRIVED, DONE }

enum class GearPosition { P, R, N, D, UNKNOWN }

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
 *   잠시 정차 = 비상등 ON (300m 이내)
 */
class TripStateMachine {
    var state: TripState = TripState.IDLE
        private set
    var destination: Destination? = null
        private set
    var meetingMode: Boolean = false

    private var nearDestination = false      // navi arrived or geofence enter
    private var parked = false               // gear P or parking brake
    private var lastRemainMin = 0

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
                        out += TripAction.Speak(Briefing.at3km(d, lastRemainMin))
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
            is TripEvent.Hazard -> if (d != null && event.on && state in listOf(TripState.BRIEF_300M, TripState.BRIEF_1KM)) {
                transition(TripState.STOPPING, out); out += TripAction.Speak(Briefing.stopping(d))
            }
            is TripEvent.BrakeHeld -> if (d != null && event.held && state in listOf(TripState.BRIEF_300M, TripState.BRIEF_1KM)) {
                transition(TripState.STOPPING, out); out += TripAction.Speak(Briefing.stopping(d))
            }
            is TripEvent.Gear -> { parked = event.position == GearPosition.P; checkArrival(d, out) }
            is TripEvent.ParkingBrake -> { if (event.on) parked = true; checkArrival(d, out) }
        }
        return out
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

    /** 기어 상태(parked)는 물리 상태라 목적지 변경에도 유지한다. 출발 시 P→D 이벤트가 오면 자연히 해제된다. */
    private fun reset() { destination = null; nearDestination = false; lastRemainMin = 0 }
}
