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
