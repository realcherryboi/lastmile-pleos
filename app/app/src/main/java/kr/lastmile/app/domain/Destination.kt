package kr.lastmile.app.domain

import android.content.Context
import org.json.JSONObject

/**
 * 목적지 1건.
 *  - source = "data": assets/destinations.json 의 등록 장소 (정차 규칙·주차·만남 지점이 확인된 데이터)
 *  - source = "navi": 내비가 알려 준 임의 목적지. 규칙 데이터가 없으므로 일반 도로 기준(5분 이내 정차)으로 안내하고,
 *    주소·인근 주차장·도로 방위는 지도 타일(OpenStreetMap)에서 보강한다(OsmEnricher).
 */
data class Destination(
    val id: String,
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val arrivalRadiusM: Float,
    val curbsideAllowed: Boolean,
    val curbsideLimitMin: Int,          // 0 = 정차 금지
    val valet: Boolean,
    val parkingName: String,
    val parkingFee: String,
    val parkingHint: String,            // 300m 전 보강 안내
    val meetingPoint: String,           // 마중 메시지에 들어갈 만남 지점
    val qa: Map<String, String>,        // 키워드 → 답변
    val address: String = "",
    val zone: List<ZoneSpot> = emptyList(),   // 도착 구역 지점 (정문 기준 m 오프셋 또는 절대 좌표)
    val roadBearing: Double = 90.0,           // 정문 앞 도로 방위(도). 지점 좌표 = 정문 + 오프셋 × 방위
    val source: String = "data",              // data | navi
    val rulesKnown: Boolean = true,           // false = 정차 규칙 데이터 없음 → 일반 도로 기준 안내
    val parkingCharger: Boolean = false,      // 주차장에 EV 충전기
    val parkingDistanceM: Int = -1,           // 정문 → 주차장 입구 거리 (navi 목적지 보강값)
    val charger: ChargerCandidate? = null,    // 머무는 동안 충전 추천 1곳 (ChargerPlanner)
    val chargers: List<ChargerCandidate> = emptyList(),   // 반경 내 후보 전체 (실시간 데이터 병합용)
    val curbLine: String = "",                // 정문 앞 차선 (CurbLine.key: white_solid / yellow_dashed / yellow_solid / yellow_double)
    val drop: DropSpot? = null,               // 지난번 내린 자리 (승객석 문이 열린 좌표, 기기 내 기억 · DropMemory)
    val dropCount: Int = 0,                   // 기억된 횟수
    val dropNew: Boolean = false,             // 이번 도착에서 방금 기억함 (승하차 문장에 한마디 덧붙임)
) {
    val isGeneric get() = source == "navi"
    val curb: CurbLine get() = CurbLine.of(curbLine)

    companion object {
        /** 내비 목적지 → 일반 규칙 목적지. id 는 좌표 기반이라 같은 곳은 같은 id (완료 목적지 무시 로직과 호환). */
        fun generic(name: String, lat: Double, lng: Double): Destination = Destination(
            id = "navi:${"%.4f".format(lat)},${"%.4f".format(lng)}",
            name = name.ifBlank { "내비 목적지" }, category = "내비 목적지",
            latitude = lat, longitude = lng, arrivalRadiusM = 80f,
            curbsideAllowed = true, curbsideLimitMin = 5,     // 도로교통법상 정차 = 5분 이내. 단속 구역 여부는 현장 표지 기준
            valet = false, parkingName = "", parkingFee = "", parkingHint = "", meetingPoint = "정문 앞", qa = emptyMap(),
            zone = listOf(ZoneSpot("entrance", "정문", 0, "5분 이내 · 일반 도로 기준"), ZoneSpot("meeting", "만남 · 정문 앞", 0, "")),
            source = "navi", rulesKnown = false,
        )
    }
}

/** 도착 구역 지점. type: entrance / parking / meeting / noentry / charger. lat/lng 가 있으면 오프셋 대신 절대 좌표. */
data class ZoneSpot(val type: String, val label: String, val offsetM: Int, val note: String, val lat: Double = Double.NaN, val lng: Double = Double.NaN) {
    val absolute get() = !lat.isNaN() && !lng.isNaN()
}

class DestinationRepository(private val context: Context) {
    private var cache: List<Destination>? = null

    fun all(): List<Destination> = cache ?: load().also { cache = it }

    fun byId(id: String): Destination? = all().firstOrNull { it.id == id }

    private fun load(): List<Destination> {
        val text = context.assets.open("destinations.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("destinations")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val qaObj = o.optJSONObject("qa") ?: JSONObject()
            Destination(
                id = o.getString("id"),
                name = o.getString("name"),
                category = o.optString("category"),
                latitude = o.getDouble("lat"),
                longitude = o.getDouble("lng"),
                arrivalRadiusM = o.optDouble("arrivalRadiusM", 80.0).toFloat(),
                curbsideAllowed = o.optBoolean("curbsideAllowed", false),
                curbsideLimitMin = o.optInt("curbsideLimitMin", 0),
                valet = o.optBoolean("valet", false),
                parkingName = o.optString("parkingName"),
                curbLine = o.optString("curbLine"),
                parkingFee = o.optString("parkingFee"),
                parkingHint = o.optString("parkingHint"),
                meetingPoint = o.optString("meetingPoint"),
                qa = qaObj.keys().asSequence().associateWith { qaObj.getString(it) },
                address = o.optString("address"),
                roadBearing = o.optDouble("roadBearing", 90.0),
                zone = o.optJSONArray("zone")?.let { z -> (0 until z.length()).map { k -> val s = z.getJSONObject(k); ZoneSpot(s.optString("type"), s.optString("label"), s.optInt("offsetM"), s.optString("note")) } } ?: emptyList(),
                parkingCharger = o.optBoolean("parkingCharger", false),
            )
        }
    }
}
