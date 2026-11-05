package kr.lastmile.app.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.lastmile.app.BuildConfig
import kr.lastmile.app.domain.Destination
import kr.lastmile.app.domain.ZoneSpot
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 내비가 준 임의 목적지("일반 규칙 목적지")를 키 없는 공개 데이터로 보강한다.
 *  - 주소: Nominatim 역지오코딩 (OpenStreetMap, 키 불필요, User-Agent 필수, 1회/초)
 *  - 인근 주차장·충전소·도로 방위: 이미 내려받은 OpenFreeMap 벡터 타일에서 조회 (ZoneMap.nearby) — 추가 서버 호출 없음
 * 실패해도 안내는 그대로 진행된다(보강은 선택).
 */
object OsmEnricher {
    private const val UA = "LastMile/${BuildConfig.VERSION_NAME} (Pleos Connect demo)"

    suspend fun address(lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lng&accept-language=ko&zoom=18"
            val conn = URL(url).openConnection() as HttpURLConnection; conn.connectTimeout = 5000; conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", UA)
            val a = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONObject("address") ?: return@runCatching ""
            fun f(vararg k: String) = k.firstNotNullOfOrNull { a.optString(it).takeIf { v -> v.isNotBlank() } } ?: ""
            val city = f("city", "county", "town"); val district = f("borough", "city_district", "district"); val road = f("road", "pedestrian", "neighbourhood", "quarter"); val no = f("house_number")
            listOf(f("province", "state").replace("특별시", "").replace("광역시", ""), city, district, listOf(road, no).filter { it.isNotBlank() }.joinToString(" "))
                .filter { it.isNotBlank() }.distinct().joinToString(" ")
        }.getOrElse { android.util.Log.w("LastMile", "Nominatim 실패: $it"); "" }
    }

    /** 타일에서 찾은 지점 + 주소로 목적지를 채운다. */
    fun merge(d: Destination, address: String, nearby: ZoneMap.Nearby): Destination {
        val parking = nearby.parking
        val zone = ArrayList<ZoneSpot>()
        zone += ZoneSpot("entrance", "정문", 0, "5분 이내 · 일반 도로 기준")
        zone += ZoneSpot("meeting", "만남 · 정문 앞", 0, "")
        parking?.let { zone += ZoneSpot("parking", it.name, 0, "${it.distanceM} m", it.lat, it.lng) }
        nearby.charger?.let { zone += ZoneSpot("charger", "EV 충전", 0, "${it.distanceM} m", it.lat, it.lng) }
        return d.copy(
            address = address.ifBlank { d.address },
            parkingName = parking?.name ?: d.parkingName,
            parkingDistanceM = parking?.distanceM ?: d.parkingDistanceM,
            parkingHint = parking?.let { "가장 가까운 주차장은 ${it.name}, 정문에서 ${it.distanceM}미터예요." } ?: d.parkingHint,
            parkingCharger = nearby.charger?.let { c -> parking != null && c.distanceM <= parking.distanceM + 60 } ?: d.parkingCharger,
            roadBearing = nearby.roadBearing ?: d.roadBearing,
            zone = zone,
        )
    }

    @Suppress("unused") private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
