package kr.lastmile.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 안내 문장 대본 검증 (11/14 B 톤 "친근한 내비": 해요체지만 짧게, '나'로 말하지 않고, 이유·"참고로"·약속 없음).
 * 실행하면 목적별 대본을 표준 출력으로 찍는다.
 */
class BriefingTest {
    private val sodam = Destination(
        id = "sodam", name = "한식당 소담", category = "restaurant", latitude = 37.4, longitude = 127.1, arrivalRadiusM = 80f,
        curbsideAllowed = false, curbsideLimitMin = 0, valet = false, parkingName = "뒤편 공영주차장", parkingFee = "10분 300원",
        parkingHint = "우회전하면 바로 주차장이에요.", meetingPoint = "정문 앞", qa = emptyMap(),
        curbLine = "yellow_solid", parkingCharger = true, parkingDistanceM = 30,
        charger = ChargerCandidate("뒤편 공영주차장", 37.4, 127.1, 29, source = "data"),
    )
    private val hospital = sodam.copy(
        id = "hospital", name = "성남시립병원", curbsideAllowed = true, curbsideLimitMin = 5, parkingName = "지하주차장",
        parkingFee = "30분 무료, 이후 10분 500원", curbLine = "yellow_dashed", parkingCharger = false,
        charger = ChargerCandidate("시청 공영주차장 충전소", 37.4, 127.1, 120, source = "tile"),
    )
    private val aiTells = listOf("안내할게요", "참고로", "알려 드릴게요", "목적이라", "있어서")

    private fun noAiTells(s: String) = aiTells.forEach { assertFalse("\"$it\" in: $s", s.contains(it)) }

    @Test fun demoScriptParkPurpose() {
        val brief = Briefing.at3km(sodam, 10, evSoc = 18, purpose = TripPurpose.PARK)
        assertEquals("한식당 소담까지 10분이에요. 주차는 뒤편 공영주차장이에요. 배터리 18%예요. 주차장에 충전기 있어요.", brief)
        assertEquals("1킬로 남았어요. 주차장 입구는 목적지 지나 30미터예요.", Briefing.at1km(sodam, TripPurpose.PARK))
        assertEquals("우회전하면 바로 주차장이에요.", Briefing.at300m(sodam, TripPurpose.PARK))
        assertEquals("주차장은 목적지 지나 30미터예요.", Briefing.stopping(sodam, TripPurpose.PARK))
        assertEquals("뒤편 공영주차장 도착이에요. 10분 300원이에요.", Briefing.arrived(sodam, false, TripPurpose.PARK))
        val boarded = Briefing.boarded(sodam.copy(dropNew = true, dropCount = 1), false, TripPurpose.PARK)
        assertEquals("내린 자리, 기억해 둘게요.", boarded)
        assertEquals("승하차 끝났어요.", Briefing.boarded(sodam, false, TripPurpose.PARK))
        listOf(brief, boarded, Briefing.stopping(sodam, TripPurpose.PARK)).forEach(::noAiTells)
    }

    @Test fun revisitSaysHowFarPast() {
        // 목적지 북쪽 약 20 m (도로 방위 기본 90°라 "옆"이 아닌 앞뒤 판정은 좌표에 따라 다르다 — 여기선 문장 모양만 본다)
        val revisit = sodam.copy(drop = DropSpot(37.4, 127.10022, 0L), dropCount = 1)
        val s = Briefing.at300m(revisit, TripPurpose.PARK)
        assertTrue(s, s.startsWith("우회전하면 바로 주차장이에요. 지난번엔 "))
        assertTrue(s, s.endsWith(" 내리셨어요."))
        assertFalse(s, s.contains("에서에서"))
    }

    @Test fun stopPurposeNoStoppingCurb() {
        assertEquals("한식당 소담까지 12분이에요. 목적지 부근은 황색 실선, 정차 금지예요. 주차는 뒤편 공영주차장이에요.",
            Briefing.at3km(sodam, 12, purpose = TripPurpose.STOP))
        assertEquals("여기는 황색 실선, 정차 금지예요. 주차장 입구는 목적지 지나 30미터예요.", Briefing.stopping(sodam, TripPurpose.STOP))
        assertEquals("뒤편 공영주차장 도착이에요. 10분 300원이에요. 기다리는 분께 도착을 알렸어요.", Briefing.arrived(sodam, true, TripPurpose.STOP))
        assertEquals("지금 도착했어요. 정문 앞으로 나와 주세요.", Briefing.meetingArrived(sodam, TripPurpose.STOP))   // 주차장에 섰다
        assertEquals("승하차 끝났어요. 안전 운전하세요.", Briefing.boarded(sodam, false, TripPurpose.STOP))
    }

    @Test fun stopPurposeAllowedCurb() {
        val s = Briefing.at3km(hospital, 8, evSoc = 15, purpose = TripPurpose.STOP)
        assertEquals("성남시립병원까지 8분이에요. 목적지 부근은 황색 점선, 5분까지예요. 길어지면 지하주차장으로 가요. " +
            "배터리 15%예요. 목적지에서 120미터 시청 공영주차장 충전소에 충전기 있어요.", s)
        assertEquals("여기는 황색 점선, 5분까지예요. 길어지면 지하주차장으로 가요.", Briefing.stopping(hospital, TripPurpose.STOP))
        assertEquals("도착이에요. 5분까지예요.", Briefing.arrived(hospital, false, TripPurpose.STOP))
        assertEquals("5분 지났어요. 지하주차장으로 옮겨 주세요.", Briefing.overstay(hospital))
        assertEquals("지금 도착했어요. 정문 앞이에요.", Briefing.meetingArrived(hospital, TripPurpose.STOP))   // 만남 지점 앞에 섰다
        noAiTells(s)
    }

    @Test fun unknownRulesSayCaution() {
        val generic = sodam.copy(id = "navi", rulesKnown = false, curbLine = "")
        assertTrue(Briefing.at3km(generic, 7).contains("주정차 단속에 주의하세요."))
        assertTrue(Briefing.stopping(generic).contains("주정차 단속에 주의하세요."))
    }

    @Test fun revisitStopKeepsParkingHint() {
        val revisit = sodam.copy(drop = DropSpot(37.4, 127.1002, 0L), dropCount = 1)
        val s = Briefing.at300m(revisit, TripPurpose.STOP)
        assertTrue(s, s.startsWith("우회전하면 바로 주차장이에요. 지난번엔 "))
        assertTrue(s, s.endsWith("내리셨어요."))
    }

    @Test fun printScript() {
        for (p in TripPurpose.values()) for (d in listOf(sodam, hospital)) {
            println("[${d.name} · $p]")
            println("  출발 " + Briefing.at3km(d, 12, 18, p))
            println("  1km " + Briefing.at1km(d, p))
            println("  300m " + Briefing.at300m(d, p))
            println("  재방문300m " + Briefing.at300m(d.copy(drop = DropSpot(37.4, 127.10022, 0L), dropCount = 2), p))
            println("  정차 " + Briefing.stopping(d, p))
            println("  도착 " + Briefing.arrived(d, false, p))
            println("  승하차 " + Briefing.boarded(d.copy(dropNew = true, dropCount = 1), false, p))
            println("  초과 " + Briefing.overstay(d))
        }
    }
}
