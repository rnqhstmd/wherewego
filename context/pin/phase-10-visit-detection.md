# Phase 10 — 장소 방문 감지 (WISH/REEL → MEMORY 자동 전환)

- 작성일: 2026-05-24
- 수정일: 2026-05-24
- 관련 레포: rnqhstmd/wherewego
- 상태: ✅ 완료 ([PR #57](https://github.com/rnqhstmd/wherewego/pull/57))

## 개요

WISH·REEL 태그로 저장한 핀 좌표 100m 이내에서 30초 이상 머무르면 토스트로 MEMORY 전환을 제안한다. 사용자가 "네, 다녀왔어요"를 누르면 핀이 MEMORY로 자동 전환되고, 마커 confetti 연출 후 메모 시트가 열려 "다녀온 흔적"을 기록할 수 있다. 짝꿍에게는 `VISIT_DETECTED` 알림이 전달된다.

> MEMORY 태그 핀은 이미 다녀온 장소이므로 감지 대상에서 제외한다.

---

## 최종 트리거 사양

| 조건 | 값 |
|------|-----|
| 대상 핀 태그 | WISH, REEL (MEMORY 제외) |
| 감지 반경 | 100m (Haversine, 프론트 계산) |
| 머무름 임계 | 30초 (`DWELL_MS = 30_000`) |
| GPS 정확도 게이트 | `accuracy ≤ 50m` 인 이벤트만 평가. **50m 초과는 평가 스킵 + firstEnterAt 보존** (불량 GPS 이벤트가 누적 진행을 무효화하지 않음) |
| 속도 게이트 | `position.coords.speed > 1.4 m/s`(보행 속도 초과, 차량 이동 추정) 감지 시 모든 후보의 firstEnterAt clear (`WALKING_SPEED_MAX_MS`) |
| 동시 감지 | 가장 가까운 핀 1개만 토스트 표시. 후보 firstEnterAt은 병행 누적해 차순위 핀을 즉시 평가 가능 |
| 중복 표시 방지 | 세션 내 `shownPinIds: Set<number>`. MapClient unmount 시 자동 소멸 |
| 평가 트리거 | GeolocateControl `geolocate` 이벤트 + 권한 `granted`일 때 5초 간격 자동 폴링 (`watchPosition` 콜백 희소성 보완) |

---

## 최종 화면 플로우

```
GPS 위치 갱신 (또는 5초 폴링)
  ↓
정확도 ≤ 50m 게이트 + 속도 ≤ 1.4 m/s 게이트
  ↓
BBox prefilter (LAT_DEG_PER_METER 기반 ±0.001도 박스)
  ↓
Haversine 거리 계산 (BBox 통과 핀만)
  ↓
100m 이내 후보 중 가장 가까운 핀의 firstEnterAt 30초 경과 확인
  ↓
[VisitToast 중앙 표시] PinPopup 스타일 카드
  ├── "다음에 올게요" → shownPinIds 추가, 차순위 후보로 즉시 재평가
  └── "네, 다녀왔어요 →"
        ↓
        ┌── flyTo({center: pin.location, duration: 1500}) (moveend 또는 안전 timeout 대기)
        └── PATCH /pins/{id} { tag: "MEMORY" } 동시 실행 (Promise.all)
        ↓
        250ms 일시 정지 (flyTo 안착 시간 확보)
        ↓
        마커 bounce 900ms (scale 1.0 → 1.5 → 1.0, cubic-bezier 4-stage)
        + 6개 하트 confetti 1000ms (22~30px, --dx/--dy CSS 변수로 위치 분산)
        ↓
        VisitMemoSheet 슬라이드 업: "🌸 다녀온 흔적"
        "다녀온 날 · YYYY.MM.DD" + 텍스트 입력
          ├── [건너뛰기] → 시트 닫힘, 핀 상세 자동 오픈
          └── [저장] → PATCH /pins/{id} { memo } + 시트 닫힘 + 핀 상세 자동 오픈
```

---

## 최종 화면 사양

### VisitToast (PinPopup 스타일 리디자인)

- 위치: 화면 정중앙 (`top: 50%; left: 50%; transform: translate(-50%, -50%)`). 데스크탑/모바일 공통.
- 최대 너비: 380px. PinPopup과 동일한 카드 디자인 (PinDot + 장소명 + 주소 + 메모 + written by).
- 핵심 카피: `"{장소명}에 함께 방문하셨나요?"` (헤드라인) + 주소 + 기존 메모 미리보기 (있을 때만) + `written by {등록자 닉네임}`.
- 메모가 비어있으면 메모 섹션 자체를 렌더링하지 않는다 (UI 잡음 회피).
- CTA: "다음에 올게요" (보조, 회색 텍스트) / "네, 다녀왔어요 →" (`colors.cta` 핑크).

### 마커 confetti

- `MapboxView`는 `forwardRef` + `useImperativeHandle`로 `triggerVisitCelebration(pinId)` 노출.
- 6개 하트(♥) DOM 노드를 마커 위치에 절대 배치하여 fan-out (CSS keyframe + 개별 `--dx/--dy` 변수).
- 하트 크기 22~30px 랜덤. 1000ms fade-out.
- 마커 자체는 `maygo-marker-bounce` 4-stage cubic-bezier 애니메이션 900ms (scale 1.0 → 1.5 → 1.0 + 미세 회전).

### VisitMemoSheet

- 헤더: "🌸 다녀온 흔적".
- 메타: "다녀온 날 · YYYY.MM.DD" (백엔드 `visitedAt` 기준).
- 입력: 한 줄 + 멀티라인 자유 입력. 저장 시 `PATCH /pins/{id} { memo }`.
- 저장/건너뛰기 모두 시트 닫힘 직후 해당 핀을 `selectedPinId`로 설정 → PinPopup 자동 오픈 (사용자가 결과를 즉시 확인).

### 알림 패널 고정 높이 + 스크롤 보존

- `NotificationPanel`은 600px 고정 높이로 변경 (모바일 sheet도 동일 cap).
- `bodyRef` + `listScrollRef`로 패널 닫기/다시 열기 사이 스크롤 위치 보존. 최대 50건 누적 시에도 사용자 위치 유지.

---

## 백엔드 변경

### 마이그레이션

- `V009__add_visit_detected_notification_type.sql`: notifications CHECK 제약에 `'VISIT_DETECTED'` 추가 + `visit_pin_id BIGINT NULL REFERENCES pins(id) ON DELETE RESTRICT` + 부분 UNIQUE 인덱스 `WHERE type='VISIT_DETECTED' AND visit_pin_id IS NOT NULL`.
- `V010__add_pins_visited_at.sql`: `ALTER TABLE pins ADD COLUMN visited_at TIMESTAMPTZ NULL`.

### 도메인

- `Pin.changeTag(PinTag, Long)`가 WISH/REEL → MEMORY 전이 시 `visitedAt = ZonedDateTime.now()` 설정. MEMORY 외 전이에서는 변경하지 않는다.
- `PinSummary`에 `visitedAt` 노출. `PinSummaryResponse`에도 ISO-8601 직렬화 추가.
- `PinService.updatePin`이 `PinUpdateResult(PinSummary summary, boolean wasWishOrReelToMemory)` record를 반환. 컨트롤러가 태그 전이 여부를 알 수 있게 됨.

### 알림 fan-out

- `NotificationService.createForVisitDetected(groupId, registeredBy, pinId)`:
  - **멤버십 사전 검증**: `groupMemberRepository.findActiveByGroupIdAndUserId(groupId, registeredBy)` → 비활성 멤버면 `log.warn` 후 early return (957761a 보강).
  - 본인 포함 fan-out: `findOtherActiveMemberIds` 결과에 `registeredBy`도 추가. Phase 11 "우리 기록" 도입 전 과도기로 본인 알림함에도 방문 기록을 노출.
  - 각 receiver마다 `NotificationVisitWriter.writeOne` 호출 후 `try-catch`:
    - `DataIntegrityViolationException` (부분 UNIQUE 충돌) → `log.debug` skip.
    - 일반 `RuntimeException` → `log.warn`으로 per-receiver 격리 (957761a 보강. 한 receiver의 예외가 나머지 fan-out을 막지 않음).
- `NotificationVisitWriter.writeOne(...)`: `@Transactional(REQUIRES_NEW)`. void 반환, 예외는 caller에 전파 (Spring `UnexpectedRollbackException` 회피용 caller-catch 패턴).

### 컨트롤러 응답

- `PinV1Dto.UpdatePinResponse(PinSummaryResponse summary, boolean transitionedToMemoryNow)`. FE가 이 플래그로 confetti 트리거 여부를 판단.
- `PinV1Dto.PinItem`에 `tag` + `memo` 필드 추가 (알림 상세에서 최신 메모/태그 표시).

---

## 프론트엔드 변경

### useVisitDetection

- 신규 훅. `MapboxView.tsx`의 `geolocate` 콜백 + 5초 자동 폴링에서 호출.
- 외부 입력: `wishReelPins` (MapClient에서 `useMemo`로 캐싱한 후보 핀 배열).
- 내부 상태: `firstEnterAtRef = Map<pinId, timestamp>`, `shownPinIdsRef`, `lastEvalAtRef`.
- 평가 절차:
  1. accuracy > 50m → 평가 스킵 (firstEnterAt 보존).
  2. speed > WALKING_SPEED_MAX_MS → `clearAllFirstEnterAt()`.
  3. BBox prefilter (±LAT_DEG_PER_METER × PROXIMITY_METERS) → Haversine으로 통과 핀만 거리 계산.
  4. 100m 이내 후보 중 firstEnterAt 30초 경과한 가장 가까운 핀 → 토스트 발동.
- 성능: 1000핀 환경에서 BBox prefilter가 Haversine 호출을 99% 컷 (~10회로 축소). 테스트로 0.002도(≈222m) 박스 외 핀이 컷되는지 검증.

### MapboxView

- `forwardRef` + `useImperativeHandle`로 `triggerVisitCelebration(pinId)` 노출.
- `runMarkerBounceAndConfetti(pinId)`: 마커 DOM에 bounce class 추가 900ms + 6개 하트 노드 생성 후 1000ms 뒤 제거.
- `geo.on("load")`에서 `navigator.permissions.query({name:"geolocation"})` 결과가 `granted`이면 5초 간격 폴링 시작 (`prompt`/`denied`는 폴링 미시작 + 자동 flyTo 미실행, iOS GeolocateControl 비활성화 회피).

### MapClient

- `wishReelPins` = `useMemo(() => allPins.filter(...), [allPins])` — 캐싱.
- `handleVisitConfirm(pin)`:
  - `Promise.all([flyToPromise, patchPromise])`.
  - flyTo는 `map.once("moveend", resolve)` + 5초 안전 timeout으로 도착 대기.
  - PATCH 응답 결과 검증 후 250ms 일시 정지 → `mapboxRef.current?.triggerVisitCelebration(pin.id)` → confetti 종료 후 `setVisitMemoPin(pin)`로 메모 시트 오픈.
- `handleVisitMemoSkip`: 메모 미저장 + 시트 닫힘 + `setSelectedPinId(visitMemoPin.id)` (closure 누수 회피 위해 로컬 변수로 캡처).
- 자동 폴링 useEffect: 권한 granted 진입 시 5초 인터벌 시작, visibilitychange로 hidden 진입 시 일시 정지.

---

## 엣지케이스 처리

| 상황 | 처리 |
|------|------|
| GPS 정확도 50m 초과 | 평가 스킵 + firstEnterAt 보존 (불량 이벤트 누적 차단 정책 — gemini 봇 권고와 다른 사용자 결정) |
| 차량 이동 추정 (speed > 1.4 m/s) | 모든 후보의 firstEnterAt clear |
| 동시 100m 이내 여러 핀 | 가장 가까운 1개만 토스트. 후보 firstEnterAt은 병행 누적 |
| 토스트 표시 중 사용자가 다른 동작 | 토스트는 사용자가 명시적으로 닫을 때까지 유지 |
| 1차 PATCH 실패 | `setVisitErrorMessage` 인라인 토스트 노출 (1.5초 자동 닫힘) + 핀 상태는 변경 없이 유지 |
| 2차 메모 PATCH 실패 | 메모만 미저장, 1차 MEMORY 전환은 이미 커밋된 상태 유지 |
| 디바이스 절전 → 깨움 | `visibilitychange` listener가 폴링 일시 정지/재개 + visible 복귀 시 `lastEvalAtRef` 리셋해 즉시 1회 평가 |
| 멤버십 비활성 사용자가 알림 발사 | `createForVisitDetected` 진입 시 `log.warn` 후 early return |
| 한 receiver fan-out 실패 | `RuntimeException` catch로 격리, 나머지 receiver는 정상 진행 |
| 동일 (group, receiver, registeredBy, pinId) 조합 재진입 | 부분 UNIQUE 인덱스가 race-free로 차단, caller가 `DataIntegrityViolationException` debug log |

---

## 테스트 결과 (최종)

- 백엔드: `./gradlew :apps:wherewego-api:test` BUILD SUCCESSFUL (`NotificationServiceVisitDetectedIT` 300줄 신규 + 13개 IT의 `truncateAll`에 notifications 정리 prepend).
- 프론트엔드: `npm test` → 22 Test Files / 165 Tests PASS (`useVisitDetection.test.ts` 374줄, `VisitToast.test.tsx`, `VisitMemoSheet.test.tsx` 신규).

---

## 외부 리뷰 처리

- cross-review (codex): 6건 지적 → 6건 모두 보강 ([commit 44f70d5](https://github.com/rnqhstmd/wherewego/pull/57/commits/44f70d5)).
- gemini-code-assist 봇 4건:
  - 멤버십 사전 검증 추가 (반영) → 957761a.
  - RuntimeException per-receiver 격리 추가 (반영) → 957761a.
  - `console.error` 페이로드 단순화 (반영) → 957761a.
  - `accuracy > 50m` 시 firstEnterAt clear 권고 → **의도적 미반영** (PRD BR-VD-3 "타이머에 영향을 주지 않는다"를 누적 보존으로 해석한 self-check Q4 결정 존중. 부작용은 trust-ledger 후속 관찰).

---

## 후속 작업

- `notification_pins.pin_id` ON DELETE 정책 ADR 기록 (soft delete 영구 유지 가정).
- `NotificationPanel.loadDetail` 실패 시 에러 UI 표시.
- `NotificationPanel` 빠른 연속 클릭 시 detail fetch race condition (AbortController).
- `registeredBy` 필드 응답 제거 (FE 미사용, 최소 공개 원칙).
- 조사 처리 자동화 (NotificationToast/Item — 받침 유무 을/를).
- Phase 11 도입 시 `createForVisitDetected`의 본인 포함 fan-out을 "우리 기록" 화면 기반으로 재검토 (현재는 과도기 정책).
