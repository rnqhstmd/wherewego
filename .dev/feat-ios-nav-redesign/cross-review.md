# Cross-Review 결과

- advisor: claude (qa-manager + security-auditor 병렬, cross-review 미션)
- 브랜치: feat/ios-nav-redesign (base: develop)
- DEV_DIR: .dev/feat-ios-nav-redesign
- 실행: 2026-06-03

## AC 충족 매트릭스

| AC | 충족 | 근거 |
|----|------|------|
| AC-1 | O | FloatingTabBar.swift:12-17 MainTab 4개, couple 없음 |
| AC-2 | O | FloatingTabBar.swift:82-84 plusButton onPlusTap만, selection 불변 (MainTabTests) |
| AC-3 | O | DeepLinkRouter.swift:16-21 .coupleChat/.botChat 없음 |
| AC-4 | O | DeepLinkRouter.swift:65-68 COUPLE_MESSAGE→.chat |
| AC-5 | O | ReverseGeocoder.swift:58-87 Debouncer generation 토큰 (ReverseGeocoderTests 3회→1회) |
| AC-6 | O | Couple/SearchPin/Crosshair 전체 미존재 |
| AC-7 | O | NotificationAPI.swift:91-105 3엔드포인트 + 좌표 Double? number 디코딩 (NotificationAPITests) |
| AC-8 | O | AddPlaceViewModel.swift:107 onMapMoved query="" + .pinpoint |
| AC-9 | O | ReverseGeocoder.swift:28-30 coordinateFallback "위도 37.1235, 경도 127.5679" |
| AC-10 | O | MyInfoViewModel.swift:14 shouldShowGroupSection (양 분기 테스트) |
| AC-11 | O | MyInfoView 3섹션만, 챗봇 없음 (Mirror 검사) |
| AC-V1~V10 | 코드 존재 O / 시각검증 DoD-B 이연 | FloatingTabBar 버전분기·＋flush·선택표시 / MapView 플로팅 / AddPlaceSheet 단일시트 / BotChatView 직행 / NotificationInbox 상태분기·flyTo / MyInfo 확인다이얼로그 / 빈·로딩·에러 / 검색 무결과 |

**[Must] AC-1~11 11/11 충족(코드+테스트). AC-V1~V10 10/10 코드 존재(시각검증 DoD-B 이연). 21/21.**

## 설계 범위 이탈

이탈 없음.

- 참고: design.md가 "GroupAPI 스텁 7곳"으로 명시했으나 실제 9곳(+AddPlaceViewModelTests·MyInfoViewModelTests 신규 in-file StubGroupAPI). 두 파일 모두 B4(신규 테스트) 범위이고 GroupAPIProtocol 구현 스텁은 컴파일 필수라 정당. 이탈 아님.

## 신규 위험 (trust-ledger·self-check 외)

### Warning
- **[GAP] 알림 list 중복 호출 → unreadCount 배지 순서 역전/재점등**
  - 위치: MainTabView.swift:113-125(.task + scenePhase .active 둘 다 onForeground), NotificationInboxView.swift:31(.task load), NotificationInboxViewModel.swift:86-93
  - 근거: 콜드스타트 시 .task와 초기 .active가 거의 동시 발화 → list 2회. 알림 탭 즉시 진입 시 load()까지 3중. readAll은 didReadAll 가드 있으나 onForeground의 list는 가드 없어 낙관적 unreadCount=0이 서버값으로 원복(배지 재점등)될 수 있음. (trust-ledger LOW "onForeground 중복"의 확장 — 배지 역전 시나리오 추가)
  - 권고: MainTabView .task의 onForeground 제거(scenePhase 단일 경로) 또는 onForeground에 in-flight 가드.

### Info
- **[ASSUMPTION] AddPlaceViewModel.mapViewModel weak 해제 시 검색/생성 무음 실패** — 위치 AddPlaceViewModel.swift:56,73,142. MainTabView 선해제는 사실상 불가라 위험 낮음. `guard let mapViewModel else { errorMessage=...; return }` 하드닝 선택.
- **[GAP] AddPlaceSheet @StateObject 시트 재오픈 시 VM 재생성** — 시트 1회=1생성 = 설계 의도 부합. 결함 아님(참고).
- **[GAP] 앱 첫 실행 네트워크 지연 시 unreadCount 0 고착** — fail-safe(배지 미표시 방향), 위험 낮음. UserDefaults 캐시로 완화 가능.

## references 위반

위반 없음 (references/ 디렉토리 없음).

## 설계 결정 확인 필요 (보안 MEDIUM)

- **[GAP] 알림 종류 푸시(VISIT_DETECTED/MANUAL_PIN/CHATBOT_PINS) → DeepLinkRouter 미매핑**
  - 위치: DeepLinkRouter.swift:61-72 (현재 BOT_RESULT/COUPLE_MESSAGE→.chat, PIN_SAVED→.map만)
  - 근거: 알림함 NotificationType 3종이 APNs 푸시로도 발송된다면 푸시 탭 시 default→nil(no-op). 알림함이 신규 추가됐으므로 알림 푸시 탭 → 알림 탭 이동 경로가 없음.
  - 설계 입장: design.md §3이 "`.notification` case 미도입(YAGNI — 현재 푸시 type 없음)"으로 **의도 제외**. NotificationType은 목록 API 분류이고 APNs 푸시 type과 별개 네임스페이스일 수 있음(미검증 가정).
  - 권고: 백엔드가 실제 이 3종을 APNs type으로 보내는지 확인. 보낸다면 `.notification` 딥링크 추가, 아니면 현행 유지(YAGNI 정당).

## 정책(PRD/BR) vs 코드 — 전부 정합
BR-1(＋selection 불변)·BR-3(역지오 300ms)·BR-4(read-all 1회·실패무시)·BR-5(탈퇴·삭제 확인다이얼로그)·BR-6(목록 실패 재시도)·FR-11/BR-2(커플챗 삭제)·FR-14·19·20·22·25·28·FR-4 전부 코드 정합 확인.

## 총평
- 강점: 설계서 6개 핵심 결정(좌표 Double?·독립맵 cameraIdle·GroupAPIProtocol 스텁·＋2진입1컴포넌트·서버 unreadCount·didReadAll 1회) 빠짐없이 반영. Debouncer generation 토큰·읽음 처리 분리(load vs onForeground)가 설계 의도 충실.
- 합산: Critical 0, 보안 CRITICAL/HIGH 0, Warning 1(알림 list 중복/배지 역전), Info 3, 설계결정 확인 1(알림 푸시 딥링크).
- 권고: AC 21/21 충족·이탈 없음으로 머지 가능. Warning 1건(알림 중복 호출 가드)은 머지 전 수정 권장, 푸시 딥링크는 백엔드 확인 후 결정.
