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
- **임의 목적지**: 내비에 어떤 목적지를 넣어도 앱이 따라온다. 등록 장소(5곳)는 확인된 정차·주차 데이터로, 그 외는 "일반 도로 기준 5분 이내 정차"로 안내하고 주소(Nominatim 역지오코딩)·가장 가까운 주차장·EV 충전소·정문 앞 도로 방위를 이미 내려받은 지도 타일(OpenStreetMap)에서 찾아 채운다. 추가 키·서버 없음
- **차량 신호 심화**(Vehicle SDK): 우측 방향지시등 → 잠시 정차 안내, 승객석 문 열림→닫힘 → 승하차 확인(정차 타이머 종료), EV 배터리 20% 이하 → 충전기 있는 주차장 안내. 권한 `CAR_LIGHTS`·`CAR_DOORS`·`CAR_ENERGY`
- **지도 모션**: 3D 건물(타일 render_height)과 목적지 건물 강조, 주행 중 헤딩업·기울임 카메라(easeCamera), 차 마커 스프링 보간, 경로선 그려지기와 지나온 구간 회색 처리, 도착 순간 반경 펄스
- **모션**: 스프링 기반(androidx.dynamicanimation). 페이지·카드·시트 전환, 숫자 카운팅, 진행 바, 누름 반응 모두 현재 값에서 이어지는 스프링(기본 임계감쇠). 시트가 올라오면 뒤 화면은 블러·축소로 밀려남. 지도 위 요소는 유리 재질(반투명 + 하이라이트 테두리)
- APK는 arm64-v8a만 포함 (Pleos 실차·에뮬레이터 모두 arm64)

## APK 위치
<!-- 제출 규칙 필수 항목 4. release 서명 빌드 -->
- `apk/김상헌_라스트마일_release.apk` — `cd app && ./gradlew assembleSdkRelease` 로 생성한 서명(v3) 빌드. 서명 키·비밀번호는 `app/keystore/`, `app/keystore.properties`(비공개)

## 시연 영상
- `docs/video/lastmile_demo_1080p.mp4` (약 100초, 무음): 목적지 목록·상세(도착 구역 지도 미리보기, 스프링 전환) → 안내 시작·2.6 km 브리핑 → 다음 안내 카드·진행 바·ETA·실제 지도(경로선·차 마커) → 1 km·300 m 안내 → 물어보기 시트(블러 밀어내기) → 우측 방향지시등·잠시 정차 → 기어 P 도착(정차 금지·3D 도착 구역 지도) → 조수석 문 열림·닫힘 → 승하차 확인

## 저장소 구조
```
docs/                 에뮬레이터 셋업·실험 기록(emulator-setup.md)·데모 대본·UI 설계 노트(ui-design.md)·시연 영상
app/                  Android 프로젝트
apk/                  release APK
scripts/inject.sh     Virtual Console 포트 포워딩
```



## 오픈소스 고지 · 데이터 출처
이 앱은 아래 오픈소스 소프트웨어와 공개 데이터를 사용합니다. 각 라이선스 조건을 따르며, 지도 화면 안에도 출처를 표기합니다.

| 구성 요소 | 용도 | 라이선스 |
|---|---|---|
| [MapLibre Native (Android)](https://github.com/maplibre/maplibre-native) 13.6.1 | 지도 렌더링 엔진 | BSD 2-Clause |
| [OpenFreeMap](https://openfreemap.org) 벡터 타일 · 글리프 서버 | 지도 타일(Positron 스타일) | 서비스는 무료 공개(제한 없음), 코드 MIT |
| [OpenMapTiles](https://openmaptiles.org) 스키마 · Positron 스타일 | 타일 데이터 구조·지도 디자인 | BSD 3-Clause (코드) · CC BY 4.0 (스키마) · Positron 스타일 © CARTO, BSD 3-Clause |
| [OpenStreetMap](https://www.openstreetmap.org/copyright) 기여자 데이터 | 도로·건물·지명 | ODbL 1.0 |
| [OSRM](https://project-osrm.org) 공개 데모 라우팅 서버 | 경로선(데모용, 응답 없으면 생략) | BSD 2-Clause · 경로 데이터는 OSM ODbL |
| Noto Sans (OpenFreeMap 글리프) | 지도 라벨 글꼴 | SIL Open Font License 1.1 |
| AndroidX (core, appcompat, lifecycle, dynamicanimation) · Material Components · Kotlin Coroutines | 앱 프레임워크 · 스프링 모션 · UI 컴포넌트 | Apache License 2.0 |
| Pleos Connect SDK (Vehicle · NaviHelper · TextToSpeech · SpeechToText · FusedLocation) | 차량 신호 · 내비 연동 · 음성 · 위치 | 현대자동차그룹 Pleos Playground 이용 약관 (비오픈소스) |

- 앱 아이콘과 화면 아이콘(`res/drawable/ic_*.xml`)은 이 프로젝트에서 직접 그린 벡터 드로어블입니다.
- 데모 목적지 5곳의 상호·주소·좌표·정문/주차장/만남 지점 데이터(`assets/destinations.json`)는 시연용 샘플이며 실제 시설 정보가 아닙니다.
- OpenFreeMap·OSRM 데모 서버는 공개 무료 서비스입니다. 상용 배포 시 자체 타일·경로 서버 또는 유료 플랜으로 교체합니다(`docs/ui-design.md` §5).
