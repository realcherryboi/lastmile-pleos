package kr.lastmile.app.source.pleos

import android.content.Context
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import kr.lastmile.app.domain.Destination
import kr.lastmile.app.source.LocationSource

/** FusedLocation SDK v1.1.3 어댑터. 목적지 반경 지오펜스는 addProximityAlert 로. 권한: pleos.car.permission.FUSED_LOCATION */
class PleosLocationSource(private val context: Context) : LocationSource {
    private var fused: FusedLocation? = null
    private var listener: LocationSource.Listener? = null
    private var alertId: String? = null

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) { data?.let { listener?.onLocation(it.latitude, it.longitude) } }
        override fun onError(e: Exception) { listener?.onStatus("위치 오류: ${e.message}") }
    }

    override fun start(listener: LocationSource.Listener) {
        this.listener = listener
        if (!PleosPermissions.has(context, PleosPermissions.FUSED_LOCATION)) { listener.onStatus("권한 없음 FUSED_LOCATION — 위치 비활성"); return }
        val f = FusedLocation(context)
        if (!f.isSupported()) { listener.onStatus("FusedLocation 미지원 환경"); return }
        try { f.initialize(); fused = f; f.registerFusedLocationCallback(locationListener); listener.onStatus("FusedLocation 연결") }
        catch (e: Exception) { listener.onStatus("FusedLocation 실패: ${e.message}") }
    }

    override fun watch(destination: Destination) {
        val f = fused ?: return
        alertId?.let { runCatching { f.removeProximityAlert(it) } }
        f.addProximityAlert(
            ProximityAlertRequest(destination.latitude, destination.longitude, destination.arrivalRadiusM, setOf(ProximityTransition.ENTER, ProximityTransition.EXIT)),
            object : ProximityAlertListener {
                override fun onRegistered(id: String) { alertId = id; listener?.onStatus("지오펜스 ${destination.name} ${destination.arrivalRadiusM.toInt()}m") }
                override fun onTransition(e: ProximityAlertEvent) { listener?.onGeofence(e.transition == ProximityTransition.ENTER) }
                override fun onError(e: Exception) { listener?.onStatus("지오펜스 오류: ${e.message}") }
            })
    }

    override fun stop() {
        fused?.let { f -> alertId?.let { runCatching { f.removeProximityAlert(it) } }; runCatching { f.unregisterFusedLocationCallback(locationListener) }; f.release() }
        fused = null; listener = null
    }
}
