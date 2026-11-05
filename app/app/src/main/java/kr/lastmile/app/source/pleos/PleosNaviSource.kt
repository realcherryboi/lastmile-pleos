package kr.lastmile.app.source.pleos

import android.content.Context
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.ChargerOperator
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import kr.lastmile.app.domain.NaviChargingStop
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.os.Handler
import android.os.Looper
import kr.lastmile.app.source.NaviSource
import kr.lastmile.app.domain.Destination

/**
 * NaviHelper SDK v3.0.5 어댑터.
 * - 남은 거리: onDestinationInfo(remainDistance[m], remainTime[s]) — 주기적으로 getDestinationInfo() 폴링(이벤트 푸시가 없을 수 있음)
 * - 도착: onDestinationArrived
 * - 목적지 설정: requestRoute(RouteInfo) 로 내비에 경로 요청
 * - 충전: getChargerOperatorInfo(내비가 아는 충전 사업자 목록 — 에뮬레이터는 35곳 전체를 돌려준다) · requestTrip(후보까지 가상 경로 → 도착 예상 잔량) · onDrivingInfo(EV 플래너 충전 경유지) · addWaypoint(추천 충전소 경유)
 */
class PleosNaviSource(private val context: Context) : NaviSource {
    private var helper: NaviHelper? = null
    private var listener: NaviSource.Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private val pollMs = 5_000L
    private val poll = object : Runnable { override fun run() { helper?.getDestinationInfo(); main.postDelayed(this, pollMs) } }
    private var pendingTrip: Triple<String, Double, Double>? = null   // requestTrip 응답을 어느 후보에 붙일지
    /** 에뮬레이터 내비는 도착 잔량을 Wh 로 준다(77074 = 77.1 kWh). 100 이하면 % 로 본다. */
    private fun energy(v: Int) = if (v > 100) "${"%.1f".format(v / 1000.0)} kWh" else "$v%"

    private val events = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { listener?.onStatus("경로 시작") ; helper?.getDestinationInfo() }
        override fun onRoutingCompleted() { listener?.onStatus("경로 탐색 완료"); helper?.getDestinationInfo() }
        override fun onDestinationChanged() { helper?.getDestinationInfo() }
        override fun onDestinationInfo(info: DestinationInfo) {
            val name = info.name?.trim().orEmpty()
            val valid = name.isNotEmpty() || (info.latitude != 0.0 && info.longitude != 0.0)
            if (!valid) { listener?.onStatus("내비 목적지 없음"); return }
            listener?.onDestinationSet(name, info.latitude, info.longitude)
            if (info.remainDistance > 0 || info.remainTime > 0) listener?.onRemainDistance(info.remainDistance, info.remainTime)
        }
        override fun onTripInfo(info: TripInfo) {
            val p = pendingTrip
            listener?.onStatus("가상 경로${p?.let { " '${it.first}'" } ?: ""}: ${info.distance}m, ${info.eta}s, 도착 잔량 ${info.estimatedSocAtDest}%")
            if (p != null) { pendingTrip = null; listener?.onTripEstimate(p.second, p.third, info.distance, info.eta, info.estimatedSocAtDest) }
        }
        override fun onChargerOperatorInfo(ops: List<ChargerOperator>) {
            val names = ops.map { it.name }.filter { it.isNotBlank() }
            listener?.onStatus("내비 충전 사업자 목록 ${names.size}곳" + (if (names.isNotEmpty()) " (${names.take(4).joinToString()} …)" else ""))
            listener?.onChargerOperators(names)
        }
        override fun onDrivingInfo(info: DrivingInfo) {
            val stops = (info.waypoints + info.destination).filter { it.isChargingStation }.map {
                NaviChargingStop(it.name, it.maxChargingPower, it.chargerCount, it.targetSoc, it.isRecommendedChargingStation, it.chargingPlan?.duration ?: 0, it.chargingPlan?.startSoc ?: -1, it.chargingPlan?.endSoc ?: -1)
            }
            listener?.onStatus("주행 정보: 목적지 도착 에너지 ${energy(info.destination.remainDestinationChargingValue)} · 충전 경유지 ${stops.size}")
            listener?.onNaviChargingStops(stops)
        }
        override fun onRouteStateInfo(info: RouteStateInfo) { listener?.onStatus("경로 상태: ${info.naviMode} · 목적지 도착 에너지 ${energy(info.remainDestinationChargingInt)}") }
        override fun onDestinationArrived(info: DestinationArrivedInfo) { listener?.onArrived() }
        override fun onNavigationStatus(status: NavigationStatus) { listener?.onStatus("내비 $status") }
        override fun onRouteCancelled() { listener?.onStatus("경로 취소") }
        override fun onError(code: NaviErrorCode) { listener?.onStatus("오류 $code") }
    }

    override fun start(listener: NaviSource.Listener) {
        this.listener = listener
        val missing = PleosPermissions.missing(context, PleosPermissions.NAVI_ROUTE, PleosPermissions.NAVI_ROUTE_SEARCH)
        if (missing.isNotEmpty()) listener.onStatus("권한 없음: ${missing.joinToString { it.substringAfterLast('.') }}")
        val h = NaviHelper(context)
        helper = h
        h.initialize({ listener.onStatus("NaviHelper 연결"); h.addListener(events); h.getDestinationInfo(); runCatching { h.getChargerOperatorInfo() }; main.postDelayed(poll, pollMs) },
                     { e -> listener.onStatus("NaviHelper 실패: ${e.message}") })
    }

    override fun requestRoute(destination: Destination) {
        helper?.requestRoute(
            RouteInfo(destination.latitude, destination.longitude, null, null, destination.name, "", "", "", RouteOption.RECOMMENDED, "", false)
        ) ?: listener?.onStatus("NaviHelper 미연결")
    }

    override fun cancelRoute() { runCatching { helper?.cancelRoute() } }

    override fun requestChargerOperators() { runCatching { helper?.getChargerOperatorInfo() }.onFailure { listener?.onStatus("충전 사업자 조회 실패: ${it.message}") } }

    override fun requestTripEstimate(name: String, lat: Double, lng: Double) {
        val h = helper ?: return
        pendingTrip = Triple(name, lat, lng)
        runCatching { h.requestTrip(TripRequest(lat, lng, "", name, "")) }.onFailure { pendingTrip = null; listener?.onStatus("가상 경로 실패: ${it.message}") }
    }

    override fun addWaypoint(name: String, lat: Double, lng: Double) {
        val h = helper ?: run { listener?.onStatus("NaviHelper 미연결"); return }
        runCatching { h.addWaypoint(RequestWaypointInfo(lat, lng, name, WaypointIndex.FIRST, "", "", "", "")) }
            .onSuccess { listener?.onStatus("경유지 추가 요청: $name") }.onFailure { listener?.onStatus("경유지 추가 실패: ${it.message}") }
    }

    override fun stop() {
        main.removeCallbacks(poll)
        helper?.removeListener(events); helper?.release(); helper = null; listener = null
    }
}
