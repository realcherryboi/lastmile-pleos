package kr.lastmile.app.source.pleos

import android.content.Context
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TripInfo
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
 */
class PleosNaviSource(private val context: Context) : NaviSource {
    private var helper: NaviHelper? = null
    private var listener: NaviSource.Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private val pollMs = 5_000L
    private val poll = object : Runnable { override fun run() { helper?.getDestinationInfo(); main.postDelayed(this, pollMs) } }

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
        override fun onTripInfo(info: TripInfo) { listener?.onStatus("ETA ${info.eta}s, ${info.distance}m") }
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
        h.initialize({ listener.onStatus("NaviHelper 연결"); h.addListener(events); h.getDestinationInfo(); main.postDelayed(poll, pollMs) },
                     { e -> listener.onStatus("NaviHelper 실패: ${e.message}") })
    }

    override fun requestRoute(destination: Destination) {
        helper?.requestRoute(
            RouteInfo(destination.latitude, destination.longitude, null, null, destination.name, "", "", "", RouteOption.RECOMMENDED, "", false)
        ) ?: listener?.onStatus("NaviHelper 미연결")
    }

    override fun cancelRoute() { runCatching { helper?.cancelRoute() } }

    override fun stop() {
        main.removeCallbacks(poll)
        helper?.removeListener(events); helper?.release(); helper = null; listener = null
    }
}
