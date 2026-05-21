# 설계: Phase 8 — 인앱 알림함 (SSE)

## 설계 규모
**대형** — 신규 도메인(notification) + SSE 인프라(Registry/Emitter/Heartbeat) + 4개 핀 등록 경로 트리거 통합 + FE 알림 패널/벨/말풍선/SSE 클라이언트 대규모 UX 추가.

## 배경 및 목적
- 그룹원이 핀을 등록해도 상대방이 인지할 수단이 없어, 카카오톡 푸시를 배제한 결정에 따라 **앱 내 실시간 알림함**으로 대체한다.
- Phase 7까지 확정된 핀 등록 3대 경로(웹 직접 등록, 챗봇 릴스 자동 처리, 챗봇 장소 카드 선택)를 모두 알림 트리거로 흡수한다.
- **성공 지표:** 알림 수신 → 지도 `flyTo` → `PinPopup` 자동 표시까지 끊김 없는 경로 완성.

## 요구사항 매핑
- [Must] FR-1 ~ FR-3, FR-5 ~ FR-7, FR-10, FR-11, FR-13 ~ FR-19, BR-1 ~ BR-4
- [Should] FR-4, FR-8, FR-9, FR-12, FR-20, BR-5, BR-6, QE-1, QE-2
- [Could] BR-7
- AC-1 ~ AC-22 전체

## 변경 범위

### 신규 (Backend 16개)
- `backend/apps/wherewego-api/src/main/resources/db/migration/V007__create_notifications.sql`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/Notification.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationPin.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationType.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationRepository.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationService.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationSseRegistry.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationSsePushListener.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationCreatedEvent.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationHeartbeatScheduler.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/notification/NotificationJpaRepository.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/notification/NotificationPinJpaRepository.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/notification/NotificationRepositoryAdapter.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/notification/NotificationV1Controller.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/notification/NotificationV1ApiSpec.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/notification/NotificationV1Dto.java`

> `NotificationView`/`NotificationPinView` 별도 도메인 record는 도입하지 않음. NotificationService가 엔티티 + 부수 Map을 반환하고 `NotificationV1Dto`에서 응답 변환.

### 신규 (Frontend 9개)
- `frontend/src/lib/notifications/types.ts`
- `frontend/src/lib/notifications/api.ts`
- `frontend/src/lib/notifications/sseClient.ts`
- `frontend/src/lib/notifications/useNotifications.ts`
- `frontend/src/app/map/_components/notifications/NotificationBell.tsx`
- `frontend/src/app/map/_components/notifications/NotificationToast.tsx`
- `frontend/src/app/map/_components/notifications/NotificationPanel.tsx`
- `frontend/src/app/map/_components/notifications/NotificationItem.tsx`
- `frontend/src/app/map/_components/notifications/NotificationPinList.tsx`

### 수정 (Backend 6개)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Controller.java` — createPin에 createForManualPin 호출 추가
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/InstagramLinkHandler.java` — handleCandidates + handleLegacySingle + handleGoogleFallback 3곳
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/PlaceSelectionHandler.java` — handle 단건 저장 성공 분기
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/group/GroupMemberRepository.java` — findOtherActiveMemberIds 추가
- `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/group/GroupMemberRepositoryImpl.java` — 위임
- `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/group/GroupMemberJpaRepository.java` — JPQL 추가

### 수정 (Frontend 3개)
- `frontend/src/app/map/_components/MobileTopNav.tsx` — 우상단 [벨][프로필] 가로 배치
- `frontend/src/app/map/_components/DesktopActionPill.tsx` — 하단 프로필 위 벨 슬롯
- `frontend/src/app/map/MapClient.tsx` — useNotifications 통합 + 패널 동시 1개 정책 + 핀 선택 핸들러

### 무변경
- `PinService.java`, `SecurityConfig.java`, `application.yml`, 메인 Application 클래스 (`@EnableScheduling`은 `RequestIdFilterConfig.java:23`에 이미 존재)

## 적용 컨벤션
- Controller: `{Domain}V1Controller` + `{Domain}V1ApiSpec` 인터페이스 분리
- DTO: `{Domain}V1Dto` nested record
- Repository: port(domain) + JpaRepository(infra) + Adapter
- 인증: `@AuthUser Long userId` (AuthUserArgumentResolver, cookie JWT)
- 에러: `CoreException(ErrorType.XXX)` + `ApiResponse.success/fail`
- 트랜잭션: write `@Transactional`(REQUIRED), read `@Transactional(readOnly=true)`
- 도메인 이벤트: record + `@TransactionalEventListener(AFTER_COMMIT)`
- DI: `@RequiredArgsConstructor` 생성자 주입
- Flyway: V006 단일 트랜잭션 패턴

## 아키텍처 개요

```
[ PinV1Controller / Chatbot Handler ]   (트랜잭션 밖)
       │  try { notificationService.createForXxx(...) }
       │  catch (RuntimeException e) { log.warn(...) }  ← BR-3 보강
       ▼
[ NotificationService.createForXxx ]   @Transactional (REQUIRED)
       │  1) findOtherActiveMemberIds(groupId, registeredBy) → receiverIds
       │  2) for each receiverId:
       │       insert Notification(receiverId, registeredBy, type)
       │       insert NotificationPin[] (sort_order)
       │       eventPublisher.publishEvent(NotificationCreatedEvent)
       ▼  (메서드 종료 = NotificationService 트랜잭션 커밋)
[ @TransactionalEventListener(AFTER_COMMIT) ]
       │
       ▼
[ NotificationSseRegistry.push(receiverId, event) ]
       │  ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>
       ▼
[ 각 SseEmitter.send(...) ]  ← IOException/IllegalStateException 시 remove + completeWithError

[ NotificationHeartbeatScheduler ] @Scheduled(fixedRate = 30s)
       └─ registry.broadcastHeartbeat() — comment ": heartbeat"
```

### 트랜잭션 분리 (A1 명문화)
- `PinV1Controller.createPin`은 메서드 레벨 `@Transactional` 없음 (검증: `PinV1Controller.java:35-46`). Controller 진입 시 트랜잭션 없음.
- 챗봇 핸들러도 메서드 레벨 `@Transactional` 없음. PinService 호출만 각자 트랜잭션.
- NotificationService는 `@Transactional`(REQUIRED). 호출자 트랜잭션 없으므로 새 트랜잭션 시작.
- **REQUIRES_NEW 미사용**: 호출자에 트랜잭션이 없으므로 REQUIRED와 동작 동일. 의미 혼선 방지 차 REQUIRED 명시.
- `@TransactionalEventListener(AFTER_COMMIT)`의 "COMMIT"은 **NotificationService 내부 트랜잭션 커밋** 기준이지 핀 트랜잭션 커밋이 아님.
- BR-3: NotificationService 자체 트랜잭션 + 호출자 try-catch 이중 격리.

## 데이터 모델 (V007)

```sql
-- ============================================================
-- V007__create_notifications.sql
-- Phase 8: 인앱 알림함 — notifications + notification_pins.
--
-- 데이터 모델 결정 (Q3 답변): receiver_id 단일 컬럼 + 행 fan-out.
--   알림 1건 = (group_id, receiver_id, registered_by, type, created_at).
--   그룹 N인 → 등록자 제외 (N-1)배 행. MVP 2인은 상대방 1명에 1행.
--
-- 보관 정책: 영구 보관(BR-2). 만료/정리 정책 없음.
-- 삭제된 핀: notification_pins 행 유지(BR-4). pins은 soft delete이므로 row 자체 살아 있음.
-- ============================================================

CREATE TABLE IF NOT EXISTS notifications
(
    id            BIGSERIAL    PRIMARY KEY,
    group_id      BIGINT       NOT NULL REFERENCES groups (id),
    receiver_id   BIGINT       NOT NULL REFERENCES users (id),
    registered_by BIGINT       NOT NULL REFERENCES users (id),
    type          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at       TIMESTAMPTZ,
    CONSTRAINT chk_notifications_type
        CHECK (type IN ('MANUAL_PIN', 'CHATBOT_PINS'))
);

CREATE INDEX IF NOT EXISTS idx_notifications_receiver_created
    ON notifications (receiver_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notifications_receiver_unread
    ON notifications (receiver_id)
    WHERE read_at IS NULL;

CREATE TABLE IF NOT EXISTS notification_pins
(
    id              BIGSERIAL   PRIMARY KEY,
    notification_id BIGINT      NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    pin_id          BIGINT      NOT NULL REFERENCES pins (id),
    sort_order      INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_pins_pair UNIQUE (notification_id, pin_id)
);

CREATE INDEX IF NOT EXISTS idx_notification_pins_notification_id
    ON notification_pins (notification_id);
```

## 백엔드 컴포넌트 명세

### 1) NotificationType
```java
public enum NotificationType { MANUAL_PIN, CHATBOT_PINS }
```

### 2) Notification (entity)
- 필드: groupId, receiverId, registeredBy, type(EnumType.STRING), readAt(Instant)
- 정적 팩토리: `Notification.create(groupId, receiverId, registeredBy, type)`
- markRead(now) idempotent, isUnread()

### 3) NotificationPin (entity)
- 필드: notificationId, pinId, sortOrder
- 정적 팩토리: `NotificationPin.link(notificationId, pinId, sortOrder)`

### 4) NotificationRepository (port)
- save, saveAllPins, findRecentByReceiverId(receiverId, limit), findByIdAndReceiverId
- findPinsByNotificationId, findPinsByNotificationIds(Collection) — N+1 회피
- countUnreadByReceiverId, markAllReadByReceiverId(receiverId, now) — bulk @Modifying

### 5) GroupMemberRepository 확장 (A4)
- `List<Long> findOtherActiveMemberIds(Long groupId, Long excludeUserId)`
- JPQL: `WHERE groupId = :gid AND userId <> :exclude AND leftAt IS NULL`

### 6) NotificationService
- `createForManualPin(groupId, registeredBy, pinId)` @Transactional
- `createForChatbotBatch(groupId, registeredBy, List<Long> pinIds)` @Transactional
  - empty → no-op (FR-4/BR-5)
  - findOtherActiveMemberIds 0건 → no-op (엣지 7)
  - 각 receiverId마다 Notification 1행 + NotificationPin N행 insert
  - publishEvent(NotificationCreatedEvent) per receiverId
- `listRecent(receiverId, limit)` @Transactional(readOnly)
- `getDetail(notificationId, receiverId)` @Transactional(readOnly)
  - findByIdAndReceiverId 없으면 ErrorType.NOT_FOUND
  - 핀별: 살아 있으면 모든 필드, soft delete면 placeName 유지 + 좌표/주소 null + deleted=true (Q6)
- `markAllRead(receiverId)` @Transactional

### 7) NotificationCreatedEvent + Listener
```java
public record NotificationCreatedEvent(
    Long receiverId, Long notificationId, NotificationType type,
    Long registeredBy, String registeredByNickname,
    String firstPlaceName, int totalPinCount, Instant createdAt
) {}

@Component
public class NotificationSsePushListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(NotificationCreatedEvent event) {
        registry.push(event.receiverId(), event);
    }
}
```

### 8) NotificationSseRegistry
- `ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>` (FR-9 다중 탭)
- EMITTER_TIMEOUT_MS = 5분 (BR-6)
- register(userId): emitter 생성 + onCompletion/onTimeout/onError → 자동 제거 + connected 이벤트
- push(userId, event): IOException/IllegalStateException 시 remove + completeWithError
- broadcastHeartbeat(): comment "heartbeat" 발사, 실패 emitter 즉시 제거
- removeEmitter(userId, emitter): list 비면 ConcurrentHashMap.remove(key, value) 비교 삭제

### 9) NotificationHeartbeatScheduler
- `@Scheduled(fixedRate = 30_000L)` → registry.broadcastHeartbeat()
- @EnableScheduling은 `RequestIdFilterConfig.java:23`에 이미 적용됨 (Q7)

### 10) NotificationV1Controller (X-Accel-Buffering 헤더 — A2)
```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@AuthUser Long userId, HttpServletResponse response) {
    response.setHeader("X-Accel-Buffering", "no");
    response.setHeader("Cache-Control", "no-cache");
    return sseRegistry.register(userId);
}

@GetMapping → list (≤50 + unreadCount)
@PostMapping("/read-all") → ReadAllResponse(updatedCount)
@GetMapping("/{id}") → NotificationDetailResponse (pins[])
```

### NotificationV1Dto records
- `NotificationItem(id, type, registeredBy, registeredByNickname, firstPlaceName, totalPinCount, createdAt, readAt)`
- `NotificationListResponse(items, unreadCount)`
- `PinItem(pinId, placeName, address, latitude, longitude, deleted)` — Q6: deleted시 좌표/주소 null
- `NotificationDetailResponse(id, type, registeredByNickname, createdAt, pins)`
- `ReadAllResponse(updatedCount)`
- SSE: `NotificationStreamEvent(id, type, registeredByNickname, firstPlaceName, totalPinCount, createdAt)`

## SSE 설계 상세

### 인증/CORS/프록시 (A2)
- 기존 cookie JWT 그대로 사용 (SecurityConfig 변경 없음)
- EventSource: `new EventSource(url, { withCredentials: true })` 명시
- SameSite=Lax는 EventSource cross-origin GET에 대해 일반적으로 쿠키 전송 허용
- 프록시 버퍼링 방지: `X-Accel-Buffering: no` + `Cache-Control: no-cache`
- 운영 검증: staging 배포 후 E2E. 실패 시 `WEB_SECURITY_COOKIE_SAME_SITE=None` (+Secure) 토글 검토 (별도 결정)

### 동시성
- ConcurrentHashMap + CopyOnWriteArrayList → 락 없이 register/push/remove/heartbeat 안전
- 빈 리스트 race는 remove(key, value) 비교 삭제로 방어

## API 설계

| Method | Path | 인증 | 응답 | 특수 헤더 | 에러 |
|--------|------|------|------|----------|------|
| GET | `/api/v1/notifications/stream` | JWT 쿠키 | text/event-stream (`connected`, `notification`, comment `heartbeat`) | `X-Accel-Buffering: no`, `Cache-Control: no-cache` | 401 |
| GET | `/api/v1/notifications` | JWT 쿠키 | items ≤50 + unreadCount | — | 401 |
| POST | `/api/v1/notifications/read-all` | JWT 쿠키 | updatedCount | — | 401 |
| GET | `/api/v1/notifications/{id}` | JWT 쿠키 | detail (pins + deleted 표기) | — | 401, 404 |

SSE 이벤트 payload:
```json
{ "id": 12, "type": "CHATBOT_PINS", "registeredByNickname": "민지",
  "firstPlaceName": "성수동베이커리", "totalPinCount": 3,
  "createdAt": "2026-05-21T12:34:56Z" }
```

## 트리거 통합

### A) PinV1Controller.createPin (Q4)
addPin 호출 후 try-catch로 createForManualPin 호출. PinService 무변경.

### B) InstagramLinkHandler (A3 4경로 명확화)
4개 진입점(handleCandidates, handleLegacySingle, autoSaveOnExpiry, autoSavePreviousImmediately) 중 후자 2개는 `runBackgroundAutoSave → runParseAndCandidates → handleCandidates/handleLegacySingle` 체인.

**3곳 트리거로 4경로 자동 커버:**
1. `handleCandidates` 종료 직전 (twoSecondMemoSession.put 후): savedPinIds.isEmpty()이 아니면 createForChatbotBatch.
2. `handleLegacySingle` 단건 저장 성공 분기 (alreadyExisted=false): List.of(pinId).
3. `handleGoogleFallback` 단건 저장 성공 분기: 동일.

`tryRegister` 시그니처에 `List<Long> savedPinIds` 추가, alreadyExisted=false인 경우만 add.

**MDC 한계 명문화**: 백그라운드 자동 저장 경로에서 RequestId 끊김. Phase 2.11 observability의 알려진 한계, 후속 Phase에서 해결.

### C) PlaceSelectionHandler.handle
alreadyExisted=false 분기 직후 createForChatbotBatch(List.of(pinId)). alreadyExisted=true와 DataIntegrityViolationException catch 분기는 트리거 안 함.

## 프론트엔드 설계

### 컴포넌트 트리
```
MapClient (수정: useNotifications 통합, 패널 동시 1개 정책)
├─ MobileTopNav (수정: 우상단 [벨][프로필] 가로 배치 — Q1)
│   └─ NotificationBell (신규)
├─ DesktopActionPill (수정: 하단 프로필 위 벨 슬롯)
│   └─ NotificationBell
├─ NotificationToast (신규: 5초 + 외부 탭 닫힘)
├─ NotificationPanel (신규: Sheet[모바일] / SidePanel left:66[데스크탑] — Q2)
│   ├─ NotificationItem[] (신규)
│   └─ NotificationPinList (신규)
└─ PinPopup (기존)
```

### useNotifications 훅
```ts
interface UseNotificationsState {
  items: NotificationItem[];
  unreadCount: number;
  connectionState: 'connecting' | 'open' | 'closed' | 'failed';
  toast: { id: number; payload: NotificationStreamEvent } | null;
  isPanelOpen: boolean;
}

interface UseNotificationsActions {
  openPanel(): Promise<void>;  // open + read-all
  closePanel(): void;
  markAllRead(): Promise<void>;
  refreshList(): Promise<void>;
  dismissToast(): void;
  loadDetail(notificationId: number): Promise<NotificationDetail>;
}
```

**내부 동작**:
- mount 시 GET /notifications → 초기화
- SSE 수신 시: items prepend (50건 cap), unreadCount++
  - 패널 열림: toast 미노출 + 즉시 markAllRead (AC-17)
  - 패널 닫힘: toast 세팅 + 5초 setTimeout dismiss
- shownToastIds Set으로 중복 차단 (AC-16)
- openPanel: 상태 open + markAllRead

### sseClient
- `new EventSource(url, { withCredentials: true })`
- 지수 백오프 2→4→8→16→30s, 최대 5회
- 5회 실패 → failed 상태 진입 (엣지 9, Q8)
- 정상 연결 시 카운터 reset
- useGroupPinSync.ts:41-126 cleanup/abort/401 정지 패턴 참조

### 모바일 (MobileTopNav) — Q1
- 우상단을 `[벨][프로필]` 가로 배치
- 컨테이너: `position: absolute; top: 14; right: 14; display: flex; gap: 10`
- 벨 44x44 원형 + 우상단 점(8x8): unreadCount>0이면 빨강, failed면 회색 (Q8)
- 프로필: 기존 Link /settings 유지

### 데스크탑 (DesktopActionPill)
- 하단 프로필 위에 NotificationBell 추가 (36x36 + tooltip "알림")

### NotificationPanel (Q2)
- 데스크탑: SidePanel left:66 (기존 검색 슬롯)
- 모바일: Sheet
- 동시 1개 패널 정책: 알림 패널 열림 시 다른 액션 시트 자동 닫기

### NotificationToast (FR-15)
- SpeechBubblePopup 직접 재사용 불가(핀 좌표 기반) → 동일 톤 자체 컴포넌트
- 5초 setTimeout + mousedown/touchstart 외부 탭 닫힘 (MobileTopNav.tsx:28-41 패턴)
- 내용: `"[닉네임]님이 [장소명] {외 N곳을} 저장했어요"` (totalPinCount=1이면 "외 N곳" 생략)

### MapClient 통합
```ts
const handleSelectPinFromNotification = (pin: NotificationPinItem) => {
  closeNotificationPanel();
  if (pin.deleted || pin.latitude == null) return;
  if (map) map.flyTo({ center: [Number(pin.longitude), Number(pin.latitude)], zoom: 14 });
  const exists = pins.some(p => p.id === pin.pinId);
  if (exists) setSelectedPinId(pin.pinId);
};
```

### 삭제된 핀 표시 (Q6)
- `pin.deleted=true`: 라벨 `"삭제된 장소: {placeName}"`, 클릭 비활성 (disabled cursor + no-op)

## 구현 순서 (25단계)

```
PR1 (Backend 인프라)
1. V007 마이그레이션 + 로컬 검증
2. NotificationType + Notification + NotificationPin
3. NotificationRepository + JpaRepository + Adapter
4. GroupMemberRepository.findOtherActiveMemberIds

PR2 (Backend 도메인/API)
5. NotificationCreatedEvent + NotificationSseRegistry + SsePushListener
6. NotificationService (create*/list/markAllRead/getDetail)
7. NotificationHeartbeatScheduler
8. NotificationV1Controller + ApiSpec + Dto (X-Accel-Buffering 포함)

PR3 (Backend 트리거 + 테스트)
9. PinV1Controller.createPin 트리거 (try-catch)
10. InstagramLinkHandler 3곳 트리거
11. PlaceSelectionHandler 트리거
12. NotificationServiceIT
13. NotificationSseRegistryTest
14. NotificationV1ControllerIntegrationTest

PR4 (FE 인프라)
15. lib/notifications/types + api
16. sseClient (재연결 + withCredentials)
17. useNotifications

PR5 (FE UI)
18. NotificationBell (빨강/회색 점 + tooltip)
19. NotificationToast (5초 + 외부 탭 + id 중복 차단)
20. NotificationPanel + Item + PinList
21. MobileTopNav 벨+프로필 가로
22. DesktopActionPill 벨 슬롯
23. MapClient 통합 (동시 1개 정책 + 핀 선택)
24. FE Vitest (useNotifications, sseClient, NotificationBell)
25. FR-20 빈 상태 UI
```

**병렬 가능**: 1↔4, 9↔10↔11, 15↔16(8 이후), 18↔19↔20(17 이후), 21↔22.

## 테스트 전략

**Backend**
- NotificationServiceIT: 본인 0건(AC-6), 상대방 1건(AC-1/2/3), autoSave 경로(AC-4), empty no-op(AC-5), 멤버 0건(엣지7), markAllRead(AC-12), soft delete 핀(AC-19), notification throw해도 핀 잔존(AC-20)
- NotificationSseRegistryTest: register/onCompletion/onTimeout/onError 자동 제거(AC-10), 다중 emitter push(FR-9), heartbeat comment, IOException 제거
- NotificationV1ControllerIntegrationTest: 401(AC-7), SSE + X-Accel-Buffering(AC-8), ≤50(AC-11), read-all(AC-14), 본인 아닌 알림 404

**Frontend (Vitest)**
- useNotifications: 초기 fetch, SSE prepend + toast, 패널 open 시 read-all 재호출(AC-17), 동일 id toast 중복 차단(AC-16)
- sseClient: 지수 백오프 2→4→8→16→30, 5회 실패 → failed
- NotificationBell: unreadCount>0 빨강, failed 회색 + tooltip

## 자가 검증

### 유저 경험 일관성
- NotificationToast 톤은 MobileTopNav 말풍선 패턴 차용
- 알림 패널은 Sheet/SidePanel 재사용 → 기존 패널과 동일 룩
- flyTo+PinPopup은 룰렛 "지도에서 보기"와 동일

### 트랜잭션·동시성 안전성
- 호출자 트랜잭션 부재 + NotificationService 자체 @Transactional → 자연 분리 (BR-3)
- 호출자 try-catch가 추가 안전망
- @TransactionalEventListener(AFTER_COMMIT) — DB 일관성 시점 push
- ConcurrentHashMap + CopyOnWriteArrayList → 락 없이 동시 안전

### 확장성 vs 단순성
- 단일 EC2 SseEmitter (ADR-0001). 수평 확장 시 Registry.push만 추상화
- 영구 보관: MVP 규모 충분, 향후 파티셔닝/TTL 별 Phase
- 50건 cap: 후속에서 cursor 확장
- 디바운스 미적용 (호출 빈도 낮음, SSE Push 비용 낮음)
