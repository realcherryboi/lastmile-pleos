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
$ADB shell am force-stop $APP; $ADB shell am start -n $APP/.ui.MainActivity >/dev/null; sleep 8

front(){ $ADB shell am start -n $APP/.ui.MainActivity >/dev/null 2>&1; sleep 2; }   # singleTask 라 기존 화면이 그대로 앞으로 온다
step "목적지 선택 (한식당 소담) → 목적 칩(정차 금지라 '도착 후 주차'가 기본) → 안내 시작 → 내비 경로 요청 → BRIEF_3KM"
$ADB shell input tap 1364 450; sleep 2.5; $ADB shell input tap 2179 1212; sleep 9   # 목록 첫 행 → 안내 시작
front   # 내비가 앞으로 오므로 앱 복귀
step "목적 바꿔 보기: 정차·픽업 → 1km 안내 문장이 '황색 실선이라 못 세워요'로 바뀜 → 다시 도착 후 주차"
$ADB shell input tap 1978 797; sleep 3; $ADB shell input tap 2178 797; sleep 2

step "접근: 1km → 500m"
$ADB emu geo fix 127.0990 37.4080 >/dev/null; sleep 7
$ADB emu geo fix 127.1015 37.4040 >/dev/null; sleep 7

step "물어보기 (음성 시트)"
$ADB shell input tap 1253 1212; sleep 3; $ADB shell input tap 2451 163; sleep 1

step "300m 진입 → 지오펜스 진입"
$ADB emu geo fix 127.1030 37.4010 >/dev/null; sleep 7
$ADB emu geo fix 127.1036 37.3999 >/dev/null; sleep 5
step "우측 방향지시등 → 잠시 정차(STOPPING)"
curl -s -X PUT "$VC/vehicle/turn-signal?mode=BlinkRight" >/dev/null; sleep 4; curl -s -X PUT "$VC/vehicle/turn-signal?mode=Off" >/dev/null
$ADB emu geo fix 127.1038 37.3997 >/dev/null; sleep 4

step "기어 P → ARRIVED (정차 금지 안내 + 도착 구역 스케치)"
curl -s -X PUT "$VC/vehicle/gear?gear=Park" >/dev/null; sleep 8
step "조수석 문 열림 → 닫힘 → 승하차 확인(DONE)"
curl -s -X PUT "$VC/vehicle/body?door_fr=true" >/dev/null; sleep 3; curl -s -X PUT "$VC/vehicle/body?door_fr=false" >/dev/null; sleep 6
$ADB exec-out screencap -p > /tmp/lastmile_demo_end.png && echo "스크린샷: /tmp/lastmile_demo_end.png"
