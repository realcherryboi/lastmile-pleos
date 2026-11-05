#!/usr/bin/env bash
# Pleos Connect Emulator v2.1.0: 차량 신호 주입은 Virtual Console(웹 UI)로 한다.
# 이 스크립트는 포트 포워딩 + 브라우저 열기만 담당. (구버전 vhal_fifo adb 주입은 2.1.0 문서에 없음)
set -euo pipefail
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
"$ADB" devices
"$ADB" forward tcp:8088 tcp:8088
open "http://127.0.0.1:8088/"
echo "Virtual Console > Emulation Controls: Motion/Gear, Motion/Brake Pedal, Convenience/Hazard Lights, Safety/Telltales(Parking brake)"
