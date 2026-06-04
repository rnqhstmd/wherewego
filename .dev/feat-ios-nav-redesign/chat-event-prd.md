# PRD: 봇 채팅 수신 WebSocket(STOMP) → 이벤트(APNs + 제한 폴링) 전환 (hotfix)

> 설계서(SSOT): `chat-event-migration.md`. 본 PRD는 설계 의도를 요구사항/수용 기준으로 정형화한다.
> 확정(2026-06-04): 폴링 상한 = **2초 간격 × 10회(20초)** (봇 SLA `sync-deadline-ms: 4500` 실측 반영). 포그라운드 APNs 즉시 reconcile = **생략**(폴링 + scenePhase로 커버). `spring-boot-starter-websocket` 제거 = 확정(사용처 STOMP 전용 확인).

## 배경

봇 채팅 결과 수신이 상시 WebSocket(STOMP) 연결에 의존한다. 송신은 이미 REST, 결과 통지는 이미 APNs(`pushBotResult`)로 전송되나, "보는 중 즉시 갱신" 하나를 위해 STOMP 스택 전체가 잔존한다. 이 상시 연결이 "재연결중" 배너가 지속되는 영역 3 장애의 근원이다.

변경 이유:
- **notification 도메인은 2026-05-21에 이미 SSE/WebSocket을 제거하고 "사용자 행위 트리거 fetch"로 전환**했다(ADR-0001/0002: 외부 인프라 의존 최소화). 봇 채팅 STOMP만 이 아키텍처 철학과 불일치다.
- 봇 채팅의 본질은 "요청 → 수 초(@Async, SLA 4.5초) → 결과 1건" 패턴이다. 양방향 실시간 스트리밍이 아니므로 상시 소켓은 과잉 설계다.
- P7에서 커플챗(사람↔사람) 제거로 WebSocket 스택의 본래 명분이 소멸했다.

## 목표

- 영역 3("재연결중" 배너 지속) 장애를 구조적으로 제거한다.
- 봇 결과 수신을 **APNs 푸시 + 전송 직후 제한 폴링 + scenePhase 재조회**의 3경로로 전환한다.
- iOS STOMP 스택(`Core/Realtime/*`) 및 백엔드 STOMP 설정·발행기를 제거하여 운영 복잡도를 낮춘다.
- 기존 REST 송수신, dedup, PROCESSING 교체, `reconcileLatest` 로직을 최대 재사용한다.

## 요구사항

### 기능 요구사항

- [Must] FR-1: 봇 전송 성공 직후, PROCESSING 상태 메시지가 남아 있는 동안에만 폴링 루프를 실행한다. PROCESSING이 모두 해소되면 루프를 즉시 중단한다.
- [Must] FR-2: 폴링 간격은 **2초**, 상한은 **10회(20초)**로 한다(봇 SLA 4.5초의 약 4배 여유). 상한 도달 시 루프를 중단한다(다음 진입/푸시/복귀가 보완).
- [Must] FR-3: 사용자가 봇방 화면을 이탈(`disappear`)하면 폴링 루프를 즉시 중단한다.
- [Must] FR-4: 포그라운드 복귀 시(`scenePhase .active`) `reconcileLatest()`를 호출하여 최신 상태를 반영한다.
- [Must] FR-5: iOS `Core/Realtime/*` 4개 파일(StompClient, StompFrame, ChatRealtimeService, ConnectionState)을 제거한다. ChatScrollContainer의 "재연결중" 배너를 제거한다.
- [Must] FR-6: 백엔드 `WebSocketStompConfig`, `StompAuthChannelInterceptor`, `ChatStompPublisher`를 제거한다. `BotChatProcessor`의 `publishBot` 호출을 제거한다(`pushBotResult`는 유지). `CoupleChatService`의 `publishCouple` 호출을 제거한다(서비스 본체는 유지).
- [Must] FR-7: 배포 순서를 iOS 먼저 → 백엔드로 한다. iOS 전환 배포 후 봇방 정상 동작 확인 뒤 백엔드 STOMP 제거를 진행한다.
- [Must] FR-8: `spring-boot-starter-websocket` 의존성을 제거한다. (사용처가 STOMP 채팅 3파일 전용임을 확인 — 보류 조건 해소.)
- [Should] FR-9: 다회 연속 전송 시 폴링 루프가 실행 중이면 새 루프를 중복 생성하지 않고 재사용한다. 최신 페이지 재조회(`reconcileLatest`)로 N개 결과를 한 번에 병합한다.

### 비즈니스 규칙

- [Must] BR-1: APNs 푸시 발송(`pushBotResult`)·수신 라우팅(`AppNotificationDelegate → DeepLinkRouter.handlePush(.chat)`)을 변경하지 않는다.
- [Must] BR-2: 백엔드 커플방 데이터(chat_room COUPLE)를 물리 삭제하지 않는다. 본 작업은 STOMP 경로 제거만 포함한다.
- [Must] BR-3: 폴링은 사용자가 직접 전송한 직후로만 한정한다. 앱 상시 폴링(`setInterval` 동등)은 허용하지 않는다(notification 도메인 "상시 폴링 회피" 정합).
- [Should] BR-4: 폴링 상한(10회/20초) 초과 후 결과 미수신 시, 다음 앱 진입/APNs 탭/scenePhase 복귀의 `reconcileLatest()`가 보완 수신한다. 별도 오류 안내는 제공하지 않는다.

## 수용 기준

### 정적 검증 / 단위테스트로 확인 가능

- AC-1: iOS `Core/Realtime/`에 StompClient·StompFrame·ChatRealtimeService·ConnectionState 파일이 없다. [FR-5]
- AC-2: ChatScrollContainer에 "재연결중" 배너 뷰 요소가 없다. [FR-5]
- AC-3: 백엔드에 WebSocketStompConfig·StompAuthChannelInterceptor·ChatStompPublisher 클래스가 없다. [FR-6]
- AC-4: BotChatProcessor에 `publishBot` 호출이 없고 `pushBotResult` 호출이 남아 있다. [FR-6, BR-1]
- AC-5: 백엔드 단위테스트 — BotChatProcessor가 PLACE_CARDS 결과 시 `pushBotResult`를 호출한다(STOMP 발행 단언 없음). [FR-6, BR-1]
- AC-6: iOS 단위테스트(`BotChatViewModelTests`) — 전송 성공 → PROCESSING 추가 → 폴링 시작 → 결과 수신 → PROCESSING 교체 → 루프 중단. [FR-1]
- AC-7: iOS 단위테스트 — 폴링이 **10회** 도달 시 중단된다. [FR-2]
- AC-8: iOS 단위테스트 — `disappear` 시 폴링 루프가 중단된다. [FR-3]
- AC-9: iOS 단위테스트 — 다회 연속 전송 시 폴링 루프가 중복 생성되지 않는다. [FR-9]
- AC-10: `StompFrameTests`가 삭제된다. [FR-5]
- AC-11: `CoupleChatService`에 `publishCouple` 호출이 없고 서비스 본체 클래스는 존재한다. [FR-6, BR-2]
- AC-12: 백엔드 `build.gradle.kts`에 `spring-boot-starter-websocket` 의존이 없고 `./gradlew build`가 성공한다. [FR-8]

### 빌드 / 실기기 필요 (DoD-B, Mac)

- AC-B1: 실기기에서 봇 전송 후 수 초 내 결과 표시 + "재연결중" 배너 미출현. [FR-1, FR-2, FR-5]
- AC-B2: 봇방에서 백그라운드 전환 후 복귀 시 미수신 결과 반영. [FR-4]
- AC-B3: APNs 권한 거부 기기에서 봇 전송 후 폴링으로 결과 수신. [FR-1, FR-2]
- AC-B4: 봇 전송 직후 화면 이탈 → 폴링 중단, 재진입 시 `reconcileLatest()` 반영. [FR-3, FR-4]
- AC-B5: 백그라운드 APNs 탭 → 봇방 진입 시 결과 표시. [BR-1]

## 안 하는 것 / 보류

- 포그라운드 APNs 수신 시 즉시 reconcile 배선 — 폴링 + scenePhase 복귀로 충분(확정 생략).
- 앱 상시 폴링(setInterval) — notification 정책 정합으로 금지.
- 백엔드 커플방(chat_room COUPLE) 물리 삭제 — 컷오버 시점.
- iOS 빌드·실기기 검증(DoD-B) — Mac 환경 필요. 본 환경(Windows)에서는 백엔드 `./gradlew`만 검증 가능.
