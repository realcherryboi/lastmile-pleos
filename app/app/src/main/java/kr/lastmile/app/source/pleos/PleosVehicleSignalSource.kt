package kr.lastmile.app.source.pleos

import android.content.Context
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import kr.lastmile.app.domain.GearPosition
import kr.lastmile.app.domain.TurnSide
import kr.lastmile.app.source.VehicleSignalSource

/**
 * Vehicle SDK v2.3.2 어댑터. 속도 API는 없으므로(릴리즈 노트: VehicleSpeedListener 삭제) 쓰지 않는다.
 * - 기어: Gear.registerGearSelection → VehicleGear
 * - 주차 브레이크: Safety.registerTelltales → PARKING_BRAKE / ELECTRONIC_PARKING_BRAKE 가 ON
 * - 비상등: TurnSignal.registerTurnSignalState — 값이 LEFT/RIGHT 뿐이라 양쪽 동시 점멸(hazard)은 Telltales 의 TURN_SIGNAL_LEFT+RIGHT 동시 BLINK 로 판정
 * - 방향지시등: 우측 = 길가 정차 의도 (CAR_LIGHTS)
 * - 승객석 문: Door.registerDoorPosition(조수석·뒷좌석) — 열림 후 닫힘 = 승하차 완료 (CAR_DOORS)
 * - EV 배터리: EvBattery.registerEvBatteryStateOfCharge — 잔량 낮으면 충전기 있는 주차장 안내 (CAR_ENERGY)
 */
class PleosVehicleSignalSource(private val context: Context) : VehicleSignalSource {
    private var vehicle: Vehicle? = null
    private var listener: VehicleSignalSource.Listener? = null
    private var hazard = false
    private val openDoors = HashSet<DoorValue>()
    private val passengerDoors = listOf(DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
    private val doorListeners = HashMap<DoorValue, DoorPositionListener>()

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { listener?.onGear((gear ?: VehicleGear.GEAR_UNKNOWN).toPosition()) }
        override fun onFailed(e: Exception) { listener?.onStatus("기어 오류: ${e.message}") }
    }
    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(t: Telltales?) { t?.let { handleTelltales(it) } }
        override fun onFailed(e: Exception) { listener?.onStatus("경고등 오류: ${e.message}") }
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) { listener?.onBrakePedal(pressed == true) }
        override fun onFailed(e: Exception) { listener?.onStatus("브레이크 오류: ${e.message}") }
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(v: TurnSignalValue?) {
            listener?.onTurnSignal(when (v) { TurnSignalValue.LEFT -> TurnSide.LEFT; TurnSignalValue.RIGHT -> TurnSide.RIGHT; else -> TurnSide.OFF })
        }
        override fun onFailed(e: Exception) { listener?.onStatus("방향지시등 오류: ${e.message}") }
    }
    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(p: Int?) { p?.let { listener?.onEvSoc(it) } }
        override fun onFailed(e: Exception) { listener?.onStatus("배터리 오류: ${e.message}") }
    }

    private fun doorListener(door: DoorValue) = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) { onDoorPosition(door, pos) }
        override fun onFailed(e: Exception) { listener?.onStatus("문 오류: ${e.message}") }
    }
    private fun onDoorPosition(door: DoorValue, pos: Int?) {
        if ((pos ?: 0) > 0) openDoors += door else openDoors -= door
        listener?.onDoor(openDoors.isNotEmpty())
    }

    override fun start(listener: VehicleSignalSource.Listener) {
        this.listener = listener
        val missing = PleosPermissions.missing(context, PleosPermissions.CAR_DRIVE, PleosPermissions.CAR_SAFETY, PleosPermissions.CAR_LIGHTS, PleosPermissions.CAR_DOORS, PleosPermissions.CAR_ENERGY)
        try {
            val v = Vehicle(context); v.initialize(); vehicle = v
            val gear = VehicleAccess.gear(v); val safety = VehicleAccess.safety(v); val turn = VehicleAccess.turnSignal(v); val brake = VehicleAccess.brake(v)
            val door = VehicleAccess.door(v); val ev = VehicleAccess.evBattery(v)
            val ok = mutableListOf<String>(); val failed = mutableListOf<String>()
            fun attempt(name: String, block: () -> Unit) { runCatching(block).onSuccess { ok += name }.onFailure { failed += "$name(${it.message?.take(40)})" } }
            if (PleosPermissions.CAR_DRIVE !in missing) attempt("기어") {
                gear.registerGearSelection(gearListener)
                gear.getCurrentGearSelectionState({ g -> listener.onGear((g ?: VehicleGear.GEAR_UNKNOWN).toPosition()) }, { listener.onStatus("기어 조회 실패: ${it.message}") })
            }
            if (PleosPermissions.CAR_SAFETY !in missing) attempt("경고등") {
                safety.registerTelltales(telltaleListener)
                safety.getTelltalesInfo({ t -> t?.let { handleTelltales(it) } }, { listener.onStatus("경고등 조회 실패: ${it.message}") })
            }
            if (PleosPermissions.CAR_LIGHTS !in missing) attempt("방향지시등") { turn.registerTurnSignalState(turnListener) }
            if (PleosPermissions.CAR_DRIVE !in missing) attempt("브레이크") { brake.registerBrakePedalStatus(brakeListener) }
            if (PleosPermissions.CAR_DOORS !in missing) attempt("승객석 문") {
                for (dv in passengerDoors) {
                    val l = doorListener(dv); doorListeners[dv] = l
                    door.registerDoorPosition(DoorArea(dv), l)
                    door.getCurrentDoorPosition(DoorArea(dv), { p -> onDoorPosition(dv, p) }, { })
                }
            }
            if (PleosPermissions.CAR_ENERGY !in missing) attempt("배터리") {
                ev.registerEvBatteryStateOfCharge(socListener)
                ev.getEvBatteryStateOfCharge({ p -> p?.let { listener.onEvSoc(it) } }, { listener.onStatus("배터리 조회 실패: ${it.message}") })
            }
            listener.onStatus("Vehicle SDK 연결 — 구독: ${ok.joinToString()}" + (if (failed.isNotEmpty()) " / 실패: ${failed.joinToString()}" else "") + (if (missing.isNotEmpty()) " / 권한 없음: ${missing.joinToString { it.substringAfterLast('.') }}" else ""))
        } catch (e: Exception) { listener.onStatus("Vehicle SDK 실패: ${e.message}") }
    }

    private fun handleTelltales(t: Telltales) {
        val on = t.telltales.filter { it.state != TelltaleState.OFF }.map { it.type }.toSet()
        listener?.onParkingBrake(TelltaleType.PARKING_BRAKE in on || TelltaleType.ELECTRONIC_PARKING_BRAKE in on)
        val h = TelltaleType.TURN_SIGNAL_LEFT in on && TelltaleType.TURN_SIGNAL_RIGHT in on
        if (h != hazard) { hazard = h; listener?.onHazard(h) }
    }

    override fun stop() {
        vehicle?.let { v ->
            runCatching {
                VehicleAccess.gear(v).unregisterGearSelection(gearListener); VehicleAccess.safety(v).unregisterTelltales(telltaleListener)
                VehicleAccess.turnSignal(v).unregisterTurnSignalState(turnListener); VehicleAccess.brake(v).unregisterBrakePedalStatus(brakeListener)
                for ((dv, l) in doorListeners) VehicleAccess.door(v).unregisterDoorPosition(DoorArea(dv), l)
                VehicleAccess.evBattery(v).unregisterEvBatteryStateOfCharge(socListener)
            }
            v.release()
        }
        doorListeners.clear(); vehicle = null; listener = null
    }

    private fun VehicleGear.toPosition() = when (this) {
        VehicleGear.GEAR_PARK -> GearPosition.P
        VehicleGear.GEAR_REVERSE -> GearPosition.R
        VehicleGear.GEAR_NEUTRAL -> GearPosition.N
        VehicleGear.GEAR_UNKNOWN -> GearPosition.UNKNOWN
        else -> GearPosition.D
    }
}
