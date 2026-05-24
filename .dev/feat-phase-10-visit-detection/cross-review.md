# Cross-Review 결과

- advisor: codex (GPT-5)
- 브랜치: feat/phase-10-visit-detection (base: develop)
- DEV_DIR: .dev/feat-phase-10-visit-detection
- 실행 시각: 2026-05-24
- 입력: PRD 22 AC + 설계 변경 범위 + Trust Ledger 11건 + self-check 5건 + codemap 12파일 + diff 4227줄

## AC 충족 매트릭스

| AC | 충족(O/X/부분) | 근거(파일:라인 또는 PRD 인용) |
|---|---|---|
| AC-VD-1 | O | `useVisitDetection.ts:72-103`, `MapClient.tsx:991-1009` |
| AC-VD-2 | O | `useVisitDetection.ts:72`; PRD: "MEMORY 태그 핀은 평가에서 제외한다." |
| AC-VD-3 | O | `useVisitDetection.ts:58-61`; PRD: "accuracy > 50m이면 ... 평가 전체를 스킵" |
| AC-VD-4 | O | `useVisitDetection.ts:83-88`, 세션 Set 추가는 `MapClient.tsx:1025-1029`, `1065`에만 존재 |
| AC-VD-5 | O | `MapClient.tsx:270-276`, `useVisitDetection.ts:73`, `MapClient.tsx:1027`, `1065` |
| AC-VD-6 | 부분 | PRD: "토스트에는 장소명과 주소를 표시한다." 코드: 장소명은 `VisitToast.tsx:71`, 주소는 `pin.address` 있을 때만 `VisitToast.tsx:73-83`; `PinSummaryResponse.address`는 nullable `types.ts:26` |
| AC-VD-7 | O | `MapClient.tsx:1025-1030` |
| AC-VD-8 | O | `MapClient.tsx:1042-1051`, `actions.ts:46-53` |
| AC-VD-9 | 부분 | 하트 3개/bounce/MEMORY 교체는 `MapboxView.tsx:104-114`, `88-89`, `420-424`; 단 PRD가 "200ms 내"와 "전체 ... 약 600ms"를 함께 명시하고 코드는 600ms `MapboxView.tsx:113`, `118-131` |
| AC-VD-10 | O | `MapClient.tsx:1068-1071` |
| AC-VD-11 | O | `VisitMemoSheet.tsx:61`, `75` |
| AC-VD-12 | O | `VisitMemoSheet.tsx:47-58`, `MapClient.tsx:1080-1091`, `actions.ts:74-90` |
| AC-VD-13 | O | `VisitMemoSheet.tsx:129-135`, `MapClient.tsx:1111-1114`; 태그 MEMORY 유지 `MapClient.tsx:1062-1071` |
| AC-VD-14 | O | `MapClient.tsx:1052-1060`, `1610-1630`; 세션 Set 추가는 성공 후 `1065` |
| AC-VD-15 | O | `MapClient.tsx:1084-1103`, `VisitMemoSheet.tsx:51-54`, `111-125` |
| AC-VD-16 | O | `MapboxView.tsx:565-575`; PRD: "비활성 중 ... geolocate 콜백이 발생하지 않으므로 훅 평가 자체 미실행" |
| AC-VD-17 | O | `PinService.java:256-258`, `PinV1Controller.java:129-132`, `NotificationService.java:94-100` |
| AC-VD-18 | O | `PinV1Controller.java:129-135`; PRD: "알림 실패 ... PATCH 응답에 영향을 주지 않는다." |
| AC-VD-19 | O | `V009__add_visit_detected_notification_type.sql:17-19`, `NotificationType.java:3-6` |
| AC-VD-20 | 부분 | PRD: "장소명, 주소, 태그, 현재 메모" 포함. 코드: 장소/주소/memo는 `NotificationService.java:206-208`; **tag는 `NotificationPinItemResult`, DTO, FE type에 없음** `NotificationService.java:259-268`, `NotificationV1Dto.java:54-63`, `types.ts:26-39` |
| AC-VD-21 | O | 세션 Set은 `useRef` 메모리 상태 `MapClient.tsx:270-276`; PRD: "MapClient unmount 시 리셋된다. localStorage 미사용." |
| AC-VD-22 | O | 후보 동시 추적/가까운 순 정렬 `useVisitDetection.ts:91-103`, 토스트 1개 가드 `MapClient.tsx:999-1005`, 차순위 테스트 `useVisitDetection.test.ts:244-285` |
| **합산** | **O 19 / 부분 3 / X 0** | **총 22개** |

## 설계 범위 이탈

기준: 설계서 변경 범위는 `design.md:54-82`의 신규/수정 대상 파일 목록.

| 파일 경로 | 변경 요약 | 이탈 사유 추정 |
|---|---|---|
| `.dev/feat-phase-10-visit-detection/*` (PRD/design/state 등) | 파이프라인 산출물 동반 커밋 | 정당 (dev 파이프라인 정책) |
| 기존 BE IT 다수: `BotLinkCodeServiceIT`, `GroupMemberServiceIT`, `PinMemoServiceIT`, `BotV1ControllerIntegrationTest` 등 | `notifications`/`notification_pins` cleanup 추가 | 정당 (V009 `visit_pin_id` FK로 truncate 순서 보강) |
| `PinServiceIT.java` | `updatePin(...).summary()` 반영 | 정당 (`PinUpdateResult` 반환 타입 변경의 파급) |
| `PinTest.java`, `PinRepositoryIT.java` | `applyManualMemo(memo, userId)` 시그니처 반영 | 정당 (기존 도메인 테스트 보정) |
| `context/notification/{README,architecture,status}.md`, `context/pin/{glossary,status}.md` | VISIT_DETECTED/방문 감지 문서화 | 정당 (컨텍스트 동기화) |
| `frontend/.../notifications/NotificationPanel.tsx` | `NotificationItem`에 `currentUserId` 전달 | 정당 (NotificationItem prop 변경의 부모 보정 — 설계 범위에 명시되지 않은 미세 보정) |
| `frontend/vitest.setup.ts` | `matchMedia` polyfill 추가 | 정당 (`VisitToast` 테스트 환경 보강 — 설계 범위에 명시되지 않은 인프라 보강) |

**판정**: 모든 이탈이 정당화 가능. 산출물 동반 / IT 보강 / 컨텍스트 동기화 / 부모 prop 보정 / 테스트 인프라 보강.

## 신규 위험

### Warning
- [Warning][GAP] **VISIT_DETECTED 알림 상세에 MEMORY 태그가 전달·표시되지 않는다**
  - 위치: `NotificationService.java:259-268`, `NotificationV1Dto.java:54-63`, `frontend/src/lib/notifications/types.ts:26-39`, `NotificationPinList.tsx:100-145`
  - 근거: PRD FR-VD-29 "응답에는 장소명, 주소, 태그, 현재 메모 ... 포함", FR-VD-31 "장소명, 주소, 태그, 현재 메모를 표시"
  - 권고: `NotificationPinItemResult` / DTO / FE type에 `tag` 필드 추가 후 VISIT_DETECTED 상세에서 MEMORY 배지 표시 + IT/단위 테스트 보강

- [Warning][GAP] **주소가 null인 핀은 방문 토스트에서 주소가 표시되지 않는다**
  - 위치: `VisitToast.tsx:73-83`, `types.ts:26`, `PinV1Dto.java:104-106`
  - 근거: PRD FR-VD-10 "토스트에는 장소명과 주소를 표시한다. ... 주소 부줄."
  - 권고: 주소 필수 보장 또는 null일 때 표시할 대체 문구/정책을 PRD에 명시

### Info
- [Info][ASSUMPTION] **confetti 지속 시간 산출물 내부 기준이 상충한다**
  - 위치: `MapboxView.tsx:113`, `118-131`, `globals.css:200-214`
  - 근거: PRD FR-VD-15가 "200ms 내"와 "전체 애니메이션 지속 시간: 약 600ms"를 동시에 명시
  - 권고: AC의 200ms가 하트 fade-out 기준인지, 전체 연출 기준인지 PRD를 정리

## references 위반

references/ 디렉토리 부재로 검증 대상 없음.

## 총평
- 강점: 프론트 감지 훅은 정확도 게이트, 30초 dwell, 세션 Set, 차순위 후보 추적을 설계에 가깝게 구현했다. 백엔드도 `PinUpdateResult`와 `REQUIRES_NEW` writer로 PATCH 응답 격리를 대체로 충족한다.
- 합산: Critical 0건, Warning 2건, Info 1건
- 권고: AC-VD-20의 tag 누락을 우선 보강하고, AC-VD-6/9는 PRD 정책을 확정해 코드와 산출물을 맞추는 것이 좋다.

## 처리 결과 (2026-05-24)

3건 모두 일괄 수정 완료 (BE/FE/PRD).

| # | 항목 | 처리 | 검증 |
|---|------|------|------|
| 1 | AC-VD-20 알림 상세 MEMORY 태그 누락 | `NotificationPinItemResult.tag` 필드 추가 + getDetail에서 `pin.getTag().name()` 전달 + `NotificationV1Dto.PinItem.tag` 추가 + FE `NotificationPinItem.tag` 추가 + `NotificationPinList.tsx`에 "● 추억" 핑크 inline 배지 추가 + IT (h) 케이스에 MEMORY tag 검증 추가 | BE IT 5/5 + FE 162/162 + 빌드 PASS |
| 2 | AC-VD-6 주소 null 정책 | PRD FR-VD-10 + AC-VD-6에 "주소 null/빈 문자열은 라인 생략" 명시. 코드 변경 없음 (현행 정합) | — |
| 3 | AC-VD-9 PRD 시간 단위 명료화 | PRD FR-VD-15 본문 재구성: 하트 fade-out 200ms / 마커 bounce 600ms / 전체 종료 600ms 기준 명시. AC-VD-9에 별개 기준 명시. 코드 변경 없음 | — |

배지 색상 결정: `#FFB3C6` (PIN_COLORS.memory 동일) inline span. PinTag 컴포넌트는 알림 리스트 12px 행에 비해 과하여 단순 span으로 처리.
