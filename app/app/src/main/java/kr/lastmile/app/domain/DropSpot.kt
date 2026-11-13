package kr.lastmile.app.domain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 승하차 위치 기억 — "내린 자리".
 * 승객석 문이 열린 좌표는 그 목적지의 실제 승하차 지점이다(Vehicle SDK Door + FusedLocation). 목적지별로 기기 안에만 저장하고,
 * 다음에 같은 곳에 가면 "지난번엔 목적지 지나서 20미터에서 내리셨어요"로 제안한다. 서버·키 없음, 폰 앱은 문 신호가 없어 만들 수 없는 데이터.
 */
data class DropSpot(val lat: Double, val lng: Double, val atMs: Long, val purpose: String = "STOP")

class DropSpotStore(context: Context) {
    private val prefs = context.getSharedPreferences("lastmile_drops", Context.MODE_PRIVATE)

    fun of(destinationId: String): List<DropSpot> = runCatching {
        val arr = JSONArray(prefs.getString(destinationId, null) ?: return emptyList())
        (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); DropSpot(o.getDouble("lat"), o.getDouble("lng"), o.optLong("at"), o.optString("p", "STOP")) }
    }.getOrDefault(emptyList())

    /** 최근 MAX 건만 보관 */
    fun add(destinationId: String, spot: DropSpot): List<DropSpot> {
        val list = (of(destinationId) + spot).takeLast(MAX)
        val arr = JSONArray(); for (s in list) arr.put(JSONObject().put("lat", s.lat).put("lng", s.lng).put("at", s.atMs).put("p", s.purpose))
        prefs.edit().putString(destinationId, arr.toString()).apply()
        return list
    }

    fun clear() = prefs.edit().clear().apply()
    fun count(): Int = prefs.all.size

    companion object { const val MAX = 10 }
}

object DropMemory {
    const val NEAR_M = 150.0        // 목적지에서 이 거리 안에서 문이 열려야 "그 목적지의 승하차"로 친다
    const val LABEL = "지난번 내린 자리"

    /** 대표 지점: 3건 이상이면 메도이드(다른 지점들과 가장 가까운 것), 아니면 가장 최근 */
    fun representative(spots: List<DropSpot>): DropSpot? {
        if (spots.isEmpty()) return null
        if (spots.size < 3) return spots.last()
        return spots.minByOrNull { a -> spots.sumOf { b -> meters(a.lat, a.lng, b.lat, b.lng) } }
    }

    /** 목적지(좌표) 기준 설명 — 정문은 목적지마다 달라 "목적지 지나서 20 m" 처럼 말한다. unit = " m"(화면) / "미터"(음성). 도로 방위(roadBearing) 기준으로 지나서/전/오른쪽/왼쪽. */
    fun describe(d: Destination, s: DropSpot, unit: String = " m"): String {
        val dy = (s.lat - d.latitude) * 111_320.0
        val dx = (s.lng - d.longitude) * 111_320.0 * cos(Math.toRadians(d.latitude))
        val dist = hypot(dx, dy)
        if (dist < 12) return "목적지 바로 앞"
        val th = Math.toRadians(d.roadBearing)
        val along = dx * sin(th) + dy * cos(th)
        val across = dx * cos(th) - dy * sin(th)
        val m = ((dist / 5).roundToInt() * 5).coerceAtLeast(5)
        return when {
            abs(along) >= abs(across) -> if (along > 0) "목적지 지나서 $m$unit" else "목적지 $m$unit 전"
            else -> if (across > 0) "목적지 오른쪽 $m$unit" else "목적지 왼쪽 $m$unit"
        }
    }

    /** 목적지에 기억을 붙인다 (대표 지점 + 횟수 + 지도 마커). 없으면 그대로. */
    fun attach(d: Destination, spots: List<DropSpot>, justSaved: Boolean = false): Destination {
        val rep = representative(spots) ?: return d.copy(drop = null, dropCount = 0, dropNew = false, zone = d.zone.filter { it.type != "drop" })
        val spot = ZoneSpot("drop", LABEL, 0, "${spots.size}회", rep.lat, rep.lng)
        return d.copy(drop = rep, dropCount = spots.size, dropNew = justSaved, zone = d.zone.filter { it.type != "drop" } + spot)
    }

    fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0; val dx = (lng2 - lng1) * 111_320.0 * cos(Math.toRadians(lat1)); return hypot(dx, dy)
    }
}
