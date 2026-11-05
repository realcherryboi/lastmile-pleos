# 라스트마일 (가칭) — 목적지 마지막 3km 비서

> 현대차·기아 차량용 앱 개발 공모전 (2026) 출품작 · Pleos Connect SDK 기반 · 개인 참가

도착 전에는 정차·주차 가능 여부를 음성으로 브리핑하고, 도착하면 기다리는 사람에게 자동으로 알리는 차량용 앱.

## 앱 이름 · 한 줄 소개
<!-- 제출 규칙 필수 항목 1. 앱명 확정 후 갱신 -->
- **앱 이름:** 라스트마일 (가칭)
- **한 줄 소개:** 도착 3km 전 음성 브리핑 + 도착 자동 알림. 주행 중 터치 0회.

## 실행 방법
<!-- 제출 규칙 필수 항목 2. 앱 코드 작성 후 채움 -->
1. Android Studio에서 `app/` 열기 (또는 `cd app && ./gradlew assembleSdkDebug`)
2. Pleos Connect Emulator v2.1.0 실행 (설치: `docs/emulator-setup.md`)
3. 빌드 변형: `sdk`(Pleos SDK 연동, 제출용) / `mock`(화면 버튼으로 신호 흉내, 앱 ID `.mock`)
4. 설치 후 `adb shell pm grant --user 10 kr.lastmile.app pleos.car.permission.FUSED_LOCATION` (실행 사용자 ID는 `am get-current-user`)
5. 데모 재현: `scripts/demo.sh` (경로 요청 → GPS 접근 → Virtual Console에서 기어 P)

## 테스트 환경 · 필요 설정
<!-- 제출 규칙 필수 항목 3 -->
- Android API 28+, Gradle 8.0+, Kotlin
- Pleos Connect Emulator v2.1.0 (AAOS, API 34) / Vehicle SDK 2.3.2 · NaviHelper 3.0.5 · TTS·STT 3.3.1 · FusedLocation 1.1.3
- 사용 SDK: Vehicle, NaviHelper, TextToSpeech, SpeechToText, FusedLocation
- 차량 신호 연출은 Pleos Connect Virtual Console (`scripts/inject.sh`로 포트 포워딩 후 http://127.0.0.1:8088)
- **지도**: MapLibre(오픈소스) + OpenFreeMap 벡터 타일(키 불필요, 인터넷 필요). 오프라인이면 같은 자리에 도착 구역 스케치(ZoneSketchView)가 대신 표시됨. 경로선은 공개 OSRM 데모 서버(데모용, 응답 없으면 생략)
- APK는 arm64-v8a·x86_64만 포함 (Pleos 실차·에뮬레이터 arm64)

## APK 위치
<!-- 제출 규칙 필수 항목 4. release 서명 빌드 -->
- `apk/김상헌_라스트마일_release.apk` — `cd app && ./gradlew assembleSdkRelease` 로 생성한 서명(v3) 빌드. 서명 키·비밀번호는 `app/keystore/`, `app/keystore.properties`(비공개)

## 시연 영상
- `docs/video/lastmile_demo_1080p.mp4` (105초, 무음): 목적지 목록·상세(도착 구역 지도 미리보기) → 안내 시작·2.6 km 브리핑 → 다음 안내 카드·진행 바·ETA·실제 지도(경로선·차 마커) → 1 km·300 m 안내 → 기어 P 도착(정차 금지·도착 구역 지도)

## 저장소 구조
```
docs/                 에뮬레이터 셋업·실험 기록(emulator-setup.md)·데모 대본·UI 설계 노트(ui-design.md)·시연 영상
app/                  Android 프로젝트
apk/                  release APK
scripts/inject.sh     Virtual Console 포트 포워딩
scripts/fill_form.py  proposal.md → 접수양식 docx 생성 (`pip install python-docx` 후 `python3 scripts/fill_form.py <양식.docx> docs/proposal.md <출력.docx>`)
```


