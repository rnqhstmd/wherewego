# 설계서: Phase 10 — 장소 방문 감지 ("이 장소에 오셨나요?")

- 작성일: 2026-05-24
- 수정일: 2026-05-24
- 관련 레포: rnqhstmd/wherewego
- 베이스 PRD: `.dev/feat-phase-10-visit-detection/prd.md`
- 반복: 2회차 (1회차 + design-critic 피드백 + 사용자 결정 반영)

## 설계 규모
**중형** — 기존 인프라(GeolocateControl, PATCH /pins, Phase 8 알림) 위에 새 훅 1개 + 토스트/시트 2개 + 알림 유형 1개를 얹는다. DB는 V009 1개 마이그레이션.

---

## 1회차 대비 변경사항

| 항목 | 1회차 | 2회차 결정 | 반영 위치 |
|------|------|-----------|----------|
| MUST-1 confetti 좌표 추적 | 별도 `VisitConfettiLayer` 컴포넌트가 markerCacheRef에서 위치를 읽어 portal로 그림 | **폐기**. MapboxView 내부에서 마커 element의 자식 노드로 직접 추가, 600ms 후 제거 | §5.5 (신규) |
| MUST-2 30초 머무름 임계 | 30초 고정 | **유지**. 차량 정차 오탐 가능성 인지, MVP 출시 후 실측 데이터 기반 후속 튜닝 | §11 후속 튜닝 후보 |
| MUST-3 알림 중복/폭주 억제 | NotificationService 호출 1회만 (애플리케이션 레벨) | DB 부분 UNIQUE 인덱스 추가. `notifications.visit_pin_id` 컬럼 신설 + `WHERE type='VISIT_DETECTED'` 부분 인덱스. UNIQUE 위반 catch 후 조용히 스킵 | §3 V009, §4.2 NotificationService |
| CONSIDER 마커 bounce 일관성 | 모든 WISH/REEL→MEMORY 전환에서 자동 트리거 | **변경**. VISIT_DETECTED 토스트 경로에서만 bounce+confetti 발사. PinPopup 칩 경로는 별도 작업으로 분리 | §5.5, §5.6 |
| CONSIDER 차순위 핀 firstEnterAt 누적 | 가장 가까운 1개 핀만 추적 | **변경**. 후보 핀 모두 firstEnterAt을 병행 추적. "다음에 올게요" 후 차순위 핀이 이미 30초+면 즉시 토스트 가능 | §5.1 useVisitDetection |
| CONSIDER 메모 노출 스코프 | 모든 알림 상세에서 메모 표시 | **VISIT_DETECTED만**. NotificationPinList에서 `type === "VISIT_DETECTED"`일 때 메모 줄 추가 | §5.7 NotificationPinList |
| CONSIDER PinUpdateResult | 옵션 A — `PinUpdateResult(PinSummary, boolean wasWishOrReelToMemory)` record 신규 | **유지** (옵션 A 채택) | §4.1 PinService.updatePin |
| CONSIDER useVisitDetection 시계 주입 | `Date.now()` 직접 사용, 테스트에서 mock | **유지**. `vi.setSystemTime`로 모킹 | §5.1, §8 테스트 |
| CONSIDER 알림 실패 운영 가시성 | `log.warn`만 | **유지**. 메트릭은 후속 작업으로 분리 | §11 후속 작업 |
| CONSIDER 훅 책임 누수 | MapClient가 토스트 상태 관리, 훅은 evaluate만 노출 | **유지** | §5.1 |

---

## 배경 및 목적

wherewego는 커플이 함께 가고 싶은 장소를 WISH·REEL 핀으로 저장하고, 실제 방문 후 MEMORY로 전환하는 흐름을 갖는다. 현재는 사용자가 수동으로 핀을 찾아 태그를 바꿔야 해 대부분 방치된다. Phase 10은 GPS 기반으로 100m 이내 30초 머무름을 감지해 MEMORY 전환을 자동 제안하고, 전환 시 짝꿍에게 VISIT_DETECTED 알림을 보내 Phase 11 "우리 기록"의 데이터 정확도를 끌어올린다.

기존 인프라 활용:
- `MapboxView.tsx:435-462` GeolocateControl + `geo.on("geolocate")` 콜백
- `PATCH /api/v1/groups/{groupId}/pins/{pinId}` (태그/메모 독립 변경)
- Phase 8 알림(MANUAL_PIN 패턴)을 NotificationType 확장으로 재사용

---

## 요구사항 및 수용 기준

PRD `.dev/feat-phase-10-visit-detection/prd.md` 참조. FR-VD-1~31, BR-VD-1~8, AC-VD-1~22 그대로 인용.

---

## 2. 변경 범위

**영향 받는 패키지/모듈:**
- 백엔드: `com.wherewego.domain.notification`, `com.wherewego.domain.pin`, `com.wherewego.interfaces.api.pin`, `com.wherewego.interfaces.api.notification`
- 프론트엔드: `frontend/src/app/map`, `frontend/src/app/map/_hooks`, `frontend/src/app/map/_components`, `frontend/src/app/map/_components/notifications`, `frontend/src/lib/notifications`

**신규 생성 파일 (백엔드 4개):**
- `backend/apps/wherewego-api/src/main/resources/db/migration/V009__add_visit_detected_notification_type.sql`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationVisitWriter.java`
- `backend/apps/wherewego-api/src/test/java/com/wherewego/domain/notification/NotificationServiceVisitDetectedIT.java`
- `backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinUpdateResult.java`

**신규 생성 파일 (프론트엔드 6개):**
- `frontend/src/app/map/_hooks/useVisitDetection.ts`
- `frontend/src/app/map/_components/VisitToast.tsx`
- `frontend/src/app/map/_components/VisitMemoSheet.tsx`
- `frontend/src/app/map/_hooks/useVisitDetection.test.ts`
- `frontend/src/app/map/_components/VisitToast.test.tsx`
- `frontend/src/app/map/_components/VisitMemoSheet.test.tsx`

**수정 대상 파일 (백엔드 5개):**
- `NotificationType.java` — VISIT_DETECTED 추가
- `Notification.java` — `visitPinId` 컬럼 + `createForVisit` 팩토리
- `NotificationService.java` — `createForVisitDetected` 추가 + getDetail에 memo join
- `NotificationV1Dto.java` — `PinItem.memo` 추가
- `PinService.java` — `updatePin` 반환 타입 `PinUpdateResult`로 변경
- `PinV1Controller.java` — `updatePin` 핸들러에서 VISIT_DETECTED 알림 호출

**수정 대상 파일 (프론트엔드 5개):**
- `MapboxView.tsx` — `triggerVisitCelebration` ref API + confetti 자식 노드 주입 + `onGeolocate` prop
- `MapClient.tsx` — useVisitDetection 연결, VisitToast/VisitMemoSheet 조건부 렌더
- `NotificationPinList.tsx` — VISIT_DETECTED 메모 줄 추가
- `NotificationItem.tsx` — VISIT_DETECTED 분기
- `lib/notifications/types.ts` — `NotificationType` union 확장, `NotificationPinItem.memo` 추가
- `globals.css` — confetti/bounce keyframes 추가

---

## 3. 데이터 모델 / 마이그레이션

### 3.1 V009__add_visit_detected_notification_type.sql

```sql
-- ============================================================
-- V009__add_visit_detected_notification_type.sql
-- Phase 10: VISIT_DETECTED 알림 유형 도입.
--
-- 1) chk_notifications_type 확장: VISIT_DETECTED 허용.
-- 2) notifications.visit_pin_id (nullable) — VISIT_DETECTED 타입에서만 채워짐.
--    기존 MANUAL_PIN/CHATBOT_PINS는 NULL 유지(notification_pins로 연결).
--    FK → pins(id). pin이 soft-delete 되어도 row는 살아 있어 무결성 유지.
-- 3) 부분 UNIQUE 인덱스 uq_notifications_visit:
--    동일 (group_id, receiver_id, registered_by, visit_pin_id) 조합으로
--    VISIT_DETECTED 알림은 1회만 생성되도록 race-free 보장.
-- ============================================================

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_type;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_type
    CHECK (type IN ('MANUAL_PIN', 'CHATBOT_PINS', 'VISIT_DETECTED'));

ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS visit_pin_id BIGINT NULL REFERENCES pins (id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_visit
    ON notifications (group_id, receiver_id, registered_by, visit_pin_id)
    WHERE type = 'VISIT_DETECTED' AND visit_pin_id IS NOT NULL;
```

**Trade-off**: 컬럼 1개 추가 + 부분 인덱스. 데이터 모델 부담은 최소. 기존 MANUAL_PIN/CHATBOT_PINS는 visit_pin_id NULL 유지하여 호환.

---

## 4. 백엔드 상세 설계

### 4.1 PinService.updatePin + PinUpdateResult

신규 `PinUpdateResult.java`:
```java
public record PinUpdateResult(
        PinSummary summary,
        boolean wasWishOrReelToMemory
) {}
```

`PinService.updatePin` 시그니처: `PinUpdateResult updatePin(Long userId, Long groupId, Long pinId, PinUpdateCommand cmd)`

동작:
- 비관 락 조회 직후 `PinTag previousTag = pin.getTag();`
- `cmd.tagProvided()`에서 `pin.changeTag(cmd.tag())` 호출
- `boolean transitioned = cmd.tagProvided() && cmd.tag() == PinTag.MEMORY && (previousTag == PinTag.WISH || previousTag == PinTag.REEL);`
- 반환: `new PinUpdateResult(toSummary(pin), transitioned)`

### 4.2 NotificationType + Notification (visit_pin_id 매핑)

```java
public enum NotificationType { MANUAL_PIN, CHATBOT_PINS, VISIT_DETECTED }
```

`Notification`:
```java
@Column(name = "visit_pin_id")
private Long visitPinId;

public static Notification createForVisit(Long groupId, Long receiverId, Long registeredBy, Long visitPinId) {
    return new Notification(groupId, receiverId, registeredBy, NotificationType.VISIT_DETECTED, visitPinId);
}
```

기존 `create()` 팩토리는 유지(visit_pin_id=NULL).

### 4.3 NotificationVisitWriter (신규) + NotificationService.createForVisitDetected

receiver별 트랜잭션 격리 (REQUIRES_NEW) + UNIQUE 위반 catch.

`NotificationVisitWriter`:
```java
@Component
@RequiredArgsConstructor
public class NotificationVisitWriter {
    private final NotificationRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean writeOne(Long groupId, Long receiverId, Long registeredBy, Long pinId) {
        try {
            Notification n = repository.save(
                    Notification.createForVisit(groupId, receiverId, registeredBy, pinId));
            repository.saveAllPins(List.of(NotificationPin.link(n.getId(), pinId, 0)));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
```

`NotificationService.createForVisitDetected` (트랜잭션 없음, fan-out 담당):
```java
public void createForVisitDetected(Long groupId, Long registeredBy, Long pinId) {
    List<Long> otherIds = groupMemberRepository.findOtherActiveMemberIds(groupId, registeredBy);
    List<Long> receiverIds = new ArrayList<>(otherIds);
    receiverIds.add(registeredBy);
    for (Long receiverId : receiverIds) {
        boolean inserted = visitWriter.writeOne(groupId, receiverId, registeredBy, pinId);
        if (!inserted) {
            log.debug("visit notification skipped (duplicate) receiverId={} pinId={}", receiverId, pinId);
        }
    }
}
```

### 4.4 PinV1Controller.updatePin

```java
PinUpdateResult result = pinService.updatePin(userId, groupId, pinId, request.toCommand());
if (result.wasWishOrReelToMemory()) {
    try {
        notificationService.createForVisitDetected(groupId, userId, pinId);
    } catch (RuntimeException e) {
        log.warn("notification (visit) failed groupId={} pinId={}", groupId, pinId, e);
    }
}
return ApiResponse.success(PinV1Dto.PinSummaryResponse.from(result.summary()));
```

### 4.5 NotificationService.getDetail + DTO에 memo 추가

`NotificationPinItemResult`에 `String memo` 추가. getDetail에서:
```java
String memo = (n.getType() == NotificationType.VISIT_DETECTED && pin != null && !pin.isDeleted())
        ? pin.getMemo()
        : null;
```

`NotificationV1Dto.PinItem`에도 `memo` 필드 추가.

---

## 5. 프론트엔드 상세 설계

### 5.1 useVisitDetection 훅 (CONSIDER 반영 — 후보 핀 전체 firstEnterAt 누적)

```typescript
import { useCallback, useRef } from "react";
import type { PinSummaryResponse } from "@/lib/api/types";
import { haversineKm } from "../_lib/roulette";

const PROXIMITY_KM = 0.1;          // 100m
const ACCURACY_MAX_M = 50;
const DWELL_MS = 30_000;

interface EvaluateInput {
  position: GeolocationPosition;
  pins: PinSummaryResponse[];
  shownPinIds: Set<number>;
}

export interface VisitEvaluation {
  detectedPinId: number | null;
}

export function useVisitDetection() {
  const firstEnterAtRef = useRef<Map<number, number>>(new Map());

  const evaluate = useCallback(({ position, pins, shownPinIds }: EvaluateInput): VisitEvaluation => {
    if (position.coords.accuracy > ACCURACY_MAX_M) {
      return { detectedPinId: null };
    }

    const userPos = { lat: position.coords.latitude, lng: position.coords.longitude };
    const now = Date.now();

    const candidates: Array<{ pinId: number; distanceKm: number }> = [];
    for (const pin of pins) {
      if (pin.tag !== "WISH" && pin.tag !== "REEL") continue;
      if (shownPinIds.has(pin.id)) continue;
      const distanceKm = haversineKm(userPos, {
        lat: Number(pin.latitude),
        lng: Number(pin.longitude),
      });
      if (distanceKm <= PROXIMITY_KM) {
        candidates.push({ pinId: pin.id, distanceKm });
      }
    }

    const candidateIds = new Set(candidates.map((c) => c.pinId));
    for (const pinId of firstEnterAtRef.current.keys()) {
      if (!candidateIds.has(pinId)) firstEnterAtRef.current.delete(pinId);
    }

    for (const { pinId } of candidates) {
      if (!firstEnterAtRef.current.has(pinId)) {
        firstEnterAtRef.current.set(pinId, now);
      }
    }

    candidates.sort((a, b) => a.distanceKm - b.distanceKm);
    for (const { pinId } of candidates) {
      const firstEnterAt = firstEnterAtRef.current.get(pinId);
      if (firstEnterAt !== undefined && now - firstEnterAt >= DWELL_MS) {
        return { detectedPinId: pinId };
      }
    }

    return { detectedPinId: null };
  }, []);

  const clearFirstEnterAt = useCallback((pinId: number) => {
    firstEnterAtRef.current.delete(pinId);
  }, []);

  return { evaluate, clearFirstEnterAt };
}
```

시계 주입: `Date.now()` 직접. 테스트는 `vi.setSystemTime`.

### 5.2 MapboxView 확장 (forwardRef + onGeolocate)

```typescript
export interface MapboxViewHandle {
  triggerVisitCelebration: (pinId: number) => void;
}

const MapboxView = forwardRef<MapboxViewHandle, MapboxViewProps>(function MapboxView(props, ref) {
  // 기존 로직 + onGeolocate prop
  useImperativeHandle(ref, () => ({
    triggerVisitCelebration: (pinId: number) => {
      const marker = markerCacheRef.current.get(pinId);
      if (!marker) return;
      const el = marker.getElement() as HTMLDivElement;
      runMarkerBounceAndConfetti(el);
    },
  }), []);
});
```

기존 `geo.on("geolocate", ...)` 리스너에서 `onGeolocateRef.current?.(e)` 추가 호출.

### 5.3 마커 bounce + confetti 자식 노드 주입 (MUST-1)

`runMarkerBounceAndConfetti(markerEl: HTMLDivElement)`:
1. 기존 자식 SVG를 `<div data-bounce-inner>` 안으로 이동
2. inner에 `animation: maygo-marker-bounce 600ms`
3. 별도 `<div>` 추가 + 하트 ♡ 3개 자식 (각도 -120°/-90°/-60°, 거리 36~44px)
4. `setTimeout(() => { confetti 제거 + inner 제거 }, 600)`
5. 마커는 다음 `renderClusters`에서 자연 복원

globals.css 추가:
```css
@keyframes maygo-marker-bounce {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.3); }
}
@keyframes maygo-confetti-heart-0 {
  0% { transform: translate(-50%, -50%) scale(0.6); opacity: 0; }
  20% { opacity: 1; }
  100% { transform: translate(calc(-50% + var(--dx)), calc(-50% + var(--dy))) scale(1); opacity: 0; }
}
/* heart-1, heart-2 동일 (dx/dy 사용) */
```

핵심 동작:
- 마커가 이동(zoom/pan)하면 confetti가 마커 element의 자식이므로 자동 따라감
- PATCH RTT 동안 사용자가 지도를 팬해도 좌표 어긋남 없음
- 트리거 직후 1회 발사, 600ms 자연 소멸

### 5.4 VisitToast 컴포넌트

```typescript
interface VisitToastProps {
  pin: PinSummaryResponse;
  onSkip: () => void;
  onConfirm: () => void;
}

export function VisitToast({ pin, onSkip, onConfirm }: VisitToastProps) {
  return (
    <div role="status" style={{ position: "fixed", bottom: 100, ... }}>
      <div>📍 {pin.placeName} 근처에 계신가요?</div>
      {pin.address && <div>{pin.address}</div>}
      <div style={{ display: "flex", gap: 8 }}>
        <BtnSub onClick={onSkip}>다음에 올게요</BtnSub>
        <BtnPrimary onClick={onConfirm}>네, 다녀왔어요 →</BtnPrimary>
      </div>
    </div>
  );
}
```

위치: 모바일 `bottom: 100` (ActionBar 위), 데스크탑 `bottom: 32 / left: 76`. 자동 닫힘 없음.

### 5.5 VisitMemoSheet 컴포넌트

`ActiveSheet` union에 `"visit-memo"` 추가. `renderPanel`로 일관 렌더.

```typescript
interface VisitMemoSheetProps {
  pin: PinSummaryResponse;
  visitedAt: Date;
  onSave: (memo: string) => Promise<{ ok: boolean; message?: string }>;
  onSkip: () => void;
}
```

내용:
- 상단: `✓ {pin.placeName}, 다녀왔어요!` + `{YYYY년 M월 D일}`
- 본문: textarea (maxLength 500)
- 인라인 에러 영역
- 버튼: `BtnSub` (건너뛰기) + `BtnPrimary` (저장)

저장 실패 시 시트 유지 (FR-VD-22). 건너뛰기는 2차 PATCH 미발사 (FR-VD-20).

### 5.6 MapClient 통합

상태:
```typescript
const shownPinIdsRef = useRef<Set<number>>(new Set());
const { evaluate: evaluateVisit, clearFirstEnterAt } = useVisitDetection();
const [visitToastPin, setVisitToastPin] = useState<PinSummaryResponse | null>(null);
const [visitMemoPin, setVisitMemoPin] = useState<PinSummaryResponse | null>(null);
const visitedAtRef = useRef<Date | null>(null);
const mapboxViewRef = useRef<MapboxViewHandle | null>(null);
```

핸들러:
- `handleGeolocate(position)`: evaluate 호출 → detectedPinId 있고 activeSheet/패널이 모두 닫혀 있으면 `setVisitToastPin(pin)`
- `handleVisitSkip()`: shownPinIds 추가, clearFirstEnterAt, 토스트 닫음
- `handleVisitConfirm()`: 1차 PATCH(tag→MEMORY) → 성공 시 shownPinIds 추가, `mapboxViewRef.current.triggerVisitCelebration(pinId)`, 메모 시트 열기. 실패 시 시스템 에러 노출 + 세션 Set 미추가
- `handleVisitMemoSave(memo)`: 2차 PATCH(memo) → 성공 시 시트 닫음, 실패 시 인라인 에러
- `handleVisitMemoSkip()`: 시트만 닫음 (2차 PATCH 미발사)

`renderPanel` 분기에 `visit-memo` 추가. `<MapboxView ref={mapboxViewRef} onGeolocate={handleGeolocate} ... />`.

### 5.7 NotificationPinList 메모 표시 (VISIT_DETECTED 한정)

```typescript
{pin.memo && type === "VISIT_DETECTED" && (
  <div style={{ fontSize: 12, color: colors.ink, marginTop: 4, padding: "6px 8px",
    background: colors.bg, borderRadius: 6, whiteSpace: "pre-wrap" }}>
    {pin.memo}
  </div>
)}
```

### 5.8 NotificationItem 분기

```typescript
const actorLabel = item.type === "VISIT_DETECTED"
  ? `${item.registeredByNickname}님이 다녀온 장소`
  : `${item.registeredByNickname}님이 장소를 저장했어요.`;
```

### 5.9 lib/notifications/types.ts

```typescript
export type NotificationType = "MANUAL_PIN" | "CHATBOT_PINS" | "VISIT_DETECTED";

export interface NotificationPinItem {
  pinId: number;
  placeName: string;
  address: string | null;
  latitude: string | null;
  longitude: string | null;
  deleted: boolean;
  instagramUrl: string | null;
  memo?: string | null;   // [신규]
}
```

---

## 6. 의존성 및 영향도

**새로 추가할 의존성**: 없음.

**기존 코드 영향:**
- `PinService.updatePin` 반환 타입 변경 → `PinV1Controller`만 호출 (Grep 사전 확인)
- `NotificationPinItemResult`에 `memo` 추가 → DTO 매핑 1줄. 프론트는 optional이라 호환
- `MapboxView`가 forwardRef로 전환 → MapClient의 dynamic import에서 ref 전달
- V009 마이그레이션: CHECK 확장 + nullable 컬럼. 기존 데이터 영향 없음

**하위 호환성:**
- 기존 MANUAL_PIN/CHATBOT_PINS 알림 동작 무변경
- PATCH API 시그니처/응답 무변경
- NotificationItem/Panel 기존 표시 무변경

---

## 7. 구현 순서

1. **[Must]** V009 마이그레이션 + 로컬 Flyway 적용 확인
2. **[Must]** NotificationType + Notification 엔티티 (`visitPinId` 매핑)
3. **[Must]** NotificationVisitWriter + NotificationService.createForVisitDetected
4. **[Must]** NotificationService.getDetail memo join + NotificationPinItemResult/PinItem DTO `memo` 필드
5. **[Must]** PinUpdateResult record + PinService.updatePin 시그니처 변경
6. **[Must]** PinV1Controller.updatePin VISIT_DETECTED 알림 호출
7. **[Must]** NotificationServiceVisitDetectedIT (UNIQUE race, 격리, MEMORY→MEMORY 무알림 등)
8. **[Must]** frontend types.ts 확장
9. **[Must]** useVisitDetection 훅 + 단위 테스트
10. **[Must]** VisitToast / VisitMemoSheet 컴포넌트 + 테스트
11. **[Must]** MapboxView forwardRef + triggerVisitCelebration imperative API + confetti/bounce + globals.css keyframes
12. **[Must]** MapClient 통합: useVisitDetection 연결, visit-memo activeSheet, 핸들러 구현
13. **[Should]** NotificationItem VISIT_DETECTED 카피 분기
14. **[Should]** NotificationPinList 메모 표시 (VISIT_DETECTED 한정)
15. 수동 시나리오 검증 (PRD 골든 패스 + 예외 흐름 3건)

**병렬 묶음:**
- 묶음 A (BE): 1 → (2,5) 동시 → (3,4,6) 단계별 → 7
- 묶음 B (FE 신규): 8 → (9,10,11) 동시 (모두 신규/독립 파일)
- 단계 12 MapClient: 9~11 완료 후 단독
- 묶음 C (FE 알림 분기): 13,14 동시

---

## 8. 테스트 전략

### 8.1 백엔드 통합 테스트 (NotificationServiceVisitDetectedIT)
- (a) 정상 fan-out: 그룹 멤버 2명, 동일 pinId 1회 → 각 receiver 1행씩
- (b) race-free UNIQUE: 동일 pinId 2회 연속 → 첫 호출만 INSERT, 두 번째는 silently skip
- (c) ExecutorService 동시성 시뮬레이션
- (d) PinV1Controller WISH→MEMORY 전환 시 알림 1회
- (e) MEMORY→MEMORY 호출 시 알림 미발송
- (f) memo만 변경 호출 시 알림 미발송
- (g) NotificationService 내부 RuntimeException 발생 → PATCH 응답 200 유지 (BR-VD-6)
- (h) getDetail에 VISIT_DETECTED 조회 → memo 최신값. soft-delete 핀이면 null

### 8.2 프론트엔드 단위 테스트
- `useVisitDetection.test.ts`: 정확도 게이트, 첫 진입, 30초 후 감지, 25초 후 이탈 → 재진입, MEMORY 제외, 세션 Set 제외, **차순위 핀 firstEnterAt 누적**, 30000ms 경계값
- `VisitToast.test.tsx`: 렌더, 버튼 클릭 콜백, 자동 닫힘 없음
- `VisitMemoSheet.test.tsx`: 렌더, 저장 성공/실패, 건너뛰기

### 8.3 수동 검증
PRD 골든 패스 + 예외 흐름 3건 (건너뛰기, 동시 다수 핀, 권한 거부). iOS Safari 실기 1회 포함.

---

## 9. 위험 요소

- **iOS Safari watchPosition 콜백 간격**: 30초 머무름 임계가 콜백 빈도에 따라 누락 가능 → §11 (a)
- **부분 UNIQUE 인덱스의 NULL 처리**: `WHERE type='VISIT_DETECTED' AND visit_pin_id IS NOT NULL` 명시로 안전
- **REQUIRES_NEW 트랜잭션 비용**: receiver 2~3명 수준이라 부담 없음
- **PinService.updatePin 반환 타입 변경**: Grep 사전 확인
- **마커가 클러스터에 포함되어 markerCacheRef에 없는 경우**: confetti/bounce 스킵. 허용
- **forwardRef + next/dynamic 호환성**: Next 15 dynamic은 forwardRef 전달 지원. AGENTS.md 따라 docs 재확인

---

## 10. 후속 작업

- 알림 실패 운영 가시성: `log.warn` 외 Prometheus counter 또는 구조화 로그 도입
- 재방문 알림 정책: 현재 동일 (그룹, 수신자, 등록자, 핀)에 평생 1회. MEMORY→WISH→MEMORY 재전환 시 알림 없음. 후속 Phase에서 시간 윈도우 정책 확장
- PinPopup 칩으로 직접 MEMORY 전환 시 bounce 자동 트리거 (현재 VISIT_DETECTED 경로만)

---

## 11. 후속 튜닝 후보

30초 머무름 임계의 차량 정차 오탐을 줄이기 위한 후보. MVP 실측 데이터 기반으로 선택:

- (a) iOS Safari watchPosition 콜백 간격 실측
- (b) 콜백 N회 이상 + 30초 보조 조건
- (c) `position.coords.speed` 게이트 (걷는 속도 이하만 머무름 카운트)

본 Phase 10 구현 자체에는 변경 없음.

---

## 12. 적용 컨벤션 요약

**백엔드 (Spring Boot, Java/Kotlin Gradle):**
- 패키지: `domain/{aggregate}` + `interfaces/api/{aggregate}` + `infrastructure/{aggregate}`
- DTO 변환: `XxxV1Dto.from(result)` static 메서드
- 트랜잭션: 호출자 격리 (BR-3 정책), REQUIRES_NEW로 receiver별 격리
- Flyway 파일명: `V{NNN}__{snake_case}.sql`

**프론트엔드 (Next.js 15 + React 19, Mapbox GL):**
- 클라이언트 컴포넌트 상단 `"use client";`
- 디자인 토큰: `@/lib/design/tokens` (colors, fonts) + inline style
- 액션 시트: Sheet(모바일) / SidePanel(데스크탑) 분기, activeSheet 단일 상태
- 서버 액션: `Result<T> = { ok: true, data: T } | { ok: false, code, message }`
- 훅: `useXxx.test.ts` 단위 테스트, vitest + `@testing-library/react`
- 시계 모킹: `vi.useFakeTimers()` + `vi.setSystemTime(...)`

**Next.js 주의 (frontend/AGENTS.md)**: "This is NOT the Next.js you know — node_modules/next/dist/docs/ 를 먼저 읽고 코드 작성". 구현자는 dynamic import + forwardRef, Server Action 등 최신 컨벤션 사전 확인 필요.
