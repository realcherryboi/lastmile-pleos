package kr.lastmile.app.domain

/** 음성 문장 생성. 원칙: 이벤트당 1문장, 되묻기 없음. */
object Briefing {
    const val LOW_SOC = 20

    fun at3km(d: Destination, remainMin: Int, evSoc: Int = -1): String {
        if (!d.rulesKnown) {
            val parking = if (d.parkingName.isNotBlank()) " 가장 가까운 주차장은 ${d.parkingName}, ${d.parkingDistanceM}미터예요." else ""
            return "${d.name} 도착 ${remainMin}분 전이에요. 정차 규칙 정보가 없는 곳이라 일반 도로 기준 5분 이내 정차로 안내할게요.$parking" + charger(d, evSoc)
        }
        val curb = if (d.curbsideAllowed) "정문 앞은 ${d.curbsideLimitMin}분까지 정차 가능하고" else "정문 앞은 정차 금지고"
        val valet = if (d.valet) "발렛이 있어요." else "발렛은 없어요."
        val parking = if (d.parkingName.isNotBlank()) " ${d.parkingName}은 ${d.parkingFee}예요." else ""
        return "${d.name} 도착 ${remainMin}분 전이에요. $curb $valet$parking" + charger(d, evSoc)
    }

    /** EV 배터리가 낮으면 충전기 유무를 한 문장 덧붙인다 (Vehicle SDK EvBattery). */
    private fun charger(d: Destination, evSoc: Int): String = when {
        evSoc < 0 || evSoc > LOW_SOC -> ""
        d.parkingCharger -> " 배터리가 ${evSoc}퍼센트예요. ${d.parkingName.ifBlank { "주차장" }}에 충전기가 있어요."
        else -> " 배터리가 ${evSoc}퍼센트인데 이곳 주차장엔 충전기 정보가 없어요."
    }

    fun at1km(d: Destination): String = when {
        !d.rulesKnown -> "1킬로 남았어요. 정문 앞에 5분 이내로 세울 수 있어요. 단속 표지판을 확인하세요."
        d.curbsideAllowed -> "1킬로 남았어요. 정문 앞에 잠깐 세울 수 있어요."
        else -> "1킬로 남았어요. 주차장으로 바로 안내할게요."
    }

    fun at300m(d: Destination): String = d.parkingHint.ifBlank {
        if (!d.rulesKnown && d.parkingName.isNotBlank()) "곧 도착이에요. ${d.parkingName}이 ${d.parkingDistanceM}미터 거리예요." else "곧 도착이에요."
    }

    fun stopping(d: Destination): String = when {
        !d.rulesKnown -> "여기는 일반 도로 기준 5분까지예요. 더 기다리면 주차장으로 안내할게요."
        d.curbsideAllowed -> "여기는 ${d.curbsideLimitMin}분까지예요. 더 기다리면 주차장으로 안내할게요."
        else -> "여기는 정차 금지 구역이에요. 주차장 입구로 안내할게요."
    }

    fun arrived(d: Destination, meetingOn: Boolean): String {
        val notice = if (meetingOn) "도착 알림 보냈어요. " else ""
        val fee = if (d.parkingFee.isNotBlank()) "여기는 ${d.parkingFee}예요." else "도착했어요."
        return notice + fee
    }

    /** 승객석 문이 열렸다 닫힘 (Vehicle SDK Door) → 승하차 완료 */
    fun boarded(d: Destination, meetingOn: Boolean): String =
        if (meetingOn) "탑승 확인했어요. 타이머는 끝났어요, 안전 운전하세요." else "승하차 확인했어요. 안전 운전하세요."

    fun overstay(d: Destination): String = "${d.curbsideLimitMin}분 지났어요. 주차장으로 갈까요?"

    /** 운전자 질문 → 1문장. 키워드 룰 기반, LLM 미사용. 모르면 가장 가까운 안내. */
    fun answer(d: Destination, utterance: String): String {
        val u = utterance.replace(" ", "")
        for ((keyword, reply) in d.qa) if (u.contains(keyword.replace(" ", ""))) return reply
        return when {
            u.contains("주차") || u.contains("어디") -> when {
                d.parkingName.isBlank() -> "주차장 정보가 없어요."
                d.parkingFee.isNotBlank() -> "${d.parkingName}, ${d.parkingFee}예요."
                else -> "${d.parkingName}, 정문에서 ${d.parkingDistanceM}미터예요."
            }
            u.contains("세워") || u.contains("정차") || u.contains("앞") -> when {
                !d.rulesKnown -> "정문 앞은 일반 도로 기준 5분 이내 정차예요. 단속 표지판을 확인하세요."
                d.curbsideAllowed -> "정문 앞은 ${d.curbsideLimitMin}분까지 정차 가능해요."
                else -> "정문 앞은 정차 금지예요. 주차장 입구가 더 가까워요."
            }
            u.contains("얼마") || u.contains("요금") -> if (d.parkingFee.isNotBlank()) "${d.parkingFee}예요." else "요금 정보가 없어요."
            u.contains("발렛") -> if (d.valet) "발렛 있어요." else "발렛은 없어요."
            u.contains("충전") -> if (d.parkingCharger) "${d.parkingName.ifBlank { "주차장" }}에 충전기가 있어요." else "충전기 정보가 없어요."
            else -> at300m(d)
        }
    }

    fun meetingEta(d: Destination, remainMin: Int): String = "${remainMin}분 후 도착해요. ${d.meetingPoint}으로 나와 주세요."
    fun meetingArrived(d: Destination): String = "지금 도착했어요. ${d.meetingPoint}이에요."
}
