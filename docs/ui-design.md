# 라스트마일 UI/UX 설계 노트 (Pleos Connect 디자인 가이드 기준)

> 2026-11-05. 근거: document.pleos.ai 디자인 가이드(Introduction·Display Layout·Window Policy·Typography·Colors·Interaction), 앱 리뷰 가이드(운전자 주의 분산·기능/성능 안정성), 앱 구조 모델, 주행 규제 개발 가이드. 순정 내비·클러스터 화면을 시각 레퍼런스로 사용.

## 1. 디자인 원칙 → 적용
| Pleos 원칙 | 적용 |
|---|---|
| Easy & Safe: 1~2초 안에 파악, 한 손 조작 | 화면당 핵심 정보 1개(남은 거리 120sp) + 안내 1문장(40sp) + 사실 3줄. 버튼은 화면 하단 1~2개 |
| 동영상·애니메이션·자동 스크롤 금지 | 상태 전환 시 200ms 크로스페이드만. 반복 애니메이션 없음 |
| 응답 0.25초 | 터치 즉시 95% 수축(80ms) — `Pressable.kt`, 가이드 "shrink effect 95%" 그대로 |
| 일관성(기본 탑재 UI와 통일) | 순정 라이트 테마, 흰 카드 + 검정 필 버튼, 28dp 라운드, 상단 타이틀+칩, 회색 스케일 토큰 그대로 사용 |
| Portrait·Landscape 모두 설계 | `layout/`(2열) · `layout-port/`(1열 스크롤) 분리. 매니페스트에서 고정 방향 제거 |
| CarUxRestrictions 준수 | `UxRestrictions.kt`: NO_KEYBOARD 시 수신자 입력·텍스트 입력 차단 |
| 텍스트 입력보다 프리셋·음성 우선 | 목적지는 타일 선택, 질문은 음성(🎤). 키보드는 정차 중 설정에서만 |
| 상태 복원 | 엔진이 Application 에 상주, 화면 복귀 시 현재 상태 재렌더 |

## 2. 디자인 토큰 (`res/values/colors.xml`)
- Basic scale (Pleos Colors 문서): `#131417 #44464E #686A72 #A4A8AE #C7C9CE #E0E1E6 #EDEEF2 #F7F8FA #FFFFFF`
- Informative: active `#00E074`, positive `#0082FF`, negative `#FF4C28`
- Semantic: background=basic_800, surface=white, text=basic_100/300, button_primary=basic_100, accent=positive
- 다크: `values-night/colors.xml` (배경 #0E0F12, 카드 #1A1C21, 기본 버튼 흰색)
- 타입 스케일 (`dimens.xml`, 160dpi 기준 1dp=1px): display 120 / headline 56 / title 40 / body 30 / label 24 / caption 20 (최소 12sp 권장 충족)
- 컴포넌트: 필 버튼 96dp, 아이콘 버튼 88dp, 카드 패딩 32, 페이지 패딩 40, 토스트 가이드 패딩 24

## 3. 화면
| 상태 | 화면 | 핵심 |
|---|---|---|
| IDLE | 출발 전 | "어디로 데리러 가세요?" + 목적지 타일(아이콘·이름·분류) + 마중 알림 카드(스위치) + [안내 시작] |
| ARMED~BRIEF | 안내 중 | 목적지명 / 남은 거리(hero) / ETA 칩 / 마지막 안내 문장 / 정차·발렛·주차 카드 / 마중 상태 카드 / [🎤 물어보기] [안내 종료] |
| STOPPING | 안내 중 + 배너 | 호박색 배너 "잠시 정차 · N분까지" |
| ARRIVED | 도착 | 녹색 체크 / "도착했어요" / 주차 요금 / 보낸 마중 메시지 / [완료] |
| 음성 | 하단 시트 | "듣고 있어요" + 예시 → 인식 문장 + 답변(5초 후 자동 닫힘). 에뮬레이터(mock)만 텍스트 입력 노출, 주행 규제 시 숨김 |
| 설정 | 하단 시트(확장) | 받는 사람 이름·번호. 주행 중 입력 불가 안내. 버전 5회 탭 → 개발자 패널(mock 신호·로그) |

## 4. 심사 체크리스트 대조
- [x] 실행 10초 이내 (실측 1~2초) · 콘텐츠 로드 10초 이내
- [x] 터치 응답 0.25초 (즉시 수축) · 버튼 반응 2초 이내
- [x] 주행 중 동영상/자동 애니메이션 없음
- [x] Portrait/Landscape 레이아웃 (세로는 에뮬레이터 `wm size` 로 확인)
- [x] CarUxRestrictions 수신·반영 (키보드 제한)
- [x] 백그라운드 복귀 시 상태 복원 (엔진 Application 상주)
- [x] Adaptive icon (fg/bg 분리)
- [ ] 더미 데이터 금지 — 데모 목적지 5곳은 "내장 샘플"이 아니라 실제 장소 데이터로 교체 필요 (제출 전)
- [ ] 릴리즈 서명 APK (keystore 생성 후 `assembleSdkRelease`)
- [ ] 폼팩터 테스트(12.9" Portrait, 14.6" Landscape) — FormFactorConsole 앱으로 재확인
- [ ] Vehicle SDK 퍼미션(CAR_DRIVE·CAR_LIGHTS) 앱마켓 사전 협의

## 5. 알려진 제약
- `안내 종료` 시 내비 경로 취소 → 내비가 앞으로 옴. 앱으로 돌아오려면 GNB 앱 라이브러리에서 선택 (Pleos 윈도 정책).
- 에뮬레이터 온디바이스 TTS 오류 → Android TTS 폴백. 실차는 Pleos TTS.
- Virtual Console HTTP API(`PUT /vehicle/gear`)는 콘솔 페이지가 브라우저에 열려 있을 때만 200.
