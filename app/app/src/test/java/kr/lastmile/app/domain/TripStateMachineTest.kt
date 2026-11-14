package kr.lastmile.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 정차 의도 판정 (11/13 대표: 방향지시등 제외 — 300 m 안 우회전마다 안내가 떠 번거롭다). 브레이크 3초 유지도 목적지 100 m 안에서만. */
class TripStateMachineTest {
    private val sodam = Destination(
        id = "sodam", name = "한식당 소담", category = "restaurant", latitude = 37.4, longitude = 127.1, arrivalRadiusM = 80f,
        curbsideAllowed = false, curbsideLimitMin = 0, valet = false, parkingName = "뒤편 공영주차장", parkingFee = "10분 300원",
        parkingHint = "잠시 후 우회전하면 바로 주차장 입구예요.", meetingPoint = "정문 앞", qa = emptyMap(), curbLine = "yellow_solid", parkingDistanceM = 30,
    )

    private fun drive(vararg meters: Int): TripStateMachine {
        val m = TripStateMachine(); m.purpose = TripPurpose.STOP
        m.handle(TripEvent.DestinationSet(sodam))
        for (x in meters) m.handle(TripEvent.RemainDistance(x, x / 10))
        return m
    }
    private fun speaks(a: List<TripAction>) = a.filterIsInstance<TripAction.Speak>()

    @Test fun brakeAtRedLightWithin1kmIsIgnored() {
        val m = drive(2600, 900)
        assertEquals(TripState.BRIEF_1KM, m.state)
        assertTrue(speaks(m.handle(TripEvent.BrakeHeld(true))).isEmpty())
        assertEquals(TripState.BRIEF_1KM, m.state)
    }

    @Test fun brakeAt250mIsStillIgnored() {
        val m = drive(2600, 900, 250)
        assertEquals(TripState.BRIEF_300M, m.state)
        assertTrue(speaks(m.handle(TripEvent.BrakeHeld(true))).isEmpty())
    }

    @Test fun brakeWithin100mMeansStopping() {
        val m = drive(2600, 900, 250, 80)
        val s = speaks(m.handle(TripEvent.BrakeHeld(true)))
        assertEquals(TripState.STOPPING, m.state)
        assertEquals("여기는 황색 실선, 정차 금지예요. 주차장 입구는 목적지 지나 30미터예요.", s.single().text)
    }

    @Test fun curbTimeUpSpeaksOnceOnlyWhenStoppedAtCurb() {
        val hospital = sodam.copy(id = "h", curbsideAllowed = true, curbsideLimitMin = 5, parkingName = "지하주차장", curbLine = "yellow_dashed")
        val m = TripStateMachine(); m.purpose = TripPurpose.STOP
        m.handle(TripEvent.DestinationSet(hospital)); m.handle(TripEvent.RemainDistance(2600, 260))
        m.handle(TripEvent.NaviArrived); m.handle(TripEvent.Gear(GearPosition.P))
        assertEquals(TripState.ARRIVED, m.state)
        assertEquals("5분 지났어요. 지하주차장으로 옮겨 주세요.", speaks(m.handle(TripEvent.CurbTimeUp)).single().text)
        assertTrue(speaks(m.handle(TripEvent.CurbTimeUp)).isEmpty())
    }
}
