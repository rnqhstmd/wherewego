# 핀 메모/사진 수정 알림 (PIN_EDITED) — 웹(main) 반영 명세

- 작성일: 2026-06-04
- 대상 브랜치: **main** (웹 운영 브랜치)
- 작성 경위: 이 기능은 `develop` 계열에서 iOS/백엔드(APNs 포함)로 먼저 구현 예정이다. 웹은 **main에서 별도 분기**해 반영하므로, develop 코드가 아니라 **main 코드 기준**의 변경점을 사전에 명세한다.
- 조사 기준: main HEAD. 최신 DB 마이그레이션 **V013**, 알림 타입 **3종**(MANUAL_PIN / CHATBOT_PINS / VISIT_DETECTED).
- ⚠️ 아래 파일:라인 번호는 **조사 시점(main HEAD)** 기준이다. 실제 분기 시점에 main이 더 진행됐다면 라인/마이그레이션 번호를 재확인할 것.

---

## 1. 기능 요약 (확정된 제품 결정)

핀의 **메모/사진을 파트너가 수정**했을 때, 그 핀 **작성자에게** 알림을 남긴다.

| 항목 | 결정 |
|------|------|
| 알림 문구 | **통합 1종**: "{닉네임}님이 장소 정보를 수정했어요" (메모 변경/사진 추가를 묶음) |
| 발송 조건 | **수정자 ≠ 핀 작성자(`createdBy`)** 일 때만. 내가 내 핀을 다듬는 건 무알림 |
| 수신자 | 핀 작성자 1명 (2인 그룹이라 곧 파트너) |
| 메모 트리거 | 메모 **실제 값이 바뀐 경우**만 (같은 값 재전송 PATCH는 무알림) |
| 사진 트리거 | 사진 **업로드 성공 시** (삭제는 무알림) |
| 전파 | main은 **폴링/REST 재조회** 구조 → 새 알림 행만 INSERT하면 자동 노출 (추가 인프라 불필요) |
| 푸시 | **해당 없음** — main에는 APNs/웹푸시가 없다. 웹은 인앱 알림함만 |

> iOS(develop)에서는 동일 트리거에 APNs 푸시(`PIN_EDITED` PushPayload)를 추가하지만, **웹(main)에는 푸시 레이어가 없으므로 이 명세에서 제외**한다.

---

## 2. main 현황 (조사 결과 — 변경 전 기준)

### 2.1 알림 생성 (백엔드, fan-out)
| 타입 | 트리거 위치 | 수신자 |
|------|------------|--------|
| `MANUAL_PIN` | `PinV1Controller.createPin` (≈L60) → `NotificationService.createForManualPin` → `fanOut` | 활성 멤버 전원 + 본인 |
| `CHATBOT_PINS` | 챗봇 핸들러 → `createForChatbotBatch` → `fanOut` | 활성 멤버 전원 + 본인 |
| `VISIT_DETECTED` | `PinV1Controller.updatePin` (≈L133, WISH/REEL→MEMORY 전환 1회) → `createForVisitDetected` → `NotificationVisitWriter.writeOne`(receiver별 `REQUIRES_NEW`, `visit_pin_id` 부분 UNIQUE 중복차단) | 다른 멤버 + 본인 |

### 2.2 실시간 전파 = 폴링 (SSE 아님)
- `NotificationService`에 SSE/emitter 호출이 **전혀 없음**. 클래스 doc에 "클라이언트는 mount / visibilitychange / focus 시점에 REST 조회로 신규 알림을 감지한다(옵션 B 다운그레이드, 2026-05-21)" 명시.
- 프론트 `frontend/src/lib/notifications/useNotifications.ts` 도 "실시간 push(SSE) 없이 mount + visibilitychange + focus 재조회".
- `docs/architecture/notification-sse-archive.md` 는 **제거된 SSE 설계의 아카이브**.
- GET `/notifications` 는 `receiver_id` 로 **타입 무관 전체 조회** → 타입 화이트리스트가 전파 경로에 없음.
- **결론: PIN_EDITED 행을 INSERT만 하면 다음 폴링에서 자동으로 노출된다. 전파를 위한 추가 작업 불필요.**

### 2.3 APNs/웹푸시 부재 (확정)
`git grep -lniE "apns|fcm|firebase|device_?token|push.{0,3}notification" main -- backend/ frontend/src` → **0건**. main에는 네이티브/웹 푸시 코드가 없다.

### 2.4 `type` 컬럼 형식
`notifications.type` = **VARCHAR(20) + CHECK 제약 `chk_notifications_type`** (V007에서 생성, V009에서 VISIT_DETECTED 추가 시 제약 재생성). enum 타입이 아니므로 컬럼 ALTER는 불필요하나 **CHECK 제약을 갱신**해야 새 값 INSERT가 통과한다.

---

## 3. 변경 명세

### 3.1 DB 마이그레이션 (신규 `V014__add_pin_edited_notification_type.sql`)

`type` 컬럼은 VARCHAR(20)이라 "PIN_EDITED"(9자)는 그대로 들어가지만, **CHECK 제약을 DROP/재생성**해야 한다(V009와 동일 패턴). **중복 차단 인덱스는 만들지 않는다**(PIN_EDITED는 매 수정마다 허용 — VISIT_DETECTED의 `visit_pin_id` UNIQUE 같은 1회 제한 불필요).

```sql
-- V014__add_pin_edited_notification_type.sql
ALTER TABLE notifications DROP CONSTRAINT IF EXISTS chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type
    CHECK (type IN ('MANUAL_PIN', 'CHATBOT_PINS', 'VISIT_DETECTED', 'PIN_EDITED'));
```

> 분기 시점 최신 마이그레이션 번호를 확인하고 V0xx 번호를 조정할 것(조사 기준 main 최신 = V013).
> 연결 핀은 기존 `notification_pins` 링크 테이블을 사용(`visit_pin_id` 컬럼 미사용).

### 3.2 백엔드 변경점

**B-1. `domain/notification/NotificationType.java`** (enum 3→4종)
```java
public enum NotificationType { MANUAL_PIN, CHATBOT_PINS, VISIT_DETECTED, PIN_EDITED }
```

**B-2. `domain/notification/NotificationService.java`** — 신규 생성 메서드 `createForPinEdited`
수신자 모델이 기존과 다르다(전원 fan-out ❌ → **핀 작성자 1명에게만**). 기존 `fanOut`(≈L121)을 재사용하지 말 것.
```java
@Transactional
public void createForPinEdited(Long groupId, Long editorId, Long pinId, Long pinCreatedBy) {
    // 수정자=작성자면 무알림(내가 내 핀 다듬기). 작성자 미상이어도 스킵.
    if (pinCreatedBy == null || pinCreatedBy.equals(editorId)) return;
    // 작성자가 그룹 활성 멤버인지 방어 검증(createForVisitDetected ≈L88 패턴 차용)
    if (groupMemberRepository.findActiveByGroupIdAndUserId(groupId, pinCreatedBy).isEmpty()) return;
    Notification n = repository.save(
            Notification.create(groupId, /*receiverId*/ pinCreatedBy, /*registeredBy*/ editorId, NotificationType.PIN_EDITED));
    repository.saveAllPins(List.of(NotificationPin.link(n.getId(), pinId, 0)));
}
```
- `registeredBy` 컬럼에 **editorId(수정자)** 를 넣으면 프론트가 "{수정자 닉네임}님이…"를 그대로 표시할 수 있다.
- `Notification.create(...)` / `repository.saveAllPins(...)` 의 실제 시그니처는 main의 `Notification.java` / `NotificationRepository.java` 로 맞출 것(위는 구조 제안).

**B-3. `interfaces/api/pin/PinV1Controller.java`** — 두 곳에 훅 추가 (best-effort try-catch 격리)
- `updatePin` (≈L130-140): 기존 VISIT_DETECTED 블록 아래에 **메모 실제 변경 시** 호출.
```java
if (result.memoChanged()) {  // ← PinUpdateResult 확장 필요(B-5)
    try {
        notificationService.createForPinEdited(groupId, userId, pinId, result.summary().createdBy());
    } catch (RuntimeException e) {
        log.warn("notification (pin-edited memo) failed groupId={} pinId={}", groupId, pinId, e);
    }
}
```
- `uploadPinPhoto` (≈L155-186): `pinService.uploadPhoto(...)` 성공 직후 동일 호출(사진은 변경여부 판정 불필요).
```java
try {
    notificationService.createForPinEdited(groupId, userId, pinId, saved.createdBy());
} catch (RuntimeException e) {
    log.warn("notification (pin-edited photo) failed groupId={} pinId={}", groupId, pinId, e);
}
```

**B-4. `domain/pin/PinService.java` + `PinUpdateResult.java`** — "메모 실제 변경" 감지
main에는 메모 변경 감지 수단이 **현재 없다**(`PinUpdateCommand.memoProvided` 만으로는 같은 값 재전송을 못 거른다).
- `PinService.updatePin` 에서 `applyManualMemo/clearMemo` 호출 **직전** `String beforeMemo = pin.getMemo();` 캡처 → 적용 후 `boolean memoChanged = !java.util.Objects.equals(beforeMemo, pin.getMemo());`
- `PinUpdateResult`(record, 현재 `summary` + `wasWishOrReelToMemory` 2필드)에 `boolean memoChanged` 필드 추가.

**B-5. 핀 작성자 id 노출 확인** — `createForPinEdited` 의 "수정자≠작성자" 판정에 핀 `createdBy` **id** 가 Controller까지 전달돼야 한다.
- `PinSummary` / `PinUpdateResult.summary()` / `uploadPhoto` 반환에 `createdBy`(Long id)가 있는지 확인. 닉네임만 있고 id가 없으면 → 반환 타입에 `createdBy` id 추가, 또는 `createForPinEdited` 내부에서 `pinRepository.findById(pinId).getCreatedBy()` 로 직접 재조회.

**B-6. (선택) `NotificationService.getDetail`** — 상세에서 바뀐 메모를 보여줄지
현재 `getDetail`(≈L218)의 memo join은 `isVisitType` 일 때만(≈L243). PIN_EDITED 상세에서 **수정된 메모를 노출**하려면 조건을 `isVisitType || isPinEditedType` 로 확장. 목록/토스트만 쓸 거면 **손대지 않아도 됨**(제품 결정).

### 3.3 프론트엔드(웹) 변경점

**F-1. `frontend/src/lib/notifications/types.ts` (≈L8-11)** — `NotificationType` 유니온에 `"PIN_EDITED"` 추가.

**F-2. `frontend/src/app/map/_components/notifications/NotificationItem.tsx` (≈L31-34)** — 타입별 라벨 분기에 케이스 추가:
```tsx
item.type === 'PIN_EDITED'
  ? `${item.registeredByNickname}님이 장소 정보를 수정했어요`
  : item.type === 'VISIT_DETECTED' ? '추억이 한 곳 더 쌓였어요'
  : `${item.registeredByNickname}님이 장소를 저장했어요.`
```
(`buildPlaceSummary`(≈L73)는 그대로 두면 placeName 1곳 표시됨.)

**F-3. `NotificationToast.tsx` (≈L42-45)** — `message` 분기에 PIN_EDITED 추가(현재 "…저장했어요" 하드코딩):
```tsx
`${payload.registeredByNickname}님이 ${payload.firstPlaceName} 정보를 수정했어요`
```

**F-4. `NotificationPinList.tsx` (≈L67-69)** — 상세 요약카드 라벨 분기에 PIN_EDITED 카피 추가(선택). `showMapButton`(≈L43)은 CHATBOT_PINS 전용이라 영향 없음.

**F-5. `frontend/src/lib/notifications/api.ts`** — **변경 불필요**(타입 무관 GET/상세/read-all 호출만).

---

## 4. 핵심 설계 결정

### 수정자 ≠ 작성자 판정
- 핀 작성자 = `Pin.createdBy`(`Pin.java` ≈L42, `getCreatedBy()`).
- 권장 판정 위치 = **`createForPinEdited` 내부**(`pinCreatedBy.equals(editorId)` → return). VISIT_DETECTED의 비멤버 방어 검증과 동일한 게이트 위치.
- 단 작성자 id가 Controller까지 전달돼야 함(B-5).

### 메모 실제 변경 감지
- **값 비교가 필수**(같은 값 재전송 PATCH를 걸러야 함). `memoProvided` 플래그만으로는 부족.
- `PinService.updatePin` 에서 적용 전/후 memo 비교 → `PinUpdateResult.memoChanged`(B-4).
- 사진은 업로드 성공 = 항상 트리거(판정 불필요).

---

## 5. 분기 후 반드시 확인할 것 (미확정 / 리스크)

1. **`PinSummary`/`PinUpdateResult`/`uploadPhoto` 반환에 핀 `createdBy` id 노출 여부**(B-5). 없으면 반환 타입 확장 또는 `pinRepository` 재조회.
2. **`getDetail` memo join 확장 여부**(B-6) — PIN_EDITED 상세에서 바뀐 메모를 보여줄지 제품 결정.
3. **사진 삭제(`deletePinPhoto`)는 무알림** — 명세상 트리거 제외(확정으로 보이나 코드 확인 권장).
4. **중복/디바운스** — PIN_EDITED는 UNIQUE 제한이 없어, 짧은 시간 메모를 여러 번 저장하면 알림이 연속 생성될 수 있다(자동저장 등). 현재 main에 억제 로직 없음. "파트너가 내 핀을 수정"은 빈도가 낮아 1차 구현은 디바운스 없이 진행, 운영에서 스팸이 관찰되면 후속(같은 (수정자,핀) N분 1회 등).
5. **마이그레이션 번호** — 분기 시점 최신 번호 확인 후 V0xx 조정(조사 기준 V014).
6. **iOS(develop)와의 정합** — develop에는 추가로 `NotificationType.PIN_EDITED`(iOS enum), `NotificationRow` 아이콘/문구, `DeepLinkRouter` 매핑, APNs `PushPayload.pinEdited` 가 들어간다. **백엔드 `PIN_EDITED` 문자열 값은 양쪽이 반드시 동일**해야 한다(컷오버 시 충돌 방지).

---

## 6. 작업 체크리스트 (웹 분기에서)

- [ ] `V014__add_pin_edited_notification_type.sql` 추가 (CHECK 제약 재생성)
- [ ] `NotificationType.java` 에 `PIN_EDITED` 추가
- [ ] `NotificationService.createForPinEdited(groupId, editorId, pinId, pinCreatedBy)` 추가
- [ ] `PinService.updatePin` 메모 변경 감지 + `PinUpdateResult.memoChanged` 추가
- [ ] 핀 `createdBy` id 노출 확인/보강 (B-5)
- [ ] `PinV1Controller.updatePin` 메모 변경 훅 추가
- [ ] `PinV1Controller.uploadPinPhoto` 사진 업로드 훅 추가
- [ ] (선택) `getDetail` memo join PIN_EDITED 확장
- [ ] `frontend types.ts` 에 `"PIN_EDITED"` 추가
- [ ] `NotificationItem.tsx` 라벨 분기 추가
- [ ] `NotificationToast.tsx` 메시지 분기 추가
- [ ] (선택) `NotificationPinList.tsx` 요약 카피 추가
- [ ] 백엔드 테스트: `createForPinEdited`(수정자=작성자 스킵 / 비멤버 스킵 / 정상 1행), `updatePin` 메모변경 훅, `uploadPinPhoto` 훅
- [ ] `./gradlew` 빌드 + 테스트 통과 확인
- [ ] 웹 알림함에서 PIN_EDITED 표시/토스트 수동 확인

---

## 부록: 관련 파일 경로 (main 기준)

**백엔드**
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationType.java`
- `.../domain/notification/NotificationService.java` (fanOut ≈L121, createForVisitDetected ≈L88, getDetail ≈L218)
- `.../domain/notification/Notification.java`, `NotificationRepository.java`, `NotificationVisitWriter.java`
- `.../interfaces/api/pin/PinV1Controller.java` (createPin ≈L60, updatePin ≈L130, uploadPinPhoto ≈L155)
- `.../domain/pin/PinService.java`, `PinUpdateResult.java`, `Pin.java` (createdBy ≈L42)
- `.../resources/db/migration/V007__create_notifications.sql`, `V009__add_visit_detected_notification_type.sql` → 신규 `V014`

**프론트엔드(웹)**
- `frontend/src/lib/notifications/types.ts`, `api.ts`, `useNotifications.ts`
- `frontend/src/app/map/_components/notifications/NotificationItem.tsx`, `NotificationToast.tsx`, `NotificationPinList.tsx`, `NotificationPanel.tsx`
