# 설계 초안: Phase 8 — 인앱 알림함 (SSE) — architect 출력

## 설계 규모
**대형** — 신규 도메인(notification) + SSE 인프라(Registry/Emitter/Heartbeat) + 4개 핀 트리거 통합 + FE 알림 패널/벨/말풍선/SSE 클라이언트의 대규모 UX 추가.

## 아키텍처 개요

```
[Pin/Chatbot 핸들러]
       │ (커밋 완료 후)
       ▼
  NotificationService.create...()
       │  ① DB insert(Notification + NotificationPin*) — @Transactional(REQUIRES_NEW)
       │  ② ApplicationEventPublisher.publishEvent(NotificationCreatedEvent)
       ▼
@TransactionalEventListener(phase=AFTER_COMMIT)
       │
       ▼
  NotificationSseRegistry.push(receiverId, payload)
       │  (in-memory ConcurrentHashMap<userId, CopyOnWriteArrayList<SseEmitter>>)
       ▼
  각 SseEmitter.send(...)  ← 실패 시 emitter complete+remove

[NotificationHeartbeatScheduler @Scheduled(fixedRate=30s)]
   → registry.broadcastHeartbeat() — comment ": heartbeat" 발사
```

### 트랜잭션 분리(BR-3) 근거
- 핀 저장 트랜잭션과 알림 생성 트랜잭션을 분리해야 알림 실패가 핀 롤백을 유발하지 않는다.
- PinService.addPin은 이미 `@Transactional`이므로 Controller에서 커밋 완료 후 NotificationService 별도 호출.
- 챗봇 핸들러는 PinService를 여러 번 호출하므로 결과를 모아 마지막에 1회 호출.

### 경로별 트리거
- **웹 직접 등록**: PinV1Controller에서 addPin 리턴 후 notificationService.createForManualPin(...) 호출.
- **챗봇 핸들러**: 핸들러 종료 직전 savedPinIds 리스트로 createForChatbotBatch(...) 호출. NotificationService 자체가 @Transactional(REQUIRES_NEW).

## 데이터 모델 (V007 SQL 초안)

```sql
CREATE TABLE IF NOT EXISTS notifications (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES groups (id),
    receiver_id BIGINT NOT NULL REFERENCES users (id),
    registered_by BIGINT NOT NULL REFERENCES users (id),
    type VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at TIMESTAMPTZ,
    CONSTRAINT chk_notifications_type
        CHECK (type IN ('MANUAL_PIN', 'CHATBOT_PINS'))
);

CREATE INDEX IF NOT EXISTS idx_notifications_receiver_created
    ON notifications (receiver_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notifications_receiver_unread
    ON notifications (receiver_id) WHERE read_at IS NULL;

CREATE TABLE IF NOT EXISTS notification_pins (
    id BIGSERIAL PRIMARY KEY,
    notification_id BIGINT NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    pin_id BIGINT NOT NULL REFERENCES pins (id),
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_notification_pins_pair UNIQUE (notification_id, pin_id)
);

CREATE INDEX IF NOT EXISTS idx_notification_pins_notification_id
    ON notification_pins (notification_id);
```

### 핵심 결정
- `receiver_id` 추가: 알림 1건 = 1수신자. 미읽음 인덱스 단순화. (확인 필요 Q3)
- 영구 보관(BR-2). 만료/정리 정책 없음.

## 백엔드 컴포넌트

### Notification (Entity)
groupId, receiverId, registeredBy, type(MANUAL_PIN/CHATBOT_PINS), readAt 보유.

### NotificationPin (Entity)
notificationId, pinId, sortOrder.

### NotificationRepository (port) + Adapter
- save, saveAllPins, findRecentByReceiverId, findByIdAndReceiverId, findPinsByNotificationIds(N+1 회피), countUnread, markAllReadByReceiverId(bulk UPDATE)

### NotificationService
- `createForManualPin(groupId, registeredBy, pinId)` — @Transactional(REQUIRES_NEW)
- `createForChatbotBatch(groupId, registeredBy, List<Long> pinIds)` — empty면 no-op (FR-4/BR-5)
- `listRecent(receiverId, 50)` — readOnly
- `getDetail(notificationId, receiverId)` — readOnly
- `markAllRead(receiverId)` — write

### NotificationSseRegistry
- ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>
- register(userId): 5분 타임아웃, onCompletion/onTimeout/onError → 제거, connected 이벤트 발사
- push(userId, event): IOException 시 제거+completeWithError
- broadcastHeartbeat(): 모든 emitter에 ": heartbeat" comment
- removeEmitter(userId, emitter): list.isEmpty()면 map에서 (userId, list) 비교 삭제

### NotificationCreatedEvent + Listener
- record(receiverId, notificationId, type, registeredBy, registeredByNickname, firstPlaceName, totalPinCount, createdAt)
- @TransactionalEventListener(phase=AFTER_COMMIT) → registry.push

### NotificationHeartbeatScheduler
- @Scheduled(fixedRate=30s) → registry.broadcastHeartbeat()
- @EnableScheduling 활성 필요 (Q7)

### NotificationV1Controller
- GET /api/v1/notifications/stream (produces=TEXT_EVENT_STREAM_VALUE) → SseEmitter
- GET /api/v1/notifications → list (최대 50건 + unreadCount)
- POST /api/v1/notifications/read-all → updatedCount
- GET /api/v1/notifications/{id} → detail (pins[])

### GroupMemberRepository 확장
- findOtherActiveMemberIds(groupId, excludeUserId): List<Long>
- JPQL: leftAt IS NULL + userId != exclude

### 트리거 통합
- **PinV1Controller.createPin**: addPin 호출 후 createForManualPin (Q4 권장 (a))
- **InstagramLinkHandler**: handleCandidates 종료 / handleLegacySingle 단건 저장 성공 / handleGoogleFallback 단건 저장 성공 3곳. tryRegister 시그니처에 List<Long> savedPinIds 추가
- **PlaceSelectionHandler**: result.alreadyExisted() false 분기 직후 트리거

### 호출자 try-catch
```java
try { notificationService.createForXxx(...); }
catch (RuntimeException e) { log.warn("notification failed", e); }
```

## SSE 설계
- Registry 동시성: ConcurrentHashMap + CopyOnWriteArrayList (락 없음)
- Heartbeat: 30s comment 이벤트
- 인증: 기존 cookie JWT, SecurityConfig 변경 없음, async dispatch SecurityContext 자동 전파
- CORS: allowCredentials=true, EventSource withCredentials 명시

## API 설계
| Method | Path | 인증 | 응답 |
|--------|------|------|------|
| GET | /api/v1/notifications/stream | JWT | text/event-stream |
| GET | /api/v1/notifications | JWT | items + unreadCount |
| POST | /api/v1/notifications/read-all | JWT | updatedCount |
| GET | /api/v1/notifications/{id} | JWT | detail with pins |

SSE 이벤트 페이로드: {id, type, registeredByNickname, firstPlaceName, totalPinCount, createdAt}

## 프론트엔드

### 컴포넌트 트리
```
MapClient (수정)
├─ MobileTopNav (수정: 우상단 프로필 → 벨 교체)
│   └─ NotificationBell (신규)
├─ DesktopActionPill (수정: 벨 슬롯 추가)
│   └─ NotificationBell
├─ NotificationToast (신규: 5초 자동 닫힘 + 외부 탭)
├─ NotificationPanel (신규: Sheet/SidePanel)
│   ├─ NotificationItem[]
│   └─ NotificationPinList
└─ PinPopup (기존)
```

### useNotifications 훅
- state: items, unreadCount, connectionState, toast
- actions: open(read-all), markAllRead, refreshList, dismissToast, loadDetail
- 패널 열려있으면 toast 미노출 + 즉시 markAllRead (AC-17)
- shownToastIds Set으로 중복 차단 (AC-16)

### sseClient
- EventSource(url, { withCredentials: true })
- 지수 백오프 2→4→8→16→30s, 최대 5회, 실패 시 failed 전이
- 정상 연결 시 카운터 reset

### NotificationToast
- SpeechBubblePopup은 핀 좌표 기반이라 재사용 어려움
- 동일 톤(panel/hairline/shadow)으로 자체 컴포넌트 구현
- 위치: position absolute, top: 58, right: 14 (벨 바로 아래)

### MapClient 핸들러
```ts
handleSelectPinFromNotification(pin) {
  closeNotificationPanel();
  if (!map || pin.deleted) return;
  map.flyTo({...});
  setSelectedPinId(pin.pinId);
}
```

## 변경 범위

### 신규 (Backend 17개)
- V007__create_notifications.sql
- domain/notification/{Notification, NotificationPin, NotificationType, NotificationRepository, NotificationService, NotificationSseRegistry, NotificationCreatedEvent, NotificationHeartbeatScheduler, NotificationView, NotificationPinView}
- infrastructure/notification/{NotificationJpaRepository, NotificationPinJpaRepository, NotificationRepositoryAdapter}
- interfaces/api/notification/{NotificationV1Controller, NotificationV1ApiSpec, NotificationV1Dto}

### 신규 (Frontend 9개)
- lib/notifications/{types, api, sseClient, useNotifications}
- map/_components/notifications/{NotificationBell, NotificationToast, NotificationPanel, NotificationItem, NotificationPinList}

### 수정 (Backend 6개)
- PinService.java (Controller가 호출이므로 PinService 자체는 무변경 — 수정 대상에서 제외)
- InstagramLinkHandler.java, PlaceSelectionHandler.java
- GroupMemberRepository.java + Impl + JpaRepository
- SecurityConfig.java (확인 후 무변경 가능)
- application.yml (notification.sse.* 추가)
- PinV1Controller.java (createPin 트리거)

### 수정 (Frontend 3개)
- MobileTopNav.tsx
- DesktopActionPill.tsx
- MapClient.tsx

## 구현 순서 (24단계)
1. V007 마이그레이션 작성 (의존 없음)
2. Entity + enum
3. Repository(port + impl)
4. GroupMemberRepository.findOtherActiveMemberIds (의존 없음, 1과 병렬)
5. NotificationService
6. SseRegistry + Event + Listener
7. HeartbeatScheduler + @EnableScheduling
8. Controller + Spec + Dto
9. PinV1Controller 트리거
10. InstagramLinkHandler 트리거
11. PlaceSelectionHandler 트리거
12. NotificationServiceIT
13. NotificationV1ControllerIntegrationTest
14. NotificationSseRegistryTest
15. FE types + api
16. FE sseClient
17. FE useNotifications
18. NotificationBell
19. NotificationToast
20. Panel + Item + PinList
21. MobileTopNav + MapClient 통합
22. DesktopActionPill 통합
23. FE Vitest
24. FR-20 빈 상태 UI

병렬화 가능: 1+4, 15+16(8 이후), 9+10+11(5 이후)

## 테스트 전략
- Backend IT: 본인 알림 0건, 상대방 1건, N개 핀 1알림, empty no-op, markAllRead, soft delete 후 deleted=true, notification 실패해도 Pin 잔존
- SseRegistryTest: 자동 제거, 다중 register, heartbeat
- ControllerIT: 401, 200 SSE, push, read-all
- FE Vitest: 초기 fetch, prepend+toast, 패널 open시 toast 미노출, 중복 id 차단, 백오프, dot 표시/제거

## 준수 규약
- 패키지: domain/notification, infrastructure/notification, interfaces/api/notification
- Lombok @RequiredArgsConstructor
- @Transactional / @Transactional(readOnly=true) / @Transactional(REQUIRES_NEW)
- 도메인 이벤트 record + AFTER_COMMIT
- nested record DTO
- 에러: 신규 ErrorType 불필요 (401 + NOT_FOUND)
- Flyway V006 단일 트랜잭션 패턴
- 프론트: Sheet/SidePanel, useMediaQuery, apiFetch, _components 컨벤션
- useEffect cleanup + AbortController

## 자가 검증

### 유저 경험
- NotificationToast 톤은 MobileTopNav 말풍선 패턴 차용
- 알림 패널은 Sheet/SidePanel 재사용
- flyTo+PinPopup은 룰렛 "지도에서 보기"와 동일

### 트랜잭션·동시성
- 핀 트랜잭션 종료 후 NotificationService 호출 (REQUIRES_NEW)
- 호출자 try-catch로 격리
- ConcurrentHashMap + CopyOnWriteArrayList
- AFTER_COMMIT 리스너

### 확장성 vs 단순성
- 단일 EC2 SseEmitter (ADR-0001), 수평 확장 시 Registry만 추상화
- 본 Phase에서는 추상화 안 함 (YAGNI)
- 영구 보관: MVP 규모 충분, 향후 파티셔닝 별도 Phase

## 확인이 필요한 사항 (8건)
Q1. 모바일 우상단 마이페이지 진입 경로 (벨 교체 후)
Q2. 데스크탑 알림 패널 위치 (left:66 vs right:14)
Q3. receiver_id 컬럼 도입 (단일 vs 별도 reads 테이블)
Q4. 트리거 호출 위치 (Controller vs PinService 파사드)
Q5. 알림 상세 핀 데이터 출처 (full response vs pinId only)
Q6. 삭제된 핀 응답 방식 (이름 유지 vs 전체 null)
Q7. @EnableScheduling 추가 위치
Q8. SSE 재연결 5회 실패 후 UI 표현
