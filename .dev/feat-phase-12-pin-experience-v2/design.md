충분한 패턴 정보를 확보했다. UserModel과 PinSummary가 record라는 점, 챗봇 handler 패키지가 `com.wherewego.domain.chatbot.handler`라는 점, 캐시는 CacheConfig에 등록하는 패턴, 그리고 ErrorType의 메시지 톤이 모두 명확하다. 이제 설계서를 작성한다.

---

# 설계서: Phase 12 — Pin Experience v2

## 1. 개요

Phase 12는 핀 시스템에 **WANT(관심 표현)** 시스템을 도입해 REEL/WISH 경계 모호성을 해소하고, **챗봇 릴스 처리 플로우**를 상태머신 기반(`ReelSavedSelectionSession`)으로 재설계하며, **오래된 핀 정리** UX와 **마커 3단계 시각화**를 추가한다.

| 축 | 영향 |
|----|------|
| 데이터 | V012 단일 마이그레이션 — 신규 테이블 `pin_events`, `pins.want_count`, `users.cleanup_snoozed_until`, `notifications.type WISH_CONVERTED` |
| 도메인(헥사고날) | `pin/Pin` 확장 + `pin/PinEvent` 신설 + `pin/want/WantService` 신설 + `pin/cleanup/CleanupService` 신설 + `notification/NotificationService.createForWishConverted` 추가 |
| 챗봇 | `PendingInstagramSession` → `ReelSavedSelectionSession` 교체(상태머신 5단계) + 신규 핸들러 4종 + `MessageClassifier` 우선순위 재배치 |
| API | WANT 토글 2개·cleanup 3개 신규 + `GET /pins` 응답·정렬·필터 확장 |
| 알림 | `WishConvertedEvent` AFTER_COMMIT → `NotificationService.createForWishConverted` (멱등 UNIQUE) |
| 프론트 | `lib/pin/markers.tsx` 3단계 글리프 + WISH 펄스 keyframe / `PinPopup` WANT 버튼·출처 뱃지·태그 진행 모달 / `CleanupBanner` 신규 / `MapFilter` 발견 드롭다운 / `MapClient` `?reel_bundle` opacity 처리 / API 클라이언트 4개 확장 |

아키텍처 영향: 헥사고날 레이어(`domain ↔ infrastructure(JPA 어댑터) ↔ interfaces(REST)`) 컨벤션을 그대로 따른다. WANT는 **별도 서비스**(`WantService`)로 분리해 트랜잭션 책임을 격리하고, `PinService`는 기존 CRUD에만 집중한다.

---

## 2. 변경 범위 매트릭스

### 백엔드

| 분류 | 파일 경로 | 상태 | 핵심 변경 | PRD 매핑 |
|------|-----------|------|----------|----------|
| 마이그레이션 | `backend/apps/wherewego-api/src/main/resources/db/migration/V012__pin_experience_v2.sql` | 신규 | `pin_events` 테이블·partial UNIQUE·`pins.want_count`·`users.cleanup_snoozed_until`·`WISH_CONVERTED` CHECK·`idx_pins_cleanup` (단일 트랜잭션) | FR-PIN-12-1, AC-12-1~4 |
| 도메인 Entity | `.../domain/pin/Pin.java` | 수정 | `want_count` 필드 + `applyWantDelta(int)` + `transitionToWishIfMajority(active, voters)` 도메인 메서드 | FR-PIN-12-4, FR-PIN-12-5 |
| 도메인 Entity | `.../domain/pin/PinEvent.java` | 신규 | JPA Entity (id, pinId, userId, groupId, action, createdAt). `BaseEntity` 미상속 (이력성·deletedAt 불필요) | FR-PIN-12-3 |
| 도메인 enum | `.../domain/pin/PinEventAction.java` | 신규 | `WANT` (단일값, P0). Phase 12.2에서 ALTER로 VIEW/SHARE/ROULETTE_SELECTED 확장 | D-8, D-17 |
| 도메인 포트 | `.../domain/pin/PinEventRepository.java` | 신규 | `save / deleteByPinAndUserAndAction / existsByPinAndUserAndAction / countWantByPinId / findWantVoterIdsByPinId` | FR-PIN-12-3, FR-PIN-12-6 |
| 어댑터 | `.../infrastructure/persistence/pin/PinEventJpaRepository.java`, `PinEventRepositoryImpl.java` | 신규 | Spring Data + 어댑터. `@Modifying` delete 쿼리 | NFR-12-2 |
| 도메인 포트 확장 | `.../domain/pin/PinRepository.java` | 수정 | `findCleanupCandidates(groupId, threshold)` + `softDeleteAll(pinIds)` + `findActiveByGroupIdSortedByWantCount(...)` + `findActiveByGroupIdInterestOnly(...)` + count 동반 메서드 | FR-PIN-12-8, FR-PIN-12-9, FR-PIN-12-23 |
| 어댑터 확장 | `.../infrastructure/persistence/pin/PinRepositoryImpl.java` (또는 동등 위치) | 수정 | JPQL/네이티브 쿼리 추가 | 〃 |
| 도메인 Entity | `.../domain/user/UserModel.java` (또는 `User.java` 실명) | 수정 | `cleanup_snoozed_until` 필드 + `snoozeCleanup(Duration)` / `isCleanupSnoozed(now)` | FR-PIN-12-25, NFR-12-8 |
| 도메인 서비스 | `.../domain/pin/want/WantService.java` | 신규 | 토글(`SELECT FOR UPDATE` → `pin_events` INSERT/DELETE → `want_count` 갱신 → 과반 검사 → `transitionToWish` → AFTER_COMMIT 이벤트) + `getMyWantStatus` 조회 | FR-PIN-12-2~6, NFR-12-1~3 |
| 도메인 서비스 | `.../domain/pin/cleanup/CleanupService.java` | 신규 | `listCandidates / executeBulk / snooze7Days` 세 메서드 | FR-PIN-12-23~25 |
| 도메인 이벤트 | `.../domain/pin/want/WishConvertedEvent.java` | 신규 | `record (groupId, pinId, triggerUserId, placeName)` | NFR-12-3 |
| 알림 enum | `.../domain/notification/NotificationType.java` | 수정 | `WISH_CONVERTED` 추가 | FR-PIN-12-6 |
| 알림 서비스 | `.../domain/notification/NotificationService.java` | 수정 | `createForWishConverted(groupId, pinId, triggerUserId, placeName)` 메서드 + `@TransactionalEventListener(AFTER_COMMIT)` 리스너 wrapper(`WishConvertedNotificationListener` 신규 클래스로 분리 권장) | FR-PIN-12-6, NFR-12-4 |
| 알림 리스너 | `.../domain/notification/WishConvertedNotificationListener.java` | 신규 | `@TransactionalEventListener(phase=AFTER_COMMIT)` + best-effort try/catch + 멱등 INSERT는 부분 UNIQUE로 보장 | NFR-12-3, NFR-12-4 |
| 알림 Entity | `.../domain/notification/Notification.java` | 참조 | (변경 없음) `type` enum 매핑만 확장됨 | — |
| Controller | `.../interfaces/api/pin/PinV1Controller.java` | 수정 | `POST/GET .../want`·`?sort=want_count`·`?interest=true` 처리 (정렬/필터 파라미터 파싱) | FR-PIN-12-2, 7~9 |
| Controller | `.../interfaces/api/pin/PinCleanupV1Controller.java` | 신규 | `GET /cleanup/candidates`, `POST /cleanup/execute` | FR-PIN-12-23, 24 |
| Controller | `.../interfaces/api/user/UserCleanupSnoozeV1Controller.java` | 신규 | `POST /users/me/cleanup-snooze` | FR-PIN-12-25 |
| DTO | `.../interfaces/api/pin/PinV1Dto.java` | 수정 | `PinSummaryResponse`에 `wantCount`, `myWant` 필드 추가 + `WantToggleResponse` 신규 record | FR-PIN-12-7, AC-12-11 |
| DTO | `.../interfaces/api/pin/PinCleanupV1Dto.java` | 신규 | `CleanupCandidateResponse`, `CleanupExecuteResponse(deletedCount)` | FR-PIN-12-23~25 |
| API Spec | `.../interfaces/api/pin/PinV1ApiSpec.java` (있을 경우) | 수정 | 위 신규 엔드포인트 시그니처 | — |
| ErrorType | `.../support/error/ErrorType.java` | 수정 | `PIN_WANT_FORBIDDEN_TAG`, `PIN_WANT_PIN_NOT_FOUND`(필요 시 PIN_NOT_FOUND 재사용), `PIN_CLEANUP_NO_CANDIDATES`, `PIN_SORT_PARAM_INVALID`, `BOT_REEL_PARSE_INVALID`(내부용), `BOT_REEL_OUT_OF_RANGE`(내부용) — 챗봇은 ErrorType 대신 안내 문자열로 처리 가능 | FR-PIN-12-17 |
| PinSummary | `.../domain/pin/PinSummary.java` | 수정 | `wantCount: int`, `myWant: boolean` 필드 추가 | FR-PIN-12-7 |
| 챗봇 세션 | `.../domain/chatbot/ReelSavedSelectionSession.java` | 신규 | Caffeine 캐시 wrapper + record `Snapshot` + 상태 enum `SessionState` | FR-PIN-12-13~22 |
| 챗봇 폐기 | `.../domain/chatbot/PendingInstagramSession.java` | 삭제 | 신 세션으로 통합 | — |
| 챗봇 폐기 | `.../domain/chatbot/PendingInstagramAutoSaveScheduler.java` | 수정 | TTL 만료 시 신 세션 기준 `forceSaveOnExpire(botUserKey)` 호출 | FR-PIN-12-18, EC-T1~T4 |
| 챗봇 분류 | `.../domain/chatbot/MessageClassifier.java` | 수정 | 신규 우선순위 + `SINGLE_WANT_YES/NO`, `REEL_PLACE_SELECTION`, `REEL_MEMO_WAITING` 매핑 | FR-PIN-12-13 |
| 챗봇 enum | `.../domain/chatbot/MessageType.java` | 수정 | `REEL_PLACE_SELECTION`, `SINGLE_WANT_YES`, `SINGLE_WANT_NO`, `REEL_MEMO_WAITING` 추가 (`INSTAGRAM_PENDING_MEMO`는 호환 유지 후 제거 candidate) | FR-PIN-12-13 |
| 챗봇 핸들러 | `.../domain/chatbot/handler/InstagramLinkHandler.java` | 수정 | PROCESSING → SINGLE_WANT / MULTI_SELECTING / BULK_SAVE 분기 진입 (1/2-30/31+) | FR-PIN-12-14~16 |
| 챗봇 핸들러 | `.../domain/chatbot/handler/ReelSingleWantHandler.java` | 신규 | SINGLE_WANT 상태 처리 | FR-PIN-12-14, 22 |
| 챗봇 핸들러 | `.../domain/chatbot/handler/ReelMultiSelectionHandler.java` | 신규 | MULTI_SELECTING 상태 + 콤마 파서 호출 + dedup | FR-PIN-12-15, 17 |
| 챗봇 핸들러 | `.../domain/chatbot/handler/ReelBulkSaveHandler.java` | 신규 | BULK_SAVE 상태 (메모 직접 입력) | FR-PIN-12-16 |
| 챗봇 핸들러 | `.../domain/chatbot/handler/ReelMemoWaitingHandler.java` | 신규 | MEMO_WAITING 공통 (저장 + WANT 일괄 적용 + 메모 broadcast) | FR-PIN-12-21 |
| 챗봇 폐기 | `.../domain/chatbot/handler/InstagramPendingMemoHandler.java` | 삭제 | `ReelMemoWaitingHandler`로 대체 | — |
| 챗봇 파서 | `.../domain/chatbot/reel/ReelCommaParser.java` | 신규 | 콤마 파싱 알고리즘 (8.4 규칙) | FR-PIN-12-17, AC-12-22~23 |
| 챗봇 가드 | `.../domain/chatbot/ChatbotWebhookService.java` | 수정 | 활성 세션 + 룰렛/공유 액션 거부(EC-R1/R2) + RESEND 가드 + 새 URL 도착 시 자동 저장 후 PROCESSING 진입 | FR-PIN-12-19, 20 |
| 캐시 | `.../config/cache/CacheConfig.java` | 수정 | `REEL_SELECTION` 캐시 이름 + TTL 180s | NFR-12-5 |
| 설정 | `backend/apps/wherewego-api/src/main/resources/application.yml` | 수정 | `chatbot.instagram.reel-selection-ttl-seconds: 180` (또는 `chatbot.reel.selection-ttl-seconds`) | NFR-12-5 |

### 프론트엔드

| 분류 | 파일 경로 | 상태 | 핵심 변경 | PRD 매핑 |
|------|-----------|------|----------|----------|
| 마커 | `frontend/src/lib/pin/markers.tsx` | 수정 | 진보라 `#7B68EE` 원형 (REEL+WANT≥1) + WISH 펄스 keyframe + 사이즈 계수(1.0/1.1/1.2) + `getMarkerVariant(pin)` 헬퍼 | FR-PIN-12-10, 11 |
| 마커 컴포넌트 | `frontend/src/components/ui/PinDot.tsx` (기존) | 수정 | `wantCount` prop 수용 + variant 결정 + 펄스 trigger 옵션 | FR-PIN-12-10, 11 |
| 토큰 | `frontend/src/lib/design/tokens.ts` | 수정 | `colors.pinInterest = "#7B68EE"` (또는 `colors["pin-interest"]`) | FR-PIN-12-10 |
| CSS | `frontend/src/app/globals.css` (`@theme`) | 수정 | `--color-pin-interest: #7B68EE` + `@keyframes pin-pulse` (0.5s, opacity+scale) | FR-PIN-12-11 |
| 말풍선 | `frontend/src/app/map/_components/PinPopup.tsx` | 수정 | (a) 출처 뱃지(📹/✏️) `pin.instagramUrl` 분기 (b) `[가고 싶어요]` 토글 버튼 (REEL/WISH 노출, MEMORY 숨김) (c) WANT 카운트 표시 (d) `?` 아이콘 → `TagProgressModal` open | FR-PIN-12-2, 12, 28 |
| 모달 | `frontend/src/app/map/_components/TagProgressModal.tsx` | 신규 | 발견→위시→추억 진행 다이어그램. 현재 핀 위치 강조 | FR-PIN-12-28 |
| 핀 카드 | `frontend/src/app/pins/_components/PinCard.tsx` | 수정 | 출처 뱃지 + WANT 카운트 + 진행 다이어그램 `?` | FR-PIN-12-12, 28 |
| 정리 배너 | `frontend/src/app/pins/_components/CleanupBanner.tsx` | 신규 | 정리 대상 N개·`[한꺼번에 정리]`·`[나중에]` + optimistic update | FR-PIN-12-23~25 |
| 정리 통합 | `frontend/src/app/pins/PinListClient.tsx` | 수정 | `CleanupBanner` 마운트 + 후처리(목록 갱신) | FR-PIN-12-23 |
| 필터 | `frontend/src/app/map/_components/MapFilter.tsx` | 신규/수정 | "발견 ▾" 드롭다운 (모든 발견 / 관심 있는 발견) | FR-PIN-12-26 |
| 지도 | `frontend/src/app/map/_components/MapClient.tsx` | 수정 | `?reel_bundle={notificationId}` 파라미터 → 번들 ID 외 핀 `opacity: 0.3` + 상단 "[해제]" 배너 | FR-PIN-12-27 |
| 지도 | `frontend/src/app/map/_components/MapClient.tsx` | 수정 | WISH 전환 1회 펄스 트리거 (이전 상태 vs 현재 상태 비교) | FR-PIN-12-11 |
| 알림 상세 | `frontend/src/app/notifications/_components/NotificationDetail.tsx` (실제 경로) | 수정 | "📍 지도에서 보기" 버튼 + `WISH_CONVERTED` 본문 | FR-PIN-12-6, 27 |
| API 클라이언트 | `frontend/src/lib/api/pin.ts` 또는 `pin-client.ts` | 수정 | `toggleWant(groupId, pinId)`, `getWantStatus(...)`, `fetchPinList`에 `sort/interest` 인자, 응답에 `wantCount/myWant` 매핑 | FR-PIN-12-7~9 |
| API 클라이언트 | `frontend/src/lib/api/cleanup-client.ts` | 신규 | `fetchCleanupCandidates(groupId)`, `executeCleanup(groupId)` | FR-PIN-12-23, 24 |
| API 클라이언트 | `frontend/src/lib/api/me-client.ts` | 수정 | `snoozeCleanup()` 메서드 추가 | FR-PIN-12-25 |
| 타입 | `frontend/src/lib/api/types.ts` | 수정 | `PinSummaryResponse`에 `wantCount: number`, `myWant: boolean` 추가 / `WantToggleResponse` 추가 | — |

---

## 3. 데이터 모델 변경 (V012)

**파일**: `backend/apps/wherewego-api/src/main/resources/db/migration/V012__pin_experience_v2.sql`

Flyway 단일 트랜잭션. V006이 단일 트랜잭션 안에서 `ALTER → UPDATE → ALTER`를 원자적으로 실행한 선례를 그대로 따른다.

```sql
-- ─── 1) pin_events 테이블 ────────────────────────────────
CREATE TABLE pin_events (
    id         BIGSERIAL   PRIMARY KEY,
    pin_id     BIGINT      NOT NULL REFERENCES pins(id),
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    group_id   BIGINT      NOT NULL REFERENCES groups(id),
    action     VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_pin_events_action CHECK (action IN ('WANT'))
);

-- D-19 영구 멱등: (pin_id, user_id) WHERE action='WANT' UNIQUE
CREATE UNIQUE INDEX uq_pin_events_pin_user_want
    ON pin_events (pin_id, user_id)
    WHERE action = 'WANT';

CREATE INDEX idx_pin_events_pin_id          ON pin_events(pin_id);
CREATE INDEX idx_pin_events_group_created   ON pin_events(group_id, created_at DESC);

COMMENT ON TABLE pin_events IS 'Phase 12: 핀 관심 표현(WANT) 이력. 후속 Phase 12.2에서 VIEW/SHARE/ROULETTE_SELECTED 확장.';
COMMENT ON CONSTRAINT chk_pin_events_action ON pin_events IS 'P0=WANT only. Phase 12.2 ALTER 예정 (D-17).';

-- ─── 2) pins.want_count ──────────────────────────────────
ALTER TABLE pins ADD COLUMN want_count INT NOT NULL DEFAULT 0;

-- 정리 대상 조회 가속 (FR-PIN-12-23)
CREATE INDEX idx_pins_cleanup
    ON pins (group_id, created_at)
    WHERE tag = 'REEL'
      AND memo_source = 'AUTO'
      AND deleted_at IS NULL;

-- 정렬 가속 (FR-PIN-12-8)
CREATE INDEX idx_pins_group_want_count
    ON pins (group_id, want_count DESC)
    WHERE deleted_at IS NULL;

-- ─── 3) notifications.type WISH_CONVERTED 확장 ───────────
ALTER TABLE notifications DROP CONSTRAINT IF EXISTS chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type
    CHECK (type IN ('MANUAL_PIN', 'CHATBOT_PINS', 'VISIT_DETECTED', 'WISH_CONVERTED'));

-- WISH_CONVERTED 알림 멱등 보장: 동일 (group_id, pin_id, type=WISH_CONVERTED) 1건만
CREATE UNIQUE INDEX uq_notifications_wish_converted
    ON notifications (group_id, registered_by, receiver_id)
    WHERE type = 'WISH_CONVERTED';
-- ※ 정확한 컬럼 조합은 기존 notifications 스키마(V008 부근) 확인 후 조정
--   핵심은 "같은 pin의 같은 receiver에게 1회만" 멱등을 부분 UNIQUE로 강제하는 것.

-- ─── 4) users.cleanup_snoozed_until ──────────────────────
ALTER TABLE users ADD COLUMN cleanup_snoozed_until TIMESTAMPTZ;
COMMENT ON COLUMN users.cleanup_snoozed_until IS '오래된 핀 정리 배너 snooze 만료. NULL = snooze 없음 (D-11).';
```

**주의 - notifications 멱등 인덱스**: 현재 notifications 테이블 구조에 `pin_id` 컬럼이 직접 있는지(`NotificationPin` 링크 테이블만 있는지)에 따라 멱등 키가 달라진다. `NotificationPin` 링크 모델이면 멱등은 "동일 pin이 link된 동일 (group_id, receiver_id, type=WISH_CONVERTED) Notification이 1개"여야 하며, 이는 부분 UNIQUE로 표현 불가하다. 대안:
- (a) `Notification`에 `pin_id BIGINT NULL` 추가 + `uq_notifications_wish_converted(group_id, receiver_id, pin_id) WHERE type='WISH_CONVERTED'` (권장)
- (b) `NotificationService.createForWishConverted`에서 사전 SELECT 후 INSERT (race-condition 위험 → 트랜잭션 + UNIQUE 필수)

→ **확인이 필요한 사항**에 명시 (질문 1).

---

## 4. 도메인 모델 변경

### 4.1 `Pin.java` 수정

```java
// 신규 필드
@Column(name = "want_count", nullable = false)
private int wantCount;

// 신규 도메인 메서드
/** WANT 증가/감소 (±1만 허용). 호출자는 트랜잭션 + 비관 락 보유. */
public void applyWantDelta(int delta) {
    if (delta != 1 && delta != -1) {
        throw new IllegalArgumentException("delta must be ±1");
    }
    int next = this.wantCount + delta;
    if (next < 0) {
        // race 방어. 호출자 트랜잭션에서 일관성 유지하지만 도메인 invariant 추가 확인.
        throw new CoreException(ErrorType.PIN_WANT_COUNT_NEGATIVE);
    }
    this.wantCount = next;
}

/**
 * 과반 충족 시 REEL → WISH 전환. 이미 WISH/MEMORY 면 no-op (멱등).
 * @return 이 호출이 실제 전환을 트리거했으면 true.
 */
public boolean transitionToWishIfMajority(int activeMemberCount) {
    if (this.tag != PinTag.REEL) return false;
    int threshold = activeMemberCount / 2 + 1;
    if (this.wantCount < threshold) return false;
    this.tag = PinTag.WISH;
    return true;
}
```

`changeTag` 기존 로직(WISH/REEL → MEMORY visitedAt 기록)은 유지. WISH 전환은 별도 메서드로 분리해 트랜잭션 흐름에서 명시적으로 호출한다.

### 4.2 `UserModel.java` (또는 실제 user Entity) 수정

```java
@Column(name = "cleanup_snoozed_until")
private ZonedDateTime cleanupSnoozedUntil;

/** 7일 snooze. 기존 값을 덮어쓴다 (재snooze 가능). */
public void snoozeCleanup(java.time.Duration duration) {
    this.cleanupSnoozedUntil = ZonedDateTime.now().plus(duration);
}

public boolean isCleanupSnoozed(ZonedDateTime now) {
    return cleanupSnoozedUntil != null && cleanupSnoozedUntil.isAfter(now);
}
```

### 4.3 `PinEvent.java` (신규)

```java
@Entity
@Getter
@Table(name = "pin_events",
       indexes = { @Index(name = "idx_pin_events_pin_id", columnList = "pin_id") })
public class PinEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pin_id", nullable = false)   private Long pinId;
    @Column(name = "user_id", nullable = false)  private Long userId;
    @Column(name = "group_id", nullable = false) private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private PinEventAction action;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    protected PinEvent() {}

    private PinEvent(Long pinId, Long userId, Long groupId, PinEventAction action) {
        this.pinId = pinId; this.userId = userId; this.groupId = groupId;
        this.action = action;
        this.createdAt = ZonedDateTime.now();
    }

    public static PinEvent wantOf(Long pinId, Long userId, Long groupId) {
        return new PinEvent(pinId, userId, groupId, PinEventAction.WANT);
    }
}
```

`BaseEntity` 미상속: pin_events는 **이력성** 테이블이라 soft-delete(`deleted_at`)·`updated_at`이 의미 없다. WANT 취소는 row DELETE (멱등 UNIQUE의 의도).

### 4.4 `PinEventAction.java` (신규)

```java
public enum PinEventAction { WANT }   // Phase 12.2에서 VIEW/SHARE/ROULETTE_SELECTED 추가
```

### 4.5 `NotificationType.java` 수정

```java
public enum NotificationType {
    MANUAL_PIN, CHATBOT_PINS, VISIT_DETECTED, WISH_CONVERTED
}
```

---

## 5. WANT 서비스 설계

### 5.1 클래스 위치·책임 분리

**`com.wherewego.domain.pin.want.WantService`** — 신규 별도 패키지·클래스. PinService(CRUD)와 책임 분리.

```java
@Service
@RequiredArgsConstructor
public class WantService {

    private final PinRepository pinRepository;
    private final PinEventRepository pinEventRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupMemberService groupMemberService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * WANT 토글. 멱등성·동시성·과반 전환을 단일 트랜잭션에 격리.
     *
     * 흐름:
     *  1) 활성 멤버십 검증
     *  2) pins SELECT FOR UPDATE (PESSIMISTIC_WRITE)
     *  3) MEMORY 핀이면 PIN_WANT_FORBIDDEN_TAG
     *  4) pin_events 존재 여부 확인 → 없으면 INSERT + delta=+1, 있으면 DELETE + delta=-1
     *  5) pin.applyWantDelta(delta)
     *  6) delta=+1 이고 tag=REEL 이면 active member count 조회 → transitionToWishIfMajority
     *  7) 전환된 경우 WishConvertedEvent publish (AFTER_COMMIT 처리)
     *  8) WantToggleResult 반환
     */
    @Transactional
    public WantToggleResult toggle(Long userId, Long groupId, Long pinId) { /* ... */ }

    @Transactional(readOnly = true)
    public WantStatus getStatus(Long userId, Long groupId, Long pinId) { /* ... */ }
}

public record WantToggleResult(
    PinTag tag, int wantCount, boolean myWant, boolean wishConverted
) {}

public record WantStatus(int wantCount, boolean myWant) {}
```

### 5.2 트랜잭션 의사코드

```
toggle(userId, groupId, pinId):
  groupMemberService.requireActiveMembership(userId, groupId)
  Pin pin = pinRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId)
              .orElseThrow(PIN_NOT_FOUND)
  if (pin.tag == MEMORY) throw PIN_WANT_FORBIDDEN_TAG

  boolean existed = pinEventRepository.existsByPinAndUserAndAction(pinId, userId, WANT)
  int delta
  boolean myWantAfter
  if (existed):
      pinEventRepository.deleteByPinAndUserAndAction(pinId, userId, WANT)
      delta = -1; myWantAfter = false
  else:
      try:
          pinEventRepository.save(PinEvent.wantOf(pinId, userId, groupId))
      catch DataIntegrityViolationException:        // 동시 클릭으로 UNIQUE 충돌
          // 멱등 보장: 이미 INSERT 된 것으로 간주 → no-op
          return getStatusInternal(pin, userId)
      delta = +1; myWantAfter = true

  pin.applyWantDelta(delta)

  boolean wishConverted = false
  if (delta == +1 && pin.tag == REEL):
      int activeMembers = groupMemberRepository.countActiveByGroupId(groupId)
      wishConverted = pin.transitionToWishIfMajority(activeMembers)
      if (wishConverted):
          eventPublisher.publishEvent(new WishConvertedEvent(
              groupId, pinId, userId, pin.placeName))

  return new WantToggleResult(pin.tag, pin.wantCount, myWantAfter, wishConverted)
```

### 5.3 멱등 보장 메커니즘

| 레이어 | 메커니즘 |
|--------|----------|
| DB | `uq_pin_events_pin_user_want` partial UNIQUE → 동시 INSERT 시 1건만 성공 |
| Repository | `DataIntegrityViolationException` catch → "이미 INSERT됨"으로 간주, 현재 상태 재조회 |
| Service | `SELECT FOR UPDATE` 으로 pins 행을 잠근 후 `pin_events` 작업 → want_count 정합성 |
| Application | 이벤트 publish는 `applyWantDelta(+1)` + `REEL → WISH` 전환 1회에서만 발생 → 자연 중복 방지 |
| Listener | `notifications.uq_notifications_wish_converted` 부분 UNIQUE로 알림 INSERT 멱등 |

### 5.4 `WishConvertedEvent`와 리스너 분리

```java
package com.wherewego.domain.pin.want;
public record WishConvertedEvent(Long groupId, Long pinId, Long triggerUserId, String placeName) {}
```

```java
package com.wherewego.domain.notification;

@Component
@RequiredArgsConstructor
public class WishConvertedNotificationListener {
    private final NotificationService notificationService;
    private static final Logger log = LoggerFactory.getLogger(...);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(WishConvertedEvent ev) {
        try {
            notificationService.createForWishConverted(
                ev.groupId(), ev.pinId(), ev.triggerUserId(), ev.placeName());
        } catch (RuntimeException e) {
            log.warn("WISH_CONVERTED notification failed groupId={} pinId={}",
                     ev.groupId(), ev.pinId(), e);
        }
    }
}
```

기존 `createForVisitDetected`(비트랜잭션 + 호출자 try/catch)와 달리, 본 리스너는 자체 try/catch로 격리. `NotificationService.createForWishConverted`는 `@Transactional` 단일 호출로 fan-out.

---

## 6. 알림 시스템 확장

### 6.1 `NotificationService.createForWishConverted`

```java
/**
 * Phase 12: REEL → WISH 자동 전환 알림.
 * - receiver = 본인(triggerUserId) 제외 활성 그룹원 전원
 * - 멱등: uq_notifications_wish_converted 부분 UNIQUE 위반 시 조용히 스킵
 * - 트랜잭션: REQUIRED (자체 커밋)
 * - 알림 본문(placeName)은 클라이언트가 N+1 회피로 함께 받을 수 있도록 NotificationPin 링크로 핀을 연결
 */
@Transactional
public void createForWishConverted(Long groupId, Long pinId, Long triggerUserId, String placeName) {
    if (groupMemberRepository.findActiveByGroupIdAndUserId(groupId, triggerUserId).isEmpty()) {
        log.warn("createForWishConverted skipped — trigger {} not active in group {}",
                 triggerUserId, groupId);
        return;
    }
    List<Long> receiverIds = groupMemberRepository.findOtherActiveMemberIds(groupId, triggerUserId);
    for (Long receiverId : receiverIds) {
        try {
            Notification n = repository.save(
                Notification.create(groupId, receiverId, triggerUserId, NotificationType.WISH_CONVERTED));
            repository.saveAllPins(List.of(NotificationPin.link(n.getId(), pinId, 0)));
        } catch (DataIntegrityViolationException e) {
            log.debug("WISH_CONVERTED notification skipped (duplicate) receiverId={} pinId={}",
                      receiverId, pinId);
        }
    }
}
```

### 6.2 알림 본문 템플릿 (D-16)

- `NotificationItemResult.firstPlaceName` 그대로 활용
- 프론트 `NotificationItem` 컴포넌트가 `type === "WISH_CONVERTED"`에서 `"🌟 '{placeName}'이 위시로 올라갔어요! 둘 다 가고 싶어해요"` 렌더링
- "📍 지도에서 보기" 버튼: `NotificationDetail`에서 `WISH_CONVERTED`·`CHATBOT_PINS` 타입에서 노출 (PRD 시나리오 5는 챗봇 알림 기준이므로 두 타입에서 모두 활성화 — 단, PRD FR-PIN-12-27은 `notificationId` 단일 키로 기술되어 있어 `?reel_bundle={notificationId}`만 발급)

---

## 7. API 명세 (신규/확장)

### 7.1 신규 — `POST /api/v1/groups/{gid}/pins/{pid}/want`

```
요청: 본문 없음
응답 200:
{
  "tag": "WISH",        // 토글 결과 핀의 현재 태그
  "wantCount": 2,
  "myWant": true,       // 토글 결과 (방금 누른 경우 true, 취소한 경우 false)
  "wishConverted": true // 이번 호출이 REEL → WISH를 트리거했으면 true
}
에러:
 - 403 GROUP_NOT_MEMBER (활성 멤버 아님)
 - 404 PIN_NOT_FOUND
 - 400 PIN_WANT_FORBIDDEN_TAG (tag=MEMORY)
```

Controller 시그니처:
```java
@PostMapping("/{groupId}/pins/{pinId}/want")
public ApiResponse<PinV1Dto.WantToggleResponse> toggleWant(
    @AuthUser Long userId,
    @PathVariable Long groupId,
    @PathVariable Long pinId
);
```

### 7.2 신규 — `GET /api/v1/groups/{gid}/pins/{pid}/want`

```
응답 200: { "wantCount": 2, "myWant": true }
```

### 7.3 확장 — `GET /api/v1/groups/{gid}/pins`

신규 쿼리 파라미터:
- `sort=created_at` (default) | `want_count`
- `interest=true|false` (default false) — `want_count >= 1` 필터

응답 각 핀에 추가:
```json
{ ... 기존 필드 ...,
  "wantCount": 1, "myWant": false }
```

검증:
- `sort` 값이 enum 외이면 `PIN_SORT_PARAM_INVALID` (400)
- `interest=true` + `tag=WISH/MEMORY` 조합은 허용 (필터 단순 AND)
- 페이지네이션 계약(`page`/`size` 부분전달 400)은 기존 그대로

### 7.4 신규 — `GET /api/v1/groups/{gid}/cleanup/candidates`

```
응답 200:
{
  "totalCount": 7,
  "snoozedUntil": null,        // 또는 ISO timestamp (snooze 중이면 totalCount=0 + snoozedUntil 노출)
  "items": [
    { "id": 12, "placeName": "...", "createdAt": "2026-04-20T...", "address": "...", ... }
  ]
}
```

- `users.cleanup_snoozed_until > now()` 이면 `items=[], totalCount=0, snoozedUntil=...` 반환
- 활성 그룹원만 호출 가능 (403)

### 7.5 신규 — `POST /api/v1/groups/{gid}/cleanup/execute`

```
요청: 본문 없음 (서버가 현재 조건으로 재계산하여 일괄 삭제 — race-safe)
응답 200: { "deletedCount": 7 }
```

- 활성 멤버 권한 검증
- 트랜잭션 내에서 `findCleanupCandidates`로 ID 목록 재조회 후 `softDeleteAll(ids)` (LOCK 없이도 idempotent — soft-delete는 BaseEntity.delete() 멱등)

### 7.6 신규 — `POST /api/v1/users/me/cleanup-snooze`

```
요청: 본문 없음 (서버가 NOW()+7일 계산)
응답 200: { "snoozedUntil": "2026-06-03T..." }
```

- `user.snoozeCleanup(Duration.ofDays(7))` + save
- 별도 그룹 검증 없음 (자기 자신)

### 7.7 DTO 변경 — `PinV1Dto`

```java
// PinSummaryResponse 에 필드 추가
public record PinSummaryResponse(
    Long id, ..., PinTag tag, ZonedDateTime createdAt, ZonedDateTime visitedAt,
    Long memoUpdatedBy, String memoUpdatedByNickname,
    int wantCount, boolean myWant   // ← 신규
) { ... }

// 신규
public record WantToggleResponse(PinTag tag, int wantCount, boolean myWant, boolean wishConverted) {
    public static WantToggleResponse from(WantToggleResult r) { ... }
}

public record WantStatusResponse(int wantCount, boolean myWant) { ... }
```

`PinSummary` record에도 `wantCount`, `myWant` 필드 추가. `myWant` 계산을 위해 `toSummaries(...)` 메서드가 `pin_events`를 batch 조회해야 한다:

```java
// PinService.toSummaries(...) 변경
// 1) pinIds 추출
// 2) pinEventRepository.findMyWantPinIds(pinIds, userId) → Set<Long>
// 3) 각 Pin → PinSummary 변환 시 myWant 주입
```

신규 PortMethod:
```java
// PinEventRepository
Set<Long> findMyWantPinIds(Collection<Long> pinIds, Long userId);
```

---

## 8. 챗봇 v2 상태머신

### 8.1 `ReelSavedSelectionSession` 설계

**파일**: `com.wherewego.domain.chatbot.ReelSavedSelectionSession`

기존 `PendingInstagramSession`이 `cache().put(String, String)` 단순 K-V 였던 것을 **상태 스냅샷 객체**로 확장.

```java
@Component
@RequiredArgsConstructor
public class ReelSavedSelectionSession {

    private final CacheManager cacheManager;

    public enum SessionState {
        PROCESSING, SINGLE_WANT, MULTI_SELECTING, BULK_SAVE, MEMO_WAITING
    }

    public record Snapshot(
        Long groupId,
        Long userId,
        String instagramUrl,
        List<ExtractedPlace> allPlaces,        // PROCESSING에서 채워짐
        Set<Integer> selectedIndices,          // 1-based
        boolean singleWantYes,                 // SINGLE_WANT에서 사용자 선택 결과
        SessionState state,
        Instant expiresAt
    ) {
        public Snapshot withState(SessionState newState, Instant newExpiry) { /* ... */ }
        public Snapshot withSelection(Set<Integer> sel) { /* ... */ }
        public Snapshot withSingleWantYes(boolean yes) { /* ... */ }
    }

    public record ExtractedPlace(int index, String placeName, double lat, double lon,
                                  String kakaoAddress, boolean confident) {}

    public void put(String botUserKey, Snapshot snapshot) {
        cache().put(botUserKey, snapshot);
    }

    public Optional<Snapshot> peek(String botUserKey) { /* ... */ }
    public void invalidate(String botUserKey) { /* ... */ }
}
```

캐시 이름: `CacheConfig.REEL_SELECTION` (신규 상수). TTL: `chatbot.instagram.reel-selection-ttl-seconds: 180` (3분). 만료 시 expireAfterWrite로 자동 evict, 별도 스케줄러는 **자동 저장 task**를 위해 유지 (PendingInstagramAutoSaveScheduler 변형).

### 8.2 핸들러 패키지 구조

```
com.wherewego.domain.chatbot.handler/
├── InstagramLinkHandler              (수정 — 분기 진입)
├── ReelSingleWantHandler             (신규 — SINGLE_WANT)
├── ReelMultiSelectionHandler         (신규 — MULTI_SELECTING)
├── ReelBulkSaveHandler               (신규 — BULK_SAVE)
├── ReelMemoWaitingHandler            (신규 — MEMO_WAITING 공통)
├── InstagramPendingMemoHandler       (삭제 — 폐기)
└── ... (기존 LinkCodeHandler / PlaceSelectionHandler / UnknownHandler 유지)

com.wherewego.domain.chatbot.reel/
├── ReelCommaParser                   (신규 — 콤마 파서)
└── ReelPlaceExtractor                (가능하면 신규 — Gemini 호출 책임 분리)
```

`ChatbotWebhookService.handle()`에서 `MessageClassifier` → 신규 타입 분기 → 각 핸들러 dispatch. 활성 세션 + 룰렛/공유 액션이면 `WebhookService`가 가드(EC-R1/R2).

### 8.3 콤마 파서 알고리즘

**파일**: `com.wherewego.domain.chatbot.reel.ReelCommaParser`

```java
@Component
public class ReelCommaParser {

    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    public sealed interface Result {
        record Success(LinkedHashSet<Integer> indices) implements Result {}
        record Invalid(InvalidReason reason) implements Result {}
    }

    public enum InvalidReason {
        FORMAT_MISMATCH,   // 콤마 외 구분자, 한글, 범위 표기 등
        OUT_OF_RANGE,      // 1 ≤ n ≤ totalCount 위반
        EMPTY              // 빈 결과
    }

    /**
     * 콤마 파싱 (D-2 침묵 dedup).
     *  1) input.split(",", -1)
     *  2) trim 후 빈 토큰 무시 (trailing/연속 콤마 허용)
     *  3) 토큰 ^\d+$ 매칭 실패 → FORMAT_MISMATCH
     *  4) 정수 변환 후 1..totalCount 검증 → OUT_OF_RANGE
     *  5) LinkedHashSet 으로 dedup
     *  6) 비어 있으면 EMPTY (",", " ," 등)
     */
    public Result parse(String input, int totalCount) { /* ... */ }
}
```

핸들러 사용:
```java
ReelCommaParser.Result r = parser.parse(utterance, snapshot.allPlaces().size());
return switch (r) {
    case ReelCommaParser.Result.Success s -> goToMemoWaiting(s.indices(), snapshot);
    case ReelCommaParser.Result.Invalid i -> retryMultiSelecting(i.reason(), snapshot);
};
```

TTL 리셋 안 함: `retryMultiSelecting`은 기존 `expiresAt`을 보존한 채 `cache.put()` (NFR-12-5).

### 8.4 MessageClassifier 우선순위

```
PLACE_SELECTION (params.placeId)
> LINK_CODE (params.code)
> INSTAGRAM_LINK (URL 패턴)
> (session.state=SINGLE_WANT) ? "가고 싶어요" / "발견으로만 저장" / "건너뛰기" 정확 매칭 → SINGLE_WANT_YES/NO/SKIP
> (session.state=MULTI_SELECTING || BULK_SAVE) → REEL_PLACE_SELECTION
> (session.state=MEMO_WAITING) → REEL_MEMO_WAITING
> TEXT_2SEC_CANDIDATE (TwoSecondMemoSession)
> UNKNOWN
```

각 분기에서 `ReelSavedSelectionSession.peek(botUserKey)` 한 번 조회 후 결과를 핸들러에 그대로 전달 (handler가 다시 peek할 필요 없음).

### 8.5 새 URL 도착 / 룰렛·공유 가드

`ChatbotWebhookService`에서 `MessageType.INSTAGRAM_LINK` 분기 직전에:

```java
ReelSavedSelectionSession.peek(botUserKey).ifPresent(snapshot -> {
    // EC-U1~U5: 현재 선택 기준으로 자동 저장
    autoSaveOnSwitch(snapshot);   // ReelMemoWaitingHandler 의 공통 저장 로직 재사용
    session.invalidate(botUserKey);
    pendingNotificationSession.put(botUserKey, /* "이전 N곳..." prepend 메시지 */);
});
```

룰렛/공유 액션 (PLACE_SELECTION / 기타) 진입 시 활성 세션이 SELECTION/MEMO 단계면:
```java
if (snapshot.state() in {MULTI_SELECTING, MEMO_WAITING}) {
    return reply("지금은 릴스 장소 처리 중이에요. 끝나면 다시 시도해주세요.");
}
```

### 8.6 TTL 만료 자동 저장

`PendingInstagramAutoSaveScheduler`를 `ReelSelectionAutoSaveScheduler`로 리네이밍 + 변경:
- URL 도착 시 `schedule(botUserKey, 180s)` 등록
- 다른 발화로 진행되면 `cancel(botUserKey)`
- 만료 시 `forceSaveOnExpire(botUserKey)`:
  - SINGLE_WANT → REEL 1건 저장, `EC-T1` 안내 prepend
  - MULTI_SELECTING → 전체 REEL 저장, `EC-T2` 안내
  - BULK_SAVE → 전체 REEL 저장, `EC-T3` 안내
  - MEMO_WAITING → 기존 선택 저장 (메모 없음), `EC-T4` 안내
- 결과는 `PendingNotificationSession`에 적재 → 다음 발화 prepend

### 8.7 MEMO_WAITING 저장 흐름 (broadcast)

`ReelMemoWaitingHandler.execute(snapshot, utterance)`:
1. utterance에서 메모 텍스트 추출 (1000자 초과 → 900자 절단 + EC-D2 안내 부착)
2. `pinService.registerFromInstagramWithDedup` 반복 호출(N건) — 각 핀에 memo 동일 적용 + `memo_source=AUTO` (`applyManualMemo`는 MANUAL 마킹이므로 사용 X — 별도 `applyAutoMemo` 메서드 추가 필요 or 핀 저장 후 `memoSource=AUTO`로 직접 marking)
3. **WANT 적용**: `snapshot.selectedIndices`에 포함된 핀에 대해 저장 직후 `WantService.toggle(...)` 또는 별도 헬퍼 `wantService.markWantOnInitialSave(userId, groupId, pinId)` 호출
   - 단순화: 별도 헬퍼는 `pin_events` INSERT + `want_count++`만 수행 (과반 검사 X — 본인 1표로 2인 그룹 과반 미달이 일반적이지만, 1인 그룹 등 케이스에서 WISH 전환 의도와 충돌 가능 → 정책 확인 필요)
   - 권장: `WantService.toggle`을 그대로 호출 (저장 직후 toggle 1회 = WANT INSERT 보장. 멱등 UNIQUE로 안전)
4. `NotificationService.createForChatbotBatch(groupId, userId, pinIds)` 호출

> **확인이 필요한 사항**: 챗봇 저장 직후 1인 그룹에서 WANT 자기 1표 = 과반 충족 → 즉시 WISH 전환되는데 의도된 동작인지 (질문 4).

### 8.8 메모 적용 정책 보강

기존 `Pin.applyManualMemo`는 `memoSource=MANUAL`로 마킹한다. 챗봇 broadcast 메모는 **AUTO**여야 하므로 도메인 메서드를 추가:

```java
// Pin.java 신규
public void applyAutoMemo(String memo) {
    this.memo = memo;
    this.memoSource = MemoSource.AUTO;
    this.memoUpdatedBy = null;       // AUTO 메모는 작성자 표기 없음
}
```

기존 `applyManualMemo`는 호출자 그대로 유지 (단일 핀 등록 + 사용자 메모 직접 입력 경로). 챗봇 broadcast만 `applyAutoMemo` 사용.

---

## 9. 프론트엔드 변경

### 9.1 `lib/pin/markers.tsx` 변경

- `PinKind` 타입 확장: `"reel" | "reel-interest" | "wish" | "memory"`
- `PIN_COLORS["reel-interest"] = "#7B68EE"`
- 신규 `getInterestSvgString(size, color)` (원형, viewBox 0 0 10 10, REEL과 동일 path / 색 다름)
- 신규 `InterestGlyph` React 컴포넌트
- `getMarkerVariant(tag, wantCount)` 헬퍼:
  ```ts
  export type MarkerVariant = "reel" | "reel-interest" | "wish" | "memory";
  export function getMarkerVariant(tag: PinTag, wantCount: number): MarkerVariant {
    if (tag === "MEMORY") return "memory";
    if (tag === "WISH")   return "wish";
    return wantCount >= 1 ? "reel-interest" : "reel";
  }
  ```
- 크기 계수: `MARKER_SIZE_FACTOR = { reel:1.0, "reel-interest":1.1, wish:1.2, memory:1.0 }`

### 9.2 펄스 keyframe

`globals.css` `@theme` 외부에 추가:
```css
@keyframes pin-pulse {
  0%   { transform: scale(1);   opacity: 1; }
  50%  { transform: scale(1.35); opacity: 0.6; }
  100% { transform: scale(1);   opacity: 1; }
}
.pin-pulse-once { animation: pin-pulse 0.5s ease-out 1; }
```

`PinDot`/`MapClient`에서 props로 `pulse: boolean`을 받아 클래스 토글. WISH 전환 trigger는 클라이언트 상태 비교(prev `tag !== "WISH"` && new `tag === "WISH"`) 또는 서버 응답 `wishConverted: true`로 판정. `MapClient`가 핀 갱신 시 1회 클래스 부착 후 0.5초 후 제거.

### 9.3 `PinPopup.tsx` 변경

`SpeechBubblePopup` 상단 또는 footer에 다음 추가:

- **출처 뱃지**: `pin.instagramUrl ? "📹" : "✏️"`
- **WANT 버튼**: `pin.tag !== "MEMORY"` 일 때만 노출
  ```tsx
  <button onClick={handleWantToggle} aria-pressed={pin.myWant}>
    {pin.myWant ? "💙 가고 싶어요 취소" : "💙 가고 싶어요"}
    <span>{pin.wantCount}</span>
  </button>
  ```
  - `useOptimistic`으로 상태 즉시 반영
  - 응답의 `wishConverted: true` 시 부모(`MapClient`)에 통지 → 마커 펄스 트리거
- **`?` 아이콘**: 태그 라벨 옆 → `TagProgressModal` open

`onWantToggle` prop 추가 (부모 `MapClient`가 제공):
```ts
onWantToggle: (pinId: number) => Promise<WantToggleResponse>;
```

### 9.4 `TagProgressModal.tsx` (신규)

```tsx
export default function TagProgressModal({
  currentTag, wantCount, onClose,
}: { currentTag: PinTag; wantCount: number; onClose: () => void }) {
  // 발견 → 관심 → 위시 → 추억 4단계 다이어그램 (SVG)
  // currentTag + wantCount로 현재 위치 강조
}
```

PRD 시나리오 6: 본문 "발견 핀에서 그룹원이 모두 '가고 싶어요'를 누르면 위시로 바뀌어요!"

### 9.5 `CleanupBanner.tsx` (신규)

**파일**: `frontend/src/app/pins/_components/CleanupBanner.tsx`

```tsx
"use client";

export default function CleanupBanner({
  groupId, onRefreshList,
}: { groupId: number; onRefreshList: () => void }) {
  const [candidates, setCandidates] = useState<CleanupCandidatesResponse | null>(null);

  useEffect(() => {
    fetchCleanupCandidates(groupId).then(setCandidates);
  }, [groupId]);

  if (!candidates || candidates.totalCount === 0) return null;

  return (
    <section aria-label="오래된 발견 핀 정리">
      <p>🗑️ 30일째 관심받지 못한 발견 핀이 {candidates.totalCount}개 있어요</p>
      <button onClick={async () => {
        await executeCleanup(groupId);
        setCandidates(null);
        onRefreshList();
      }}>🧹 한꺼번에 정리</button>
      <button onClick={async () => {
        await snoozeCleanup();
        setCandidates(null);
      }}>⏰ 나중에</button>
    </section>
  );
}
```

`PinListClient.tsx` 하단에 `<CleanupBanner groupId={...} onRefreshList={refetch} />` 삽입.

### 9.6 `MapFilter.tsx` (신규/수정)

기존 필터 탭 구조는 추가 확인 필요 (`Glob`이 빈 결과). 신규 작성 시:

```tsx
<button onClick={toggleDropdown}>발견 ▾</button>
{open && (
  <ul>
    <li onClick={() => onSelect({ tag: "REEL", interest: false })}>✓ 모든 발견</li>
    <li onClick={() => onSelect({ tag: "REEL", interest: true })}>🙋 관심 있는 발견</li>
  </ul>
)}
```

URL 쿼리: `?tag=REEL&interest=true`. `MapClient`가 `useSearchParams`로 읽어 핀 목록 fetch에 전달.

### 9.7 `MapClient.tsx` 변경

```ts
const reelBundleId = searchParams.get("reel_bundle");  // notificationId
const bundlePinIds: Set<number> = reelBundleId
  ? await fetchNotificationDetail(reelBundleId).then(d => new Set(d.pins.map(p => p.pinId)))
  : new Set();

// 렌더링:
pins.map(pin => (
  <PinDot
    pin={pin}
    opacity={bundlePinIds.size > 0 && !bundlePinIds.has(pin.id) ? 0.3 : 1}
    pulse={recentlyConvertedPinIds.has(pin.id)}
  />
));
```

상단 배너: `bundlePinIds.size > 0`일 때 "릴스 저장 핀 N개 표시 중 [해제]" → `[해제]` 클릭 시 `router.push("/map")` (쿼리 제거).

### 9.8 API 클라이언트 함수

```ts
// frontend/src/lib/api/pin.ts (또는 pin-client.ts)
export async function toggleWant(groupId: number, pinId: number): Promise<WantToggleResponse> {
  return http.post(`/api/v1/groups/${groupId}/pins/${pinId}/want`);
}

export async function getWantStatus(groupId: number, pinId: number): Promise<WantStatusResponse> {
  return http.get(`/api/v1/groups/${groupId}/pins/${pinId}/want`);
}

export async function fetchPinList(
  groupId: number,
  opts: { tag?: PinTag; sort?: "want_count"; interest?: boolean; page?: number; size?: number }
): Promise<PinListResponse> { /* 기존 + 새 파라미터 */ }

// frontend/src/lib/api/cleanup-client.ts (신규)
export async function fetchCleanupCandidates(groupId: number): Promise<CleanupCandidatesResponse> { ... }
export async function executeCleanup(groupId: number): Promise<{ deletedCount: number }> { ... }

// me-client.ts 확장
export async function snoozeCleanup(): Promise<{ snoozedUntil: string }> {
  return http.post(`/api/v1/users/me/cleanup-snooze`);
}
```

타입 추가:
```ts
// frontend/src/lib/api/types.ts
export interface PinSummaryResponse {
  /* ... 기존 ... */
  wantCount: number;
  myWant: boolean;
}
export interface WantToggleResponse {
  tag: PinTag;
  wantCount: number;
  myWant: boolean;
  wishConverted: boolean;
}
export interface CleanupCandidatesResponse {
  totalCount: number;
  snoozedUntil: string | null;
  items: PinSummaryResponse[];
}
```

---

## 10. 에러 처리·검증

`ErrorType`에 추가:

| 코드 | HTTP | 메시지 | 사용처 |
|------|------|--------|--------|
| `PIN_WANT_FORBIDDEN_TAG` | 400 | "추억 핀에는 가고 싶어요를 누를 수 없습니다." | `WantService.toggle` (tag=MEMORY) |
| `PIN_WANT_COUNT_NEGATIVE` | 500 | "WANT 카운트 상태가 잘못되었습니다." | `Pin.applyWantDelta` 방어 (race 후 정합성 오류 — 일반 호출에선 발생 안 함) |
| `PIN_SORT_PARAM_INVALID` | 400 | "정렬 파라미터가 유효하지 않습니다." | Controller `?sort=` 파라미터 |
| `PIN_INTEREST_PARAM_INVALID` | 400 | "관심 필터 파라미터가 유효하지 않습니다." | Controller `?interest=` 파라미터 (선택사항 — `Boolean.parseBoolean`이면 silently false) |

챗봇 영역은 ErrorType 대신 안내 문자열로 처리 (사용자 노출 텍스트). 내부 enum:
```java
enum ReelParseInvalidReason { FORMAT_MISMATCH, OUT_OF_RANGE, EMPTY }
```
각 reason → `ChatbotErrorMessages` 또는 핸들러 내 상수 문자열 매핑.

기존 `PIN_NOT_FOUND`, `GROUP_NOT_MEMBER`, `PIN_PAGE_PARAM_INVALID`, `PIN_TAG_INVALID`, `PIN_MEMO_TOO_LONG`은 그대로 재사용.

---

## 11. 테스트 전략

### 11.1 백엔드 통합 테스트 (`*IT.java`, Testcontainers PostgreSQL)

| 테스트 클래스 | 검증 항목 |
|---------------|----------|
| `V012MigrationIT` | `pin_events` 테이블·partial UNIQUE·`want_count` 기본값·`cleanup_snoozed_until` 컬럼·`WISH_CONVERTED` CHECK 모두 존재 (AC-12-1~4) |
| `WantToggleIT` | 1st 클릭 INSERT + count=1, 2nd 클릭 DELETE + count=0 (AC-12-5, 7) / MEMORY 핀 400 / 비활성 멤버 403 |
| `WantWishConvertedIT` | 2인 그룹 A→B 순서로 WANT → tag=WISH, `wishConverted=true` 응답, B에게 알림 1건. 이후 A WANT 취소 → tag=WISH 유지 (AC-12-8~10) |
| `WantConcurrencyIT` | `CountDownLatch`로 동일 핀 동시 토글 100회 → want_count는 0 또는 1 (멱등) |
| `WantNotificationIdempotentIT` | 같은 핀에 두 번 전환 트리거 (이론상 불가능하나 race 시) → notifications row 1건 |
| `PinListWantFieldsIT` | `GET /pins` 응답에 `wantCount`, `myWant` 포함. 다른 사용자 토글 후 본인 토큰으로 조회 시 본인 기준 myWant 반영 (AC-12-12) |
| `PinListSortWantCountIT` | `?sort=want_count` 정렬 (AC-12-13) |
| `PinListInterestOnlyIT` | `?interest=true` 필터 (AC-12-14) |
| `CleanupCandidateIT` | 30일+ AUTO REEL want_count=0 핀만 노출. snooze 중이면 totalCount=0 (AC-12-31, 33) |
| `CleanupExecuteIT` | 일괄 soft-delete 후 후속 GET이 빈 결과 (AC-12-32) |
| `CleanupSnoozeIT` | 7일 snooze 후 cleanup_snoozed_until 갱신 / GET 후보가 가려짐 (AC-12-33, 34) |
| `ReelSavedSelectionSessionIT` | URL → 1개 추출 시 SINGLE_WANT / 2-30개 MULTI / 31+ BULK / 새 URL 도착 자동 저장 / TTL 3분 만료 시 보수 저장 (AC-12-20~26) |
| `ReelMemoBroadcastIT` | MEMO_WAITING 메모 입력 시 같은 릴스 N개 핀에 동일 memo + `memo_source=AUTO` (AC-12-28) |
| `ReelSingleWantApplyIT` | SINGLE_WANT "가고 싶어요" → pin_events WANT 1건 + want_count=1 (AC-12-29) |
| `ReelRouletteGuardIT` | MULTI_SELECTING/MEMO_WAITING 중 PLACE_SELECTION 액션 → 거부 + 세션 유지 (AC-12-27) |

### 11.2 백엔드 단위 테스트 (`*Test.java`)

| 테스트 클래스 | 검증 항목 |
|---------------|----------|
| `ReelCommaParserTest` | EC-P1~P15 케이스(공백/범위/한글/dedup/trim/trailing/빈/단일) 모두 (AC-12-21~23) |
| `PinTransitionToWishTest` | `transitionToWishIfMajority` 멱등 (WISH/MEMORY 핀 호출 시 false) |
| `PinApplyWantDeltaTest` | ±1 외 입력 IllegalArgumentException / 0 미만 보호 |
| `UserSnoozeCleanupTest` | snoozeCleanup(7d) 후 isCleanupSnoozed=true / 만료 후 false |
| `MessageClassifierReelTest` | 신규 우선순위 (SINGLE_WANT 상태 + 정확 텍스트 매칭) |

### 11.3 프론트엔드 (Vitest)

| 테스트 | 검증 |
|--------|------|
| `markers.test.tsx` | `getMarkerVariant(tag, wantCount)` 4분기 |
| `PinPopup.test.tsx` | MEMORY 핀에서 WANT 버튼 미노출 (AC-12-6) / REEL에서 노출 / 클릭 시 optimistic 갱신 |
| `CleanupBanner.test.tsx` | totalCount=0 시 미렌더 / 정리 클릭 시 onRefreshList 호출 / 나중에 클릭 시 즉시 사라짐 |
| `TagProgressModal.test.tsx` | currentTag/wantCount별 강조 위치 |
| `MapClient.reel-bundle.test.tsx` | `?reel_bundle=` 쿼리 시 비번들 핀 opacity 0.3 |
| `MapClient.pulse.test.tsx` | `wishConverted=true` 응답 시 해당 핀에 `pin-pulse-once` 클래스 부착 후 500ms 후 제거 |

---

## 12. 구현 순서 (배치 분할)

PRD 우선순위(P0 Must → Should → Could)를 반영하되 **파일 배타성**과 **의존 관계**를 우선시한다. 같은 배치 안의 단계는 병렬 실행 가능.

### Batch A — 데이터 모델 기반 (P0 진입 게이트)

1. [Must] **V012 마이그레이션 작성** (의존: 없음)
   - 파일: `V012__pin_experience_v2.sql`
   - 산출: `pin_events`, `want_count`, `cleanup_snoozed_until`, `WISH_CONVERTED` CHECK + 부분 UNIQUE + 인덱스

### Batch B — 도메인 Entity & enum (병렬 가능, 1에만 의존)

2. [Must] `Pin.java` 필드/메서드 추가 (의존: 1)
3. [Must] `PinEvent.java` + `PinEventAction.java` 생성 (의존: 1)
4. [Must] `UserModel.java` `cleanup_snoozed_until` + 메서드 (의존: 1)
5. [Must] `NotificationType.java` `WISH_CONVERTED` 추가 (의존: 1)
6. [Must] `PinSummary.java`에 `wantCount`/`myWant` 필드 추가 (의존: 2)

→ 2, 3, 4, 5는 서로 독립이므로 병렬 실행 가능.

### Batch C — Repository 포트·어댑터 (의존: 2, 3)

7. [Must] `PinEventRepository` 포트 + `PinEventJpaRepository` + `PinEventRepositoryImpl` (의존: 3)
8. [Must] `PinRepository` 확장: cleanup 후보 조회, want_count 정렬, interest 필터 (의존: 2)
9. [Must] `PinRepositoryImpl` 구현 (의존: 8)

→ 7, 8은 병렬. 9는 8에 의존.

### Batch D — WANT 핵심 서비스 (의존: B, C)

10. [Must] `WishConvertedEvent` record (의존: 없음 / 패키지 결정)
11. [Must] `WantService` 클래스 (의존: 2, 7, 10)
12. [Must] `WishConvertedNotificationListener` (의존: 10, 5)
13. [Must] `NotificationService.createForWishConverted` 메서드 추가 (의존: 5)

→ 10은 다른 작업 없이 즉시 작성 가능. 11과 12, 13은 11이 가장 늦게 시작 가능(트랜잭션 흐름 검증을 위해).

### Batch E — REST API & DTO (의존: D)

14. [Must] `PinV1Dto`에 `wantCount`/`myWant` + `WantToggleResponse` 추가 (의존: 6)
15. [Must] `PinService.toSummaries` 변경 — `pin_events` batch 조회로 `myWant` 주입 (의존: 7, 14)
16. [Must] `PinV1Controller`에 `POST/GET /pins/{pid}/want` + `?sort=want_count` + `?interest=true` (의존: 11, 15)
17. [Must] `ErrorType` 신규 항목 추가 (의존: 없음, 16에 사용됨)

### Batch F — 마커 시각화 (백엔드 독립, 의존 없음)

18. [Must] `globals.css` `--color-pin-interest` + `@keyframes pin-pulse` (의존: 없음)
19. [Must] `lib/design/tokens.ts`에 `pinInterest` 토큰 (의존: 없음)
20. [Must] `lib/pin/markers.tsx` Interest 글리프 + `getMarkerVariant` (의존: 18, 19)
21. [Must] `PinDot.tsx` `wantCount`/`pulse` props 수용 (의존: 20)

→ 18, 19 병렬. 20은 둘에 의존. 21은 20에 의존.

### Batch G — 프론트 API & PinPopup (의존: E, F)

22. [Must] `types.ts` `wantCount`/`myWant`/`WantToggleResponse` 추가 (의존: 14)
23. [Must] `lib/api/pin.ts` `toggleWant`/`getWantStatus`/`fetchPinList` 확장 (의존: 22)
24. [Must] `PinPopup.tsx` WANT 버튼 + 출처 뱃지 + `?` 아이콘 (의존: 22, 23, 21)
25. [Must] `TagProgressModal.tsx` (의존: 22)
26. [Must] `MapClient.tsx` 펄스 trigger + WANT 토글 prop 전달 (의존: 24, 21)

→ 24와 25는 22에 의존하지만 서로 독립이므로 병렬. 26은 둘 모두에 의존.

### Batch H — 챗봇 v2 (백엔드 독립, B 이후)

27. [Must] `CacheConfig.REEL_SELECTION` 캐시 + `application.yml` TTL 설정 (의존: 없음)
28. [Must] `ReelSavedSelectionSession` (의존: 27)
29. [Must] `ReelCommaParser` (의존: 없음 — 순수 로직)
30. [Must] `Pin.applyAutoMemo` 메서드 추가 (의존: 2)
31. [Must] `MessageType`/`MessageClassifier` 확장 (의존: 28)
32. [Must] 4개 핸들러 신규 작성 (`ReelSingleWantHandler`, `ReelMultiSelectionHandler`, `ReelBulkSaveHandler`, `ReelMemoWaitingHandler`) (의존: 28, 29, 30, 11)
33. [Must] `InstagramLinkHandler` 수정 — 분기 진입 (의존: 28, 32)
34. [Must] `ChatbotWebhookService` 가드(룰렛/공유 거부, 새 URL 자동 저장) (의존: 31, 33)
35. [Must] `PendingInstagramAutoSaveScheduler` → `ReelSelectionAutoSaveScheduler` 변경 + TTL 만료 처리 (의존: 28, 32)
36. [Must] `PendingInstagramSession` + `InstagramPendingMemoHandler` 삭제 (의존: 34)

→ 27, 29, 30은 병렬. 28은 27에 의존. 29와 30은 독립. 32는 28·29·30·11 모두 필요. 36은 마지막(전환 검증 후).

### Batch I — 정리 시스템 (P1, 의존: C, F)

37. [Should] `CleanupService` 클래스 (의존: 9, 4)
38. [Should] `PinCleanupV1Controller` + `PinCleanupV1Dto` (의존: 37)
39. [Should] `UserCleanupSnoozeV1Controller` (의존: 4)
40. [Should] `lib/api/cleanup-client.ts` + `me-client.ts` 확장 (의존: 38, 39)
41. [Should] `CleanupBanner.tsx` + `PinListClient.tsx` 통합 (의존: 40)

→ 37, 39 병렬. 38은 37에 의존.

### Batch J — 맵 필터 & 알림 (P1)

42. [Should] `MapFilter.tsx` 발견 드롭다운 (의존: 23)
43. [Should] `MapClient.tsx` `?reel_bundle=` opacity 처리 + "[해제]" 배너 (의존: 26)
44. [Should] `NotificationDetail.tsx` "📍 지도에서 보기" 버튼 + `WISH_CONVERTED` 본문 (의존: 5, frontend types)

→ 42, 43, 44 병렬.

### Batch K — 테스트 (각 배치와 병렬 가능, 최소 코드 완성 후)

45. [Must] V012/WANT/Notification 통합 테스트 (의존: A, D)
46. [Must] ReelCommaParser/MessageClassifier 단위 테스트 (의존: 29, 31)
47. [Must] ReelSavedSelectionSession 통합 테스트 (의존: H)
48. [Should] Cleanup 통합 테스트 (의존: I)
49. [Must] 프론트 Vitest (의존: F, G, J)

### 병렬화 요약

- Batch B 내부(2, 3, 4, 5): 4개 작업 병렬
- Batch C 내부(7, 8): 2개 병렬
- Batch F 내부(18, 19): 2개 병렬
- Batch H 내부(27, 29, 30): 3개 병렬
- Batch I 내부(37, 39): 2개 병렬
- Batch J 내부(42, 43, 44): 3개 병렬
- Batch F(프론트) ⊥ Batch B-D-E(백엔드): 별도 워커로 병렬 가능
- Batch H(챗봇) ⊥ Batch F-G(프론트): 별도 워커로 병렬 가능 (단 챗봇은 11 WantService 완성 후 32 핸들러 시작)

---

## 설계 규모

**대형** — V012 마이그레이션 + 25+ 신규 백엔드 파일 + 8+ 신규 프론트 파일 + 챗봇 핸들러 전면 재설계. PRD가 명시한 대로 P0(WANT/챗봇/마커) + P1(정리/필터) + P2(알림 확장) 다층 변경.

---

## 탐색 추가 항목 (코드 맵 누적용)

- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/ChatbotWebhookService.java:69` → 챗봇 분기 진입점. 신규 세션 상태머신 가드를 여기에 추가
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/handler/InstagramLinkHandler.java:113` → 기존 `pending-ttl-seconds` 사용처. 신규 `reel-selection-ttl-seconds` 로 교체
- `backend/apps/wherewego-api/src/main/java/com/wherewego/config/cache/CacheConfig.java:18` → `INSTAGRAM_PENDING` 캐시 등록 패턴. `REEL_SELECTION` 신규 등록
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/PendingNotificationSession.java:53` → 콜백 push 실패 시 prepend 세션. TTL 만료 자동 저장 알림에 재사용
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/RecentlyAutoSavedSession.java` → RESEND-1 가드(EC-U6)에 활용 가능
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/user/UserModel.java` → `cleanup_snoozed_until` 컬럼 추가 대상 (실제 User Entity 클래스 이름이 `UserModel`)
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/chatbot/MessageType.java:13` → 신규 타입 추가 위치
- `backend/apps/wherewego-api/src/main/java/com/wherewego/support/error/ErrorType.java:51-61` → 신규 에러 코드 추가 위치
- `backend/apps/wherewego-api/src/main/resources/application.yml:99` → `chatbot.instagram.pending-ttl-seconds`. `reel-selection-ttl-seconds: 180` 추가
- `frontend/src/components/ui/PinDot.tsx` → 마커 컴포넌트 수정 대상
- `frontend/src/lib/api/types.ts` → `PinSummaryResponse` 타입 정의
- `frontend/src/lib/api/pin.ts` / `pin-client.ts` → 클라이언트 함수 확장 (두 파일 모두 존재 — 역할 분리 확인 필요)
- `frontend/src/app/pins/PinListClient.tsx` → `CleanupBanner` 마운트 지점
- `frontend/src/app/pins/_components/PinCard.tsx` → 출처 뱃지·WANT 카운트·`?` 추가 대상
- `frontend/AGENTS.md` → 변경된 Next.js 버전이므로 `node_modules/next/dist/docs/` 우선 참조 필요 (코더 단계 주의)

---

## 확인이 필요한 사항

1. **WISH_CONVERTED 알림 멱등 UNIQUE 키 구조**
   - 맥락: 현재 `notifications` 테이블이 `Notification` 본행 + `NotificationPin` 링크 테이블로 분리되어 있다 (`NotificationService.fanOut`이 `Notification.create` + `NotificationPin.link` 별도 INSERT). 부분 UNIQUE로 "동일 (group_id, pin_id, type=WISH_CONVERTED, receiver_id) 1건"을 강제하려면 `Notification` 본행에 `pin_id`가 직접 있어야 한다. Phase 10 VISIT_DETECTED는 `uq_notifications_visit`이 이미 존재한다고 코드 주석에 명시되어 있어 `Notification` 본행에 `pin_id` 컬럼이 있을 가능성이 높지만 V008 이후 마이그레이션 확인 필요.
   - 유형: 선택
   - 선택지:
     - (a) `Notification` 본행에 이미 `pin_id`가 있으므로 V012에서 `uq_notifications_wish_converted(group_id, receiver_id, pin_id) WHERE type='WISH_CONVERTED'` 부분 UNIQUE 1개만 추가 (권장) — VISIT_DETECTED 선례 그대로 따름, 검증 후 진행
     - (b) `Notification` 본행에 `pin_id`가 없으면 V012에서 `notifications`에 `pin_id BIGINT NULL` 컬럼을 먼저 추가하고 그 위에 부분 UNIQUE — 기존 데이터 영향 분석 추가 필요
     - (c) 직접 입력 — 위 선택지 외 다른 방식을 직접 입력합니다

2. **WantService와 PinService의 책임 경계**
   - 맥락: WANT 토글은 `Pin` Entity의 `want_count`/`tag`를 직접 변경하므로 트랜잭션이 PinService와 같은 단위로 작동한다. 별도 클래스로 분리할지 단일 PinService 메서드로 통합할지.
   - 유형: 선택
   - 선택지:
     - (a) 별도 `com.wherewego.domain.pin.want.WantService` 클래스 (권장) — 단일 책임. 챗봇 저장 시 WANT 적용 등 재사용 명확. PinService 비대화 방지
     - (b) `PinService.toggleWant(...)` 단일 메서드 — 의존성 줄어듦. 단 PinService에 GroupMemberRepository / ApplicationEventPublisher 추가 의존
     - (c) 직접 입력 — 위 선택지 외 다른 방식을 직접 입력합니다

3. **챗봇 핸들러 패키지·세션 위치**
   - 맥락: 기존 핸들러는 `com.wherewego.domain.chatbot.handler/`에 평탄 배치되어 있다. Phase 12에서 릴스 v2 핸들러 4개 + 파서 + 세션이 추가되는데, 평탄 유지 vs 하위 패키지로 그룹화.
   - 유형: 선택
   - 선택지:
     - (a) 평탄 유지: `handler/ReelSingleWantHandler` 등을 기존 `handler/` 그대로 (권장) — 기존 컨벤션 일관성. Spring DI 스캔 단순
     - (b) 하위 패키지: `handler/reel/ReelSingleWantHandler` 등으로 그룹화 + `chatbot/reel/`에 세션·파서 — Phase 12 변경 범위가 명확. 단 기존 컨벤션과 어긋남
     - (c) 직접 입력 — 위 선택지 외 다른 방식을 직접 입력합니다

4. **챗봇 저장 시 WANT 적용 — 1인 그룹 즉시 WISH 전환 동작**
   - 맥락: PRD FR-PIN-12-22는 "SINGLE_WANT에서 '가고 싶어요'를 선택한 핀은 저장 시점에 해당 사용자의 WANT 1표가 자동으로 기록된다"고 명시. WantService.toggle을 그대로 재사용하면 1인 그룹에서 본인 1표 = 과반 충족 → 즉시 WISH 전환 + WishConvertedEvent 발행 → 본인 제외 fan-out이지만 receiver=0명. 의도된 동작인지 확인.
   - 유형: 선택
   - 선택지:
     - (a) `WantService.toggle` 그대로 재사용 (권장) — MVP 2인 그룹에서 본인 1표는 과반 미달이므로 정상 동작. 1인 그룹 즉시 WISH는 receiver=0이므로 알림 무발송 + tag 전환만 발생 → 사용자 의도와 일치 (혼자도 가고 싶어요로 표시)
     - (b) 챗봇 저장 경로 전용 메서드 `WantService.markWantOnInitialSave` 신설 — `pin_events` INSERT + `want_count++`만 수행. 과반 검사 X (챗봇 경로에서는 WISH 전환 차단)
     - (c) 직접 입력 — 위 선택지 외 다른 방식을 직접 입력합니다

5. **`Pin.applyAutoMemo` 메서드 신설 vs 기존 흐름 변경**
   - 맥락: 챗봇 broadcast 메모는 `memo_source=AUTO`여야 하는데 현재 `Pin.applyManualMemo`는 MANUAL로 마킹한다. 챗봇 저장 시 핀 도메인 메서드를 신설하는 vs 핀 생성 직후 setter류로 직접 marking하는 vs 기존 `registerFromInstagramWithDedup`에 memo 파라미터를 AUTO 마킹 분기로 확장.
   - 유형: 선택
   - 선택지:
     - (a) `Pin.applyAutoMemo(String memo)` 신규 도메인 메서드 (권장) — 도메인 캡슐화 유지. `memo_source=AUTO` 명시적
     - (b) `PinService.registerFromInstagramWithDedup`에 `MemoSource memoSource` 파라미터 추가 — 호출자 의도 명확. 단 기존 호출 시그니처 변경
     - (c) 직접 입력 — 위 선택지 외 다른 방식을 직접 입력합니다
---

# 설계서 패치 v2 (design-critic + 사용자 결정 반영)

> 본 문서는 `design-draft.md` (v1) 에 대한 변경 패치이며, **최종 design.md 는 draft + 본 patch 를 합친 것**이다. coder 는 두 파일을 모두 Read 하여 v2 의도로 구현한다.

---

## 패치 P-1: §3 V012 SQL — `wish_pin_id` 추가 + 멱등 키 정정

### 변경 전 (§3, 100~157행 핵심)
```sql
-- ─── 3) notifications.type WISH_CONVERTED 확장 ───────────
ALTER TABLE notifications DROP CONSTRAINT IF EXISTS chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type
    CHECK (type IN ('MANUAL_PIN', 'CHATBOT_PINS', 'VISIT_DETECTED', 'WISH_CONVERTED'));

-- WISH_CONVERTED 알림 멱등 보장: 동일 (group_id, pin_id, type=WISH_CONVERTED) 1건만
CREATE UNIQUE INDEX uq_notifications_wish_converted
    ON notifications (group_id, registered_by, receiver_id)
    WHERE type = 'WISH_CONVERTED';
-- ※ 정확한 컬럼 조합은 기존 notifications 스키마(V008 부근) 확인 후 조정
```

말미 §3 "주의 - notifications 멱등 인덱스" 박스(155~159행) 전체.

### 변경 후
```sql
-- ─── 3) notifications WISH_CONVERTED 확장 ───────────────
-- V009 visit_pin_id 선례를 그대로 답습한다.

ALTER TABLE notifications DROP CONSTRAINT IF EXISTS chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type
    CHECK (type IN ('MANUAL_PIN', 'CHATBOT_PINS', 'VISIT_DETECTED', 'WISH_CONVERTED'));

-- WISH_CONVERTED 전용 pin 참조 컬럼 (V009 visit_pin_id 와 동일한 정책)
--  - nullable: 기존 MANUAL_PIN/CHATBOT_PINS/VISIT_DETECTED 행은 NULL 유지.
--  - ON DELETE RESTRICT: pins 는 soft-delete 정책이라 평상시 영향 없음. hard DELETE 실수 차단.
ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS wish_pin_id BIGINT NULL
        REFERENCES pins (id) ON DELETE RESTRICT;

-- 멱등: 동일 (group_id, receiver_id, registered_by, wish_pin_id) 조합으로
-- WISH_CONVERTED 알림은 1회만 생성되도록 race-free 보장.
-- pin_id를 키에 포함해야만 "(triggerUser→receiver) 1회"가 아닌
-- "(triggerUser→receiver→해당 핀) 1회" 멱등이 되어 다른 핀 알림 누락이 발생하지 않는다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_wish_converted
    ON notifications (group_id, receiver_id, registered_by, wish_pin_id)
    WHERE type = 'WISH_CONVERTED' AND wish_pin_id IS NOT NULL;

COMMENT ON COLUMN notifications.wish_pin_id IS
    'Phase 12: WISH_CONVERTED 알림에서만 채워지는 핀 참조. 부분 UNIQUE 키.';
```

§3 말미 "주의 - notifications 멱등 인덱스" 박스(155~159행)는 **삭제**하고 다음 한 줄로 대체한다.

> V009 `visit_pin_id` 선례(`backend/.../V009__add_visit_detected_notification_type.sql`)와 동일한 패턴을 적용한다. `Notification` 본행에 타입 전용 nullable pin 컬럼을 두고 부분 UNIQUE로 멱등을 강제하며, `NotificationPin` 링크 테이블은 본 타입에서는 사용하지 않는다.

### 이유
M-1. critic 지적대로 기존 `(group_id, registered_by, receiver_id)` 키는 "(트리거→수신자) 1회" 멱등이 되어 다른 핀 WISH_CONVERTED 알림이 통째로 누락됨 (AC-12-10 위반). V009 선례의 `visit_pin_id` 패턴을 그대로 차용.

---

## 패치 P-2: §4.5 → §4.6 신설 — `Notification.java` wishPinId 필드 + 팩토리

### 변경 전
§4는 4.1~4.5만 존재하며 `Notification.java`는 §2 변경 범위 매트릭스에서 "참조 (변경 없음)"로 표기됨.

§2 매트릭스의 해당 행:
```
| 알림 Entity | .../domain/notification/Notification.java | 참조 | (변경 없음) `type` enum 매핑만 확장됨 | — |
```

### 변경 후
§2 매트릭스의 `Notification.java` 행을 다음으로 교체:
```
| 알림 Entity | .../domain/notification/Notification.java | 수정 | `wish_pin_id` 컬럼 필드 + `createForWishConverted(...)` 팩토리 추가 (V009 `visit_pin_id` 패턴 답습) | FR-PIN-12-6, AC-12-10 |
```

§4에 **§4.6 `Notification.java` 수정** 신설:

```java
/**
 * Phase 12: WISH_CONVERTED 알림에서만 채워지는 핀 참조. 부분 UNIQUE 인덱스
 * {@code uq_notifications_wish_converted} (group_id, receiver_id, registered_by, wish_pin_id) 와
 * 결합되어 동일 핀에 대한 중복 WISH 전환 알림을 race-free 하게 차단한다.
 * 다른 타입(MANUAL_PIN/CHATBOT_PINS/VISIT_DETECTED)에서는 NULL 유지.
 */
@Column(name = "wish_pin_id")
private Long wishPinId;

// 신규 생성자 (visitPinId 생성자와 동일 패턴, 별도 오버로드)
private Notification(Long groupId, Long receiverId, Long registeredBy,
                     NotificationType type, Long wishPinId, boolean wishMarker /* tag-only */) {
    this.groupId = groupId;
    this.receiverId = receiverId;
    this.registeredBy = registeredBy;
    this.type = type;
    this.wishPinId = wishPinId;
}

/**
 * WISH_CONVERTED 알림 팩토리. {@code wishPinId} 는 부분 UNIQUE 키 컬럼이므로 non-null 이어야 한다.
 * (V009 createForVisit 패턴과 동일)
 */
public static Notification createForWishConverted(
        Long groupId, Long receiverId, Long registeredBy, Long wishPinId) {
    return new Notification(groupId, receiverId, registeredBy,
            NotificationType.WISH_CONVERTED, wishPinId, true);
}
```

> 구현 메모: 기존 `visit` 전용 private 생성자와 시그니처 충돌이 없도록 `boolean wishMarker` 등 마커 인자 또는 별도 패키지-private 헬퍼로 분기한다. 구현자 자유. 핵심은 `wishPinId` 만 채우고 `visitPinId`는 NULL을 유지하는 것.

### 이유
M-1 후속. 컬럼이 추가되었으므로 도메인 엔티티도 V009 `createForVisit` 패턴을 그대로 따라 전용 팩토리를 둔다. `Notification.create(...)` 일반 팩토리는 `wish_pin_id=NULL`을 남기므로 WISH_CONVERTED에 사용 금지.

---

## 패치 P-3: §5 WantService — 그룹원 수 race 박제 + `markWantOnInitialSave` 헬퍼

### 변경 전 (§5.1, §5.2 핵심)

§5.1 클래스 JavaDoc:
```java
@Service
@RequiredArgsConstructor
public class WantService {
    /* (JavaDoc 없음) */
    ...
}
```

§5.2 의사코드 상단:
```
toggle(userId, groupId, pinId):
  groupMemberService.requireActiveMembership(userId, groupId)
  Pin pin = pinRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId)
              .orElseThrow(PIN_NOT_FOUND)
```

§5는 `toggle` / `getStatus` 두 메서드만 존재. 챗봇 저장 경로 헬퍼 없음.

### 변경 후

§5.1 클래스 JavaDoc 신설:
```java
/**
 * Phase 12 WANT(관심 표현) 서비스. 토글 트랜잭션·과반 검사·REEL→WISH 전환 이벤트 발행을 단일 책임으로 격리한다.
 *
 * <p><b>Phase 12 범위 제약</b>: PRD 결정에 따라 그룹원 탈퇴/가입 시 want_count 소급 재계산은
 * Phase 12 범위 외이며, <b>2인 MVP 가정</b> 하에서만 안전하다. 본 서비스는 {@code pins} 행만
 * SELECT FOR UPDATE 로 잠그고 {@code group_members} 는 잠그지 않으므로, 3인↑ 그룹에서 활성
 * 멤버 수가 race 로 변동하면 과반 임계에 미세한 오차가 발생할 수 있다. 3인↑ 그룹 지원 시
 * {@code group_members} 잠금 정책(예: 그룹 행 advisory lock 또는 active_count 캐시 컬럼)을
 * 재설계해야 한다.</p>
 */
@Service
@RequiredArgsConstructor
public class WantService {
    ...
}
```

§5.2 의사코드 상단에 race 박제 주석 한 줄 추가:
```
toggle(userId, groupId, pinId):
  groupMemberService.requireActiveMembership(userId, groupId)
  Pin pin = pinRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId)
              .orElseThrow(PIN_NOT_FOUND)
  // NOTE(Phase 12 MVP): group_members 미잠금. 2인 그룹 가정 하에서만 안전.
  //                     3인↑ 확장 시 active_count race 정책 재설계 필요.
  ...
```

§5에 **§5.5 챗봇 저장 전용 INSERT-only 헬퍼** 신설:

```java
/**
 * 챗봇 SINGLE_WANT/MULTI 선택 핀 저장 직후 WANT 1회 적용 전용 헬퍼.
 *
 * <p>{@link #toggle} 과 달리 <b>INSERT-only</b>: 이미 WANT 가 존재하면 no-op. 따라서
 * 카카오 웹훅 재시도/중복 발화로 같은 botUserKey 가 두 번 들어와도 의도치 않은 DELETE
 * 가 발생하지 않는다.</p>
 *
 * <p>과반 검사·WISH 전환·{@link WishConvertedEvent} 발행은 {@link #toggle} 과 동일하게
 * 수행한다 (PRD FR-PIN-12-22). 1인 그룹의 경우 본인 1표로 즉시 WISH 전환되며, fan-out
 * receiver=0 이므로 알림은 발송되지 않고 태그만 전환된다 — 사용자 결정에 따른 의도된 동작.</p>
 *
 * @param activeMemberCount 호출자(챗봇 핸들러)가 사전에 조회한 활성 멤버 수.
 *                          핸들러가 N건 일괄 저장 시 그룹원 수 1회 조회로 재사용 가능.
 */
@Transactional
public WantToggleResult markWantOnInitialSave(
        Long userId, Long groupId, Long pinId, int activeMemberCount) {

    groupMemberService.requireActiveMembership(userId, groupId);
    Pin pin = pinRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId)
                  .orElseThrow(() -> new CoreException(ErrorType.PIN_NOT_FOUND));

    // 챗봇이 막 생성한 REEL 핀에 호출되므로 MEMORY 가드는 사실상 불필요하나 도메인 보호 차원에서 유지.
    if (pin.getTag() == PinTag.MEMORY) {
        throw new CoreException(ErrorType.PIN_WANT_FORBIDDEN_TAG);
    }

    boolean inserted;
    try {
        pinEventRepository.save(PinEvent.wantOf(pinId, userId, groupId));
        inserted = true;
    } catch (DataIntegrityViolationException e) {
        // 이미 동일 (pin_id, user_id, WANT) 가 존재 → 멱등 no-op.
        // want_count 는 이미 카운팅되었으므로 증가 건너뜀.
        log.debug("markWantOnInitialSave skipped (duplicate) pinId={} userId={}", pinId, userId);
        inserted = false;
    }

    boolean wishConverted = false;
    if (inserted) {
        pin.applyWantDelta(+1);
        if (pin.getTag() == PinTag.REEL) {
            wishConverted = pin.transitionToWishIfMajority(activeMemberCount);
            if (wishConverted) {
                eventPublisher.publishEvent(new WishConvertedEvent(
                        groupId, pinId, userId, pin.getPlaceName()));
            }
        }
    }
    return new WantToggleResult(pin.getTag(), pin.getWantCount(), true, wishConverted);
}
```

§5.3 멱등 메커니즘 표에 다음 행 추가:
```
| Service (initial save) | markWantOnInitialSave: try INSERT → DIVException catch → want_count 증가 건너뜀 (이미 카운팅됨) |
```

### 이유
M-2 (race 박제) + M-3 (INSERT-only 헬퍼). 챗봇 경로에서 `toggle` 재사용은 웹훅 재시도 시 DELETE race 발생. 1인 그룹 즉시 WISH 전환은 사용자 결정사항 #4(a)로 유지.

---

## 패치 P-4: §6 알림 시스템 — `createForWishConverted` 시그니처/구현 정정

### 변경 전 (§6.1)
```java
@Transactional
public void createForWishConverted(Long groupId, Long pinId, Long triggerUserId, String placeName) {
    if (groupMemberRepository.findActiveByGroupIdAndUserId(groupId, triggerUserId).isEmpty()) {
        log.warn("createForWishConverted skipped — trigger {} not active in group {}",
                 triggerUserId, groupId);
        return;
    }
    List<Long> receiverIds = groupMemberRepository.findOtherActiveMemberIds(groupId, triggerUserId);
    for (Long receiverId : receiverIds) {
        try {
            Notification n = repository.save(
                Notification.create(groupId, receiverId, triggerUserId, NotificationType.WISH_CONVERTED));
            repository.saveAllPins(List.of(NotificationPin.link(n.getId(), pinId, 0)));
        } catch (DataIntegrityViolationException e) {
            log.debug("WISH_CONVERTED notification skipped (duplicate) receiverId={} pinId={}",
                      receiverId, pinId);
        }
    }
}
```

### 변경 후
```java
/**
 * Phase 12: REEL → WISH 자동 전환 알림.
 *
 * <p><b>V009 createForVisitDetected 패턴 답습</b>: {@code Notification.createForWishConverted}
 * 팩토리로 {@code wish_pin_id} 컬럼을 직접 채우고, {@code NotificationPin} 링크 테이블은
 * 사용하지 않는다. 부분 UNIQUE 인덱스 {@code uq_notifications_wish_converted}
 * (group_id, receiver_id, registered_by, wish_pin_id) 가 동일 핀에 대한 중복 알림을
 * race-free 하게 차단한다.</p>
 *
 * <p>receiver = 본인(triggerUserId) 제외 활성 그룹원 전원 (1인 그룹이면 receiver=0 → 알림 무발송).</p>
 *
 * <p>호출 계약: {@link WishConvertedNotificationListener} 가 AFTER_COMMIT 단계에서 호출하며,
 * 자체 try/catch 로 격리한다. 본 메서드는 REQUIRED (자체 트랜잭션) 로 동작한다.</p>
 */
@Transactional
public void createForWishConverted(Long groupId, Long pinId, Long triggerUserId, String placeName) {
    if (groupMemberRepository.findActiveByGroupIdAndUserId(groupId, triggerUserId).isEmpty()) {
        log.warn("createForWishConverted skipped — trigger {} not active in group {}",
                 triggerUserId, groupId);
        return;
    }
    List<Long> receiverIds = groupMemberRepository.findOtherActiveMemberIds(groupId, triggerUserId);
    for (Long receiverId : receiverIds) {
        try {
            repository.save(
                Notification.createForWishConverted(groupId, receiverId, triggerUserId, pinId));
            // NOTE: NotificationPin 링크 호출 없음. wish_pin_id 컬럼이 핀 참조를 직접 담당.
            //       V009 visit_pin_id 와 동일한 정책.
        } catch (DataIntegrityViolationException e) {
            // 부분 UNIQUE 위반 = 동일 (group_id, receiver_id, registered_by, wish_pin_id) 가 이미 존재
            // → race-free 중복 차단 (조용히 스킵).
            log.debug("WISH_CONVERTED notification skipped (duplicate) receiverId={} pinId={}",
                      receiverId, pinId);
        } catch (RuntimeException e) {
            // V009 createForVisitDetected 와 동일하게 per-receiver best-effort 격리.
            log.warn("WISH_CONVERTED notification per-receiver failed receiverId={} pinId={}",
                      receiverId, pinId, e);
        }
    }
}
```

§6.2 알림 본문 템플릿 보강:
> WISH_CONVERTED 본문에 표시할 placeName 은 알림 상세 조회 시 `wish_pin_id` 로 `pins`를 join 하여 가져온다(`NotificationService.getDetail`이 본 타입에 대해 `wish_pin_id` 기반 단건 Pin 로드 분기를 추가). `NotificationPin` 링크는 비어 있으므로 기존 `loadPinsByIds(links)` 흐름과 별도로 처리.

### 이유
M-1 후속. `wish_pin_id` 컬럼 도입에 따라 V009 패턴(컬럼 + 부분 UNIQUE, 링크 테이블 불사용)으로 일관화. `NotificationPin.link` 호출은 제거.

---

## 패치 P-5: §8.7 챗봇 저장 — `WantService.toggle` → `markWantOnInitialSave`

### 변경 전 (§8.7 step 3)
```
3. **WANT 적용**: `snapshot.selectedIndices`에 포함된 핀에 대해 저장 직후 `WantService.toggle(...)` 또는 별도 헬퍼 `wantService.markWantOnInitialSave(userId, groupId, pinId)` 호출
   - 단순화: 별도 헬퍼는 `pin_events` INSERT + `want_count++`만 수행 (과반 검사 X — 본인 1표로 2인 그룹 과반 미달이 일반적이지만, 1인 그룹 등 케이스에서 WISH 전환 의도와 충돌 가능 → 정책 확인 필요)
   - 권장: `WantService.toggle`을 그대로 호출 (저장 직후 toggle 1회 = WANT INSERT 보장. 멱등 UNIQUE로 안전)
4. `NotificationService.createForChatbotBatch(groupId, userId, pinIds)` 호출

> **확인이 필요한 사항**: 챗봇 저장 직후 1인 그룹에서 WANT 자기 1표 = 과반 충족 → 즉시 WISH 전환되는데 의도된 동작인지 (질문 4).
```

### 변경 후
```
3. **WANT 적용**: `snapshot.selectedIndices`에 포함된 핀에 대해 저장 직후
   `wantService.markWantOnInitialSave(userId, groupId, pinId, activeMemberCount)` 호출.
   - `WantService.toggle` 은 양방향(존재 시 DELETE)이라 웹훅 재시도/중복 발화에서 의도치
     않은 DELETE race 가 발생할 수 있어 **사용 금지**.
   - `markWantOnInitialSave` 는 INSERT-only: 기존 WANT 가 있으면 멱등 no-op. 과반 검사 +
     `WishConvertedEvent` 발행은 동일 (PRD FR-PIN-12-22, AC-12-29).
   - **1인 그룹 정책**: 본인 1표 = 과반 충족 → 즉시 WISH 전환 + receiver=0 이므로 알림 무발송,
     태그 전환만 발생. 사용자 결정사항으로 이 동작을 의도된 것으로 유지.
   - `activeMemberCount` 는 핸들러가 N건 저장 루프 진입 전 1회 조회 (`groupMemberRepository.countActiveByGroupId(groupId)`) 하여 재사용.
4. `NotificationService.createForChatbotBatch(groupId, userId, pinIds)` 호출
```

§8.7 말미의 "> **확인이 필요한 사항** ... 질문 4" 인용 블록은 **삭제** (사용자 결정으로 확정됨).

### 이유
M-3. INSERT-only 헬퍼로 전환하여 웹훅 재시도 race 차단. 1인 그룹 즉시 WISH 정책을 본문에 명시.

---

## 패치 P-6: §8.3 `ReelCommaParser` — `NumberFormatException` + ASCII 명시

### 변경 전 (§8.3 핵심 주석)
```java
/**
 * 콤마 파싱 (D-2 침묵 dedup).
 *  1) input.split(",", -1)
 *  2) trim 후 빈 토큰 무시 (trailing/연속 콤마 허용)
 *  3) 토큰 ^\d+$ 매칭 실패 → FORMAT_MISMATCH
 *  4) 정수 변환 후 1..totalCount 검증 → OUT_OF_RANGE
 *  5) LinkedHashSet 으로 dedup
 *  6) 비어 있으면 EMPTY (",", " ," 등)
 */
public Result parse(String input, int totalCount) { /* ... */ }
```

### 변경 후
```java
/**
 * 콤마 파싱 (D-2 침묵 dedup).
 *
 * 입력 정규화 + 단계:
 *  1) input.split(",", -1)
 *  2) 각 토큰 trim 후 빈 토큰 무시 (trailing/연속 콤마 허용)
 *  3) 토큰 매칭: ^\d+$ (ASCII 0-9 한정).
 *     ※ 전각 숫자 '１,３,５', zero-width space 'U+200B', NBSP 'U+00A0',
 *        한글/영문 등은 모두 본 정규식에서 차단 → FORMAT_MISMATCH.
 *  4) Integer.parseInt 시도:
 *      - NumberFormatException (Long 범위 초과 또는 매우 큰 수)
 *        → FORMAT_MISMATCH 로 매핑 (사용자에게 동일한 안내 문자열로 노출).
 *  5) 변환된 정수가 1..totalCount 범위 위반 → OUT_OF_RANGE
 *  6) LinkedHashSet 으로 dedup (입력 순서 보존)
 *  7) 비어 있으면 EMPTY (',', ' ,' 등 모든 토큰이 빈 경우)
 */
public Result parse(String input, int totalCount) { /* ... */ }
```

### 이유
C-2. 큰 숫자 입력 시 `NumberFormatException` 미처리로 예외 누출 가능.

---

## 패치 P-7: §1 / §2 — PinTag enum 리네이밍 범위 외 명시

§1 말미에 한 단락 추가:
```
**Phase 12 범위 외 명시**: PinTag enum 자체 리네이밍(예: `REEL → DISCOVER`)은 Phase 12 범위 외이다.
PRD 시나리오 6 다이어그램과 챗봇 안내 문구·프론트 UI 라벨에서만 "발견" 어휘를 사용하며, DB CHECK
제약·`PinTag` enum 값·기존 API 응답 필드명은 `REEL/WISH/MEMORY` 그대로 유지한다. 후속 Phase 에서
enum 리네이밍은 V0xx 마이그레이션 + 도메인/DTO 호환성 영향 분석을 별도로 진행한다.
```

§2 변경 범위 매트릭스 백엔드 표 마지막 행 다음에 안내 행 추가:
```
| (참고) | `PinTag.java` | 변경 없음 | enum 리네이밍은 Phase 12 범위 외. UI 라벨만 "발견" 어휘 사용 | — |
```

### 이유
C-1. PinTag 어휘 변경이 범위 안인지 모호하므로 명시적으로 차단.

---

## 패치 P-8: §12 구현 순서 — `wishPinId` 컬럼 추가 의존성 반영

Batch A:
```
1. [Must] V012 마이그레이션 작성 (의존: 없음)
   - 산출: pin_events, want_count, cleanup_snoozed_until, WISH_CONVERTED CHECK,
           notifications.wish_pin_id 컬럼, uq_notifications_wish_converted 부분 UNIQUE,
           uq_pin_events_pin_user_want, idx_pins_cleanup, idx_pins_group_want_count
```

Batch B에 5-bis 추가 (5번을 2개로 분할):
```
5a. [Must] NotificationType.WISH_CONVERTED 추가 (의존: 1)
5b. [Must] Notification.java 에 wishPinId 필드 + createForWishConverted 팩토리 추가 (의존: 1, 5a)
```

Batch D 수정:
```
10. [Must] WishConvertedEvent record (의존: 없음)
11. [Must] WantService 클래스 — toggle + getStatus + markWantOnInitialSave + 클래스 JavaDoc(2인 MVP 박제) (의존: 2, 7, 10)
12. [Must] WishConvertedNotificationListener (의존: 10, 5a)
13. [Must] NotificationService.createForWishConverted — Notification.createForWishConverted 팩토리 사용,
           NotificationPin.link 호출 제거 (의존: 5a, 5b)
13-bis. [Must] NotificationService.getDetail WISH_CONVERTED 분기 추가 — wish_pin_id 기반 단건 Pin 로드 (의존: 5b)
```

Batch H 수정:
```
32. [Must] 4개 핸들러 신규 작성 — ReelMemoWaitingHandler 가 WantService.markWantOnInitialSave 호출
           (toggle 사용 금지), activeMemberCount 는 N건 저장 루프 진입 전 1회 조회 후 재사용
           (의존: 28, 29, 30, 11)
```

병렬화 요약 갱신:
```
- Batch B 내부(2, 3, 4, 5a, 5b): 4개 병렬 (2/3/4/5a). 5b는 5a 직후
```

### 이유
M-1으로 `notifications.wish_pin_id` 컬럼과 `Notification.createForWishConverted` 팩토리가 추가되어 마이그레이션·엔티티·서비스 의존 관계가 갱신됨. M-3으로 챗봇 핸들러가 신규 헬퍼에 의존.

---

## 새로 발견한 탐색 추가 항목

- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationVisitWriter.java` (NotificationService 의존) → V009 visit 알림이 `REQUIRES_NEW` 분리 트랜잭션을 위해 별도 writer 컴포넌트를 둔 패턴. Phase 12 WISH_CONVERTED 도 트래픽 폭발/실패 격리가 필요해지면 동일하게 `NotificationWishWriter` 분리를 검토할 수 있음. **현 설계는 fan-out 규모가 작아(2인 그룹 receiver=1) 단일 `@Transactional` 메서드로 충분 — writer 미분리.**
- `NotificationService.getDetail()` → `links` (NotificationPin) 기반으로 핀을 로드한다. WISH_CONVERTED 는 `NotificationPin` 링크가 비어 있으므로 본 메서드에 WISH_CONVERTED 분기 추가 필요. **구현 순서 Batch D의 13-bis 단계로 분리**.

---

## 변경 없는 섹션

- §4.1 `Pin.java` 수정 (`applyWantDelta`, `transitionToWishIfMajority`, `applyAutoMemo`)
- §4.2 `UserModel.java` (`snoozeCleanup` / `isCleanupSnoozed`)
- §4.3 `PinEvent.java`, §4.4 `PinEventAction.java`, §4.5 `NotificationType.java`
- §5.4 `WishConvertedEvent` + 리스너 스켈레톤
- §6.2 알림 본문 템플릿 (보강 1줄, P-4에 흡수됨)
- §7 전체 (API 명세) — `wantCount/myWant/wishConverted` 응답 필드는 그대로
- §8.1 `ReelSavedSelectionSession`, §8.2 패키지 구조 (평탄 유지 - 사용자 결정), §8.4 MessageClassifier, §8.5 가드, §8.6 TTL 자동 저장, §8.8 `applyAutoMemo`
- §9 프론트엔드 전체
- §10 에러 처리·검증
- §11 테스트 전략 (§11.1 `WantNotificationIdempotentIT` 검증 시 `wish_pin_id` 기반 부분 UNIQUE 충돌과 동일 `(group, receiver, trigger, wishPinId)` 조합에서만 멱등이 성립함을 명시적 확인)
