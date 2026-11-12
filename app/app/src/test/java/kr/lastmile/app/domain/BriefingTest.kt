package kr.lastmile.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 안내 문장 대본 검증 (11/12 대본 개편: 이유 → 할 일 → 참고 순서, 배터리는 맨 끝 "참고로"). 실행하면 목적별 대본을 표준 출력으로 찍는다. */
class BriefingTest {
    private val sodam = Destination(
        id = "sodam", name = "한식당 소담", category = "restaurant", latitude = 37.4, longitude = 127.1, arrivalRadiusM = 80f,
        curbsideAllowed = false, curbsideLimitMin = 0, valet = false, parkingName = "뒤편 공영주차장", parkingFee = "10분 300원",
        parkingHint = "잠시 후 우회전하면 바로 주차장 입구예요.", meetingPoint = "정문 앞", qa = emptyMap(),
        curbLine = "yellow_solid", parkingCharger = true,
        charger = ChargerCandidate("뒤편 공영주차장", 37.4, 127.1, 29, source = "data"),
    )
    private val hospital = sodam.copy(
        id = "hospital", name = "성남시립병원", curbsideAllowed = true, curbsideLimitMin = 5, parkingName = "지하주차장",
        parkingFee = "30분 무료, 이후 10분 500원", curbLine = "yellow_dashed", parkingCharger = false,
        charger = ChargerCandidate("시청 공영주차장 충전소", 37.4, 127.1, 120, source = "tile"),
    )

    @Test fun demoScriptParkPurpose() {
        val brief = Briefing.at3km(sodam, 12, evSoc = 18, purpose = TripPurpose.PARK)
        assertEquals(
            "한식당 소담까지 12분 남았어요. 주차 목적이라 정문 대신 뒤편 공영주차장으로 안내할게요. 요금은 10분 300원이에요. " +
                "참고로 배터리가 18퍼센트예요. 가시는 주차장에 충전기가 있어서, 세워 두는 동안 충전할 수 있어요.", brief)
        assertTrue(brief.indexOf("배터리") > brief.indexOf("안내할게요"))   // 배터리는 할 일 뒤
        assertEquals("주차 목적이라 여기 말고 뒤편 공영주차장 입구까지 가 주세요.", Briefing.stopping(sodam, TripPurpose.PARK))
        assertEquals("뒤편 공영주차장에 도착했어요. 요금은 10분 300원이에요.", Briefing.arrived(sodam, false, TripPurpose.PARK))
        val boarded = Briefing.boarded(sodam.copy(dropNew = true, dropCount = 1), false, TripPurpose.PARK)
        assertEquals("승하차 확인했어요. 내리신 자리는 기억해 뒀다가 다음에 알려 드릴게요.", boarded)
        assertFalse(boarded.contains("안전 운전"))
    }

    @Test fun stopPurposeNoStoppingCurb() {
        assertEquals(
            "한식당 소담까지 12분 남았어요. 정문 앞은 황색 실선이라 세울 수 없어요. 대신 뒤편 공영주차장으로 안내할게요. 요금은 10분 300원이에요.",
            Briefing.at3km(sodam, 12, purpose = TripPurpose.STOP))
        assertEquals("뒤편 공영주차장에 도착했어요. 요금은 10분 300원이에요. 기다리는 분께 도착을 알렸어요.", Briefing.arrived(sodam, true, TripPurpose.STOP))
        assertEquals("도착했어요. 5분까지 세울 수 있어요.", Briefing.arrived(hospital, false, TripPurpose.STOP))
    }

    @Test fun stopPurposeAllowedCurbWithFarCharger() {
        val s = Briefing.at3km(hospital, 8, evSoc = 15, purpose = TripPurpose.STOP)
        assertTrue(s, s.startsWith("성남시립병원까지 8분 남았어요. 정문 앞은 황색 점선이라 5분까지 세울 수 있어요."))
        assertTrue(s, s.endsWith("참고로 배터리가 15퍼센트예요. 정문에서 120미터 시청 공영주차장 충전소에 충전기가 있어요."))
    }

    @Test fun printScript() {
        for (p in TripPurpose.values()) for (d in listOf(sodam, hospital)) {
            println("[${d.name} · $p]")
            println("  출발 " + Briefing.at3km(d, 12, 18, p))
            println("  1km " + Briefing.at1km(d, p))
            println("  300m " + Briefing.at300m(d, p))
            println("  정차 " + Briefing.stopping(d, p))
            println("  도착 " + Briefing.arrived(d, false, p))
            println("  승하차 " + Briefing.boarded(d.copy(dropNew = true, dropCount = 1), false, p))
        }
    }
}
