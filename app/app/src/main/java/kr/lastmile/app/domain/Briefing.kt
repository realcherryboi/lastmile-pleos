package kr.lastmile.app.domain

/** 음성 문장 생성. 원칙: 이벤트당 한 번, 짧게, 되묻기 없음(B 톤, 11/14). 목적(정차·주차·경유)과 목적지 부근 차선에 따라 문장이 달라진다. */
object Briefing {
    const val LOW_SOC = 20

    /** 조사 (으)로: 받침 있으면 "으로", 없거나 ㄹ 받침이면 "로" */
    fun ro(word: String): String {
        val ch = word.lastOrNull { it in '가'..'힣' } ?: return "로"
        val jong = (ch - '가') % 28
        return if (jong == 0 || jong == 8) "로" else "으로"
    }

    /** 조사 은/는 */
    fun eunneun(word: String): String {
        val ch = word.lastOrNull { it in '가'..'힣' } ?: return "은"
        return if ((ch - '가') % 28 == 0) "는" else "은"
    }

    /** 조사 이/가 */
    fun iga(word: String): String {
        val ch = word.lastOrNull { it in '가'..'힣' } ?: return "이"
        return if ((ch - '가') % 28 == 0) "가" else "이"
    }

    /** 조사 (이)에요: 받침 있으면 "이에요", 없으면 "예요" */
    fun ieyo(word: String): String {
        val ch = word.lastOrNull { it in '가'..'힣' } ?: return "이에요"
        return if ((ch - '가') % 28 == 0) "예요" else "이에요"
    }

    /** "10분 300원이에요." (요금 정보가 없으면 빈 문자열) */
    private fun feeSentence(d: Destination): String = if (d.parkingFee.isNotBlank()) " ${d.parkingFee}${ieyo(d.parkingFee)}." else ""

    /**
     * 대본 원칙 (대표 결정 11/14, B 톤 "친근한 내비"): 실제 내비처럼 짧게. 해요체지만
     *  - 앱이 '나'로 말하지 않는다("안내할게요" 없음), 이유를 설명하지 않는다("주차 목적이라" 없음),
     *  - "참고로" 같은 덧붙임과 "다음에 알려 드릴게요" 같은 약속을 하지 않는다.
     *  - 남은 시간 → 사실 → 할 일. 요금은 도착 때·화면에서, 배터리는 낮을 때만 따로 한 문장.
     * 근거: 순정 내비("목적지 주변입니다. 음성 안내를 종료합니다."), TMAP·카카오 안내 문구 조사(_workspace/benchmark-narrator-2026-11-14.md).
     *   주차:      "한식당 소담까지 10분이에요. 주차는 뒤편 공영주차장이에요. 배터리 18%예요. 주차장에 충전기 있어요."
     *   정차·금지: "… 목적지 부근은 황색 실선, 정차 금지예요. 주차는 뒤편 공영주차장이에요."
     *   정차·가능: "… 목적지 부근은 황색 점선, 5분까지예요. 길어지면 지하주차장으로 가요."
     */
    fun at3km(d: Destination, remainMin: Int, evSoc: Int = -1, purpose: TripPurpose = TripPurpose.STOP): String {
        val head = "${if (purpose == TripPurpose.VIA) "경유지 " else ""}${d.name}까지 ${remainMin}분이에요."
        val valet = if (d.valet) " 발렛도 있어요." else ""
        val line = d.curb
        val parkTo = if (d.parkingName.isNotBlank()) " 주차는 ${d.parkingName}${ieyo(d.parkingName)}." else ""
        val body = when {
            purpose == TripPurpose.PARK -> (if (d.parkingName.isBlank()) " 주차장 정보는 없어요." else parkTo) + valet
            !d.rulesKnown -> " 정차 규칙 정보가 없는 곳이에요. 5분 안에만 세우시고, 주정차 단속에 주의하세요." +
                (if (d.parkingName.isNotBlank() && d.parkingDistanceM >= 0) " 가까운 주차장은 ${d.parkingName}, ${d.parkingDistanceM}미터예요." else "")
            d.curbsideAllowed -> " 목적지 부근은 ${if (line.known) "${line.label}, " else ""}${d.curbsideLimitMin}분까지예요." +
                (if (d.parkingName.isNotBlank()) " 길어지면 ${d.parkingName}${ro(d.parkingName)} 가요." else "") + valet
            else -> " 목적지 부근은 ${if (line.known) "${line.label}, " else ""}정차 금지예요." +
                (if (d.parkingName.isNotBlank()) parkTo else " 주변 주차장을 찾아 주세요.") + valet
        }
        return head + body + charger(d, evSoc)
    }

    /** EV 배터리가 낮을 때만 따로 한 문장 (Vehicle SDK EvBattery + ChargerPlanner). "참고로" 없이 사실만. */
    private fun charger(d: Destination, evSoc: Int): String {
        if (evSoc < 0 || evSoc > LOW_SOC) return ""
        val c = d.charger
        val atParking = d.parkingName.isNotBlank() && (if (c != null) c.name == d.parkingName else d.parkingCharger)
        return " 배터리 ${evSoc}%예요." + when {
            atParking && c != null && c.live -> " 주차장에 빈 충전기 ${c.available}대 있어요."
            atParking -> " 주차장에 충전기 있어요."
            c != null -> " ${chargerLine(c)}"
            else -> " 근처 충전기 정보는 없어요."
        }
    }

    /** "목적지에서 120미터 환경부 충전소에 충전기 있어요." — 아는 것만 말한다. */
    fun chargerLine(c: ChargerCandidate): String {
        val where = "목적지에서 ${c.distanceM}미터 ${c.name}"
        val status = when {
            c.live -> "에 빈 충전기 ${c.available}대 있어요."
            c.maxKw > 0 -> "에 ${c.maxKw}킬로와트 충전기 있어요."
            else -> "에 충전기 있어요."
        }
        val op = if (c.preferred && c.operator.isNotBlank()) " 설정하신 ${c.operator}예요." else ""
        return where + status + op
    }

    /** 목적지 → 주차장 입구 거리 (보강값 또는 등록 데이터의 주차장 오프셋) */
    fun parkingDistance(d: Destination): Int = if (d.parkingDistanceM >= 0) d.parkingDistanceM else d.zone.firstOrNull { it.type == "parking" && !it.absolute }?.offsetM?.let { kotlin.math.abs(it) } ?: -1

    /** "주차장 입구는 목적지 지나 30미터예요" / "… 목적지 30미터 전이에요" (정문은 목적지마다 달라 목적지 좌표가 기준점, 대표 11/13) */
    private fun parkingEntrance(d: Destination): String {
        val m = parkingDistance(d)
        val off = d.zone.firstOrNull { it.type == "parking" && !it.absolute }?.offsetM ?: 0
        return when {
            m <= 0 -> "주차장 입구로 가세요."
            off < 0 -> "주차장 입구는 목적지 ${m}미터 전이에요."
            else -> "주차장 입구는 목적지 지나 ${m}미터예요."
        }
    }

    /**
     * 1 km: 3 km 에서 말한 규칙을 되풀이하지 않고 할 일만 (대표 결정 11/8).
     * 규칙을 다시 말하는 때는 둘뿐 — 안내 중에 목적을 바꿨을 때, 등록되지 않은 목적지라 선 읽는 법을 알려 줘야 할 때.
     */
    fun at1km(d: Destination, purpose: TripPurpose = TripPurpose.STOP, purposeChanged: Boolean = false): String {
        val line = d.curb
        if (!d.rulesKnown) return "1킬로 남았어요. 목적지 부근 차선을 보세요. 황색 점선이면 5분, 실선이면 못 세워요."
        val allowed = "목적지 부근은 ${if (line.known) "${line.label}, " else ""}${d.curbsideLimitMin}분까지예요."
        if (purposeChanged) return "1킬로 남았어요. " + when {
            purpose == TripPurpose.PARK -> parkingEntrance(d)
            d.curbsideAllowed -> allowed
            else -> "목적지 부근은 ${if (line.known) "${line.label}, " else ""}정차 금지예요. ${parkingEntrance(d)}"
        }
        return "1킬로 남았어요. " + when {
            purpose == TripPurpose.PARK -> if (d.parkingName.isNotBlank()) parkingEntrance(d) else "목적지 부근에서 주차 자리를 찾아 보세요."
            d.curbsideAllowed -> "목적지 부근은 ${d.curbsideLimitMin}분까지예요."
            else -> parkingEntrance(d)
        }
    }

    /** 지난번 내린 자리 말하기: "20미터 더 가서" / "목적지 20미터 앞에서" / "목적지 바로 앞에서" / "목적지 오른쪽 20미터에서" */
    private fun dropSpoken(d: Destination, s: DropSpot): String {
        val desc = DropMemory.describe(d, s, "미터")
        return when {
            desc.startsWith("목적지 지나서 ") -> desc.removePrefix("목적지 지나서 ") + " 더 가서"
            desc.endsWith(" 전") -> desc.removeSuffix(" 전") + " 앞에서"
            else -> "${desc}에서"
        }
    }

    fun at300m(d: Destination, purpose: TripPurpose = TripPurpose.STOP): String {
        // 지난번 내린 자리(승객석 문이 열렸던 좌표)가 있으면 그걸 말한다. 주차 목적이면 주차장 입구 안내 뒤에 붙인다.
        val drop = d.drop?.let { " 지난번엔 ${dropSpoken(d, it)} 내리셨어요." } ?: ""
        if (drop.isNotEmpty() && purpose != TripPurpose.PARK) {
            // 목적지 부근이 정차 금지인 곳이면 지난 자리만 말하고 끝내지 않는다 — 주차장 입구 안내를 먼저(대표 승인 11/13)
            val toLot = d.rulesKnown && !d.curbsideAllowed && d.parkingHint.isNotBlank()
            return (if (toLot) d.parkingHint else "곧 도착이에요.") + drop
        }
        return d.parkingHint.ifBlank {
            when {
                purpose == TripPurpose.PARK && d.parkingName.isNotBlank() -> "곧 주차장 입구예요."
                !d.rulesKnown && d.parkingName.isNotBlank() -> "곧 도착이에요. ${d.parkingName}${iga(d.parkingName)} ${d.parkingDistanceM}미터 거리예요."
                else -> "곧 도착이에요."
            }
        } + drop
    }

    /** "지난번엔 20미터 더 가서 내리셨어요." (질문 답) */
    fun dropLine(d: Destination): String? = d.drop?.let { "지난번엔 ${dropSpoken(d, it)} 내리셨어요." }

    /** 목적지 100 m 안에서 브레이크로 멈춰 "여기 세우려나" 싶을 때. 사실 → 할 일. */
    fun stopping(d: Destination, purpose: TripPurpose = TripPurpose.STOP): String = when {
        purpose == TripPurpose.PARK -> if (parkingDistance(d) > 0) "주차장은 목적지 지나 ${parkingDistance(d)}미터예요." else "주차장 입구까지 가세요."
        !d.rulesKnown -> "정차 규칙 정보가 없는 곳이에요. 5분 안에만 세우시고, 주정차 단속에 주의하세요."
        // 대표 문장(11/13)의 B 톤: "여기는 황색 점선, 5분까지예요. 길어지면 지하주차장으로 가요."
        d.curbsideAllowed -> "여기는 ${if (d.curb.known) "${d.curb.label}, " else ""}${d.curbsideLimitMin}분까지예요." +
            (if (d.parkingName.isNotBlank()) " 길어지면 ${d.parkingName}${ro(d.parkingName)} 가요." else "")
        else -> "여기는 ${if (d.curb.known) "${d.curb.label}, " else ""}정차 금지예요. ${parkingEntrance(d)}"
    }

    fun arrived(d: Destination, meetingOn: Boolean, purpose: TripPurpose = TripPurpose.STOP): String {
        val notice = if (meetingOn) " 기다리는 분께 도착을 알렸어요." else ""
        return when (purpose) {
            TripPurpose.PARK -> if (d.parkingName.isNotBlank()) "${d.parkingName} 도착이에요.${feeSentence(d)}" else "도착이에요. 주차 자리를 찾아 보세요."
            TripPurpose.VIA -> if (d.curbsideAllowed) "경유지예요. ${d.curbsideLimitMin}분 안에 출발하면 돼요." else "경유지예요. 정차 금지 구역이라 바로 내리고 출발하세요."
            TripPurpose.STOP -> when {
                d.curbsideAllowed -> "도착이에요. ${d.curbsideLimitMin}분까지예요."
                d.rulesKnown && d.parkingName.isNotBlank() -> "${d.parkingName} 도착이에요.${feeSentence(d)}"   // 목적지 부근 정차 금지라 주차장으로 갔다
                else -> "도착이에요."
            }
        } + notice
    }

    /** 승객석 문이 열렸다 닫힘 (Vehicle SDK Door) → 승하차 완료. 방금 내린 자리를 기억했으면 첫 문장을 바꾼다. */
    fun boarded(d: Destination, meetingOn: Boolean, purpose: TripPurpose = TripPurpose.STOP): String {
        val remembered = d.dropNew && d.dropCount == 1
        return when {
            purpose == TripPurpose.VIA -> (if (remembered) "내린 자리, 기억해 둘게요. " else "") + "다음 목적지로 이어가요."
            meetingOn -> "탑승 확인했어요. 안전 운전하세요."
            purpose == TripPurpose.PARK -> if (remembered) "내린 자리, 기억해 둘게요." else "승하차 끝났어요."   // 주차한 뒤라 "안전 운전하세요"는 맞지 않는다
            else -> (if (remembered) "내린 자리, 기억해 둘게요." else "승하차 끝났어요.") + " 안전 운전하세요."
        }
    }

    /** 정차 허용 시간이 끝났을 때 한 번. 되묻지 않고 할 일만(대표 승인 11/13). */
    fun overstay(d: Destination): String = "${d.curbsideLimitMin}분 지났어요. " +
        if (d.parkingName.isNotBlank()) "${d.parkingName}${ro(d.parkingName)} 옮겨 주세요." else "주정차 단속에 주의하세요."

    /** 운전자 질문 → 1문장. 키워드 룰 기반, LLM 미사용. 모르면 가장 가까운 안내. */
    fun answer(d: Destination, utterance: String, purpose: TripPurpose = TripPurpose.STOP): String {
        val u = utterance.replace(" ", "")
        for ((keyword, reply) in d.qa) if (u.contains(keyword.replace(" ", ""))) return reply
        return when {
            u.contains("주차") || u.contains("어디") -> when {
                d.parkingName.isBlank() -> "주차장 정보가 없어요."
                d.parkingFee.isNotBlank() -> "${d.parkingName}, ${d.parkingFee}예요."
                else -> "${d.parkingName}, 목적지에서 ${d.parkingDistanceM}미터예요."
            }
            u.contains("지난번") || u.contains("내렸") || u.contains("저번") -> dropLine(d) ?: "이 곳은 처음이에요. 내리면 자리를 기억해 둘게요."
            u.contains("차선") || u.contains("점선") || u.contains("실선") -> if (d.curb.known) "목적지 부근은 ${d.curb.label}이에요. ${d.curb.rule}." else "차선 정보가 없어요. 황색 점선이면 5분 정차, 실선이면 금지예요."
            u.contains("세워") || u.contains("정차") || u.contains("앞") -> when {
                !d.rulesKnown -> "목적지 부근은 일반 도로 기준 5분 이내 정차예요. 주정차 단속에 주의하세요."
                d.curbsideAllowed -> "목적지 부근은 ${if (d.curb.known) "${d.curb.label}, " else ""}${d.curbsideLimitMin}분까지 정차 가능해요."
                else -> "목적지 부근은 ${if (d.curb.known) "${d.curb.label}이라 " else ""}정차 금지예요. 주차장 입구가 더 가까워요."
            }
            u.contains("얼마") || u.contains("요금") -> if (d.parkingFee.isNotBlank()) "${d.parkingFee}${ieyo(d.parkingFee)}." else "요금 정보가 없어요."
            u.contains("발렛") -> if (d.valet) "발렛 있어요." else "발렛은 없어요."
            u.contains("충전") -> d.charger?.let { chargerLine(it) } ?: if (d.parkingCharger) "${d.parkingName.ifBlank { "주차장" }}에 충전기가 있어요." else "근처 충전기 정보가 없어요."
            else -> at300m(d, purpose)
        }
    }

    fun meetingEta(d: Destination, remainMin: Int): String = "${remainMin}분 후 도착해요. ${d.meetingPoint}${ro(d.meetingPoint)} 나와 주세요."
    /** 차가 만남 지점 앞에 섰으면 "정문 앞이에요", 주차장 등 다른 곳에 섰으면 "정문 앞으로 나와 주세요" (대표 승인 11/13) */
    fun meetingArrived(d: Destination, purpose: TripPurpose = TripPurpose.STOP): String =
        if (purpose != TripPurpose.PARK && d.curbsideAllowed) "지금 도착했어요. ${d.meetingPoint}${ieyo(d.meetingPoint)}."
        else "지금 도착했어요. ${d.meetingPoint}${ro(d.meetingPoint)} 나와 주세요."
}
