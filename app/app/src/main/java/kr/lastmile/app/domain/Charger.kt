package kr.lastmile.app.domain

import kr.lastmile.app.ui.ZoneGeo

/**
 * 목적지 주변 충전소 후보 1곳. "도착해서 세우는 동안 충전" 추천용 (장거리 경로 충전은 내비 EV 경로 플래너가 한다).
 *  source: tile(OSM 타일, 위치·이름만) / data(등록 장소의 주차장 충전기) / navi(내비 EV 플래너 추천 경유지) / public(한국환경공단 실시간, 키 필요)
 */
data class ChargerCandidate(
    val name: String,
    val lat: Double,
    val lng: Double,
    val distanceM: Int,                 // 정문 → 충전소
    val operator: String = "",          // 운영 사업자 (실시간 데이터 또는 타일 operator 태그)
    val preferred: Boolean = false,     // 운전자가 설정한 선호 충전 사업자 (키 단계에서 설정 화면 추가 예정)
    val available: Int = -1,            // 빈 충전기 수 (-1 = 모름, 실시간 데이터 필요)
    val total: Int = -1,                // 충전기 수
    val maxKw: Int = -1,                // 최대 출력 kW
    val fee: String = "",               // 요금 (사업자 공시 단가, 비어 있으면 표시 안 함)
    val parkingFree: Boolean? = null,   // 주차료 무료 여부
    val socAtArrival: Int = -1,         // 내비 가상 경로의 도착 예상 잔량 % (-1 = 모름)
    val source: String = "tile",
) {
    val live get() = available >= 0
    /** 카드 한 줄: "목적지에서 120 m · 환경부 · 빈 충전기 2/4 · 50 kW" */
    fun summary(): String = listOfNotNull(
        "목적지에서 $distanceM m",
        operator.takeIf { it.isNotBlank() }?.let { if (preferred) "$it ★" else it },
        if (live) "빈 충전기 $available/$total" else if (total > 0) "충전기 ${total}대" else null,
        maxKw.takeIf { it > 0 }?.let { "$it kW" },
        fee.takeIf { it.isNotBlank() },
        parkingFree?.let { if (it) "주차 무료" else null },
        socAtArrival.takeIf { it >= 0 }?.let { "도착 시 $it%" },
    ).joinToString(" · ")
}

/** 내비 EV 경로 플래너가 경유지로 잡은 충전소 (NaviHelper DrivingInfo). 내비가 이미 충전을 계획했으면 우리 추천은 그 뒤로 물린다. */
data class NaviChargingStop(val name: String, val maxKw: Int, val chargerCount: Int, val targetSoc: Int, val recommended: Boolean, val chargeMin: Int, val startSoc: Int, val endSoc: Int)

/** 충전소 후보 → 1곳 추천. 규칙 기반, 점수 낮을수록 좋음. */
object ChargerPlanner {
    const val HINT_SOC = 40       // 이하면 안내 중 카드에 충전 추천 표시
    const val RADIUS_M = 500      // 목적지에서 이 거리 안만 "머무는 동안 충전" 후보

    fun pick(cands: List<ChargerCandidate>, preferredOps: List<String>, parkingLat: Double = Double.NaN, parkingLng: Double = Double.NaN): ChargerCandidate? {
        if (cands.isEmpty()) return null
        return cands.filter { it.distanceM <= RADIUS_M && it.available != 0 }
            .map { c -> c.copy(preferred = c.preferred || preferredOps.any { op -> op.isNotBlank() && (c.operator.contains(op) || op.contains(c.operator.takeIf { it.isNotBlank() } ?: "\u0000")) }) }
            .minByOrNull { c ->
                var s = c.distanceM.toDouble()                                  // 가까울수록
                if (c.preferred) s -= 150                                       // 선호 사업자 = 150 m 가치
                if (c.live) s -= 40.0 * minOf(c.available, 3)                   // 빈 충전기 1대당 40 m
                if (c.maxKw >= 50) s -= 50                                      // 급속
                if (c.source == "data" || c.source == "navi") s -= 100          // 확인된 데이터
                if (!parkingLat.isNaN()) s += 0.5 * ZoneGeo.distanceM(parkingLat, parkingLng, c.lat, c.lng)   // 주차장과 같이 있으면 좋음
                s
            }
    }

    /** 실시간 데이터(이름·좌표 다를 수 있음)를 기존 후보에 좌표로 매칭해 상태를 덮어쓴다. 매칭 안 되면 새 후보로 추가. */
    fun merge(base: List<ChargerCandidate>, live: List<ChargerCandidate>): List<ChargerCandidate> {
        val out = base.toMutableList()
        for (l in live) {
            val i = out.indexOfFirst { ZoneGeo.distanceM(it.lat, it.lng, l.lat, l.lng) < 40.0 }
            if (i >= 0) out[i] = out[i].copy(name = l.name.ifBlank { out[i].name }, operator = l.operator.ifBlank { out[i].operator }, available = l.available, total = l.total, maxKw = maxOf(l.maxKw, out[i].maxKw), fee = l.fee.ifBlank { out[i].fee }, parkingFree = l.parkingFree ?: out[i].parkingFree, source = l.source)
            else out += l
        }
        return out
    }
}
