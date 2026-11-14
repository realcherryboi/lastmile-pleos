#!/usr/bin/env python3
"""condense.py --cues-out 시각 → motion.py polish 계획(plan.json).

사용: python scripts/demo_motion_plan.py <times.json> <plan.json> [body.mp4]
  body.mp4 를 주면 화면에서 헤드업 카드가 실제로 떠 있는 구간(카드의 '물어보기' 버튼 오른쪽 끝 픽셀이 검다)을 재서
  확대를 그 구간 안에 맞춘다(카드가 뜨는 시각은 음성 시작 뒤 0.2~1.1초로 촬영마다 다르다, 11/15 14회차).
음성 순서(demo.sh 병원 흐름): 0 3 km · 1 1 km · 2 300 m · 3 100 m 멈춤 · 4 도착 · 5 승하차 · 6~8 다음 방문 3 km·1 km·300 m.
헤드업 카드는 음성 시작 약 1초 뒤 내비 위(x 690~1230, y 30~262)에 4.7초 떠 있다. 도착하면 앱이 앞으로 나와 5분 타이머를 띄운다.
"""
import json, sys

t = json.load(open(sys.argv[1], encoding="utf-8")); c = t["cues"]
first = next(s for s, l in t["scenes"] if l.startswith("안내 시작"))
second = next(s for s, l in t["scenes"] if l.startswith("다음 방문 · 같은"))
CARD = dict(rect=[620, 0, 960, 540], box=[690, 30, 540, 232])
RAMP = 0.7
cards = []
if len(sys.argv) > 3:
    import subprocess
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", sys.argv[3], "-vf", "crop=2:2:1150:195,fps=10", "-f", "rawvideo", "-pix_fmt", "gray", "-"], capture_output=True, check=True).stdout
    v = [raw[i * 4] < 40 for i in range(len(raw) // 4)] + [False]; on = None
    for i, w in enumerate(v):
        if w and on is None: on = i / 10
        if not w and on is not None: cards.append((on, i / 10)); on = None


def on_card(cue, label, hold=3.0):
    """음성 cue 뒤에 뜬 카드 안에서 다가가고 머물고 돌아온다. 카드 구간을 모르면 음성 시작 1.15초 뒤."""
    iv = next(((a, b) for a, b in cards if cue - 0.5 <= a <= cue + 2.5), None)
    if iv: at = iv[0] + 0.15; hold = max(2.2, min(3.4, iv[1] - at - 2 * RAMP))
    else: at = cue + 1.15
    return dict(at=at, hold=hold, label=label, **CARD)
plan = {
    "visits": [
        {"title": "첫 방문 · 성남시립병원", "from": first, "to": second, "nodes": [c[0], c[1], c[2], c[4], c[5]]},
        {"title": "다음 방문 · 같은 병원", "from": second, "to": t["total"] + 1, "nodes": [c[6], c[7], c[8], None, None]},
    ],
    "highlights": [
        on_card(c[1], "내비 위에 뜨는 안내 카드 · ‘물어보기’를 누르면 바로 질문"),
        on_card(c[3], "목적지 100 m 안에서 브레이크를 3초 밟으면 ‘세우려는구나’"),
        dict(at=c[4] + 1.25, hold=3.2, label="기어를 P(주차)에 두면 도착 확정 · 5분 정차 타이머 시작", rect=[620, 100, 960, 540], box=[668, 282, 340, 180]),
        on_card(c[8], "지난번 내린 자리를 300 m 전에 먼저 알려 준다"),
    ],
}
json.dump(plan, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("cards", cards); print("zoom", [(round(h["at"], 1), round(h["hold"], 1)) for h in plan["highlights"]])
