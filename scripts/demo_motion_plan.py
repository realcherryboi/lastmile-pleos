#!/usr/bin/env python3
"""condense.py --cues-out 시각 → motion.py polish 계획(plan.json).

사용: python scripts/demo_motion_plan.py <times.json> <plan.json>
음성 순서(demo.sh 병원 흐름): 0 3 km · 1 1 km · 2 300 m · 3 100 m 멈춤 · 4 도착 · 5 승하차 · 6~8 다음 방문 3 km·1 km·300 m.
헤드업 카드는 음성 시작 약 1초 뒤 내비 위(x 690~1230, y 30~262)에 4.7초 떠 있다. 도착하면 앱이 앞으로 나와 5분 타이머를 띄운다.
"""
import json, sys

t = json.load(open(sys.argv[1], encoding="utf-8")); c = t["cues"]
first = next(s for s, l in t["scenes"] if l.startswith("안내 시작"))
second = next(s for s, l in t["scenes"] if l.startswith("다음 방문 · 같은"))
CARD = dict(rect=[620, 0, 960, 540], box=[690, 30, 540, 232])
plan = {
    "visits": [
        {"title": "첫 방문 · 성남시립병원", "from": first, "to": second, "nodes": [c[0], c[1], c[2], c[4], c[5]]},
        {"title": "다음 방문 · 같은 병원", "from": second, "to": t["total"] + 1, "nodes": [c[6], c[7], c[8], None, None]},
    ],
    "highlights": [
        dict(at=c[1] + 1.15, hold=3.0, label="내비 위 안내 카드 · ‘물어보기’ 한 번으로 질문", **CARD),
        dict(at=c[3] + 1.15, hold=3.0, label="100 m 안에서 브레이크 3초 → ‘여기 세우려나’", **CARD),
        dict(at=c[4] + 1.25, hold=3.2, label="기어 P라야 도착 · 5분 정차 타이머가 돈다", rect=[620, 100, 960, 540], box=[668, 282, 340, 180]),
        dict(at=c[8] + 1.15, hold=3.0, label="지난번 내린 자리를 300 m 전에 먼저 말한다", **CARD),
    ],
}
json.dump(plan, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(json.dumps(plan["highlights"], ensure_ascii=False)[:400])
