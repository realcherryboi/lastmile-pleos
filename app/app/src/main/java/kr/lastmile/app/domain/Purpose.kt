package kr.lastmile.app.domain

/**
 * 이번 목적지에서 무엇을 할지. 목적지 입력 때 고르고(없으면 정차 규칙으로 기본값), 브리핑·도착 화면이 달라진다.
 *  - STOP: 잠깐 정차·픽업. 정문 앞 차선 규칙과 정차 타이머 중심.
 *  - PARK: 도착해서 주차. 정문 앞은 건너뛰고 주차장·요금·충전기로 바로 안내.
 *  - VIA: 경유. 잠깐 멈췄다 사람·짐만 내리고 다음 목적지로 이어감(정차 규칙은 STOP 과 같음).
 */
enum class TripPurpose(val label: String, val short: String) {
    STOP("잠깐 정차 · 픽업", "정차"),
    PARK("도착 후 주차", "주차"),
    VIA("경유 · 잠깐 멈춤", "경유");

    val briefStop get() = this != PARK

    companion object {
        /** 정차 금지면 주차, 아니면 정차·픽업이 기본 */
        fun defaultFor(d: Destination) = if (d.curbsideAllowed) STOP else PARK
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: STOP
    }
}

/**
 * 정문 앞 도로 가장자리 차선 (도로교통법 시행규칙 별표 6 노면표시).
 *  흰색 실선 = 주·정차 가능 / 황색 점선 = 정차 5분 가능·주차 금지 / 황색 실선 = 주·정차 금지(보조 표지의 허용 시간만 예외) / 황색 복선 = 절대 금지
 */
enum class CurbLine(val key: String, val label: String, val rule: String, val stopAllowed: Boolean, val parkAllowed: Boolean) {
    UNKNOWN("", "차선 정보 없음", "현장 차선과 표지를 확인하세요", true, false),
    WHITE_SOLID("white_solid", "흰색 실선", "주·정차 가능", true, true),
    YELLOW_DASHED("yellow_dashed", "황색 점선", "5분 이내 정차 가능 · 주차 금지", true, false),
    YELLOW_SOLID("yellow_solid", "황색 실선", "주·정차 금지 · 표지의 허용 시간만 예외", false, false),
    YELLOW_DOUBLE("yellow_double", "황색 복선", "주·정차 절대 금지", false, false);

    val known get() = this != UNKNOWN

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key && it.key.isNotBlank() } ?: UNKNOWN }
}
