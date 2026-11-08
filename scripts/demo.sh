#!/usr/bin/env bash
# 라스트마일 데모 자동 재현 (Pleos Connect Emulator v2.1.0, sdk 플레이버)
# 전제: 에뮬레이터 부팅, app-sdk-debug.apk 설치, Virtual Console 포트 포워딩(scripts/inject.sh)
#       Virtual Console 브리지는 "브라우저 세션" 하트비트(POST /browser/session, 3초 타임아웃)가 있을 때만 PUT 을 받는다.
#       이 스크립트는 백그라운드에서 1.5초마다 세션을 갱신하므로 브라우저를 열어 둘 필요가 없다.
#
# 주행은 GPS 주입이 아니라 **내비 자체의 주행 시뮬레이션**으로 한다(docs/emulator-setup.md §11).
#   안내 중 내비 화면 오른쪽 아래, 볼륨 버튼과 설정 버튼 사이 빈 곳(2460,1176)을 길게 누르면 패널이 뜨고
#   시작(2141,772)을 누르면 내비가 스스로 경로를 달린다. 패널은 X(2435,592)로 닫아도 계속 달린다.
#   라스트마일은 내비 remainDistance 로 3 km·1 km·300 m·도착을 판정하므로 GPS 없이 전부 발화한다.
#   도착 확정은 기어 P, 내린 자리 기억은 FusedLocation 좌표라 도착 직후 geo fix 한 번만 넣는다.
set -euo pipefail
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
VC=http://127.0.0.1:8088
APP=kr.lastmile.app
SHOT=${SHOT:-/tmp/lastmile_demo}   # 장면별 스크린샷 폴더 (SHOT= 로 끄기)
step(){ echo; echo "▶ $*"; }
shot(){ [ -n "$SHOT" ] && { mkdir -p "$SHOT"; $ADB exec-out screencap -p > "$SHOT/$1.png"; } || true; }

$ADB forward tcp:8088 tcp:8088 >/dev/null
( while true; do curl -s -o /dev/null -X POST "$VC/browser/session?client=lastmile-demo"; sleep 1.5; done ) & HB=$!
trap 'kill $HB 2>/dev/null' EXIT
sleep 2
U=$($ADB shell am get-current-user | tr -d "\r"); $ADB shell pm grant --user "$U" $APP pleos.car.permission.FUSED_LOCATION 2>/dev/null || true
$ADB shell pm grant --user "$U" $APP android.permission.POST_NOTIFICATIONS 2>/dev/null || true   # 음성 안내를 헤드업 알림으로도 띄운다 (docs/emulator-setup.md §12)

# 내비 남은 거리(m). 내비 프로세스가 NavigationUseCase remainDistance:NNNN 을 찍는다.
remain(){ $ADB logcat -d -v brief 2>/dev/null | grep -oE "remainDistance:[0-9]+" | tail -1 | cut -d: -f2; }
# 내비가 DIST m 이하가 될 때까지 기다린다 (최대 MAX 초)
wait_until(){ local dist=$1 max=${2:-240} t=0 r; while [ $t -lt $max ]; do r=$(remain); [ -n "$r" ] && [ "$r" -le "$dist" ] && { echo "   내비 남은 거리 ${r} m"; return 0; }; sleep 3; t=$((t+3)); done; echo "   (시간 초과: 남은 거리 ${r:-?} m)"; return 0; }
# 내비 전체 창에서 주행 시뮬레이션 시작 → 패널 닫기
simulate(){ $ADB shell input swipe 2460 1176 2460 1176 1300; sleep 2; $ADB shell input tap 2141 772; sleep 1; $ADB shell input tap 2435 592; sleep 1; }
# 분할 화면 (대표 결정: 내비 왼쪽 · 라스트마일 오른쪽). 창 바를 끌면 "그때 앞에 있던 앱"이 왼쪽, 직전 앱이 오른쪽으로 간다.
split(){ $ADB shell am start -n $APP/.ui.MainActivity >/dev/null 2>&1; sleep 2; $ADB shell input tap 1157 1368; sleep 2.5; $ADB shell input swipe 2545 720 1700 720 600; sleep 3; }
front_full(){ $ADB shell am start -n $APP/.ui.MainActivity >/dev/null 2>&1; sleep 2; $ADB shell input swipe 1706 720 2545 720 500; sleep 2.5; }

step "초기화: 출발 위치, 기어 D, EV 18%, 이전 안내 종료, 내린 자리 기억 초기화"
$ADB emu geo fix 127.0947 37.4127 >/dev/null
curl -sf -X PUT "$VC/vehicle/gear?gear=Drive" >/dev/null || { echo "Virtual Console 브리지 응답 없음 (에뮬레이터/포트포워딩 확인)"; exit 1; }
curl -s -X PUT "$VC/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0" >/dev/null
curl -s -X PUT "$VC/vehicle/ev-battery?field=soc&value=18" >/dev/null   # EV 배터리 18% → 3km 브리핑에 "도착해서 충전" 추천 + 안내 중 충전 카드
curl -s -X PUT "$VC/vehicle/body?door_fr=false" >/dev/null; curl -s -X PUT "$VC/vehicle/turn-signal?mode=Off" >/dev/null
front_full
$ADB shell input tap 1731 1210; sleep 3                      # 앱이 '안내 중'이면 '안내 종료'로 내비 경로도 취소 (출발 전 화면이면 빈 곳 탭)
$ADB shell am force-stop $APP; sleep 1
$ADB logcat -c
$ADB shell am start -n $APP/.ui.MainActivity --ez clear_drops true >/dev/null; sleep 8   # 디버그 빌드: 내린 자리 기억 초기화

step "목적지 선택 (한식당 소담) → 목적 칩(정차 금지라 '도착 후 주차'가 기본, 정차·픽업으로 바꿨다 되돌림) → 안내 시작"
$ADB shell input tap 1364 450; sleep 3                                   # 목록 첫 행 → 상세 카드
shot 01_card
$ADB shell input tap 1978 797; sleep 2.5; $ADB shell input tap 2178 797; sleep 2   # 칩: 정차·픽업 → 도착 후 주차 (카드 문구가 바뀜)
$ADB shell input tap 2179 1212; sleep 9                                   # 안내 시작 → 내비가 앞으로(전체 창, 경로 표시) + 3 km 브리핑
shot 02_nav_start

step "내비 주행 시뮬레이션 시작 → 분할 화면(왼쪽 내비 · 오른쪽 라스트마일)"
simulate
split
shot 03_split

step "1 km → 300 m (내비 남은 거리로 브리핑)"
wait_until 1000 150; sleep 4; shot 04_1km
wait_until 300 120; sleep 3; shot 05_300m

step "우측 방향지시등 → 잠시 정차(STOPPING) 배너"
curl -s -X PUT "$VC/vehicle/turn-signal?mode=BlinkRight" >/dev/null; sleep 4; shot 06_stopping; curl -s -X PUT "$VC/vehicle/turn-signal?mode=Off" >/dev/null

step "내비 도착 → 기어 P → ARRIVED (앱이 스스로 앞으로 나옴 + 주차장·도착 구역 지도)"
wait_until 0 90; sleep 3
curl -s -X PUT "$VC/vehicle/gear?gear=Park" >/dev/null; sleep 6
$ADB emu geo fix 127.10404 37.39967 >/dev/null; sleep 3   # 내린 자리 기억용 좌표 (정문 22 m 지난 자리)
shot 07_arrived
step "조수석 문 열림 → 닫힘 → 승하차 확인(DONE) + 내린 자리 기억"
curl -s -X PUT "$VC/vehicle/body?door_fr=true" >/dev/null; sleep 3; curl -s -X PUT "$VC/vehicle/body?door_fr=false" >/dev/null; sleep 6
shot 08_done

step "두 번째 방문: 완료 → 같은 목적지 다시 선택 → 카드에 '지난번 내린 자리' → 안내 시작 → 시뮬레이션 → 300 m에서 '지난번엔 …에서 내렸어요'"
curl -s -X PUT "$VC/vehicle/gear?gear=Drive" >/dev/null
front_full
$ADB shell input tap 1693 1212; sleep 2.5                     # 완료
$ADB emu geo fix 127.0947 37.4127 >/dev/null; sleep 1         # 출발 위치로 돌려놓는다
$ADB shell input tap 1364 450; sleep 4                        # 목록 첫 행 → 상세 카드(지난번 행 + 지도 핀)
shot 09_card_revisit
$ADB logcat -c
$ADB shell input tap 2179 1212; sleep 10                      # 안내 시작 → 내비가 앞 (3 km 브리핑)
simulate
split
wait_until 1000 150; sleep 4; shot 10_1km_revisit
wait_until 300 120; sleep 8; shot 11_300m_revisit             # "… 지난번엔 정문 지나서 20미터에서 내렸어요."
echo; echo "스크린샷: $SHOT"
