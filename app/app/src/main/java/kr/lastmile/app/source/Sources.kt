package kr.lastmile.app.source

import kr.lastmile.app.domain.GearPosition
import kr.lastmile.app.domain.Destination

/** 내비 신호. 실구현: NaviHelper SDK. 데모: MockNaviSource. */
interface NaviSource {
    interface Listener {
        fun onDestinationSet(name: String, lat: Double, lng: Double)
        fun onRemainDistance(meters: Int, remainSec: Int)
        fun onArrived()
        fun onStatus(text: String)
    }
    fun start(listener: Listener)
    fun stop()
    /** 앱에서 목적지를 내비로 보낼 때(요청 전송 가능 여부는 구현체가 결정). */
    fun requestRoute(destination: Destination) {}
    /** 안내 종료: 내비 경로도 취소 */
    fun cancelRoute() {}
}

/** 차량 신호. 실구현: Vehicle SDK(Gear·Safety.Telltales·TurnSignal). */
interface VehicleSignalSource {
    interface Listener {
        fun onGear(position: GearPosition)
        fun onHazard(on: Boolean)
        fun onParkingBrake(on: Boolean)
        fun onBrakePedal(pressed: Boolean) {}
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
