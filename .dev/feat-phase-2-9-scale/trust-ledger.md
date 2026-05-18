# Trust Ledger — Phase 2.9 규모 대응

> phase-review 통합 감사 결과. QA(qa-manager) + ZT(security-auditor) 병렬 검토 합산.

## 통합 감사 (review)

### Critical / CRITICAL
- **0건** — 즉시 수정 필요 사항 없음.

### High (수정 권고)

- **[HIGH/GAP] AC-6 테스트: `totalCount` 대비 합집합 단언 누락**
  - 위치: `backend/.../PinV1ControllerIntegrationTest.java:682-688`
  - 근거: 설계서 §5 영역 A AC-6 단언 요건은 "id 집합 disjoint + 합집합 크기가 totalCount와 일치". 현재 코드는 disjoint 검증 + `union.size() == firstIds.size() + secondIds.size()` (2 페이지 합)만 수행. totalCount(=25)와의 비교 단언 누락.
  - 권고: 3페이지 전체 합산 후 `totalCount`와 비교하는 케이스 추가 또는 AC-6 단언 의도를 totalCount 기반으로 재정의.

- **[HIGH/RISK] `ListPinsOptions` 타입: `{ page: 0 }` 단독 전달 허용으로 런타임 400 위험**
  - 위치: `frontend/src/lib/api/pin.ts`, `frontend/src/lib/api/types.ts`
  - 근거: TypeScript optional 필드 `page?`/`size?`가 한쪽만 전달 가능. 백엔드 Q3 분기 규칙에 따라 부분 전달은 400 `PIN_PAGE_PARAM_INVALID`. 현재 호출부는 영향 없으나 미래 호출부가 `{ page: 0 }` 단독 전달 시 런타임 오류.
  - 권고: `ListPinsOptions`를 `({ page: number; size: number; tag?: PinTag } | { tag?: PinTag })` 유니온으로 강제하거나 함수 진입부에서 `page !== undefined && size === undefined` runtime check.

### Medium

- **[MEDIUM/RISK] `MethodArgumentTypeMismatchException` 경유 시 에러 코드 비일관 (NFR-3)**
  - 위치: `PinV1Controller.java:55`, `ApiControllerAdvice.java:36-42`
  - 근거: `page=abc` 같은 비숫자 입력 시 Spring MVC가 `Integer` 변환 실패로 `MethodArgumentTypeMismatchException` → 제네릭 `BAD_REQUEST`. NFR-3 일관성과 충돌. 통합 테스트 AC-4에 비숫자 케이스 없음.
  - 권고: `page`/`size`를 `String`으로 수신 후 명시 파싱하거나, `MethodArgumentTypeMismatchException` 핸들러에서 파라미터명 기반 `PIN_PAGE_PARAM_INVALID` 매핑.

- **[MEDIUM/GAP] `listGroupPinsPaged` 비멤버 접근 차단 회귀 테스트 누락**
  - 위치: `PinServiceIT.java:417-478`
  - 근거: 기존 `listGroupPins`에는 `listGroupPins_nonMember_throwsGroupNotMember` 존재. 신규 `listGroupPinsPaged`에는 동일 단언 없음. 로직상 `requireActiveMembership`이 선행되나 회귀 가드 부재.
  - 권고: `listGroupPinsPaged_nonMember_throwsGroupNotMember` 테스트 추가.

- **[MEDIUM/GAP] GL 마이그레이션 문서: 권한 검증 우회 위험 분석 부재**
  - 위치: `context/map/gl-migration-plan.md:61-67`
  - 근거: GL 전환 시 클릭 핸들러가 feature property에서 pinId를 읽는 방식으로 변경. 클라이언트 측 pinId 위조 가능성 언급 없음 (실제 위험은 백엔드 인증이 차단).
  - 권고: "GL 전환 후에도 핀 조작 권한 검증은 백엔드 `requireActiveMembership`이 담당하며 클라이언트 feature 위조는 서버 인증을 우회하지 못함" 한 줄 명시.

### Warning (QA)

- **[Warning/QA] `PinListResult.java:5` — primitive 타입 사용으로 설계서 박싱 의도와 불일치**
  - 근거: `PinListResult`는 `long totalCount, boolean hasNext` (원시 타입). `PinListResponse`의 `Long, Boolean` 박싱과 비대칭. 도메인 record가 페이지 모드 전용이라 null이 없으므로 기능 동작은 정상.
  - 권고: Javadoc으로 "페이지 모드 전용, 항상 유의미한 값" 명시.

- **[Warning/QA] `PinRepositoryImpl.java:51,56` — `PageRequest.of(page, size)` Sort 명시 누락**
  - 근거: Sort가 메서드명 `OrderByCreatedAtDesc` 파싱에 의존. 메서드명 변경/JPQL 전환 시 정렬 소거 위험.
  - 권고: `PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))`로 명시화. `org.springframework.data.domain.Sort` import 추가.

### Info / Low

- **[Info/QA] `PinV1ControllerIntegrationTest.java:540` — `containsExactly("items")` 단언 견고성**
  - 단일 원소라 현재는 동일하나 필드 추가 시 무조건 실패. `containsExactlyInAnyOrder` 또는 `containsOnly` 권장.

- **[INFO/ZT] `ApiResponse.Metadata` null 직렬화** — 기존 동작, 이번 변경 무관.
- **[INFO/ZT] `hasNext` long 캐스팅** — 정확히 적용 검증됨 (오버플로 불가).
- **[INFO/ZT] `DEFAULT_PAGE_SIZE = 20` 상수 미선언** — 설계서 명시이나 실제 사용 경로 없음 (부분 전달 400으로 분기). 기능 영향 없음.
- **[INFO/ZT] `seedTwentyFivePins()` Thread.sleep 없음** — PostgreSQL INSERT 순서/HEAP 순서 일치로 실용 위험 낮음.

### QUESTION (사용자 확인 필요)

- **[QA Q1] `seedTwentyFivePins()` createdAt 순서 보장 여부**
  - 맥락: JDBC 직접 INSERT, sleep 없음. 동일 ms INSERT 행 다수 가능성. AC-6 테스트는 id 집합 기반이라 영향 최소이나, 일부 단언이 createdAt 정렬에 의존.
  - 선택지: (a) 문제없음 — 현재 통합 테스트 통과로 검증 / (b) 잠재 불안정 — Thread.sleep(1) 추가

- **[QA Q2] `@JsonInclude(NON_NULL)` 적용 범위 — Jackson 전역 설정 충돌 여부**
  - 맥락: Mechanical Gate AC-0 통합 테스트 통과로 사실상 검증됨. JacksonConfig 별도 전역 설정 미확인.
  - 선택지: (a) 확인함 — 통합 테스트 통과로 record 레벨 단독 동작 검증 / (b) JacksonConfig 명시 확인 필요

## 수정 적용 (phase-review 라운드 1)

모든 항목이 일괄 수정 + Mechanical Gate 재통과로 검증됨.

| # | 항목 | 상태 |
|---|------|------|
| Q1 | seedTwentyFivePins Thread.sleep(1) | ✅ |
| Q2 | PinV1Dto record `@JsonInclude` 제거 (전역 NON_NULL로 통합) | ✅ |
| HIGH-1 | AC-6에 totalCount 단언 추가 | ✅ |
| HIGH-2 | listPins runtime check (page-size 짝 검증) | ✅ |
| MEDIUM-1 | PinV1Controller String 파라미터 + 명시 파싱, page=abc 테스트 케이스 추가 | ✅ |
| MEDIUM-2 | listGroupPinsPaged_nonMember 테스트 추가 | ✅ |
| MEDIUM-3 | gl-migration-plan.md 권한 검증 disclaimer 추가 | ✅ |
| Warning-1 | PinListResult Javadoc | ✅ |
| Warning-2 | PinRepositoryImpl PageRequest Sort.by 명시 | ✅ |
| Info-1 | containsExactlyInAnyOrder 교체 | ✅ |

**최종 Mechanical Gate**:
- 백엔드 테스트: `BUILD SUCCESSFUL in 3m 1s` (16 task 전체 실행, 모든 신규/기존 테스트 통과)
- 프론트엔드 빌드: BUILD SUCCESSFUL (TypeScript + Next.js)

## 통과 항목 요약

- AC-0~12: 모두 코드 레벨 충족 (Mechanical Gate 통과로 검증)
- FR-1~5, FR-7: ✅
- 도메인 경계: Pageable 미노출 (헥사고날 유지)
- 하위 호환: 룰렛(MapClient.tsx:353) + /pins(page.tsx:15) 무수정 동작
- 에러 코드: `CoreException` 경유 시 `ApiResponse` 포맷 일관 (단, MethodArgumentTypeMismatchException 경로는 MEDIUM 항목)
- GL 문서: 3개 항목 + disclaimer + status.md cross-link 완비
