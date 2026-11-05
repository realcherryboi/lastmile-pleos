package kr.lastmile.app.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.lastmile.app.BuildConfig
import kr.lastmile.app.domain.ChargerCandidate
import kr.lastmile.app.ui.ZoneGeo
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 2단계(키 필요): 한국환경공단 "전기자동차 충전소 정보" 공개 API (공공데이터포털 15076352).
 *  - 키: app/keys.properties 의 DATA_GO_KR_KEY (커밋 제외) → BuildConfig.DATA_GO_KR_KEY. 비어 있으면 enabled=false, 아무 호출도 하지 않는다.
 *  - 조회 단위가 시군구 코드(zscode)라 Nominatim 역지오코딩 → assets/sgg_codes.json 으로 코드를 찾고, 응답을 목적지 반경으로 거른다.
 *  - 충전소(statId) 단위로 묶어 빈 충전기 수(stat=2 충전대기)·총 대수·최대 출력·운영기관·주차료 무료를 만든다.
 *  - 요금은 API 에 없다. 사업자별 공시 단가는 FEE_BY_OPERATOR 에 손으로 채운다(비어 있으면 표시 안 함).
 * 아직 키가 없어 실행은 검증하지 못했다. 응답 필드 이름은 공공데이터포털 명세 기준.
 */
object PublicChargerSource {
    private const val ENDPOINT = "https://apis.data.go.kr/B552584/EvCharger/getChargerInfo"
    private const val UA = "LastMile/${BuildConfig.VERSION_NAME} (Pleos Connect demo)"
    val enabled: Boolean get() = BuildConfig.DATA_GO_KR_KEY.isNotBlank()

    /** 운영기관명(bnm) → 공시 요금 문구. 비어 있으면 카드에 요금이 안 나온다. 키 받은 뒤 채운다. */
    private val FEE_BY_OPERATOR: Map<String, String> = emptyMap()

    /** 시군구 코드 조회용. 설정 안 하면 Nominatim 으로 매번 찾는다. */
    @Volatile var codeLookup: (suspend (lat: Double, lng: Double) -> String?)? = null
    private val cache = HashMap<String, Pair<Long, List<ChargerCandidate>>>()   // zscode → (시각, 충전소들). 실시간성은 2분
    private const val CACHE_MS = 120_000L

    suspend fun near(lat: Double, lng: Double, radiusM: Double): List<ChargerCandidate> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val zscode = runCatching { codeLookup?.invoke(lat, lng) }.getOrNull() ?: return@withContext emptyList()
        val stations = cache[zscode]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.second ?: fetch(zscode).also { cache[zscode] = System.currentTimeMillis() to it }
        stations.map { it.copy(distanceM = ZoneGeo.distanceM(lat, lng, it.lat, it.lng).toInt()) }.filter { it.distanceM <= radiusM }.sortedBy { it.distanceM }
    }

    private fun fetch(zscode: String): List<ChargerCandidate> = runCatching {
        val url = "$ENDPOINT?serviceKey=${URLEncoder.encode(BuildConfig.DATA_GO_KR_KEY, "UTF-8")}&pageNo=1&numOfRows=9999&zscode=$zscode&dataType=JSON"
        val conn = URL(url).openConnection() as HttpURLConnection; conn.connectTimeout = 5000; conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", UA)
        val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val items = root.optJSONObject("items")?.optJSONArray("item") ?: return@runCatching emptyList()
        data class Row(val statId: String, val name: String, val lat: Double, val lng: Double, val op: String, val stat: String, val kw: Int, val parkingFree: Boolean)
        val rows = (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val la = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lo = o.optString("lng").toDoubleOrNull() ?: return@mapNotNull null
            if (o.optString("delYn") == "Y") return@mapNotNull null
            Row(o.optString("statId"), o.optString("statNm"), la, lo, o.optString("bnm").ifBlank { o.optString("busiNm") }, o.optString("stat"), o.optString("output").toIntOrNull() ?: -1, o.optString("parkingFree") == "Y")
        }
        rows.groupBy { it.statId }.values.map { g ->
            val f = g.first()
            ChargerCandidate(
                name = f.name, lat = f.lat, lng = f.lng, distanceM = 0, operator = f.op,
                available = g.count { it.stat == "2" }, total = g.size, maxKw = g.maxOf { it.kw },
                fee = FEE_BY_OPERATOR[f.op] ?: "", parkingFree = f.parkingFree, source = "public",
            )
        }
    }.getOrElse { android.util.Log.w("LastMile", "공공 충전소 API 실패: $it"); emptyList() }
}
