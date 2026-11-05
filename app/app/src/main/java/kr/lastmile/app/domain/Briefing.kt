package kr.lastmile.app.domain

/** 음성 문장 생성. 원칙: 이벤트당 1문장, 되묻기 없음. */
object Briefing {

    fun at3km(d: Destination, remainMin: Int): String {
        val curb = if (d.curbsideAllowed) "정문 앞은 ${d.curbsideLimitMin}분까지 정차 가능하고" else "정문 앞은 정차 금지고"
        val valet = if (d.valet) "발렛이 있어요." else "발렛은 없어요."
        val parking = if (d.parkingName.isNotBlank()) " ${d.parkingName}은 ${d.parkingFee}예요." else ""
        return "${d.name} 도착 ${remainMin}분 전이에요. $curb $valet$parking"
    }

    fun at1km(d: Destination): String =
        if (d.curbsideAllowed) "1킬로 남았어요. 정문 앞에 잠깐 세울 수 있어요."
        else "1킬로 남았어요. 주차장으로 바로 안내할게요."

    fun at300m(d: Destination): String = d.parkingHint.ifBlank { "곧 도착이에요." }

    fun stopping(d: Destination): String =
        if (d.curbsideAllowed) "여기는 ${d.curbsideLimitMin}분까지예요. 더 기다리면 주차장으로 안내할게요."
        else "여기는 정차 금지 구역이에요. 주차장 입구로 안내할게요."

    fun arrived(d: Destination, meetingOn: Boolean): String {
        val notice = if (meetingOn) "도착 알림 보냈어요. " else ""
        val fee = if (d.parkingFee.isNotBlank()) "여기는 ${d.parkingFee}예요." else "도착했어요."
        return notice + fee
    }

    fun overstay(d: Destination): String = "${d.curbsideLimitMin}분 지났어요. 주차장으로 갈까요?"

    /** 운전자 질문 → 1문장. 키워드 룰 기반, LLM 미사용. 모르면 가장 가까운 안내. */
    fun answer(d: Destination, utterance: String): String {
        val u = utterance.replace(" ", "")
        for ((keyword, reply) in d.qa) if (u.contains(keyword.replace(" ", ""))) return reply
        return when {
            u.contains("주차") || u.contains("어디") -> if (d.parkingName.isNotBlank()) "${d.parkingName}, ${d.parkingFee}예요." else "주차장 정보가 없어요."
            u.contains("세워") || u.contains("정차") || u.contains("앞") ->
                if (d.curbsideAllowed) "정문 앞은 ${d.curbsideLimitMin}분까지 정차 가능해요." else "정문 앞은 정차 금지예요. 주차장 입구가 더 가까워요."
            u.contains("얼마") || u.contains("요금") -> if (d.parkingFee.isNotBlank()) "${d.parkingFee}예요." else "요금 정보가 없어요."
            u.contains("발렛") -> if (d.valet) "발렛 있어요." else "발렛은 없어요."
            else -> at300m(d)
        }
    }

    fun meetingEta(d: Destination, remainMin: Int): String = "${remainMin}분 후 도착해요. ${d.meetingPoint}으로 나와 주세요."
    fun meetingArrived(d: Destination): String = "지금 도착했어요. ${d.meetingPoint}이에요."
}
