package kr.lastmile.app.source

import kr.lastmile.app.domain.Destination
import kr.lastmile.app.domain.GearPosition
import kr.lastmile.app.domain.TurnSide

/** 화면 버튼·슬라이더로 신호를 흉내 낸다. 에뮬레이터에서 내비 연동이 안 될 때의 폴백이기도 하다. */
class MockNaviSource : NaviSource {
    private var listener: NaviSource.Listener? = null
    override fun start(listener: NaviSource.Listener) { this.listener = listener; listener.onStatus("모의 내비") }
    override fun stop() { listener = null }
    override fun requestRoute(destination: Destination) {
        listener?.onDestinationSet(destination.name, destination.latitude, destination.longitude)
    }
    fun setRemainDistance(meters: Int) { listener?.onRemainDistance(meters, meters / 10) }   // 36km/h 가정
    fun arrive() { listener?.onArrived() }
}

class MockVehicleSignalSource : VehicleSignalSource {
    private var listener: VehicleSignalSource.Listener? = null
    override fun start(listener: VehicleSignalSource.Listener) { this.listener = listener; listener.onStatus("모의 차량") }
    override fun stop() { listener = null }
    fun gear(p: GearPosition) = listener?.onGear(p)
    fun hazard(on: Boolean) = listener?.onHazard(on)
    fun parkingBrake(on: Boolean) = listener?.onParkingBrake(on)
    fun brake(pressed: Boolean) = listener?.onBrakePedal(pressed)
    fun turn(side: TurnSide) = listener?.onTurnSignal(side)
    fun door(open: Boolean) = listener?.onDoor(open)
    fun soc(percent: Int) = listener?.onEvSoc(percent)
}

class MockLocationSource : LocationSource {
    private var listener: LocationSource.Listener? = null
    override fun start(listener: LocationSource.Listener) { this.listener = listener; listener.onStatus("모의 위치") }
    override fun watch(destination: Destination) { listener?.onStatus("지오펜스 ${destination.name} ${destination.arrivalRadiusM.toInt()}m") }
    override fun stop() { listener = null }
    fun enter() = listener?.onGeofence(true)
}
