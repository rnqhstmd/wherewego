## AC 충족 매트릭스

| AC | 충족(O/X/부분) | 근거(파일:라인 또는 PRD 인용) |
|---|---|---|
| AC-VD-1 | O | `useVisitDetection.ts:72-103`, `MapClient.tsx:991-1009` |
| AC-VD-2 | O | `useVisitDetection.ts:72`; PRD: “MEMORY 태그 핀은 평가에서 제외한다.” |
| AC-VD-3 | O | `useVisitDetection.ts:58-61`; PRD: “accuracy > 50m이면 ... 평가 전체를 스킵” |
| AC-VD-4 | O | `useVisitDetection.ts:83-88`, 세션 Set 추가는 `MapClient.tsx:1025-1029`, `1065`에만 존재 |
| AC-VD-5 | O | `MapClient.tsx:270-276`, `useVisitDetection.ts:73`, `MapClient.tsx:1027`, `1065` |
| AC-VD-6 | 부분 | PRD: “토스트에는 장소명과 주소를 표시한다.” 코드: 장소명은 `VisitToast.tsx:71`, 주소는 `pin.address` 있을 때만 `VisitToast.tsx:73-83`; `PinSummaryResponse.address`는 nullable `types.ts:26` |
| AC-VD-7 | O | `MapClient.tsx:1025-1030` |
| AC-VD-8 | O | `MapClient.tsx:1042-1051`, `actions.ts:46-53` |
| AC-VD-9 | 부분 | 하트 3개/bounce/MEMORY 교체는 `MapboxView.tsx:104-114`, `88-89`, `420-424`; 단 PRD가 “200ms 내”와 “전체 ... 약 600ms”를 함께 명시하고 코드는 600ms `MapboxView.tsx:113`, `118-131` |
| AC-VD-10 | O | `MapClient.tsx:1068-1071` |
| AC-VD-11 | O | `VisitMemoSheet.tsx:61`, `75` |
| AC-VD-12 | O | `VisitMemoSheet.tsx:47-58`, `MapClient.tsx:1080-1091`, `actions.ts:74-90` |
| AC-VD-13 | O | `VisitMemoSheet.tsx:129-135`, `MapClient.tsx:1111-1114`; 태그 MEMORY 유지 `MapClient.tsx:1062-1071` |
| AC-VD-14 | O | `MapClient.tsx:1052-1060`, `1610-1630`; 세션 Set 추가는 성공 후 `1065` |
| AC-VD-15 | O | `MapClient.tsx:1084-1103`, `VisitMemoSheet.tsx:51-54`, `111-125` |
| AC-VD-16 | O | `MapboxView.tsx:565-575`; PRD: “비활성 중 ... geolocate 콜백이 발생하지 않으므로 훅 평가 자체 미실행” |
| AC-VD-17 | O | `PinService.java:256-258`, `PinV1Controller.java:129-132`, `NotificationService.java:94-100` |
| AC-VD-18 | O | `PinV1Controller.java:129-135`; PRD: “알림 실패 ... PATCH 응답에 영향을 주지 않는다.” |
| AC-VD-19 | O | `V009__add_visit_detected_notification_type.sql:17-19`, `NotificationType.java:3-6` |
| AC-VD-20 | 부분 | PRD: “장소명, 주소, 태그, 현재 메모” 포함. 코드: 장소/주소/memo는 `NotificationService.java:206-208`; tag는 `NotificationPinItemResult`, DTO, FE type에 없음 `NotificationService.java:259-268`, `NotificationV1Dto.java:54-63`, `types.ts:26-39` |
| AC-VD-21 | O | 세션 Set은 `useRef` 메모리 상태 `MapClient.tsx:270-276`; PRD: “MapClient unmount 시 리셋된다. localStorage 미사용.” |
| AC-VD-22 | O | 후보 동시 추적/가까운 순 정렬 `useVisitDetection.ts:91-103`, 토스트 1개 가드 `MapClient.tsx:999-1005`, 차순위 테스트 `useVisitDetection.test.ts:244-285` |
| 합산 | O 19 / 부분 3 / X 0 | 총 22개 |

## 설계 범위 이탈

기준: 설계서 변경 범위는 `design.md:54-82`의 신규/수정 대상 파일 목록.

| 파일 경로 | 변경 요약 | 이탈 사유 추정 |
|---|---|---|
| `.dev/feat-phase-10-visit-detection/codemap.md` | 코드맵 산출물 추가 | 파이프라인 산출물 동반 커밋 |
| `.dev/feat-phase-10-visit-detection/design.md` | 설계 산출물 추가 | 파이프라인 산출물 동반 커밋 |
| `.dev/feat-phase-10-visit-detection/diff.txt` | 전체 diff 산출물 추가 | 검증용 산출물 동반 커밋 |
| `.dev/feat-phase-10-visit-detection/prd.md` | PRD 산출물 추가 | 파이프라인 산출물 동반 커밋 |
| `.dev/feat-phase-10-visit-detection/self-check.md` | 자기점검 산출물 추가 | 파이프라인 산출물 동반 커밋 |
| `.dev/feat-phase-10-visit-detection/state.md` | 작업 상태 산출물 추가 | 파이프라인 산출물 동반 커밋 |
| `.dev/feat-phase-10-visit-detection/trust-ledger.md` | Trust Ledger 산출물 추가 | 파이프라인 산출물 동반 커밋 |
| 기존 BE IT 다수: `BotLinkCodeServiceIT`, `GroupMemberServiceIT`, `PinMemoServiceIT`, `BotV1ControllerIntegrationTest` 등 | `notifications`/`notification_pins` cleanup 추가 | V009 `visit_pin_id` FK로 테스트 truncate 순서 보강 |
| `backend/.../domain/pin/PinServiceIT.java` | `updatePin(...).summary()` 반영, memo updatedBy 인자 반영 | `PinUpdateResult` 반환 타입 변경의 파급 수정 |
| `backend/.../domain/pin/PinTest.java`, `backend/.../infrastructure/pin/PinRepositoryIT.java` | `applyManualMemo(memo, userId)` 시그니처 반영 | 기존 메모 도메인 테스트 보정 |
| `context/notification/README.md`, `architecture.md`, `status.md` | VISIT_DETECTED 문서화 | 컨텍스트 문서 동기화 |
| `context/pin/glossary.md`, `status.md` | 방문 감지 용어/상태 문서화 | 컨텍스트 문서 동기화 |
| `frontend/src/app/map/_components/notifications/NotificationPanel.tsx` | `NotificationItem`에 `currentUserId` 전달 | `NotificationItem` prop 변경의 부모 보정이나 설계 범위 누락 |
| `frontend/vitest.setup.ts` | `matchMedia` polyfill 추가 | `VisitToast` 테스트 환경 보강이나 설계 범위 누락 |

## 신규 위험

- [Warning] [GAP] VISIT_DETECTED 알림 상세에 MEMORY 태그가 전달·표시되지 않는다.
  - 위치: `NotificationService.java:259-268`, `NotificationV1Dto.java:54-63`, `frontend/src/lib/notifications/types.ts:26-39`, `NotificationPinList.tsx:100-145`
  - 근거: PRD FR-VD-29 “응답에는 장소명, 주소, 태그, 현재 메모 ... 포함”, FR-VD-31 “장소명, 주소, 태그, 현재 메모를 표시”
  - 권고: `NotificationPinItemResult`/DTO/FE type에 `tag` 추가 후 VISIT_DETECTED 상세에서 MEMORY 배지 표시 및 테스트 추가.

- [Warning] [GAP] 주소가 null인 핀은 방문 토스트에서 주소가 표시되지 않는다.
  - 위치: `VisitToast.tsx:73-83`, `types.ts:26`, `PinV1Dto.java:104-106`
  - 근거: PRD FR-VD-10 “토스트에는 장소명과 주소를 표시한다. ... 주소 부줄.”
  - 권고: 주소 필수 보장 또는 null일 때 표시할 대체 문구/정책을 PRD에 명시.

- [Info] [ASSUMPTION] confetti 지속 시간 산출물 내부 기준이 상충한다.
  - 위치: `MapboxView.tsx:113`, `118-131`, `globals.css:200-214`
  - 근거: PRD FR-VD-15가 “200ms 내”와 “전체 애니메이션 지속 시간: 약 600ms”를 동시에 명시
  - 권고: AC의 200ms가 하트 fade-out 기준인지, 전체 연출 기준인지 PRD를 정리.

## references 위반

references/ 디렉토리 부재로 검증 대상 없음.

## 총평

- 강점: 프론트 감지 훅은 정확도 게이트, 30초 dwell, 세션 Set, 차순위 후보 추적을 설계에 가깝게 구현했다. 백엔드도 `PinUpdateResult`와 `REQUIRES_NEW` writer로 PATCH 응답 격리를 대체로 충족한다.
- Critical 0건, Warning 2건.
- 권고: AC-VD-20의 tag 누락을 우선 보강하고, AC-VD-6/9는 PRD 정책을 확정해 코드와 산출물을 맞추는 것이 좋다.
