# 설계: 봇 채팅 수신을 WebSocket(STOMP) → 이벤트(APNs+폴링) 전환

> 결정(2026-06-04): 영역 3("재연결중") 근본 해소. 봇방만 남은 현재 상시 WebSocket은 over-engineering.
> 송신은 이미 REST, 결과 통지는 이미 APNs, 복구는 이미 REST 재조회 — STOMP는 "보는 중 즉시 갱신" 하나만 담당.

## 배경

- 봇 채팅은 **요청 → @Async 1턴 처리(수 초) → 결과 1건** 패턴. 양방향 실시간/스트리밍 아님.
- 송신은 이미 `POST /api/v1/chat/bot/messages`(REST). STOMP는 **서버→클라 단방향 결과 푸시**로만 쓰임.
- 결과는 `BotChatProcessor`가 STOMP 발행 + `pushBotResult`(APNs)를 **이미 둘 다** 보냄.
- P7에서 커플챗(사람↔사람 실시간) 제거 → WebSocket의 본래 명분 소멸. STOMP 스택이 비실시간 봇방 하나를 위해 잔존.
- 상시 연결이 "재연결중" 배너·루프·`/ws` 토큰 도달성 문제(영역 3)의 근원.

## 목표 / 비목표

- [목표] 봇 결과 수신을 **APNs 푸시 + 전송 후 짧은 폴링 + scenePhase 재조회**로 전환.
- [목표] iOS `Core/Realtime/*` 및 백엔드 STOMP 설정·발행기 제거. "재연결중" 배너 제거.
- [목표] 기존 REST 송수신·dedup·PROCESSING 교체·reconcile 로직 최대 재사용.
- [비목표] 백엔드 커플방(chat_room COUPLE) 물리 삭제 — 컷오버 시점(BR-2 유지). 본 작업은 **STOMP 경로만** 제거.
- [비목표] 봇 멀티턴/스트리밍 — 범위 밖.

## 수신 3경로 (전환 후)

| 상황 | 수단 | 비고 |
|---|---|---|
| 포그라운드 + 봇방 보는 중 | **전송 후 폴링** `GET /bot/messages`(cursor=nil) | PROCESSING 대기 중에만, 결과 dedup 병합 후 중단 |
| 백그라운드 / 앱 밖 | **APNs `pushBotResult`** (기존) | 탭 → `.chat` 진입 → `appear()`의 `load()`가 최신 반영 |
| 포그라운드 복귀 | **scenePhase `.active` → `reconcileLatest()`** | 알림함이 쓰는 패턴과 동일 |

> 봇은 **사용자 전송으로만** 결과가 생성되므로, "내가 안 보낸 사이 도착한 봇 결과"는 사실상 없음 → 폴링은 *전송 직후*만으로 충분.

## 폴링 전략(BotChatViewModel)

- 트리거: `send()` 성공으로 PROCESSING 추가 직후 폴링 루프 보장(이미 돌고 있으면 재사용).
- 간격: **2초**. 상한: **10회(20초)** — 봇 SLA(`sync-deadline-ms: 4500`)의 약 4배 여유(실측 확정). 초과 시 중단(다음 진입/푸시/복귀가 보완).
- 종료 조건: `pendingProcessingIds` 비면(모든 결과 수신) 즉시 중단 / 화면 이탈(`disappear`) 시 중단 / 상한 도달.
- 동작: 매 틱 `reconcileLatest()` 재사용(cursor=nil 최신 재조회 + `knownIds` dedup + PROCESSING 교체). 신규 로직은 "루프 수명 관리"뿐.
- 동시 다건 전송: 한 루프가 최신 페이지로 N개 결과를 한 번에 병합 → 그대로 흡수.

## 제거 / 유지 / 신규

**iOS 제거**
- `Core/Realtime/StompClient.swift`, `StompFrame.swift`, `ChatRealtimeService.swift`, `ConnectionState.swift`
- `ChatScrollContainer` "재연결 중…" 배너, `BotChatViewModel.realtimeState`/구독/옵저버
- `AppDependencies`·`MainTabView`의 realtime 조립·주입
- 테스트: `StompFrameTests.swift` 삭제, `BotChatViewModelTests.swift` 폴링 기반 재작성

**iOS 유지/재사용**
- `ChatAPI`(REST 송수신), `knownIds` dedup, `pendingProcessingIds` FIFO, `reconcileLatest()`, `savePlaceCards`
- `AppNotificationDelegate → DeepLinkRouter.handlePush(.chat)`(기존 푸시 라우팅)

**iOS 신규**
- BotChatViewModel 폴링 루프(간격/상한/종료) + scenePhase `.active` 재조회 훅

**백엔드 제거**
- `config/websocket/WebSocketStompConfig.java`, `StompAuthChannelInterceptor.java`, `domain/chat/ChatStompPublisher.java`
- `BotChatProcessor.publishSafely`의 `publisher.publishBot(...)` 호출(**`pushBotResult`는 유지**)
- `CoupleChatService`의 `publishCouple(...)` 호출(서비스 자체는 잔존, STOMP 경로만 제거)
- 의존성: `spring-boot-starter-websocket`(다른 사용처 없으면 제거)

**백엔드 유지**
- `ChatV1Controller` REST, `BotChatProcessor` 처리 본체, `PushNotificationService.pushBotResult`

## 단계 / 안전

1. **iOS 먼저**: 폴링 전환 배포(STOMP 구독은 어차피 연결 실패 상태라 무영향). 봇방 정상 동작 확인.
2. **백엔드**: STOMP 설정·발행기 제거(클라가 더는 구독 안 함). `publishBot/Couple` 제거.
3. 롤백: 단계별 독립 커밋 → revert 단위 분리. 백엔드 STOMP 제거 전까지 기존 클라와 호환(발행은 best-effort).

## 미해결 질문(착수 전 확정)

- [ ] 폴링 간격/상한 — 봇 `PlaceProperties.search().syncDeadlineMs()` 실측값에 맞춰 확정.
- [ ] 포그라운드 푸시 수신 시 봇방 자동 reconcile 배선을 둘지(폴링으로 충분하면 생략).
- [ ] `spring-boot-starter-websocket` 외 STOMP 잔여 의존 유무 최종 확인.
- [ ] iOS↔백엔드 배포 순서(1→2) 합의.

## 테스트 영향

- iOS: `StompFrameTests` 삭제. `BotChatViewModelTests`를 "전송→폴링→결과 교체/중단", "상한 종료", "이탈 시 중단"으로 재작성. dedup/PROCESSING 교체 단언 유지.
- 백엔드: STOMP 발행 단언 제거. `BotChatProcessor` 푸시 트리거(PLACE_CARDS만) 단언 유지. WebSocket IT 제거.
