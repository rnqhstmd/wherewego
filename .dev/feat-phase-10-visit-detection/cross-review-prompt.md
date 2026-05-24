<task>
ttutak 파이프라인 산출물(PRD/설계/Trust Ledger)과 변경 코드를 교차 검증한다.
변경된 코드가 산출물의 약속을 충족하는지, 산출물에 정의되지 않은 신규 위험이 있는지 보고한다.

diff 파일: /Users/bonseung/projects/wherewego/.dev/feat-phase-10-visit-detection/diff.txt
이 파일을 Read하여 변경사항을 확인한다. (4227줄, develop merge-base 대비 전체 diff)

브랜치: feat/phase-10-visit-detection
베이스: develop
프로젝트: Spring Boot(BE) + Next.js 15 App Router(FE)
</task>

<grounding_rules>
- 모든 지적은 PRD 또는 설계서의 정확한 인용으로 근거를 제시한다.
- trust-ledger.md에 이미 보고된 항목은 보고하지 않는다 (중복 금지).
- self-check.md의 Warning/Info는 중복 보고하지 않는다.
- 코드를 직접 확인하지 못한 추정은 ASSUMPTION으로 분리한다.
- PRD 자체가 코드와 일치하지 않을 가능성이 의심되면 ASSUMPTION으로 분류한다.
</grounding_rules>

<structured_output_contract>
다음 5개 섹션을 정확히 이 순서로 출력한다 (references 위반은 references/가 없으므로 본문에 "references/ 디렉토리 부재로 검증 대상 없음"으로 적되 섹션 헤더는 유지):

## AC 충족 매트릭스
표 형식. 컬럼: AC | 충족(O/X/부분) | 근거(파일:라인 또는 PRD 인용).
22개 모두 평가. 합산 라인 추가.

## 설계 범위 이탈
설계서의 "변경 범위"에 명시되지 않은 파일 수정 목록.
항목별로: 파일 경로 / 변경 요약 / 이탈 사유 추정.
없으면 "이탈 없음".

## 신규 위험
trust-ledger.md에 없는 신규 risk/policy/gap/assumption만.
- [Critical/Warning/Info] [RISK/POLICY/GAP/ASSUMPTION] 항목 설명
  - 위치: 파일:라인
  - 근거: ...
  - 권고: ...

## references 위반
references/ 디렉토리 부재로 검증 대상 없음.

## 총평
- 강점 1-2개
- Critical/Warning 합산
- 권고 사항 1줄
</structured_output_contract>

<language>
모든 출력은 한국어로 작성한다. 영어 단어는 고유명사·기술 용어에 한해 허용한다.
</language>

<artifacts>

### PRD 수용 기준 (Phase 10 — 장소 방문 감지)

| # | 수용 기준 | 대응 |
|---|-----------|------|
| AC-VD-1 | WISH·REEL 핀에서 100m 이내에 30초 이상 머물면 방문 토스트가 표시된다 | FR-VD-7 |
| AC-VD-2 | MEMORY 핀은 100m 이내에 있어도 토스트가 표시되지 않는다 | FR-VD-2, BR-VD-7 |
| AC-VD-3 | GPS 정확도가 50m를 초과하는 이벤트에서는 근접 판정 평가가 실행되지 않는다 | FR-VD-3, BR-VD-3 |
| AC-VD-4 | 30초 이전에 100m 밖으로 이탈하면 토스트가 표시되지 않으며 세션 Set에도 추가되지 않는다 | FR-VD-8 |
| AC-VD-5 | 동일 세션에서 동일 pinId의 토스트는 1회만 표시된다 | FR-VD-5, FR-VD-12 |
| AC-VD-6 | 토스트에 장소명(📍 {장소명} 근처에 계신가요?)과 주소가 표시된다 | FR-VD-10 |
| AC-VD-7 | "다음에 올게요" 클릭 시 토스트가 닫히고 세션 Set에 pinId가 추가된다 | FR-VD-12 |
| AC-VD-8 | "네, 다녀왔어요 →" 클릭 시 즉시 태그 PATCH가 발사된다 | FR-VD-14 |
| AC-VD-9 | 태그 PATCH 성공 시 마커에서 하트 confetti(3개, fan-out, ~200ms) + scale bounce가 동시에 실행되며 마커 아이콘이 MEMORY로 교체된다 | FR-VD-15 |
| AC-VD-10 | 태그 PATCH 성공 시 confetti와 동시에 메모 시트가 슬라이드 업된다 | FR-VD-16 |
| AC-VD-11 | 메모 시트에 "✓ {장소명}, 다녀왔어요!" + YYYY년 M월 D일 형식의 날짜가 표시된다 | FR-VD-17 |
| AC-VD-12 | 메모 입력 후 "저장" 클릭 시 메모 PATCH가 발사되고 성공 시 시트가 닫힌다 | FR-VD-19 |
| AC-VD-13 | "건너뛰기" 클릭 시 메모 PATCH가 발사되지 않고 시트가 닫힌다. 태그는 MEMORY 상태를 유지한다 | FR-VD-20 |
| AC-VD-14 | 태그 PATCH 실패 시 메모 시트가 열리지 않고 인라인 에러 토스트가 표시된다. pinId는 세션 Set에 추가되지 않는다 | FR-VD-21 |
| AC-VD-15 | 메모 PATCH 실패 시 시트 내 인라인 에러가 표시되며 시트가 유지된다 | FR-VD-22 |
| AC-VD-16 | GeolocateControl 비활성 상태에서는 방문 감지 평가가 실행되지 않는다 | FR-VD-23 |
| AC-VD-17 | WISH/REEL → MEMORY 태그 전환 성공 시 짝꿍에게 VISIT_DETECTED 알림이 생성된다 (메모 작성 여부 무관) | FR-VD-28 |
| AC-VD-18 | VISIT_DETECTED 알림 생성 실패가 태그 PATCH API 응답에 영향을 주지 않는다 | FR-VD-28, BR-VD-6 |
| AC-VD-19 | V009 마이그레이션 적용 후 notifications 테이블에 VISIT_DETECTED 값 insert가 성공한다 | FR-VD-26 |
| AC-VD-20 | 알림 상세에서 VISIT_DETECTED 알림 클릭 시 장소명, 주소, MEMORY 태그, 현재 메모(없으면 비움)가 표시된다 | FR-VD-29, FR-VD-31 |
| AC-VD-21 | MapClient 페이지 unmount 시 세션 Set이 소멸하여, 재방문 시 동일 핀에 대해 방문 감지가 재시작된다 | FR-VD-5, BR-VD-5 |
| AC-VD-22 | 동시에 100m 이내에 2개 이상의 WISH·REEL 핀이 있을 때, 토스트는 1개만 표시되며 닫힌 후 다음 가까운 핀에 대한 평가가 자동 재개된다 | FR-VD-13 |

핵심 기능 요구사항 발췌:
- FR-VD-3: position.coords.accuracy > 50m이면 해당 GPS 이벤트의 근접 판정 평가 전체를 스킵한다.
- FR-VD-7: 30초 경과한 시점의 GPS 이벤트에서도 100m 이내로 확인되면 방문 감지를 확정하고 토스트를 노출한다.
- FR-VD-14: "네, 다녀왔어요 →" 클릭 시 즉시 1차 PATCH를 발사한다. body { "tag": "MEMORY" }.
- FR-VD-15: 1차 PATCH 성공 시 마커 confetti 애니메이션을 실행한다 (하트 3개 fan-out + scale bounce, ~600ms).
- FR-VD-16: 1차 PATCH 성공 후 confetti 애니메이션 시작과 함께 VisitMemoSheet를 슬라이드 업으로 표시한다.
- FR-VD-19: "저장" 클릭 시 2차 PATCH를 발사한다. body { "memo": "{입력값}" }.
- FR-VD-20: "건너뛰기" 클릭 시 2차 PATCH를 발사하지 않고 시트를 닫는다.
- FR-VD-21: 1차 PATCH 실패 시 토스트를 닫고 인라인 에러 토스트(시스템 레벨)를 표시한다. pinId 세션 Set 미추가.
- FR-VD-22: 2차 PATCH 실패 시 시트 내 인라인 에러를 표시하고 재시도 가능 상태를 유지한다.
- FR-VD-26: V009 마이그레이션으로 CHECK 제약을 ('MANUAL_PIN','CHATBOT_PINS','VISIT_DETECTED')로 확장한다.
- FR-VD-27: 활성 멤버 전원(등록자 본인 포함)에게 Notification 1행 + NotificationPin 1행을 생성한다. 본인 포함은 Phase 11 도입 전 과도기 용도. 트랜잭션은 receiver 단위 REQUIRES_NEW로 처리하며, 부분 UNIQUE 인덱스 uq_notifications_visit 위반은 try-catch로 조용히 스킵.
- FR-VD-28: PinV1Controller의 PATCH /pins/{id} 핸들러에서 태그가 WISH/REEL → MEMORY로 변경되는 케이스에 한해, try-catch로 NotificationService.createForVisitDetected를 호출한다.
- FR-VD-29: 알림 상세 응답에 장소명, 주소, 태그, 현재 메모(항상 최신값 join)를 포함한다.
- FR-VD-30: NotificationItem에서 등록자가 본인이면 "내가 다녀온 장소", 짝꿍이면 "{닉네임}님이 다녀온 장소"로 라벨링.

비즈니스 규칙:
- BR-VD-1: 감지 반경 100m (Haversine, 프론트 계산).
- BR-VD-2: 매 geolocate 이벤트마다 평가, throttle 없음.
- BR-VD-3: accuracy ≤ 50m 이벤트만 근접 판정. 50m 초과는 머무름 타이머에도 영향 없음.
- BR-VD-4: 30초 머무름은 "첫 진입 시각 기록 → 이후 콜백에서 시간 차이 계산". setInterval 미사용.
- BR-VD-5: 세션 Set은 메모리만. MapClient unmount 시 자동 소멸. 페이지 재방문 시 재시작.
- BR-VD-6: 알림 실패가 PATCH 응답에 영향 없음. try-catch 격리.
- BR-VD-7: MEMORY 핀은 감지 대상 제외. 전환 직후 세션 Set 추가.
- BR-VD-8: "다음에 올게요" 후 세션 Set 추가. 차순위 후보 자동 평가.

---

### 설계서 변경 범위 (Phase 10 — 2회차)

**신규 파일 (백엔드 4개):**
- backend/apps/wherewego-api/src/main/resources/db/migration/V009__add_visit_detected_notification_type.sql
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/notification/NotificationVisitWriter.java
- backend/apps/wherewego-api/src/test/java/com/wherewego/domain/notification/NotificationServiceVisitDetectedIT.java
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinUpdateResult.java

**신규 파일 (프론트엔드 6개):**
- frontend/src/app/map/_hooks/useVisitDetection.ts
- frontend/src/app/map/_components/VisitToast.tsx
- frontend/src/app/map/_components/VisitMemoSheet.tsx
- frontend/src/app/map/_hooks/useVisitDetection.test.ts
- frontend/src/app/map/_components/VisitToast.test.tsx
- frontend/src/app/map/_components/VisitMemoSheet.test.tsx

**수정 대상 파일 (백엔드 5개):**
- domain/notification/NotificationType.java (VISIT_DETECTED 추가)
- domain/notification/Notification.java (visitPinId 컬럼 + createForVisit 팩토리)
- domain/notification/NotificationService.java (createForVisitDetected + getDetail memo join + NotificationPinItemResult.memo)
- interfaces/api/notification/NotificationV1Dto.java (PinItem.memo 추가)
- domain/pin/PinService.java (updatePin 반환 타입 PinUpdateResult로 변경)
- interfaces/api/pin/PinV1Controller.java (try-catch 알림 호출)

**수정 대상 파일 (프론트엔드 5개):**
- _components/MapboxView.tsx (forwardRef + triggerVisitCelebration ref API + onGeolocate prop + confetti)
- MapClient.tsx (useVisitDetection 연결, VisitToast/VisitMemoSheet 조건부 렌더)
- _components/notifications/NotificationPinList.tsx (VISIT_DETECTED 메모 줄 + 동사 분기)
- _components/notifications/NotificationItem.tsx (VISIT_DETECTED 분기, 본인이면 "내가 다녀온 장소")
- lib/notifications/types.ts (NotificationType union 확장, NotificationPinItem.memo 추가)
- app/globals.css (confetti/bounce keyframes 추가)

### 구현 순서 (15단계)

1. V009 마이그레이션 + 로컬 Flyway 적용 확인
2. NotificationType + Notification 엔티티 (visitPinId 매핑)
3. NotificationVisitWriter + NotificationService.createForVisitDetected
4. NotificationService.getDetail memo join + NotificationPinItemResult/PinItem DTO memo 필드
5. PinUpdateResult record + PinService.updatePin 시그니처 변경
6. PinV1Controller.updatePin VISIT_DETECTED 알림 호출
7. NotificationServiceVisitDetectedIT
8. frontend types.ts 확장
9. useVisitDetection 훅 + 단위 테스트
10. VisitToast / VisitMemoSheet 컴포넌트 + 테스트
11. MapboxView forwardRef + triggerVisitCelebration imperative API + confetti/bounce + globals.css keyframes
12. MapClient 통합
13. NotificationItem VISIT_DETECTED 카피 분기
14. NotificationPinList 메모 표시 (VISIT_DETECTED 한정)
15. 수동 시나리오 검증

---

### 기존 Trust Ledger (이미 보고된 항목, 중복 금지)

#### CRITICAL / Critical(QA)
- 0건.

#### HIGH (security-auditor)
- [HIGH/인증-인가] NotificationService.createForVisitDetected 내부 멤버십 재검증 부재 → JavaDoc에 호출자 계약 명시로 해결
- [HIGH/알림-팬아웃] NotificationVisitWriter REQUIRES_NEW + DataIntegrityViolationException 이중 catch → 검증 결과 위험 없음, INFO 수준 재분류
- [HIGH/데이터-무결성] visit_pin_id FK ON DELETE 정책 미명시 → V009에 ON DELETE RESTRICT 명시로 해결

#### MEDIUM (security-auditor)
- [MEDIUM/입력검증] UpdatePinRequest memo 500자 검증 레이어 불일치
- [MEDIUM/개인정보] handleVisitConfirm 실패 시 console.error에 groupId, pinId 노출
- [MEDIUM/DoS] shownPinIdsRef Set 누적 상한 없음 (MVP 규모에서 무위험)
- [MEDIUM/트랜잭션] writeOne 반환 타입 설계서(boolean) vs 구현(void) 불일치
- [MEDIUM/XSS] React JSX 자동 escape 활용 확인 (안전)
- [MEDIUM/CSRF] JWT stateless API + csrf.disable 기존 정책 유지 (안전)

#### Critical 외 QA Warning
- [Warning/SPEC] MapClient.tsx:1044~1047 — AC-VD-14 미충족 → 5건 보강에서 visitErrorMessage state + 1.5초 자동 닫힘으로 해결
- [Warning/MAINT] NotificationVisitWriter visit_pin_id와 notification_pins 이중 저장 (의도된 분담)
- [Warning/PERF] runMarkerBounceAndConfetti 600ms setTimeout 안 detached node 조작 위험 (가드 있음)

#### QUESTION
- [QUESTION-Q1] AC-VD-14 → 인라인 에러 토스트 추가 구현 결정
- [QUESTION-Q2] NotificationPinList 동사 분기 텍스트 확인
- [QUESTION-Q3] Controller IT 3건 (c/d/g) → 별도 이슈로 분리 결정
- [QUESTION-Q4] useVisitDetection accuracy 보존 정책 → BR-VD-3 명시 부합으로 Accept
- [QUESTION-Q5] (e)/(f) 테스트 실효성 → Accept
- [QUESTION-Q6] visit-memo 시트 × 닫기 UX → Accept
- [QUESTION-Q7] writeOne void vs boolean → 실제 구현이 안전

#### ZT INFO
- [INFO/알림] VISIT_DETECTED 본인에게도 알림 발송 → PRD FR-VD-27 수정으로 정합

### 자기점검 발견 사항 (중복 금지)

Critical 0건, Warning 3건 (위 Trust Ledger와 동일), Info 2건:
- [Info] NotificationService.loadPinsByIds N 쿼리 (인지된 부채)
- [Info] VisitMemoSheet 날짜 fonts.mono 사용 (디자인 의도 가능)

---

### 코드 맵 (탐색 가이드)

**핵심 파일:**
- frontend/src/app/map/MapClient.tsx → Phase 10 통합 지점 (훅 연결, VisitToast/VisitMemoSheet 조건부 렌더, marker 전환 트리거)
- frontend/src/app/map/_components/MapboxView.tsx:435-462 → GeolocateControl + geo.on("geolocate") 콜백, 자체 user-location-marker. confetti 진입점
- backend/.../domain/notification/NotificationType.java → enum에 VISIT_DETECTED 추가. DB CHECK 동시 확장
- backend/.../domain/notification/NotificationService.java → createForVisitDetected 신규
- backend/.../domain/pin/Pin.java → applyTag / applyManualMemo (재사용)

**참조 파일:**
- frontend/src/app/map/_hooks/useGeolocation.ts → 기존 watchPosition 패턴
- frontend/src/app/map/_components/PinCoordinateEditPicker.tsx → 시트 컨벤션 (VisitMemoSheet 참조)
- frontend/src/app/map/actions.ts → pin PATCH 서버 액션 진입점
- backend/.../interfaces/api/pin/PinV1Controller.java → PATCH 핸들러 + Phase 8 MANUAL_PIN 알림 호출 try-catch 패턴
- frontend/src/app/map/_components/notifications/NotificationItem.tsx → 알림 항목 표시 (VISIT_DETECTED 분기)
- frontend/src/app/map/_components/notifications/NotificationPanel.tsx → 알림 상세 표시
- frontend/src/app/map/_components/notifications/NotificationPinList.tsx → 핀 목록 렌더링

**설정:**
- backend/.../db/migration/V009__add_visit_detected_notification_type.sql → CHECK 확장 + visit_pin_id + 부분 UNIQUE 인덱스 + ON DELETE RESTRICT
- context/pin/phase-10-visit-detection.md → PRD 베이스
- context/notification/architecture.md → 알림 도메인 아키텍처

---

### references (외부 표준)

references/ 디렉토리 부재. 검증 대상 없음.

</artifacts>
