# notification 아키텍처

## 전체 흐름

```
[ PinV1Controller / 챗봇 Handler ] (트랜잭션 밖)
       │
       │  try { notificationService.createFor*(groupId, userId, pinIds) }
       │  catch (RuntimeException e) { log.warn(...) }   ← BR-3 호출자 격리
       ▼
[ NotificationService ] @Transactional (REQUIRED)
       │  1) groupMemberRepository.findOtherActiveMemberIds(groupId, registeredBy)
       │  2) 각 receiverId마다:
       │     - insert Notification(receiverId, registeredBy, type)
       │     - insert NotificationPin[] (sort_order 0..N-1)
       │     - eventPublisher.publishEvent(NotificationCreatedEvent)
       ▼  (메서드 종료 = 트랜잭션 커밋)
[ NotificationSsePushListener ] @TransactionalEventListener(AFTER_COMMIT)
       │
       ▼
[ NotificationSseRegistry.push(receiverId, event) ]
       │  ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>
       ▼
[ 각 SseEmitter.send(event:"notification") ]
   ← IOException / IllegalStateException → removeEmitter + completeWithError

[ NotificationHeartbeatScheduler ] @Scheduled(fixedRate=30s)
   └─ registry.broadcastHeartbeat() — comment ": heartbeat" 발사
```

## 트랜잭션 분리 근거 (BR-3)

- `PinV1Controller.createPin`은 메서드 레벨 `@Transactional` 없음. Controller 진입 시 트랜잭션 없음.
- 챗봇 핸들러(`InstagramLinkHandler`, `PlaceSelectionHandler`)도 메서드 레벨 트랜잭션 없음. `pinService.addPin` 등 PinService 호출만 각자 트랜잭션이며 핸들러 코드 흐름은 트랜잭션 밖.
- 따라서 NotificationService는 `@Transactional`(REQUIRED, 기본)로 새 트랜잭션을 시작·종료한다. REQUIRES_NEW는 불필요 (호출자에 트랜잭션이 없으므로 REQUIRED와 동작 동일).
- `@TransactionalEventListener(AFTER_COMMIT)`의 "COMMIT" 기준은 **NotificationService 내부 트랜잭션 커밋**이지 핀 트랜잭션 커밋이 아니다.
- 호출자 try-catch는 알림 실패가 핀 응답에 영향을 주지 않도록 하는 추가 안전망.

## SSE 인프라

### Registry 구조

`ConcurrentHashMap<Long userId, CopyOnWriteArrayList<SseEmitter>>` — 다중 탭(FR-9) 지원, 락 없는 동시 접근.

### Emitter 라이프사이클

- **등록 (`register`)**:
  - 사용자당 최대 10개(`MAX_EMITTERS_PER_USER`) 가드: 초과 시 가장 오래된 emitter를 `complete` + 제거.
  - `new SseEmitter(EMITTER_TIMEOUT_MS)` — 5분 타임아웃(BR-6).
  - `onCompletion`/`onTimeout`/`onError` 콜백에 `removeEmitter` 등록.
  - 초기 `connected` 이벤트 발사. 실패 시 `removeEmitter` + `completeWithError`로 누수 차단.
- **푸시 (`push`)**:
  - userId별 emitter 리스트에 `event("notification").data(payload)` 발사.
  - `IOException` / `IllegalStateException` 시 즉시 제거 + `completeWithError`.
- **해제**:
  - 클라이언트 종료/타임아웃/에러 → onCompletion/onTimeout/onError 콜백으로 자동 제거.
  - `CopyOnWriteArrayList`가 비면 `ConcurrentHashMap.remove(userId, list)` 비교 삭제 (빈 리스트 race 방어).

### Heartbeat

`@Scheduled(fixedRate=30_000L)` → 모든 emitter에 `event().comment("heartbeat")` 발사. 실패 emitter는 즉시 제거 + `completeWithError`.

## 데이터 모델 (V007)

```sql
CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES groups(id),
    receiver_id BIGINT NOT NULL REFERENCES users(id),    -- 단일 컬럼 fan-out
    registered_by BIGINT NOT NULL REFERENCES users(id),
    type VARCHAR(20) NOT NULL CHECK (type IN ('MANUAL_PIN','CHATBOT_PINS')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,    -- BaseEntity 정합
    read_at TIMESTAMPTZ
);

CREATE INDEX idx_notifications_receiver_created
    ON notifications (receiver_id, created_at DESC);
CREATE INDEX idx_notifications_receiver_unread
    ON notifications (receiver_id) WHERE read_at IS NULL;

CREATE TABLE notification_pins (
    id BIGSERIAL PRIMARY KEY,
    notification_id BIGINT NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    pin_id BIGINT NOT NULL REFERENCES pins(id),
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (notification_id, pin_id)
);
```

### Fan-out 의미

알림 1건 = (group_id, receiver_id, registered_by, type, created_at) 단위. 그룹 N인일 경우 등록자 제외 (N-1)개 row 생성. MVP 2인에서는 상대방 1명에 1행. SSE push도 receiverId별로 별도 발화.

### 핀 삭제 처리 (BR-4)

- `notification_pins.pin_id`는 `ON DELETE RESTRICT`(기본). 단 `pins`는 soft delete(`deleted_at` 컬럼)이므로 실제 row 삭제 미발생.
- 알림 상세 조회 시 `pin.isDeleted()`인 경우 `placeName`은 유지하고 `latitude`/`longitude`/`address`를 null로 마스킹 + `deleted: true` 응답. FE는 "삭제된 장소: {이름}" 표시 + flyTo 비활성.

## 프론트엔드 통합

### 컴포넌트 트리

```
MapClient (useNotifications 단일 인스턴스)
├─ MobileTopNav (notificationBell prop) — 우상단 [벨][프로필] 가로 배치
├─ DesktopActionPill (notificationBell prop) — 사이드바 하단 벨 슬롯
├─ NotificationToast — 5초 자동 닫힘 + 외부 mousedown/touchstart 감지
├─ NotificationPanel — Sheet(모바일) / SidePanel left:66(데스크탑)
│   ├─ NotificationItem[]
│   └─ NotificationPinList — pin.deleted 시 disabled
└─ PinPopup (기존)
```

### 동시 1개 패널 정책

알림 패널 열림 시 `setActiveSheet(null)`로 다른 액션 시트 닫기. 반대로 다른 시트 열림 시 `useEffect(() => { if (activeSheet && isPanelOpen) closePanel(); })`로 알림 패널 닫기.

### SSE 클라이언트 (`sseClient.ts`)

- `EventSource(url, { withCredentials: true })`.
- preflight `fetch`로 401/403 사전 감지 → 즉시 `failed` (재시도 0회).
- 지수 백오프 2→4→8→16→30s cap, 최대 5회. 실패 시 `failed` 상태로 수렴.
- 정상 연결(`onopen` 또는 `connected` 이벤트) 시 retryCount=0 리셋.

### useNotifications 훅

- mount 시 `GET /notifications` 초기 로드 + SSE 구독.
- 수신 시 items prepend(50건 cap) + `shownToastIds` 등록.
- 패널 열림: toast 미노출 + `markAllRead()` 재호출 (AC-17).
- 패널 닫힘: 5초 setTimeout 자동 닫힘 + 외부 탭.

### BFF SSE 전용 라우트

`frontend/src/app/api/v1/notifications/stream/route.ts`:
- `runtime = 'nodejs'`, `dynamic = 'force-dynamic'`.
- `fetch(backendUrl, { signal: request.signal, headers: { Cookie } })` — 클라이언트 disconnect 시 upstream 즉시 종료.
- 응답 헤더: `Content-Type: text/event-stream`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`, `Connection: keep-alive`.
- 401/4xx 응답에 `Connection: close` 헤더 — EventSource 재연결 시그널 약화.

## 운영 검증 항목 (후속)

- Vercel ↔ EC2 cross-origin EventSource 쿠키 전달 (SameSite=Lax 동작).
- Vercel/Cloudflare 프록시 SSE 스트리밍 버퍼링 (X-Accel-Buffering 효과).
- 다중 탭 독립 SSE 수신 검증.
- 30초 heartbeat가 즉시 전달되는지 (long-poll 버퍼 없는지).

## 관련 ADR

- ADR-0001 Redis/Kafka 도입 검토 (폐기) — 단일 EC2 SseEmitter 전제.
- ADR-0002 Redis 제거 + Caffeine — 외부 의존 최소화 결정 일관.
