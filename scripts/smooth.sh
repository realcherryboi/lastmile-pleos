#!/usr/bin/env bash
# 에뮬레이터 화면 녹화의 끊김(빠진 프레임 0.2~0.5초)을 움직임 보간으로 메운다 (11/15 대표: "영상이 뚝뚝 끊어진다").
# 사용: scripts/smooth.sh <raw_fixed.mp4> <out.mp4> [동시 작업 수=11]
#   screenrecord 는 에뮬레이터가 바쁠 때 프레임을 놓쳐 지도가 멈췄다 튄다(14회차 실측: 0.12초 넘는 빈틈 554번, 합 128초).
#   ① 같은 프레임을 걷어 내고(mpdecimate) ② 그 사이를 움직임 보간으로 30 fps 로 채운다(minterpolate mci).
#   화면이 크게 바뀌는 순간(헤드업 카드가 뜨고 사라짐)은 보간하면 글자가 뭉개져서 scd_threshold=1 로 건너뛴다.
#   보간이 한 줄로만 돌아(약 3.4 fps) 40초 조각으로 나눠 동시에 돌리고, 조각마다 정확히 1200 프레임으로 맞춰
#   logcat 시각(자막·음성)과 화면이 어긋나지 않게 한다. 결과는 1920×1080 — captions.py 에 raw_fixed 대신 넣는다.
set -euo pipefail
IN=${1:?raw_fixed.mp4}; OUT=${2:?out.mp4}; JOBS=${3:-11}
TMP=$(mktemp -d); IN=$(cd "$(dirname "$IN")" && pwd)/$(basename "$IN")
DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$IN")
N=$(python3 -c "import math;print(math.ceil($DUR/40))")
for i in $(seq 0 $((N - 1))); do echo "$i $((i * 40))"; done | xargs -P "$JOBS" -L 1 bash -c '
  i=$0; s=$1
  ffmpeg -v error -y -ss $s -t 40 -i "'"$IN"'" -vf "scale=1920:1080,mpdecimate=hi=256:lo=64:frac=0.1,minterpolate=fps=30:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1:scd=fdiff:scd_threshold=1,tpad=stop_mode=clone:stop_duration=2" \
    -frames:v 1200 -an -c:v libx264 -crf 15 -preset veryfast -r 30 "'"$TMP"'/c_$(printf %03d $i).mp4"'
ls "$TMP"/c_*.mp4 | sed "s/^/file /" > "$TMP/list.txt"
ffmpeg -v error -y -f concat -safe 0 -i "$TMP/list.txt" -c copy "$OUT"
echo "smoothed $OUT $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT")s (원본 ${DUR}s)"
rm -rf "$TMP"
