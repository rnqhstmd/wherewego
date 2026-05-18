# Cross-Review 결과

- advisor: **codex** (GPT-5.4)
- 브랜치: `feat/phase-2-9-scale` (base: `develop`)
- DEV_DIR: `.dev/feat-phase-2-9-scale`
- 실행 시각: 2026-05-18T15:50:00+09:00

## AC 충족 매트릭스

| AC | 충족 | 근거 |
|----|------|------|
| AC-1 | ✅ | `PinV1Controller.java:81` + `PinV1Dto.java:63` + 통합 테스트 `:547` |
| AC-2 | ✅ | `PinV1Controller.java:75` legacy 분기 + `PinV1Dto.java:56` + `JacksonConfig.java:24` 전역 NON_NULL + 테스트 `:521` |
| AC-3 | ✅ | `PinV1Controller.java:88` size>100 → `ErrorType.java:59` PIN_PAGE_SIZE_EXCEEDED + 테스트 `:574` |
| AC-4 | ✅ | `PinV1Controller.java:66` 부분 전달/음수/0 모두 PIN_PAGE_PARAM_INVALID + 테스트 4 케이스 (`:588`, `:612`, `:698`) |
| AC-5 | ✅ | `PinService.java:95` tagFilter 분기 + `PinRepositoryImpl.java:57` countActiveByGroupIdAndTag + 테스트 `:636` |
| AC-6 | ⚠️ **부분** | `:657` disjoint·totalCount 일치 단언은 통과. **다만 정렬 키가 `createdAt DESC` 단일이라 동일 timestamp 시 안정 페이지네이션 보장 약함** (`PinRepositoryImpl.java:51`). 테스트는 `Thread.sleep(1)`로 강제 분산 |
| AC-7 | ✅ | `MapClient.tsx:347` 1-인자 fetch 유지 + `types.ts:35` items 필수 + Controller legacy 분기 |
| AC-8 | ✅ | `pins/page.tsx:15` 1-인자 호출 유지 + `pin.ts:27` 유니온 시그니처 |
| AC-9 | ✅ | `tokens.ts:12` 색상 토큰 + `PinDot.tsx:15` + `SpeechBubblePopup.tsx:97` + `PinPopup.tsx:187` 무변경 |
| AC-10 | ⚠️ **부분** | 변경 테스트는 페이지네이션 + 일부 patch/delete 회귀 중심. **Phase 2.8 AC 1~17 전량 재실행 증적이 이번 산출물에 명시적으로 보고되지 않음** |
| AC-11 | ✅ | `MapClient.tsx:135` useOptimistic + `clusterer.ts:33` supercluster + `PinPopup.tsx:111` map.project 무변경 |
| AC-12 | ✅ | `gl-migration-plan.md:18` 3개 항목 현재/전환 후 대비 + `architecture.md:42` 링크 + `status.md:21` cross-link |

**합산**: [Must] 11 항목 중 9 ✅ / 2 ⚠️ 부분. [Should] AC-12 ✅.

## 설계 범위 이탈

| # | 파일 | 변경 요약 | 이탈 사유 추정 |
|---|------|----------|--------------|
| 1 | `backend/gradle.properties` | toolchain `auto-download=true` 2줄 추가 | 설계서 §1 범위 외. **로컬 JDK 17/21 환경 이슈 해결로 phase-implement 중 정당화** (state.md execution-log) |
| 2 | `context/pin/architecture.md` | 페이지네이션 계약 + 검증 규칙 + 포트 순수성 추가 | 설계서 §1은 문서 변경을 `context/map/*` 3건으로 한정. **phase-complete Step 4 context 환류로 사용자 승인 후 추가** |
| 3 | `context/pin/glossary.md` | PinListResult, legacy/페이지 모드, 신규 에러 코드 5개 용어 추가 | 위와 동일 — 환류 결과 |
| 4 | `context/pin/status.md` | Phase 2.9 완료 상태 + 계약 요약 | 위와 동일 — phase-complete Step 3 status.md 동기화 |

> 모든 이탈은 phase-implement 또는 phase-complete의 사용자 승인 절차를 거친 정당한 추가. 단, 설계서 §1에 명시되지 않았다는 점은 사실.

## 신규 위험

### ⚠️ Warning

#### 1. [RISK] AC-6 페이지네이션 정렬 안정성 (tie-breaker 부재)
- **위치**: `PinRepositoryImpl.java:51`, `PinJpaRepository.java:33`, 테스트 `PinV1ControllerIntegrationTest.java:505`
- **근거**: PRD AC-6은 "`page=1&size=20` 요청이 다음 20건을 반환하고 결과가 중복되지 않는다"를 요구. 구현은 `PageRequest.of(page, size, Sort.by(DESC, "createdAt"))` 단일 키만 사용. 테스트는 `Thread.sleep(1)`로 timestamp를 인위적으로 분산시켜 안정성을 우회. 운영에서 다수 동일 ms INSERT 시 페이지 간 중복/누락 가능성.
- **권고**: `Sort.by(Order.desc("createdAt"), Order.desc("id"))` 같은 tie-breaker 추가 + 동일 createdAt 데이터셋에서도 page 0/1 disjoint를 검증하는 테스트 보강.

#### 2. [GAP] AC-10 Phase 2.8 회귀 증적 부족
- **위치**: `PinServiceIT.java:417`, `PinV1ControllerIntegrationTest.java:521`
- **근거**: PRD AC-10은 "Phase 2.8 AC 1~17 항목 모두 충족"을 요구. 이번 변경 테스트는 페이지네이션 + 일부 patch/delete 회귀에 집중. Phase 2.8 17개 전량에 대한 명시적 재실행/체크리스트 증적이 산출물에 없음. (Mechanical Gate에서 `:apps:wherewego-api:test` 전체가 통과한 사실은 있으나 AC와 1:1 매핑된 증적은 아님)
- **권고**: 영향도 높은 경로(`PinDot/PinTag/SpeechBubblePopup`, `useOptimistic patch|remove`, `supercluster`, `PinPopup map.project`) 회귀 체크리스트 또는 Phase 2.8 AC ↔ 테스트 매핑 문서를 추가.

## 총평

- **강점 1**: legacy/page 두 모드 분기 + `JacksonConfig` 전역 NON_NULL 직렬화 조합으로 AC-2/AC-7/AC-8 하위 호환성을 깔끔하게 유지.
- **강점 2**: `gl-migration-plan.md`가 AC-12의 3개 비교 항목 + 보안 disclaimer를 명확히 충족.
- **합산**: Critical 0, **Warning 2** (AC-6 tie-breaker, AC-10 회귀 증적), Info 0.
- **권고 한 줄**: 페이지네이션 정렬에 `id DESC` tie-breaker를 추가하고, Phase 2.8 전량 회귀 증적을 명시화하라.

---

## 처리 후보 정리

| # | 항목 | 분류 | 위치 |
|---|------|------|------|
| 1 | AC-6 tie-breaker 추가 (`Sort.by(createdAt DESC, id DESC)`) + 동일 timestamp disjoint 테스트 | Warning/RISK | PinRepositoryImpl + 테스트 |
| 2 | Phase 2.8 AC ↔ 테스트 매핑 또는 회귀 체크리스트 추가 | Warning/GAP | 문서 또는 테스트 |

이외 AC 충족 매트릭스의 AC-6 / AC-10 "부분" 표기는 위 두 권고를 처리하면 ✅로 승격됨.

---

## 처리 결과

| # | 항목 | 처리 | 결과 |
|---|------|------|------|
| 1 | AC-6 tie-breaker (`Sort.by(createdAt DESC, id DESC)`) + 동일 timestamp disjoint 테스트 | ✅ 수정됨 | 커밋 `349548d` — PinJpaRepository 메서드명 OrderBy 제거, PinRepositoryImpl Sort 명시, IntegrationTest 신규 테스트 `listPins_pagination_tieBreaker_disjoint_AC6` 추가. 백엔드 전체 테스트 `BUILD SUCCESSFUL in 2m 30s` (16 task 통과) |
| 2 | Phase 2.8 AC ↔ 테스트 매핑 문서 | ✅ 추가됨 | `.dev/feat-phase-2-9-scale/phase-2-8-regression-coverage.md` (8.9KB, 17 AC 1:1 매핑). `.dev/`는 `.gitignore` 대상이라 PR에는 포함되지 않으나 작업 트리에 보존 |

**AC 매트릭스 승격 후 상태**: AC-6 / AC-10 모두 ✅. [Must] 11/11 / [Should] 1/1 충족.

**최종 Mechanical Gate**: 백엔드 `:apps:wherewego-api:test` `BUILD SUCCESSFUL` (신규 tie-breaker 테스트 포함, 50+ 케이스 모두 통과).
