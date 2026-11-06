package kr.lastmile.app.domain

/** 음성 문장 생성. 원칙: 이벤트당 1문장, 되묻기 없음. 목적(정차·주차·경유)과 정문 앞 차선에 따라 문장이 달라진다. */
object Briefing {
    const val LOW_SOC = 20

    /** 조사 (으)로: 받침 있으면 "으로", 없거나 ㄹ 받침이면 "로" */
    fun ro(word: String): String {
        val ch = word.lastOrNull { it in '가'..'힣' } ?: return "로"
        val jong = (ch - '가') % 28
        return if (jong == 0 || jong == 8) "로" else "으로"
    }

    /** 조사 (이)에요: 받침 있으면 "이에요", 없으면 "예요" */
    fun ieyo(word: String): String {
        val ch = word.lastOrNull { it in '가'..'힣' } ?: return "이에요"
        return if ((ch - '가') % 28 == 0) "예요" else "이에요"
    }

    /** "정문 앞은 황색 점선, 5분까지 정차 가능하고" / "정문 앞은 황색 실선이라 정차 금지고" */
    private fun curbClause(d: Destination): String {
        val line = d.curb
        return when {
            d.curbsideAllowed && line.known -> "정문 앞은 ${line.label}, ${d.curbsideLimitMin}분까지 정차 가능하고"
            d.curbsideAllowed -> "정문 앞은 ${d.curbsideLimitMin}분까지 정차 가능하고"
            line.known -> "정문 앞은 ${line.label}이라 정차 금지고"
            else -> "정문 앞은 정차 금지고"
        }
    }

    fun at3km(d: Destination, remainMin: Int, evSoc: Int = -1, purpose: TripPurpose = TripPurpose.STOP): String {
        val head = "${if (purpose == TripPurpose.VIA) "경유지 " else ""}${d.name} 도착 ${remainMin}분 전이에요."
        if (purpose == TripPurpose.PARK) {
            val parking = when {
                d.parkingName.isBlank() -> " 주차장 정보가 없어서 정문 앞으로 안내할게요."
                d.parkingFee.isNotBlank() -> " ${d.parkingName}, ${d.parkingFee}${ro(d.parkingFee)} 바로 안내할게요."
                d.parkingDistanceM >= 0 -> " ${d.parkingName}, 정문에서 ${d.parkingDistanceM}미터로 바로 안내할게요."
                else -> " ${d.parkingName}${ro(d.parkingName)} 바로 안내할게요."
            }
            val valet = if (d.valet) " 발렛도 있어요." else ""
            return head + parking + valet + charger(d, evSoc)
        }
        if (!d.rulesKnown) {
            val parking = if (d.parkingName.isNotBlank()) " 가장 가까운 주차장은 ${d.parkingName}, ${d.parkingDistanceM}미터예요." else ""
            return "$head 정차 규칙 정보가 없는 곳이라 일반 도로 기준 5분 이내 정차로 안내할게요.$parking" + charger(d, evSoc)
        }
        val valet = if (d.valet) "발렛이 있어요." else "발렛은 없어요."
        val parking = if (d.parkingName.isNotBlank()) " ${d.parkingName}은 ${d.parkingFee}${ieyo(d.parkingFee)}." else ""
        return "$head ${curbClause(d)} $valet$parking" + charger(d, evSoc)
    }

    /** EV 배터리가 낮으면 "머무는 동안 충전" 추천을 한 문장 덧붙인다 (Vehicle SDK EvBattery + ChargerPlanner). */
    private fun charger(d: Destination, evSoc: Int): String {
        if (evSoc < 0 || evSoc > LOW_SOC) return ""
        val c = d.charger
        return when {
            c != null -> " 배터리가 ${evSoc}퍼센트예요. ${chargerLine(c)}"
            d.parkingCharger -> " 배터리가 ${evSoc}퍼센트예요. ${d.parkingName.ifBlank { "주차장" }}에 충전기가 있어요."
            else -> " 배터리가 ${evSoc}퍼센트인데 이곳 주차장엔 충전기 정보가 없어요."
        }
    }

    /** "정문에서 120미터 환경부 충전소에 빈 충전기 2대 있어요." — 아는 것만 말한다. */
    fun chargerLine(c: ChargerCandidate): String {
        val where = "정문에서 ${c.distanceM}미터 ${c.name}"
        val status = when {
            c.live -> "에 빈 충전기 ${c.available}대 있어요."
            c.maxKw > 0 -> "에 ${c.maxKw}킬로와트 충전기가 있어요."
            else -> "에 충전기가 있어요."
        }
        val op = if (c.preferred && c.operator.isNotBlank()) " 설정하신 ${c.operator}예요." else ""
        return where + status + op
    }

    fun at1km(d: Destination, purpose: TripPurpose = TripPurpose.STOP): String {
        val line = d.curb
        return when {
            purpose == TripPurpose.PARK -> if (d.parkingName.isNotBlank()) "1킬로 남았어요. ${d.parkingName} 입구로 바로 안내할게요." else "1킬로 남았어요. 정문 앞에 세우고 주차 자리를 보세요."
            !d.rulesKnown -> "1킬로 남았어요. 정문 앞 차선을 보세요. 황색 점선이면 5분, 실선이면 세울 수 없어요."
            d.curbsideAllowed && line.known -> "1킬로 남았어요. 정문 앞 ${line.label}, ${d.curbsideLimitMin}분까지 세울 수 있어요."
            d.curbsideAllowed -> "1킬로 남았어요. 정문 앞에 잠깐 세울 수 있어요."
            line.known -> "1킬로 남았어요. 정문 앞은 ${line.label}이라 못 세워요. 주차장으로 안내할게요."
            else -> "1킬로 남았어요. 주차장으로 바로 안내할게요."
        }
    }

    fun at300m(d: Destination, purpose: TripPurpose = TripPurpose.STOP): String {
        // 지난번 내린 자리(승객석 문이 열렸던 좌표)가 있으면 그걸 말한다 — 이 목적지에서 실제로 세웠던 곳. 주차 목적이면 주차장 입구 안내 뒤에 붙인다.
        val drop = d.drop?.let { " 지난번엔 ${DropMemory.describe(d, it, "미터")}에서 내렸어요." } ?: ""
        if (drop.isNotEmpty() && purpose != TripPurpose.PARK) return "곧 도착이에요.$drop"
        return d.parkingHint.ifBlank {
            when {
                purpose == TripPurpose.PARK && d.parkingName.isNotBlank() -> "곧 ${d.parkingName} 입구예요."
                !d.rulesKnown && d.parkingName.isNotBlank() -> "곧 도착이에요. ${d.parkingName}이 ${d.parkingDistanceM}미터 거리예요."
                else -> "곧 도착이에요."
            }
        } + drop
    }

    /** "지난번엔 정문 지나서 20미터에서 내렸어요 · 3회" */
    fun dropLine(d: Destination): String? = d.drop?.let { "지난번엔 ${DropMemory.describe(d, it, "미터")}에서 내렸어요." }

    fun stopping(d: Destination, purpose: TripPurpose = TripPurpose.STOP): String = when {
        purpose == TripPurpose.PARK -> if (d.parkingName.isNotBlank()) "주차 목적이에요. 정문 말고 ${d.parkingName} 입구로 안내할게요." else "주차 목적이에요. 여기는 잠깐만 세우세요."
        !d.rulesKnown -> "여기는 일반 도로 기준 5분까지예요. 더 기다리면 주차장으로 안내할게요."
        d.curbsideAllowed -> "여기는 ${d.curbsideLimitMin}분까지예요. 더 기다리면 주차장으로 안내할게요."
        d.curb.known -> "여기는 ${d.curb.label}, 정차 금지 구역이에요. 주차장 입구로 안내할게요."
        else -> "여기는 정차 금지 구역이에요. 주차장 입구로 안내할게요."
    }

    fun arrived(d: Destination, meetingOn: Boolean, purpose: TripPurpose = TripPurpose.STOP): String {
        val notice = if (meetingOn) "도착 알림 보냈어요. " else ""
        return notice + when (purpose) {
            TripPurpose.PARK -> if (d.parkingFee.isNotBlank()) "주차장 도착했어요. ${d.parkingFee}${ieyo(d.parkingFee)}." else "도착했어요. 주차 자리를 보세요."
            TripPurpose.VIA -> if (d.curbsideAllowed) "경유지예요. ${d.curbsideLimitMin}분 안에 출발하면 돼요." else "경유지예요. 정차 금지 구역이라 바로 내리고 출발하세요."
            TripPurpose.STOP -> if (d.parkingFee.isNotBlank()) "여기는 ${d.parkingFee}${ieyo(d.parkingFee)}." else "도착했어요."
        }
    }

    /** 승객석 문이 열렸다 닫힘 (Vehicle SDK Door) → 승하차 완료 */
    fun boarded(d: Destination, meetingOn: Boolean, purpose: TripPurpose = TripPurpose.STOP): String = when {
        purpose == TripPurpose.VIA -> "승하차 확인했어요. 다음 목적지로 이어갈게요."
        meetingOn -> "탑승 확인했어요. 타이머는 끝났어요, 안전 운전하세요."
        else -> "승하차 확인했어요. 안전 운전하세요."
    } + if (d.dropNew && d.dropCount == 1) " 이 자리는 기억해 둘게요." else ""

    fun overstay(d: Destination): String = "${d.curbsideLimitMin}분 지났어요. 주차장으로 갈까요?"

    /** 운전자 질문 → 1문장. 키워드 룰 기반, LLM 미사용. 모르면 가장 가까운 안내. */
    fun answer(d: Destination, utterance: String, purpose: TripPurpose = TripPurpose.STOP): String {
        val u = utterance.replace(" ", "")
        for ((keyword, reply) in d.qa) if (u.contains(keyword.replace(" ", ""))) return reply
        return when {
            u.contains("주차") || u.contains("어디") -> when {
                d.parkingName.isBlank() -> "주차장 정보가 없어요."
                d.parkingFee.isNotBlank() -> "${d.parkingName}, ${d.parkingFee}예요."
                else -> "${d.parkingName}, 정문에서 ${d.parkingDistanceM}미터예요."
            }
            u.contains("지난번") || u.contains("내렸") || u.contains("저번") -> dropLine(d) ?: "이 곳은 처음이에요. 내리면 자리를 기억해 둘게요."
            u.contains("차선") || u.contains("점선") || u.contains("실선") -> if (d.curb.known) "정문 앞은 ${d.curb.label}이에요. ${d.curb.rule}." else "차선 정보가 없어요. 황색 점선이면 5분 정차, 실선이면 금지예요."
            u.contains("세워") || u.contains("정차") || u.contains("앞") -> when {
                !d.rulesKnown -> "정문 앞은 일반 도로 기준 5분 이내 정차예요. 단속 표지판을 확인하세요."
                d.curbsideAllowed -> "정문 앞은 ${if (d.curb.known) "${d.curb.label}, " else ""}${d.curbsideLimitMin}분까지 정차 가능해요."
                else -> "정문 앞은 ${if (d.curb.known) "${d.curb.label}이라 " else ""}정차 금지예요. 주차장 입구가 더 가까워요."
            }
            u.contains("얼마") || u.contains("요금") -> if (d.parkingFee.isNotBlank()) "${d.parkingFee}${ieyo(d.parkingFee)}." else "요금 정보가 없어요."
            u.contains("발렛") -> if (d.valet) "발렛 있어요." else "발렛은 없어요."
            u.contains("충전") -> d.charger?.let { chargerLine(it) } ?: if (d.parkingCharger) "${d.parkingName.ifBlank { "주차장" }}에 충전기가 있어요." else "근처 충전기 정보가 없어요."
            else -> at300m(d, purpose)
        }
    }

    fun meetingEta(d: Destination, remainMin: Int): String = "${remainMin}분 후 도착해요. ${d.meetingPoint}으로 나와 주세요."
    fun meetingArrived(d: Destination): String = "지금 도착했어요. ${d.meetingPoint}이에요."
}
