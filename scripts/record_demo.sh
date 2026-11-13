#!/usr/bin/env bash
# 시연 영상 원본 녹화: demo.sh 를 돌리며 에뮬레이터 화면을 녹화한다 (docs/emulator-setup.md §10).
# 사용: scripts/record_demo.sh <출력 폴더>
# 산출: raw.mp4(screenrecord 원본), raw_fixed.mp4(30 fps 정규화), logcat_epoch.txt, rec_start.txt, demo.log, shots/
# 다음 단계: scripts/captions.py raw_fixed.mp4 … --voice edge:ko-KR-SunHiNeural --rate +10%  →  scripts/condense.py …
set -uo pipefail
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
OUT=${1:?출력 폴더를 주세요}; mkdir -p "$OUT"
cd "$(dirname "$0")/.."
$ADB logcat -c
$ADB logcat -v epoch -s LastMile:D > "$OUT/logcat_epoch.txt" & LC=$!
sleep 1
python3 -c 'import time;print(time.time())' > "$OUT/rec_start.txt"   # macOS date 는 %N 이 없다
$ADB shell screenrecord --time-limit 0 --bit-rate 20M /sdcard/lastmile_raw.mp4 & REC=$!   # 기본 제한 180초라 0 필수
sleep 2
SHOT="$OUT/shots" bash scripts/demo.sh > "$OUT/demo.log" 2>&1
echo "demo exit $?" >> "$OUT/demo.log"
sleep 9                      # 마지막 300 m 헤드업·자막이 끝날 때까지
$ADB shell pkill -INT screenrecord
wait $REC 2>/dev/null
sleep 3
$ADB pull /sdcard/lastmile_raw.mp4 "$OUT/raw.mp4" >/dev/null && $ADB shell rm /sdcard/lastmile_raw.mp4
kill $LC 2>/dev/null
# screenrecord 원본은 시각값이 단조 증가하지 않고 빈 스트림이 붙어 자막 단계 ffmpeg 가 멈출 수 있다 → 30 fps 로 정규화
ffmpeg -v error -y -i "$OUT/raw.mp4" -map 0:v:0 -fps_mode cfr -r 30 -c:v libx264 -preset veryfast -crf 16 -pix_fmt yuv420p "$OUT/raw_fixed.mp4"
grep -E '시간 초과|다시 시도|demo exit' "$OUT/demo.log"
echo "rec_start $(cat "$OUT/rec_start.txt")"; ls -la "$OUT"
