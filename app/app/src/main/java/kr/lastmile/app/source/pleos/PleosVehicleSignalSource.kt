package kr.lastmile.app.source.pleos

import android.content.Context
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import kr.lastmile.app.domain.GearPosition
import kr.lastmile.app.source.VehicleSignalSource

/**
 * Vehicle SDK v2.3.2 어댑터. 속도 API는 없으므로(릴리즈 노트: VehicleSpeedListener 삭제) 쓰지 않는다.
 * - 기어: Gear.registerGearSelection → VehicleGear
 * - 주차 브레이크: Safety.registerTelltales → PARKING_BRAKE / ELECTRONIC_PARKING_BRAKE 가 ON
 * - 비상등: TurnSignal.registerTurnSignalState — 값이 LEFT/RIGHT 뿐이라 양쪽 동시 점멸(hazard)은 Telltales 의 TURN_SIGNAL_LEFT+RIGHT 동시 BLINK 로 판정
 */
class PleosVehicleSignalSource(private val context: Context) : VehicleSignalSource {
    private var vehicle: Vehicle? = null
    private var listener: VehicleSignalSource.Listener? = null
    private var hazard = false

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
        override fun onTurnSignalStateUpdated(v: TurnSignalValue?) { listener?.onStatus("방향지시등 $v") }
        override fun onFailed(e: Exception) { listener?.onStatus("방향지시등 오류: ${e.message}") }
    }

    override fun start(listener: VehicleSignalSource.Listener) {
        this.listener = listener
        val missing = PleosPermissions.missing(context, PleosPermissions.CAR_DRIVE, PleosPermissions.CAR_SAFETY, PleosPermissions.CAR_LIGHTS)
        try {
            val v = Vehicle(context); v.initialize(); vehicle = v
            val gear = VehicleAccess.gear(v); val safety = VehicleAccess.safety(v); val turn = VehicleAccess.turnSignal(v); val brake = VehicleAccess.brake(v)
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
            runCatching { VehicleAccess.gear(v).unregisterGearSelection(gearListener); VehicleAccess.safety(v).unregisterTelltales(telltaleListener); VehicleAccess.turnSignal(v).unregisterTurnSignalState(turnListener); VehicleAccess.brake(v).unregisterBrakePedalStatus(brakeListener) }
            v.release()
        }
        vehicle = null; listener = null
    }

    private fun VehicleGear.toPosition() = when (this) {
        VehicleGear.GEAR_PARK -> GearPosition.P
        VehicleGear.GEAR_REVERSE -> GearPosition.R
        VehicleGear.GEAR_NEUTRAL -> GearPosition.N
        VehicleGear.GEAR_UNKNOWN -> GearPosition.UNKNOWN
        else -> GearPosition.D
    }
}
