package kr.lastmile.app.ui

import android.content.Context
import android.util.Log

/**
 * 주행 규제(CarUxRestrictions) 감시. 플랫폼이 내려주는 ActiveRestrictions 를 그대로 반영한다(앱이 지역을 판단하지 않음).
 * android.car 가 없는 환경에서는 조용히 "규제 없음"으로 동작한다.
 */
class UxRestrictions(private val context: Context, private val onChanged: (restricted: Boolean, noKeyboard: Boolean) -> Unit) {
    private var car: android.car.Car? = null
    private var manager: android.car.drivingstate.CarUxRestrictionsManager? = null
    private val listener = android.car.drivingstate.CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r -> publish(r) }

    fun start() {
        try {
            val c = android.car.Car.createCar(context) ?: return
            car = c
            val m = c.getCarManager(android.car.Car.CAR_UX_RESTRICTION_SERVICE) as? android.car.drivingstate.CarUxRestrictionsManager ?: return
            manager = m
            m.registerListener(listener)
            m.currentCarUxRestrictions?.let { publish(it) }
        } catch (t: Throwable) { Log.w("UxRestrictions", "unavailable: ${t.message}") }
    }

    private fun publish(r: android.car.drivingstate.CarUxRestrictions) {
        val restricted = r.isRequiresDistractionOptimization
        val noKeyboard = restricted && (r.activeRestrictions and android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD) != 0
        onChanged(restricted, noKeyboard)
    }

    fun stop() { runCatching { manager?.unregisterListener(); car?.disconnect() }; manager = null; car = null }
}
