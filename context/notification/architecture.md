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
       ▼  (메서드 종료 = 트랜잭션 커밋 = 알림 생성 완료)

[ 클라이언트 ] mount / visibilitychange / focus 이벤트
       │
       ▼
[ GET /api/v1/notifications ] (REST, JWT 쿠키)
       └─ 최신 ≤50건 + unreadCount 반환
       └─ 빨간 점 / 패널 UI 갱신
       └─ 직전 max id 초과한 신규 알림 시 NotificationToast 1회 (FR-15 변형)
```

## 트랜잭션 분리 근거 (BR-3)

- `PinV1Controller.createPin`은 메서드 레벨 `@Transactional` 없음. Controller 진입 시 트랜잭션 없음.
- 챗봇 핸들러(`InstagramLinkHandler`, `PlaceSelectionHandler`)도 메서드 레벨 트랜잭션 없음. `pinService.addPin` 등 PinService 호출만 각자 트랜잭션이며 핸들러 코드 흐름은 트랜잭션 밖.
- 따라서 NotificationService는 `@Transactional`(REQUIRED, 기본)로 새 트랜잭션을 시작·종료한다. REQUIRES_NEW는 불필요 (호출자에 트랜잭션이 없으므로 REQUIRED와 동작 동일).
- 호출자 try-catch는 알림 실패가 핀 응답에 영향을 주지 않도록 하는 추가 안전망.

## Fetch 트리거 정책 (옵션 B 다운그레이드, 2026-05-21)

SSE 인프라(`NotificationSseRegistry`, `NotificationHeartbeatScheduler`, `NotificationSsePushListener`, `NotificationCreatedEvent`, `GET /stream` 엔드포인트, BFF SSE 라우트, `sseClient.ts`)를 제거하고 클라이언트 폴링 없이 사용자 행위 트리거 fetch로 전환했다. 실시간성 대신 운영 단순성을 우선한다.

### 트리거 이벤트

| 이벤트 | 발화 시점 | 동작 |
|--------|----------|------|
| `mount` | `useNotifications` 훅 최초 마운트 | `GET /notifications` 호출, items + unreadCount 초기화 (toast 미노출) |
| `visibilitychange` (visibility=visible) | 사용자가 탭/앱을 다시 활성화 | 동일 fetch — 빨간 점 갱신 + 신규 알림 감지 시 toast |
| `focus` | 다른 탭/창에서 돌아옴 | 동일 fetch — 빨간 점 갱신 + 신규 알림 감지 시 toast |

### 신규 알림 감지 (FR-15 변형)

`visibilitychange`/`focus` 트리거 fetch 결과에서 직전 items의 max id보다 큰 새 알림이 존재하면 `NotificationToast`를 1회 노출(5초 자동 닫힘 + 외부 탭). 동일 id는 `shownToastIds` Set으로 중복 차단. 마운트 시점 초기 fetch는 toast 미노출(직전 max id 없음).

### 안 하는 것

- 폴링 (`setInterval` 기반 주기 조회) — 배터리/네트워크 비용 회피
- SSE / WebSocket — Phase 8 옵션 B 결정으로 인프라 제거
- 브라우저 Push API / 푸시 알림 — 운영체제 권한 + 인프라 부담

## 데이터 모델 (V007)

```sql
CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES groups(id),
    receiver_id BIGINT NOT NULL REFERENCES users(id),    -- 단일 컬럼 fan-out
    registered_by BIGINT NOT NULL REFERENCES users(id),
    type VARCHAR(20) NOT NULL CHECK (type IN ('MANUAL_PIN','CHATBOT_PINS','VISIT_DETECTED')),  -- V009: VISIT_DETECTED 추가
    visit_pin_id BIGINT NULL REFERENCES pins(id) ON DELETE RESTRICT,  -- V009: VISIT_DETECTED 부분 UNIQUE 인덱스 키 (다른 유형은 NULL)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,    -- BaseEntity 정합
    read_at TIMESTAMPTZ
);

-- Phase 10 race-free 중복 차단: 동일 (group_id, receiver_id, registered_by, visit_pin_id) 조합 1회만 허용
CREATE UNIQUE INDEX uq_notifications_visit
    ON notifications (group_id, receiver_id, registered_by, visit_pin_id)
    WHERE type = 'VISIT_DETECTED' AND visit_pin_id IS NOT NULL;

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

알림 1건 = (group_id, receiver_id, registered_by, type, created_at) 단위. 그룹 N인일 경우 등록자 제외 (N-1)개 row 생성. MVP 2인에서는 상대방 1명에 1행. 옵션 B에서는 클라이언트가 receiverId 기준으로 자기 row만 fetch.

### 핀 삭제 처리 (BR-4)

- `notification_pins.pin_id`는 `ON DELETE RESTRICT`(기본). 단 `pins`는 soft delete(`deleted_at` 컬럼)이므로 실제 row 삭제 미발생.
- 알림 상세 조회 시 `pin.isDeleted()`인 경우 `placeName`은 유지하고 `latitude`/`longitude`/`address`를 null로 마스킹 + `deleted: true` 응답. FE는 "삭제된 장소: {이름}" 표시 + flyTo 비활성.

## 프론트엔드 통합

### 컴포넌트 트리

```
MapClient (useNotifications 단일 인스턴스)
├─ MobileTopNav (notificationBell prop) — 우상단 [벨][프로필] 가로 배치
├─ DesktopActionPill (notificationBell prop) — 사이드바 하단 벨 슬롯
├─ NotificationToast — visibilitychange/focus 트리거 fetch 결과에서 신규 알림 시 1회 노출
├─ NotificationPanel — Sheet(모바일) / SidePanel left:66(데스크탑)
│   ├─ NotificationItem[]
│   └─ NotificationPinList — pin.deleted 시 disabled
└─ PinPopup (기존)
```

### 동시 1개 패널 정책

알림 패널 열림 시 `setActiveSheet(null)`로 다른 액션 시트 닫기. 반대로 다른 시트 열림 시 `useEffect(() => { if (activeSheet && isPanelOpen) closePanel(); })`로 알림 패널 닫기.

### useNotifications 훅

- mount 시 `GET /notifications` 초기 로드 + `visibilitychange`/`focus` 이벤트 리스너 등록.
- visibilitychange(visible) / focus 시 동일 fetch 재실행 + 직전 max id 비교로 신규 감지 → toast 노출.
- 패널 열림: `markAllRead()` 호출 후 빨간 점 제거. 패널 내부에서는 자동 갱신 없음 (SSE 미사용).

## 안 하는 것 / 보류

| 항목 | 사유 |
|------|------|
| SSE 실시간 push (FR-5~FR-9) | 옵션 B로 제거. 사용자 100명+ 시점 재평가 |
| 패널 열림 중 새 알림 자동 추가 (FR-17) | fetch 트리거가 외부 이벤트 기반이라 패널 내부에서는 자동 갱신 없음. 사용자가 패널을 닫고 다시 열거나 새로고침해야 반영 |
| 다중 탭 동시 SSE 수신 (FR-9) | fetch 기반이라 본질적으로 N/A. 각 탭이 독립 fetch |
| SseEmitter 타임아웃 (BR-6) | SSE 제거로 무관 |

## 관련 ADR

- ADR-0001 Redis/Kafka 도입 검토 (폐기) — 외부 인프라 미도입 결정 일관.
- ADR-0002 Redis 제거 + Caffeine — 외부 의존 최소화 결정 일관.
