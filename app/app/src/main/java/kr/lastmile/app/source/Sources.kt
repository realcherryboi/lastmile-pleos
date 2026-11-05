package kr.lastmile.app.source

import kr.lastmile.app.domain.GearPosition
import kr.lastmile.app.domain.TurnSide
import kr.lastmile.app.domain.Destination
import kr.lastmile.app.domain.NaviChargingStop

/** 내비 신호. 실구현: NaviHelper SDK. 데모: MockNaviSource. */
interface NaviSource {
    interface Listener {
        fun onDestinationSet(name: String, lat: Double, lng: Double)
        fun onRemainDistance(meters: Int, remainSec: Int)
        fun onArrived()
        fun onStatus(text: String)
        /** 내비가 아는 충전 사업자 이름 목록 (NaviHelper.getChargerOperatorInfo). 에뮬레이터는 전체 카탈로그를 준다 — 선호 설정이 아니라 이름 정규화용 */
        fun onChargerOperators(names: List<String>) {}
        /** 가상 경로(requestTrip) 응답: 그 지점까지 거리·시간·도착 예상 잔량 */
        fun onTripEstimate(lat: Double, lng: Double, distanceM: Int, etaSec: Int, socAtDest: Int) {}
        /** 내비 EV 경로 플래너가 잡은 충전 경유지 (DrivingInfo) */
        fun onNaviChargingStops(stops: List<NaviChargingStop>) {}
    }
    fun start(listener: Listener)
    fun stop()
    /** 앱에서 목적지를 내비로 보낼 때(요청 전송 가능 여부는 구현체가 결정). */
    fun requestRoute(destination: Destination) {}
    /** 안내 종료: 내비 경로도 취소 */
    fun cancelRoute() {}
    /** 충전 사업자 목록 조회 → onChargerOperators */
    fun requestChargerOperators() {}
    /** 후보 지점까지 가상 경로 → onTripEstimate (현재 경로는 바꾸지 않음) */
    fun requestTripEstimate(name: String, lat: Double, lng: Double) {}
    /** 추천 충전소를 현재 경로의 경유지로 추가 */
    fun addWaypoint(name: String, lat: Double, lng: Double) {}
}

/** 차량 신호. 실구현: Vehicle SDK(Gear·Safety.Telltales·TurnSignal·Door·EvBattery). */
interface VehicleSignalSource {
    interface Listener {
        fun onGear(position: GearPosition)
        fun onHazard(on: Boolean)
        fun onParkingBrake(on: Boolean)
        fun onBrakePedal(pressed: Boolean) {}
        fun onTurnSignal(side: TurnSide) {}
        /** 승객석(조수석·뒷좌석) 문 중 하나라도 열려 있으면 true */
        fun onDoor(open: Boolean) {}
        fun onEvSoc(percent: Int) {}
        fun onStatus(text: String)
    }
    fun start(listener: Listener)
    fun stop()
}

/** 위치·지오펜스. 실구현: FusedLocation SDK(addProximityAlert). */
interface LocationSource {
    interface Listener {
        fun onGeofence(entered: Boolean)
        fun onLocation(lat: Double, lng: Double)
        fun onStatus(text: String)
    }
    fun start(listener: Listener)
    fun watch(destination: Destination)
    fun stop()
}
