<task>
oh-my-gx 파이프라인 산출물(PRD/설계/Trust Ledger)과 변경 코드를 교차 검증한다.
변경된 코드가 산출물의 약속을 충족하는지, 산출물에 정의되지 않은 신규 위험이 있는지 보고한다.

diff 파일: .dev/feat-phase-2-9-scale/diff.txt
이 파일은 --stat 요약만 포함되어 있다. 변경된 20개 파일을 Read 도구로 직접 확인하라.

주요 변경 파일 (코드):
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinListResult.java (신규)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinRepository.java
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinService.java
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/pin/PinJpaRepository.java
- backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/pin/PinRepositoryImpl.java
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1ApiSpec.java
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Controller.java
- backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/pin/PinV1Dto.java
- backend/apps/wherewego-api/src/main/java/com/wherewego/support/error/ErrorType.java
- frontend/src/lib/api/pin.ts
- frontend/src/lib/api/types.ts

테스트:
- backend/apps/wherewego-api/src/test/java/com/wherewego/domain/pin/PinServiceIT.java
- backend/apps/wherewego-api/src/test/java/com/wherewego/interfaces/api/pin/PinV1ControllerIntegrationTest.java

문서:
- context/map/gl-migration-plan.md (신규)
- context/map/architecture.md, status.md
- context/pin/architecture.md, glossary.md, status.md

기준 브랜치: develop. 작업 브랜치: feat/phase-2-9-scale. 합산 3 커밋 (d99410e, ca71a26, 69efd1e).
</task>

<grounding_rules>
- 모든 지적은 PRD 또는 설계서의 정확한 인용으로 근거를 제시한다.
- trust-ledger.md에 이미 보고된 항목은 보고하지 않는다 (중복 금지).
- self-check.md의 Warning/Info는 중복 보고하지 않는다.
- 코드를 직접 확인하지 못한 추정은 ASSUMPTION으로 분리한다.
- PRD 자체가 코드와 일치하지 않을 가능성이 의심되면 ASSUMPTION으로 분류한다.
</grounding_rules>

<structured_output_contract>
다음 4개 섹션을 정확히 이 순서로 출력한다:

## AC 충족 매트릭스
| AC | 충족 | 근거 (파일:라인 또는 PRD 인용) |
|----|------|--------|
| AC-1 | O/X/부분 | ... |

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

## 총평
- 강점 1-2개
- Critical/Warning 합산
- 권고 사항 1줄

(references/ 디렉토리는 없으므로 references 위반 섹션은 생략한다)
</structured_output_contract>

<language>
모든 출력은 한국어로 작성한다. 영어 단어는 고유명사·기술 용어에 한해 허용한다.
</language>

<artifacts>

### PRD 수용 기준 (§4)

#### 페이지네이션 API [Must]

AC-1: GET /api/v1/groups/{groupId}/pins?page=0&size=20 호출 시 응답이 { items, totalCount, hasNext } 구조를 반환하고, items의 건수는 최대 20건이다.

AC-2: page/size 파라미터 없이 GET /api/v1/groups/{groupId}/pins 호출 시 기존과 동일한 { items } 구조로 전체 목록이 반환되고, totalCount/hasNext 필드는 응답에 포함되지 않는다.

AC-3: size=101 요청 시 400 응답이 반환된다 (PIN_PAGE_SIZE_EXCEEDED).

AC-4: page=-1 또는 size=0 요청 시 400 응답이 반환된다. 추가로 page만 전달 / size만 전달 시에도 동일하게 400 PIN_PAGE_PARAM_INVALID.

AC-5: tag=PLACE&page=0&size=10 요청 시 PLACE 태그 핀만 최대 10건 반환되고, totalCount는 해당 그룹의 PLACE 태그 전체 수를 반영한다.

AC-6: page=0&size=20으로 조회 후 hasNext: true이면, page=1&size=20 요청이 다음 20건을 반환하고 결과가 중복되지 않는다. totalCount 페이지 간 일치.

#### 하위 호환성 [Must]

AC-7: MapClient의 룰렛 stale 재조회 코드(apiFetch<PinListResponse>('/groups/{groupId}/pins'))가 파라미터 변경 없이 기존과 동일한 { items } 응답을 받고, pool = res.items 참조가 정상 동작한다.

AC-8: 기존 listPins(groupId, tag?) 호출부 및 /pins 초기 서버 fetch가 코드 변경 없이 동작하고, 반환 데이터가 이전과 동일하다.

#### Phase 2.8 회귀 방지 [Must]

AC-9: PinDot(PLACE 파란 동그라미 #7BB3E8, MEMORY 핑크 하트 #F4A8B0), PinTag 칩, SpeechBubblePopup의 시각적 표현이 Phase 2.8 완료 시점과 동일하다.

AC-10: Phase 2.8 AC 1~17 항목이 이번 변경 후에도 모두 충족된다.

AC-11: useOptimistic patch|remove reducer, supercluster 클러스터링, PinPopup 말풍선 좌표 계산이 정상 동작한다.

#### GL 마이그레이션 사전 분석 [Should]

AC-12: context/map/ 문서에 GL symbol layer 전환 시 변경되는 3개 항목(① PinPopup 좌표 계산 방식, ② useOptimistic 패치 방법, ③ supercluster + GL layer 클러스터 클릭 핸들러)이 각각 현재 방식과 전환 후 방식의 대비와 함께 기록된다.

#### NFR

NFR-1: size=20 기준 페이지네이션 응답 시간이 기존 전체 조회 대비 동일 건수 기준으로 크게 증가하지 않는다.
NFR-2: 페이지네이션 API는 기존 클라이언트와 동시에 운영되는 배포 지원.
NFR-3: 잘못된 파라미터 에러 응답이 기존 CoreException/ApiResponse 오류 포맷을 따른다.

---

### 설계서 변경 범위 (§1) + 구현 순서 (§6)

#### 1. 변경 범위 요약

신규 파일 2개:
- backend/.../domain/pin/PinListResult.java
- context/map/gl-migration-plan.md

수정 파일 9개 (백엔드 7, 프론트 2):
- backend/.../domain/pin/PinService.java
- backend/.../domain/pin/PinRepository.java
- backend/.../infrastructure/pin/PinJpaRepository.java
- backend/.../infrastructure/pin/PinRepositoryImpl.java
- backend/.../interfaces/api/pin/PinV1Controller.java
- backend/.../interfaces/api/pin/PinV1Dto.java
- backend/.../interfaces/api/pin/PinV1ApiSpec.java
- backend/.../support/error/ErrorType.java
- frontend/src/lib/api/types.ts
- frontend/src/lib/api/pin.ts

문서 3건:
- context/map/gl-migration-plan.md (신규)
- context/map/architecture.md (테이블 행 추가)
- context/map/status.md (cross-link)

테스트 2건:
- PinV1ControllerIntegrationTest.java
- PinServiceIT.java

#### 6. 구현 순서 (9단계 / 5배치)

B1 — 백엔드 기반 타입 (병렬)
  단계 1: ErrorType + PinListResult + PinRepository 포트
  단계 2: GL 마이그레이션 문서 패키지 (gl-migration-plan.md, architecture.md, status.md)

B2 — 인프라 어댑터
  단계 3: PinJpaRepository + PinRepositoryImpl

B3 — 도메인 서비스
  단계 4: PinService.listGroupPinsPaged

B4 — 인터페이스 레이어
  단계 5: PinV1Dto + PinV1ApiSpec
  단계 6: PinV1Controller (page/size 분기, 부분 전달 400)

B5 — 프론트 + 테스트 + 빌드 (병렬)
  단계 7: types.ts + pin.ts (FR-7)
  단계 8: 백엔드 테스트 (AC-0~6 + Service)
  단계 9: 빌드 검증

#### 핵심 설계 결정

- 두 모드 메서드: 기존 listGroupPins 유지 + 신규 listGroupPinsPaged
- Pageable 미도입 (도메인 포트 순수성). PinRepository에 int page,size + long count* (4개 메서드)
- PinListResponse 단일 record + JacksonConfig 전역 NON_NULL (review에서 record 레벨 @JsonInclude 제거)
- 부분 전달은 PIN_PAGE_PARAM_INVALID, size>100은 PIN_PAGE_SIZE_EXCEEDED
- hasNext = (long)(page + 1) * size < totalCount (오버플로 방지)
- 프론트 listPins(groupId, optionsOrTag?: ListPinsOptions | PinTag) 유니온
- gl-migration-plan.md: 3개 항목 + disclaimer + status.md 양방향 cross-link

---

### 기존 Trust Ledger (이미 보고된 항목, 중복 금지)

phase-review 라운드 1에서 합산된 결과:

Critical / CRITICAL: 0건

HIGH (수정 권고):
- AC-6 totalCount 단언 누락 → 수정됨 (PinV1ControllerIntegrationTest)
- ListPinsOptions page-단독 전달 위험 → 수정됨 (runtime check 추가)

Medium:
- MethodArgumentTypeMismatchException 경유 BAD_REQUEST → 수정됨 (Integer→String 파싱)
- listGroupPinsPaged 비멤버 차단 테스트 누락 → 추가됨
- gl-migration-plan.md 권한 검증 disclaimer → 추가됨

Warning:
- PinListResult primitive vs DTO 박싱 불일치 → Javadoc으로 명시
- PinRepositoryImpl PageRequest.of Sort 미지정 → Sort.by(DESC, "createdAt") 명시

Info:
- containsExactly("items") 단언 견고성 → containsExactlyInAnyOrder로 교체

QUESTION (사용자 답변으로 수렴):
- Q1: seedTwentyFivePins Thread.sleep(1) 추가
- Q2: record 레벨 @JsonInclude(NON_NULL) 제거 (JacksonConfig 전역 NON_NULL 활용)

이 항목들은 모두 이미 적용되었으니 재보고 금지.

---

### self-check 발견 사항 (중복 금지)

phase-implement 자기점검 (Critical 0, Warning 1, Info 2, QUESTION 3) — 모두 trust-ledger와 동일하거나 그곳에서 처리됨. 재보고 금지.

---

### 코드 맵 — 핵심 파일

#### ① GL symbol layer 마이그레이션 (사전 분석만, 실코딩 X)
- frontend/src/app/map/_components/MapboxView.tsx (DOM Marker)
- frontend/src/app/map/MapClient.tsx (룰렛 호출부 — AC-7 회귀 위험 1순위)
- frontend/src/app/map/_components/PinPopup.tsx (map.project 좌표 계산)
- frontend/src/app/map/_lib/clusterer.ts (supercluster)
- frontend/src/app/map/_components/PinDot.tsx

#### ② 핀 페이지네이션 (실구현)
- backend/.../domain/pin/PinService.java
- backend/.../domain/pin/PinRepository.java
- backend/.../infrastructure/pin/PinJpaRepository.java
- backend/.../infrastructure/pin/PinRepositoryImpl.java
- backend/.../interfaces/api/pin/PinV1Controller.java
- backend/.../interfaces/api/pin/PinV1Dto.java
- frontend/src/app/pins/PinListClient.tsx (변경 없음, AC-8 검증 대상)
- frontend/src/lib/api/pin.ts

#### 회귀 검증 1순위
- frontend/src/app/map/MapClient.tsx:353 — 룰렛 stale 재조회 `apiFetch<PinListResponse>('/groups/${groupId}/pins')`
- frontend/src/app/pins/page.tsx:15 — `listPins(group.groupId)` 1-인자 호출

#### 설정
- backend/apps/wherewego-api/src/main/resources/application.yml
- backend/supports/jackson/.../JacksonConfig.java:24 — 전역 serializationInclusion(NON_NULL)

---

### 주요 컨벤션 참조

- 컨트롤러 명시 검증 컨벤션: PlaceV1Controller.search:28-30 (if (...) throw new CoreException(...))
- @JsonInclude(NON_NULL) record 사례: ChatbotV1Dto.java
- Pageable 인프라 단독 사용: GroupMemberJpaRepository:17 + GroupMemberRepositoryImpl:20
- 글로벌 에러 포맷: ApiResponse.java + ApiControllerAdvice.java
- 통합 테스트 setup: PinV1ControllerIntegrationTest (TestRestTemplate + JwtTokenProvider)

</artifacts>
