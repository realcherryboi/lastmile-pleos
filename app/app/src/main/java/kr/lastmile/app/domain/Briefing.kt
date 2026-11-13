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

    /** " 요금은 10분 300원이에요." (요금 정보가 없으면 빈 문자열) */
    private fun feeLine(d: Destination): String = if (d.parkingFee.isNotBlank()) " 요금은 ${d.parkingFee}${ieyo(d.parkingFee)}." else ""

    /**
     * 출발·3 km 브리핑. 실제 내비 안내처럼 "남은 거리·시간 → 할 일" 순서로 짧게 말하되, 할 일 앞에 이유(목적·정문 앞 차선)를 붙인다.
     * 배터리는 주행 판단과 무관한 참고 정보라 맨 끝에 "참고로"로 붙인다 (대표 피드백 11/12).
     *   주차: "한식당 소담까지 12분 남았어요. 주차 목적이라 정문 대신 뒤편 공영주차장으로 안내할게요. 요금은 10분 300원이에요. 참고로 …"
     *   정차·금지: "… 정문 앞은 황색 실선이라 정차 금지고 뒤편 공영주차장은 10분 300원이에요." (대표 문장 11/13)
     *   정차·가능: "… 정문 앞은 황색 점선이라 5분까지 세울 수 있어요. 오래 기다리시면 지하주차장이 있어요. 요금은 30분 무료예요."
     */
    fun at3km(d: Destination, remainMin: Int, evSoc: Int = -1, purpose: TripPurpose = TripPurpose.STOP): String {
        val head = "${if (purpose == TripPurpose.VIA) "경유지 " else ""}${d.name}까지 ${remainMin}분 남았어요."
        val valet = if (d.valet) " 발렛도 있어요." else ""
        val line = d.curb
        val body = when {
            purpose == TripPurpose.PARK -> if (d.parkingName.isBlank()) " 주차장 정보가 없어서 정문 앞까지 안내할게요."
                else " 주차 목적이라 정문 대신 ${d.parkingName}${ro(d.parkingName)} 안내할게요.${feeLine(d)}$valet"
            !d.rulesKnown -> " 처음 가는 곳이라 정차 규칙 정보가 없어요. 일반 도로 기준으로 5분 안에만 세우시고, 주정차 단속에 주의하세요." +
                (if (d.parkingName.isNotBlank() && d.parkingDistanceM >= 0) " 가까운 주차장은 ${d.parkingName}, ${d.parkingDistanceM}미터예요." else "")
            d.curbsideAllowed -> " 정문 앞은 ${if (line.known) "${line.label}이라 " else ""}${d.curbsideLimitMin}분까지 세울 수 있어요." +
                (if (d.parkingName.isNotBlank()) " 오래 기다리시면 ${d.parkingName}${iga(d.parkingName)} 있어요.${feeLine(d)}" else "") + valet
            // 대표 문장(11/13): "1번출구 앞은 황색 실선이라 정차 금지고 뒤편 공영주차장은 10분 300원, 5분 회차 무료예요."
            d.parkingName.isNotBlank() -> " 정문 앞은 ${if (line.known) "${line.label}이라 " else ""}정차 금지고 " +
                (if (d.parkingFee.isNotBlank()) "${d.parkingName}${eunneun(d.parkingName)} ${d.parkingFee}${ieyo(d.parkingFee)}." else "${d.parkingName}${ro(d.parkingName)} 안내할게요.") + valet
            else -> " 정문 앞은 ${if (line.known) "${line.label}이라 " else "정차 금지라 "}세울 수 없어요. 주변 주차장을 찾아 주세요.$valet"
        }
        return head + body + charger(d, evSoc, purpose)
    }

    /**
     * EV 배터리가 낮으면 맨 끝에 참고로 덧붙인다 (Vehicle SDK EvBattery + ChargerPlanner).
     * 가는 주차장에 충전기가 있으면 "세워 두는 동안 충전" — 따로 들를 필요가 없다는 게 핵심이라 그걸 먼저 말한다.
     */
    private fun charger(d: Destination, evSoc: Int, purpose: TripPurpose): String {
        if (evSoc < 0 || evSoc > LOW_SOC) return ""
        val lead = " 참고로 배터리가 ${evSoc}퍼센트예요."
        val c = d.charger
        val atParking = d.parkingName.isNotBlank() && (if (c != null) c.name == d.parkingName else d.parkingCharger)
        val goingThere = purpose == TripPurpose.PARK || (!d.curbsideAllowed && d.rulesKnown)
        val what = if (c != null && c.live) "빈 충전기가 ${c.available}대 있어서" else "충전기가 있어서"
        return lead + when {
            atParking && goingThere -> " 가시는 주차장에 $what, 세워 두는 동안 충전할 수 있어요."
            atParking -> " ${d.parkingName}에 충전기가 있어요. 오래 머무시면 거기 세우고 충전하세요."
            c != null -> " ${chargerLine(c)}"
            else -> " 이 근처엔 충전기 정보가 없어요."
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

    /** 정문 → 주차장 입구 거리 (보강값 또는 등록 데이터의 주차장 오프셋) */
    fun parkingDistance(d: Destination): Int = if (d.parkingDistanceM >= 0) d.parkingDistanceM else d.zone.firstOrNull { it.type == "parking" && !it.absolute }?.offsetM?.let { kotlin.math.abs(it) } ?: -1

    /** "주차장 입구는 정문 지나서 30미터예요" / "주차장 입구로 안내할게요" */
    private fun parkingEntrance(d: Destination): String {
        val name = d.parkingName.ifBlank { "주차장" }
        val m = parkingDistance(d)
        val off = d.zone.firstOrNull { it.type == "parking" && !it.absolute }?.offsetM ?: 0
        return if (m > 0) "$name 입구는 정문 ${if (off < 0) "${m}미터 전" else "지나서 ${m}미터"}예요." else "$name 입구로 안내할게요."
    }

    /**
     * 1 km: 3 km 에서 말한 규칙을 되풀이하지 않고 행동만 말한다 (대표 결정 11/8).
     * 규칙을 다시 말하는 때는 둘뿐 — 안내 중에 목적을 바꿨을 때(purposeChanged), 등록되지 않은 목적지라 선 읽는 법을 알려 줘야 할 때.
     */
    fun at1km(d: Destination, purpose: TripPurpose = TripPurpose.STOP, purposeChanged: Boolean = false): String {
        val line = d.curb
        if (!d.rulesKnown) return "1킬로 남았어요. 정문 앞 차선을 보세요. 황색 점선이면 5분, 실선이면 세울 수 없어요."
        if (purposeChanged) return "1킬로 남았어요. " + when {
            purpose == TripPurpose.PARK -> "주차로 바꾸셨으니 ${parkingEntrance(d)}"
            d.curbsideAllowed && line.known -> "정문 앞 ${line.label}, ${d.curbsideLimitMin}분까지 세울 수 있어요."
            d.curbsideAllowed -> "정문 앞에 ${d.curbsideLimitMin}분까지 세울 수 있어요."
            line.known -> "정문 앞은 ${line.label}이라 못 세워요. ${parkingEntrance(d)}"
            else -> "정문 앞은 정차 금지예요. ${parkingEntrance(d)}"
        }
        return "1킬로 남았어요. " + when {
            purpose == TripPurpose.PARK -> if (d.parkingName.isNotBlank()) parkingEntrance(d) else "정문 앞에 세우고 주차 자리를 보세요."
            d.curbsideAllowed -> "정문 앞에 ${d.curbsideLimitMin}분까지 세울 수 있어요."
            else -> parkingEntrance(d)
        }
    }

    fun at300m(d: Destination, purpose: TripPurpose = TripPurpose.STOP): String {
        // 지난번 내린 자리(승객석 문이 열렸던 좌표)가 있으면 그걸 말한다 — 이 목적지에서 실제로 세웠던 곳. 주차 목적이면 주차장 입구 안내 뒤에 붙인다.
        val drop = d.drop?.let { " 지난번엔 ${DropMemory.describe(d, it, "미터")}에서 내리셨어요." } ?: ""
        if (drop.isNotEmpty() && purpose != TripPurpose.PARK) {
            // 정문 앞이 정차 금지인 곳이면 지난 자리만 말하고 끝내지 않는다 — 주차장 입구 안내를 먼저(대표 승인 11/13)
            val toLot = d.rulesKnown && !d.curbsideAllowed && d.parkingHint.isNotBlank()
            return (if (toLot) d.parkingHint else "잠시 후 도착해요.") + drop
        }
        return d.parkingHint.ifBlank {
            when {
                purpose == TripPurpose.PARK && d.parkingName.isNotBlank() -> "잠시 후 ${d.parkingName} 입구예요."
                !d.rulesKnown && d.parkingName.isNotBlank() -> "잠시 후 도착해요. ${d.parkingName}${iga(d.parkingName)} ${d.parkingDistanceM}미터 거리예요."
                else -> "잠시 후 도착해요."
            }
        } + drop
    }

    /** "지난번엔 정문 지나서 20미터에서 내리셨어요." */
    fun dropLine(d: Destination): String? = d.drop?.let { "지난번엔 ${DropMemory.describe(d, it, "미터")}에서 내리셨어요." }

    /** 목적지 100 m 안에서 브레이크로 멈춰 "여기 세우려나" 싶을 때. 이유 → 할 일 순서. */
    fun stopping(d: Destination, purpose: TripPurpose = TripPurpose.STOP): String = when {
        purpose == TripPurpose.PARK -> if (d.parkingName.isNotBlank()) "주차 목적이라 여기 말고 ${d.parkingName} 입구까지 가 주세요." else "주차장 정보가 없어요. 여기는 잠깐만 세우세요."
        !d.rulesKnown -> "정차 규칙 정보가 없는 곳이에요. 5분 안에만 세우시고, 주정차 단속에 주의하세요."
        // 대표 문장(11/13): "여기는 황색 점선, 5분까지예요. 더 기다리시려면 근처 공영 주차장으로 안내할게요."
        d.curbsideAllowed -> "여기는 ${if (d.curb.known) "${d.curb.label}, " else ""}${d.curbsideLimitMin}분까지예요. 더 기다리시려면 " +
            (if (d.parkingName.isNotBlank()) "${d.parkingName}${ro(d.parkingName)}" else "근처 주차장으로") + " 안내할게요."
        else -> "여기는 ${if (d.curb.known) "${d.curb.label}이라 " else "정차 금지 구역이라 "}세울 수 없어요. ${d.parkingName.ifBlank { "주차장" }} 입구로 안내할게요."
    }

    fun arrived(d: Destination, meetingOn: Boolean, purpose: TripPurpose = TripPurpose.STOP): String {
        val notice = if (meetingOn) " 기다리는 분께 도착을 알렸어요." else ""
        return when (purpose) {
            TripPurpose.PARK -> if (d.parkingName.isNotBlank()) "${d.parkingName}에 도착했어요.${feeLine(d)}" else "도착했어요. 주차 자리를 보세요."
            TripPurpose.VIA -> if (d.curbsideAllowed) "경유지예요. ${d.curbsideLimitMin}분 안에 출발하면 돼요." else "경유지예요. 정차 금지 구역이라 바로 내리고 출발하세요."
            TripPurpose.STOP -> when {
                d.curbsideAllowed -> "도착했어요. ${d.curbsideLimitMin}분까지 세울 수 있어요."
                d.rulesKnown && d.parkingName.isNotBlank() -> "${d.parkingName}에 도착했어요.${feeLine(d)}"   // 정문 앞 정차 금지라 주차장으로 갔다
                else -> "도착했어요."
            }
        } + notice
    }

    /** 승객석 문이 열렸다 닫힘 (Vehicle SDK Door) → 승하차 완료 */
    fun boarded(d: Destination, meetingOn: Boolean, purpose: TripPurpose = TripPurpose.STOP): String = when {
        purpose == TripPurpose.VIA -> "승하차 확인했어요. 다음 목적지로 이어갈게요."
        meetingOn -> "탑승 확인했어요. 안전 운전하세요."
        purpose == TripPurpose.PARK -> "승하차 확인했어요."          // 주차한 뒤라 "안전 운전하세요"는 맞지 않는다
        else -> "승하차 확인했어요. 안전 운전하세요."
    } + if (d.dropNew && d.dropCount == 1) " 내리신 자리는 기억해 뒀다가 다음에 알려 드릴게요." else ""

    /** 정차 허용 시간이 끝났을 때 한 번. 되물어도 "응"을 받을 수단이 없어 질문 대신 할 일을 말한다(대표 승인 11/13). */
    fun overstay(d: Destination): String = "${d.curbsideLimitMin}분 지났어요. " +
        if (d.parkingName.isNotBlank()) "더 기다리시려면 ${d.parkingName}${ro(d.parkingName)} 옮겨 주세요." else "주정차 단속에 주의하세요."

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
                !d.rulesKnown -> "정문 앞은 일반 도로 기준 5분 이내 정차예요. 주정차 단속에 주의하세요."
                d.curbsideAllowed -> "정문 앞은 ${if (d.curb.known) "${d.curb.label}, " else ""}${d.curbsideLimitMin}분까지 정차 가능해요."
                else -> "정문 앞은 ${if (d.curb.known) "${d.curb.label}이라 " else ""}정차 금지예요. 주차장 입구가 더 가까워요."
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
