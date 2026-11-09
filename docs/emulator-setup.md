# Pleos Connect 에뮬레이터·SDK 셋업 가이드 (2026-11-05 공식 문서 기준)

> 출처: document.pleos.ai (Emulator 설치·설정·Virtual Console 가이드, SDK 릴리즈 노트 2026-10-19). HANDOFF.md §1의 SDK 정보는 2.0.x 기준이라 **아래가 우선**한다.

## 0. 이 Mac의 현재 상태
- Apple M3 Pro / 18 GB RAM / macOS Darwin 27.2 → arm64 시스템 이미지 사용. (문서의 GPU 권장 사양은 Windows 노트북 기준. Apple Silicon은 arm64 이미지가 별도 제공되므로 진행 가능)
- Android Studio 설치됨 (`/Applications/Android Studio.app`), SDK `~/Library/Android/sdk` (platforms 30·34·35·36·36.1·37, platform-tools adb 1.0.41, emulator 있음)
- JDK: 시스템 java는 Temurin 25. Pleos 샘플 앱 기준은 **JDK 17** → Android Studio 내장 JBR 사용(기본값)이면 문제 없음.
- Pleos 시스템 이미지: **설치 완료 (2026-11-05)** → `~/Library/Android/sdk/system-images/android-34/android-automotive/arm64-v8a` (SHA1 검증됨). AVD **`Pleos_Connect_v2_1`** 생성 완료 (Cold boot, RAM 4 GB, GPU host, 2560×1440). 아래 §8 참고.

## 1. 버전 (2026-10-19 릴리즈, 공모전 접수 시작 2일 전)
| 구성요소 | 버전 | 비고 |
|---|---|---|
| Pleos Connect Emulator | **v2.1.0** | HANDOFF의 2.0.5는 구버전. Virtual Console은 2.1.0부터 |
| Vehicle SDK | **v2.3.2** | `VehicleSpeedListener`/`onSpeedUpdated` **삭제**. 기어 API 없음 |
| NaviHelper SDK | v3.0.5 | `onDestinationArrived`, `onDestinationInfo`(remainDistance/remainTime), `onTripInfo`(eta) |
| TextToSpeech SDK | v3.3.1 | ON_DEVICE 모드는 앱 등록 불필요 |
| SpeechToText SDK | v3.3.1 | ON_DEVICE 모드는 앱 등록 불필요. 음성 명령 실행은 안 함(텍스트만 반환) |
| FusedLocation SDK | v1.1.3 | `addProximityAlert` 지오펜스 신규 |
| 샘플 앱 기준 | JDK 17, Gradle 8.10.2, AGP 8.8.2, Kotlin 2.0.0, compileSdk 35 | minSdk 28 필수(서명 키 로테이션 요건) |

## 2. 시스템 이미지 설치 (Android Studio GUI)
1. Tools > SDK Manager > **SDK Update Sites** 탭 > `+`
   - Name: `Pleos Connect System Image`
   - URL (Mac): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
2. **SDK Platforms** 탭 > Show Package Details > Android API 34 아래 **Pleos Connect v2.1.0 APIs arm64 System Image** 체크 > OK
   - 패키지 경로 `system-images;android-34;pleos_car_emulator;arm64-v8a`, zip **4.67 GB** (42dot SDK 라이선스 동의 필요), emulator 24+ 필요
3. 설치 후 Android Studio 재시작. 이전 버전 Pleos 이미지가 있으면 먼저 삭제.

### CLI 대안 (GUI가 안 될 때)
```bash
# zip 받기 (4.67 GB)
curl -L -o ~/Downloads/crp_car_arm64_emulator.zip https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp_car_arm64_emulator.zip
```
```bash
shasum ~/Downloads/crp_car_arm64_emulator.zip   # 24d28eb2f18eba5e87c861a7eb2bdccebb049419
```
```bash
mkdir -p ~/Library/Android/sdk/system-images/android-34/pleos_car_emulator && cd ~/Library/Android/sdk/system-images/android-34/pleos_car_emulator && unzip ~/Downloads/crp_car_arm64_emulator.zip
```
압축 해제 후 폴더명이 `arm64-v8a`인지 확인(아니면 그렇게 rename). 그 다음 AVD 생성:
```bash
~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager create avd -n Pleos_Connect_v2_1 -k "system-images;android-34;pleos_car_emulator;arm64-v8a" -d automotive_1024p_landscape
```
`~/.android/avd/Pleos_Connect_v2_1.avd/config.ini`에서 `hw.ramSize=4096`, `fastboot.forceColdBoot=yes` 설정.

## 3. AVD 생성 (GUI)
Tools > Device Manager > Create device > Form Factor **Automotive** > **Pleos Connect v2.1.0** > Next
- Advanced Settings > Startup > Default boot: **Cold** (quick boot면 VHAL 사용 불가)
- Emulated Performance > RAM **4 GB**
- Device 탭 > system image API 34 > **Pleos Connect v2.1.0 APIs** > Finish
- 언어: 차량 설정 > 일반 > 언어 (기본 한글)
- STT 테스트: 에뮬레이터 Extended Controls(…) > Microphone > 호스트 오디오 입력 허용

## 4. Pleos Connect Virtual Console (차량 신호 주입 — adb vhal_fifo 방식을 대체)
```bash
adb forward tcp:8088 tcp:8088
```
브라우저에서 `http://127.0.0.1:8088/` > Emulation Controls.

우리 앱 데모에 필요한 카드:
| 카드 | 도메인 | 데모 용도 |
|---|---|---|
| Gear Selection (P/R/N/D) | Motion | 정차/주차 상태 연출 (※ 앱이 Vehicle SDK로 기어를 **읽을 수는 없음**) |
| Brake Pedal 0~100% | Motion | `registerBrakePedalStatus` 로 "브레이크 밟고 정지" 감지 |
| Hazard Lights On/Off | Convenience | `registerTurnSignalState` 로 비상등 = 잠시 정차 의도 |
| Telltales > Parking brake On | Safety | `registerTelltales` 로 주차 브레이크 = 주차 확정 |
| Steering Angle | Motion | (미사용) |
- **차속 카드는 없음.** Vehicle SDK v2.3.2에서 속도 리스너도 삭제됨 → 속도는 쓰지 않는다.
- 기어 상태 변경은 Virtual Console에서만 가능(문서의 "기어 상태 변경" 항목).

## 5. Gradle 설정
`settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
```
`app/build.gradle.kts`:
```kotlin
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")   // 좌표 확인 필요
    implementation("ai.pleos.playground:SpeechToText:3.3.1")   // 좌표 확인 필요
    implementation("ai.pleos.playground:FusedLocation:1.1.3")  // 좌표 확인 필요
}
```
TTS/STT/FusedLocation의 artifact 이름은 각 "Importing … SDK" 페이지에서 확인 후 확정 (Vehicle·NaviHelper는 확인됨).

권한(Manifest): FusedLocation `pleos.car.permission.FUSED_LOCATION`. Vehicle 도메인별 권한(예: Door는 `CAR_DOORS`)은 `/Vehicle/vehicle-permission`, 나머지는 각 SDK의 `declaring-permissions` 페이지. **퍼미션은 앱마켓 사전 승인 범위 내에서만** 선언 가능(심사 기준).

## 6. 우리 앱의 SDK 매핑 (수정판)
| 기능 | SDK / API | 에뮬레이터 연출 |
|---|---|---|
| F1 브리핑 트리거 | NaviHelper `addListener` → `onDestinationInfo(remainDistance, remainTime)`, `onTripInfo(eta)` | 에뮬레이터 내장 내비에 목적지 설정 후 주행 시뮬레이션 가능 여부 **실험 필요**. 불가 시 앱 내 모의 입력 |
| F2 음성 Q&A | SpeechToText(ON_DEVICE) `request()` / TextToSpeech(ON_DEVICE) `speak()` | Extended Controls 마이크 |
| F3 마중 알림 | FusedLocation `registerFusedLocationCallback`, NaviHelper `onTripInfo.eta` | — |
| F4 도착 판정 | ① NaviHelper `onDestinationArrived(DestinationArrivedInfo)` ② FusedLocation `addProximityAlert`(목적지 반경 진입) ③ Vehicle Safety `registerTelltales`(주차 브레이크) / Brake `registerBrakePedalStatus` / TurnSignal(hazard) | Virtual Console: Telltales Parking brake ON, Hazard ON, Brake 100% |

도착 확정 규칙: (① 또는 ②) AND (주차브레이크 ON 또는 브레이크 100% 5초 유지). 비상등 ON이면 "잠시 정차" 상태.

## 7. 확인 체크리스트
- [ ] 시스템 이미지 설치 (4.67 GB) → AVD Cold boot 부팅
- [ ] `adb devices`에 emulator 표시, Virtual Console 접속
- [ ] 에뮬레이터 내장 내비 앱 존재 여부, 목적지 설정 시 `onDestinationInfo`가 오는지, 주행 시뮬레이션이 되는지
- [ ] TTS ON_DEVICE 모드 재생, STT ON_DEVICE 모드 인식
- [ ] Telltales 주차브레이크·Hazard가 앱 콜백으로 들어오는지
- [ ] TTS/STT/FusedLocation Gradle 좌표

## 8. 설치 결과 (2026-11-05 실측)
```bash
~/Library/Android/sdk/emulator/emulator -avd Pleos_Connect_v2_1 -no-snapshot -gpu host
```
```bash
scripts/inject.sh
```
- 부팅 17초. `adb devices` → `emulator-5554`. 빌드 `RELEASE.release_connect_v2.1.0`.
- Virtual Console(http://127.0.0.1:8088) 접속 OK. 화면 구성: 주행 시각화(Highway 테마) + **ACCEL/BRAKE 페달·스티어링** + Systems(Gear P/R/N/D, Speed, Wipers, Ambient, ETCS) + Climate + Emulation Controls(48 features / 7 domains). "Select D + hold accel"로 주행 시뮬레이션 가능 → 에뮬레이터 내부에서는 속도가 올라가지만 **앱은 Vehicle SDK로 속도를 못 읽는다** (v2.3.2 삭제). 내비 쪽 신호(remainDistance 등)로 대체.
- 내장 내비 앱 `ai.umos.maps.android.navigation.app` 존재. 검색창 "어디로 갈까요?", 현재 위치 "수정구 시흥동" 표시. 지도 타일은 첫 실행에서 비어 있음(네트워크/지도 데이터 확인 필요).
- SDK 서비스 패키지 존재: `ai.pleos.playground.service.navi` / `.service.vehicle` / `.service.location.fused` / `.service.adas` / `.service.cap`. `ai.pleos.connecttokensampleapp`(앱 등록 토큰 샘플)도 포함.
- 기기 정의는 `~/.android/devices.xml`에 복사해 등록했고, Android Studio Device Manager에서도 `Pleos_Connect_v2_1`이 보여야 함.

### 다음 실험 (앱 코드 작성 전)
- [ ] 내비에 목적지 설정 → Virtual Console D + ACCEL로 주행 → 내비가 실제로 안내를 진행하는지(모의 주행) 확인
- [ ] Vehicle SDK Sample App(문서의 Tutorials & Samples)으로 Telltales·TurnSignal·Brake 콜백 수신 확인
- [ ] TTS/STT ON_DEVICE 동작 확인 (Extended Controls > Microphone)

## 9. SDK 연동 실험 결과 (2026-11-05, app/ `sdk` 플레이버)

| 항목 | 결과 | 비고 |
|---|---|---|
| NaviHelper `initialize` → 서비스 3.0.4 연결 | ✅ | `requestRoute(RouteInfo)` 하면 내장 내비가 실제로 경로 안내 시작(한식당 소담 10분·2.6km 표시) |
| `onDestinationInfo` (remainDistance/remainTime) | ✅ 폴링 5초 | 목적지 없을 때 name="" / 0,0 → 필터링 필요(구현됨) |
| 내비 위치 이동 (`adb emu geo fix`, NMEA) | ❌ | 내비 앱이 NMEA 체크섬을 검증하는데 에뮬레이터 GPS HAL의 NMEA는 체크섬이 `*47` 고정 → 전부 거부, 위치 고정 (127.0947,37.4127). `onDestinationArrived` 못 받음 |
| FusedLocation `registerFusedLocationCallback` | ✅ | `adb emu geo fix` 를 그대로 따라옴 → 앱에서 직선거리 계산 폴백 구현 (내비 값 15초 불변 시 전환) |
| FusedLocation `addProximityAlert` (80m) | ✅ | 진입 이벤트 수신 확인 |
| Vehicle `Gear.registerGearSelection` | ✅ | 권한 `CAR_DRIVE`(normal). deprecated 경고: "Google GEAR_SELECTION 속성 사용" → 실차 대비 android.car 전환 검토 |
| Vehicle `Brake.registerBrakePedalStatus` | ✅ 구독 | `CAR_DRIVE`. 3초 유지 → STOPPING |
| Vehicle `TurnSignal.registerTurnSignalState` | ✅ 구독 | `CAR_LIGHTS`(signature지만 에뮬레이터에서 부여됨). **비상등은 이 값에 반영되지 않음** (별도 HAZARD 토픽, SDK API 없음) |
| Vehicle `Safety.registerTelltales` | ❌ | `CAR_SAFETY` signature 권한, 서비스가 "need Permission" 으로 거부 → 주차 브레이크 경고등 사용 불가 |
| TextToSpeech ON_DEVICE | ⚠ | 초기화·setLocale(ko) OK, `speak` 시 `ON_DEVICE_ENGINE_ERROR` → Android 기본 TTS 폴백 구현. HYBRID/SERVER는 앱 등록 필요 |
| SpeechToText ON_DEVICE | ✅ 준비 | 실제 인식은 마이크 실험 필요 |
| 권한 | `pm grant kr.lastmile.app pleos.car.permission.FUSED_LOCATION` 필요(normal인데 미부여됨). 나머지는 설치 시 부여 | 서명 권한(CAR_SAFETY 등)은 앱마켓 심사 범위 |
| 전체 플로우 | ✅ IDLE→ARMED→3KM→1KM→300M→(지오펜스)→기어 P→ARRIVED, TTS 안내 | 스크린샷: scratchpad app_sdk_9 |

### 데모 재현 절차 (SDK 모드)
```bash
scripts/demo.sh
```
1. 앱 실행 → "목적지 설정"(한식당 소담) → 내비가 경로 안내 시작, 앱 BRIEF_3KM
2. `adb emu geo fix <lng> <lat>` 로 접근 (스크립트가 순서대로 주입) → 1KM, 300M, 지오펜스 진입
3. Virtual Console > Gear Selection > **P** → ARRIVED + 도착 안내

## 10. 릴리즈 서명·시연 영상 (2026-11-05)
- 서명 키: `app/keystore/lastmile-release.jks` (alias `lastmile`), 비밀번호는 `app/keystore.properties` (둘 다 .gitignore, **백업 필수** — 분실 시 같은 앱 ID로 업데이트 불가)
- 빌드: `cd app && ./gradlew assembleSdkRelease` → `app/build/outputs/apk/sdk/release/app-sdk-release.apk` → `apk/김상헌_라스트마일_release.apk` (APK Signature Scheme v3 검증 완료, 26 MB)
- 설치 후 실행 사용자 권한: `adb shell pm grant --user $(adb shell am get-current-user) kr.lastmile.app pleos.car.permission.FUSED_LOCATION` — `--user` 를 빼면 user 0 에만 부여되어 위치 콜백이 오지 않는다(실측).
- 시연 영상: `docs/video/lastmile_demo_1080p.mp4` (11/9 촬영, 181초, 1920×1080, 음성 나레이션 포함). screenrecord 는 오디오를 담지 않아 에뮬레이터 TTS 소리는 녹음되지 않는다 → `captions.py --voice Yuna` 가 logcat 의 🔊 문장을 macOS `say -v Yuna`(한국어)로 합성해 자막 시각에 섞는다(자막 길이도 음성 길이에 맞춤, 큐 목록은 `<out>.cues.json`). 원본(2560×1440, 7분, 325 MB)과 자막·음성을 입힌 전체본은 `docs/submission/video/`(커밋 제외).
- 재현(11/9 실측 순서): ① `adb logcat -c; adb logcat -v epoch -s LastMile:D > logcat_epoch.txt &` ② `python3 -c 'import time;print(time.time())' > rec_start.txt`(macOS `date` 는 `%N` 이 없다) ③ `adb shell screenrecord --time-limit 0 --bit-rate 20M /sdcard/raw.mp4 &`(기본 제한 180초라 `0` 필수) ④ `SHOT=shots scripts/demo.sh`(약 6분 40초) ⑤ 9초 뒤 `adb shell pkill -INT screenrecord` → `adb pull` ⑥ `scripts/captions.py raw.mp4 logcat_epoch.txt $(cat rec_start.txt) full.mp4 --voice Yuna` ⑦ `scripts/condense.py full.mp4 logcat_epoch.txt $(cat rec_start.txt) out.mp4 --shots shots --speed 8 --start 15.5`(자막·장면 캡처 앞뒤만 1배속, 10초 넘는 빈 주행은 8배속 + 배지; 입력에 소리가 있으면 1배속 구간은 그대로, 배속 구간은 무음). 에뮬레이터 시계는 호스트와 1초 안에서 맞아 epoch 를 그대로 쓴다.
- 11/9 촬영 중 고친 것: 도착 순간은 앱이 스스로 앞으로 나오는데 `activityResumed` 가 아직 false 라 "도착" 헤드업이 앱 위에 5초 겹쳐 떴다 → ARRIVED 전이가 있는 배치의 Speak 는 헤드업을 올리지 않는다(`TripEngine.dispatch`).

## 11. 내비 주행 시뮬레이션 — 11/8 02시 (에뮬레이터 내비를 움직이는 유일한 방법)
- **여는 법**: 안내 중인 내비 화면(전체 창) 오른쪽 아래 볼륨 버튼과 설정 버튼 **사이의 빈 곳**(전체 창 기준 2460,1176)을 1.3초쯤 길게 누르면 "주행 시뮬레이션" 패널이 뜬다(슬라이더 = 속도, 시작/정지/종료). 달리는 중에 다시 열면 같은 자리가 **정지** 버튼이라, 스크립트로 "시작했는지" 판정할 땐 remainDistance 가 100 m 단위로만 바뀌는 점(약 16 m/s → 7초로는 안 바뀔 때가 있음)을 감안해 16초까지 본다(`scripts/demo.sh simulate()`, 11/9 촬영 1회차에서 재시도가 정지를 눌러 2400 m 에서 멈춘 실측). `adb shell input swipe 2460 1176 2460 1176 1300` → 시작 버튼 (2141,772), 종료 (2353,772), 닫기 X (2435,592). 버튼 위를 누르면 볼륨·설정이 열리니 꼭 사이를 누른다.
- 시작하면 내비 차 마커가 경로를 따라 달리고 TBT·남은 거리·ETA·클러스터 제한속도 표시까지 전부 갱신된다. 2.6 km 경로가 약 160초(≈60 km/h)에 끝나며, 도착하면 내비 Trip 이 END → IDLE 로 넘어간다.
- 라스트마일은 NaviHelper remainDistance 를 1차로 쓰므로 GPS 없이도 3 km(도착 6분 전)·1 km·300 m·도착 안내가 그대로 나온다(11/8 02:17 실측: 2500→0 m, 🔊 5문장 전부 발화). 도착 확정은 기어 P(Virtual Console) 가 필요하고, 내린 자리 기억은 FusedLocation 좌표를 쓰므로 도착 직후 `adb emu geo fix 127.10404 37.39967` 한 번은 넣어 준다(시뮬레이션은 FusedLocation 을 움직이지 않는다).
- **GPS 주입은 막다른 길이었다**: 내비(Navi42 3.3.2)는 LocationManager NMEA 리스너로 `$GPGGA`/`$GPGSA` 만 파싱하는데(`NmeaParser`, GPRMC 없음), ranchu GNSS HAL 은 체크섬을 항상 `*47` 로 찍어 전부 버려진다. 고도(GGA)·음수 속도(RMC) 자릿수로 XOR 체크섬을 0x47 에 맞춰 통과시켜도(호스트 시계 초 기준, `scratchpad/nmea_drive.py`) 엔진 입력 `mapmatching org(...)` 은 바뀌지 않았다. 차속 40 km/h(`PUT /vehicle/controls?speed=40&accelerator=25&brake=0&steering=0`)를 함께 줘도 마찬가지. 내비 엔진은 자체 시뮬레이션 외의 위치 소스를 에뮬레이터에서 받지 않는다고 보면 된다.
- Virtual Console `controls` 엔드포인트: `speed`(km/h)·`accelerator`·`brake`(%)·`steering`(deg). 클러스터 속도 표시에 바로 반영된다(영상에서 "0 km/h" 대신 달리는 숫자를 보여 줄 때 쓸 수 있다).

- **반 창에서도 열린다** (11/9 01시 실측): 분할 상태의 내비 창(왼쪽)에서는 볼륨·설정 버튼 사이가 (1619,1176). `adb shell input swipe 1619 1176 1619 1176 1300` → 시작 (1300,772), 종료 (1512,772), 닫기 X (1595,592). 내비 설정(톱니)은 반 창을 지원하지 않는 별도 앱이라 누르면 "창 크기를 넓게 조정하세요" 화면이 왼쪽 슬롯을 차지하고 내비가 오른쪽으로 밀리니 데모 중엔 누르지 않는다.
- **반 창 내비의 차량 마커는 내비 자신의 하단 도착정보 카드(1:03 AM · 한식당 소담 · 안내 종료) 뒤에 놓인다.** 따라가기 모드의 차 위치 앵커가 "창 가로 중앙 · 아래쪽"인데, 전체 창에서는 카드가 왼쪽에 치우쳐 마커가 보이고(2D 기준 x≈1690,y≈1085) 반 창에서는 카드가 중앙을 덮는다. 피해 보려 한 것 전부 실패: 카드 탭·아래로 스와이프(반응 없음), 지도 빈 곳 탭(반응 없음), 3D 전환(마커가 안내 종료 버튼 뒤 오른쪽 아래로 옮겨질 뿐), 지도 위로 끌기(그 순간은 보이지만 다음 위치 갱신에서 따라가기가 다시 가운데로 되돌려 숨긴다), 설정(반 창 미지원). 내비 쪽에서 고칠 방법은 없고 우리 앱 쪽 문제도 아니다 — 영상 구성에서 감수하거나 주행 장면을 내비 전체 창으로 찍는 수밖에 없다.

## 12. 다른 앱 위에 안내를 띄우는 수단 — 11/9 01시 실측
- **헤드업 알림이 된다.** CarSystemUI 에 `HeadsUpNotification` 창이 있고(`dumpsys window windows`), 중요도 HIGH 채널 + `CATEGORY_NAVIGATION` 알림을 올리면 내비가 전체 창이든 분할이든 **앱 영역 왼쪽 위(x≈920~1640, y≈40~200)** 에 제목 + 본문 2줄 카드로 뜬다. 소리·진동 없음, `setTimeoutAfter(8_000)` 대로 약 8초 뒤 사라진다(1.5초·5.5초 캡처에 있고 10.5초엔 없음). 본문은 2줄에서 잘린다(출발 브리핑처럼 긴 문장은 끝이 잘림). 상태바 왼쪽에 앱 아이콘 + 빨간 점이 남는다. 캡처: `/tmp/lastmile_demo/p_hun2.png`(전체 창), `h_1km_t1.png`·`h_300m_split.png`·`h_stopping_split.png`·`h_arrived_split.png`.
- 코드: `TripEngine.notifyGuidance()` — `say()` 가 부를 때마다 채널 `guidance` 로 id 1001 알림을 갱신한다. 권한 `POST_NOTIFICATIONS` 는 런타임 승인 필요: `adb shell pm grant --user $(adb shell am get-current-user) kr.lastmile.app android.permission.POST_NOTIFICATIONS`.
- 쉘에서 올린 알림(`cmd notification post`, 중요도 DEFAULT)은 헤드업으로 뜨지 않았다 → 중요도 HIGH 가 조건.
- **안 되는 것**: `SYSTEM_ALERT_WINDOW`(다른 앱 위 그리기)는 `appops set … allow` 를 줘도 `default` 그대로라 막혀 있다. 상태바를 끌어내려도 알림 센터는 안 열렸다(알림 센터는 상태바의 알림 아이콘). 내비 설정은 반 창 미지원 앱이라 분할 중 누르면 레이아웃이 깨진다.
- 문서 근거: Pleos 디자인 컴포넌트에 "System Notification"(OS 기본 알림, 타입 중 Image 는 "내비게이션 등 알림 수신용")이 있고, 심사 가이드는 "미디어 앱은 헤드업 알림 금지, 주행과 무관한 알림 사용 불가"만 적는다. 주행 안내 알림은 조항 밖이다. 플랫폼 SDK(`ai.pleos.ui.windowstate.WindowStateManager`)는 창 상태(fullscreen·overlay·task slot·이벤트 위젯 패널)를 **조회**만 하고 바꾸진 못한다(BIND_PLEOS_SERVICE 권한, 에뮬레이터 지원 여부 미확인). 창 비율은 Connect-S 1:1/2:0, Connect-W 2:1/1:2(문서 Window Policy).
- 참고: 우측 방향지시등을 켜면 에뮬레이터 내비 창 아래에 측면 카메라 영상 팝업이 뜬다(`h_stopping_split.png`). 데모 캡처 때 알아 둘 것.
