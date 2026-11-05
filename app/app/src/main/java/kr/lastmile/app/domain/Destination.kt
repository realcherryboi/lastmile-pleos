package kr.lastmile.app.domain

import android.content.Context
import org.json.JSONObject

/** 데모용 목적지 1건. 실데이터 API 연동 전까지 assets/destinations.json 에서 읽는다. */
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
)

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
                parkingFee = o.optString("parkingFee"),
                parkingHint = o.optString("parkingHint"),
                meetingPoint = o.optString("meetingPoint"),
                qa = qaObj.keys().asSequence().associateWith { qaObj.getString(it) },
            )
        }
    }
}
