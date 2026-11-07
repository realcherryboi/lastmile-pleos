#!/usr/bin/env bash
# 라스트마일 90초 데모 자동 재현 (Pleos Connect Emulator v2.1.0, sdk 플레이버)
# 전제: 에뮬레이터 부팅, app-sdk-debug.apk 설치, Virtual Console 포트 포워딩(scripts/inject.sh)
#       Virtual Console 브리지는 "브라우저 세션" 하트비트(POST /browser/session, 3초 타임아웃)가 있을 때만 PUT 을 받는다.
#       이 스크립트는 백그라운드에서 1.5초마다 세션을 갱신하므로 브라우저를 열어 둘 필요가 없다.
set -euo pipefail
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
VC=http://127.0.0.1:8088
APP=kr.lastmile.app
step(){ echo; echo "▶ $*"; }

$ADB forward tcp:8088 tcp:8088 >/dev/null
( while true; do curl -s -o /dev/null -X POST "$VC/browser/session?client=lastmile-demo"; sleep 1.5; done ) & HB=$!
trap 'kill $HB 2>/dev/null' EXIT
sleep 2
U=$($ADB shell am get-current-user | tr -d "\r"); $ADB shell pm grant --user "$U" $APP pleos.car.permission.FUSED_LOCATION 2>/dev/null || true   # 실행 사용자(보통 10)에 부여

step "출발 위치로 GPS 초기화, 기어 D"
$ADB emu geo fix 127.0947 37.4127 >/dev/null
curl -sf -X PUT "$VC/vehicle/gear?gear=Drive" >/dev/null || { echo "Virtual Console 브리지 응답 없음 (에뮬레이터/포트포워딩 확인)"; exit 1; }
curl -s -X PUT "$VC/vehicle/ev-battery?field=soc&value=18" >/dev/null   # EV 배터리 18% → 3km 브리핑에 "도착해서 충전" 추천 + 안내 중 충전 카드
curl -s -X PUT "$VC/vehicle/body?door_fr=false" >/dev/null; curl -s -X PUT "$VC/vehicle/turn-signal?mode=Off" >/dev/null
$ADB shell am start -n $APP/.ui.MainActivity >/dev/null; sleep 4
$ADB shell input swipe 1706 720 2545 720 500; sleep 2        # 분할 창이 남아 있으면 창 바를 오른쪽 끝으로 밀어 전체 창으로
$ADB shell input tap 1731 1210; sleep 3                      # 이전 내비 경로가 남아 앱이 '안내 중'이면 앱의 '안내 종료'로 경로 취소 (출발 전 화면이면 빈 곳 탭)
$ADB shell am force-stop $APP; sleep 1
$ADB shell am start -n $APP/.ui.MainActivity --ez clear_drops true >/dev/null; sleep 8   # 디버그 빌드: 내린 자리 기억 초기화(두 번째 방문 장면을 매번 재현)

front(){ $ADB shell am start -n $APP/.ui.MainActivity >/dev/null 2>&1; sleep 2; }   # singleTask 라 기존 화면이 그대로 앞으로 온다
step "목적지 선택 (한식당 소담) → 목적 칩(정차 금지라 '도착 후 주차'가 기본, 정차·픽업으로 바꿨다 되돌림) → 안내 시작"
$ADB shell input tap 1364 450; sleep 3                                   # 목록 첫 행 → 상세 카드
$ADB shell input tap 1978 797; sleep 2.5; $ADB shell input tap 2178 797; sleep 2   # 칩: 정차·픽업 → 도착 후 주차 (카드 문구가 바뀜)
$ADB shell input tap 2179 1212; sleep 9                                   # 안내 시작 → 내비가 앞으로(전체 창, 경로 표시)
# 분할 화면 (대표 결정: 내비 왼쪽 · 라스트마일 오른쪽). 창 바를 끌면 "그때 앞에 있던 앱"이 왼쪽, 직전 앱이 오른쪽으로 간다.
split(){ $ADB shell am start -n $APP/.ui.MainActivity >/dev/null 2>&1; sleep 2; $ADB shell input tap 1157 1368; sleep 2.5; $ADB shell input swipe 2545 720 1700 720 600; sleep 3; }
step "분할 화면: 왼쪽 내비 · 오른쪽 라스트마일 브리핑 카드 (주행 구간 내내 둘 다 보임)"
split

step "접근: 1km → 500m"
$ADB emu geo fix 127.0995 37.4075 >/dev/null; sleep 7
$ADB emu geo fix 127.1015 37.4040 >/dev/null; sleep 7

step "300m 진입 → 지오펜스 진입"
$ADB emu geo fix 127.1030 37.4010 >/dev/null; sleep 7
$ADB emu geo fix 127.1036 37.3999 >/dev/null; sleep 5
step "우측 방향지시등 → 잠시 정차(STOPPING)"
curl -s -X PUT "$VC/vehicle/turn-signal?mode=BlinkRight" >/dev/null; sleep 4; curl -s -X PUT "$VC/vehicle/turn-signal?mode=Off" >/dev/null
$ADB emu geo fix 127.1038 37.3997 >/dev/null; sleep 4

step "기어 P → ARRIVED (앱이 스스로 앞으로 나옴 + 주차장·도착 구역 지도)"
curl -s -X PUT "$VC/vehicle/gear?gear=Park" >/dev/null; sleep 6
$ADB emu geo fix 127.10404 37.39967 >/dev/null; sleep 3   # 정문을 22 m 지난 자리에 섰다 (내린 자리 기억이 정문 마커와 겹치지 않게)
step "조수석 문 열림 → 닫힘 → 승하차 확인(DONE) + 내린 자리 기억"
curl -s -X PUT "$VC/vehicle/body?door_fr=true" >/dev/null; sleep 3; curl -s -X PUT "$VC/vehicle/body?door_fr=false" >/dev/null; sleep 6

step "두 번째 방문: 완료 → 같은 목적지 다시 선택 → 카드에 '지난번 내린 자리' → 안내 시작 → 300 m에서 '지난번엔 …에서 내렸어요'"
curl -s -X PUT "$VC/vehicle/gear?gear=Drive" >/dev/null
$ADB shell am start -n $APP/.ui.MainActivity >/dev/null 2>&1; sleep 2; $ADB shell input swipe 1706 720 2545 720 500; sleep 2.5   # 앱을 앞으로 → 창 바를 오른쪽으로 밀어 전체 창(버튼 좌표를 전체 창 기준으로)
$ADB shell input tap 1693 1212; sleep 2.5                     # 완료
$ADB emu geo fix 127.0947 37.4127 >/dev/null; sleep 1         # 출발 위치로 먼저 돌려놓는다 (안 그러면 0 m에서 바로 3 km 브리핑)
$ADB shell input tap 1364 450; sleep 4                        # 목록 첫 행 → 상세 카드(지난번 행 + 지도 핀)
$ADB shell input tap 2179 1212; sleep 10                      # 안내 시작 → 내비가 앞 (3 km 브리핑)
split                                                         # 다시 분할: 왼쪽 내비 · 오른쪽 라스트마일
$ADB emu geo fix 127.0995 37.4075 >/dev/null; sleep 12        # 1 km (내비 거리 15초 정지 후 위치 폴백이 받는다)
$ADB emu geo fix 127.1030 37.4010 >/dev/null; sleep 10        # 300 m: "… 지난번엔 정문 지나서 20미터에서 내렸어요."
$ADB exec-out screencap -p > /tmp/lastmile_demo_end.png && echo "스크린샷: /tmp/lastmile_demo_end.png"
