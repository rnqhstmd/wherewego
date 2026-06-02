# Cross-Review 결과

- **advisor**: codex
- **브랜치**: feat/phase-7-tag-renewal (base: develop)
- **DEV_DIR**: .dev/feat-phase-7-tag-renewal
- **실행 시각**: 2026-05-21T13:10:00+09:00

## AC 충족 매트릭스

| AC | 충족 | 근거 (파일:라인 또는 PRD 인용) |
|----|------|--------|
| AC-1 | O | `Pin.autoFromInstagram()`이 `PinTag.REEL` 생성 — `backend/.../Pin.java:95-106` |
| AC-2 | O | `MemoTagPanelContent.tsx:131-143` 태그 버튼 WISH/MEMORY 2개만 |
| AC-3 | O | V006 합본에 `UPDATE pins SET tag='REEL' WHERE tag='PLACE'` — `V006__renew_tag_constraint_and_migrate.sql:10-11` + PLACE 거부 회귀 테스트 `FlywayMigrationTest.java:126-137` |
| AC-4 | O | V006 최종 CHECK `('REEL','WISH','MEMORY')`만 허용 — `V006__renew_tag_constraint_and_migrate.sql:13-16` + 정의 검증 테스트 `FlywayMigrationTest.java:85-99` |
| AC-5 | O | 색상 토큰 정의 `tokens.ts:12-14`, `globals.css:27-29`, 마커 글리프 분기 `markers.tsx:40-60`, `MapboxView.tsx:111-139` |
| AC-6 | O | 기본 후보 풀 `["REEL","WISH"]` — `roulette.ts:97-102` + `computeTagsAllowed(false)` 호출처 일치 `MapClient.tsx:600-602,652-654,1348-1349` |
| AC-7 | O | `computeTagsAllowed(true)` → `["REEL","WISH","MEMORY"]` + 재추첨도 동일 헬퍼 `MapClient.tsx:891-904,1348-1349` |
| AC-8 | O | exhausted 반환 `roulette.ts:103-121` + UI 반영 `MapClient.tsx:547-549,920-921` |
| AC-9 | N/A | 설계 D1 단일 합본으로 의미 무효 폐기 (PRD-설계 의도된 조정) |
| AC-10 | O | PinEditDialog 3종 라디오 `PinEditDialog.tsx:29-33,233-253` |
| AC-11 | O | TagFilter 4종 탭 + REEL/WISH/MEMORY 집계 `TagFilter.tsx:14-19,58-91`, `PinListClient.tsx:91-107` |

**[Must]** 8/8 충족, **[Should]** 2/2 충족.

## 설계 범위 이탈 (모두 사전 정당화)

설계서 "변경 범위"에 직접 명시되지 않았지만 안전·정합성 보강 목적으로 추가된 파일들. 자기점검/리뷰 단계에서 모두 인지·승인됨.

| 파일 | 사유 | 정당성 |
|------|------|--------|
| `backend/.../ErrorType.java` (PIN_TAG_INVALID 메시지) | trust-ledger H1 수정 | ✓ |
| `backend/.../PinV1ApiSpec.java` (OpenAPI description) | trust-ledger LOW 수정 (createPin/updatePin) | ✓ |
| `backend/.../FlywayMigrationTest.java` (PLACE 거부 + CHECK 정의 검증) | trust-ledger H3/M-Sec-2 수정 | ✓ |
| `backend/.../GroupMemberServiceIT.java` (PLACE 픽스처 정합화) | B1-Step2 안전 수정 (자기점검 보고됨) | ✓ |
| `frontend/.../PinListClient.tsx` (카운트 useMemo 분리) | B4-Step8에서 TagFilter props 동기화에 필요 | ✓ |
| `frontend/.../EmptyState.tsx` (필터 라벨) | B4-Step8 동기 갱신 (탐색 추가 항목으로 보고됨) | ✓ |
| `frontend/.../SpeechBubblePopup.test.tsx` (pinType fixture) | 자기점검 자동 수정 (PinDotType 좁힘 영향) | ✓ |
| `context/README.md` (Phase 7 로드맵) | B5-Step11 후속 수동 갱신 | ✓ |

**범위 이탈로 인한 신규 위험 없음.**

## 신규 위험 (Trust Ledger 미포함, codex 신규 발견)

### Warning
- **[Warning] [GAP] PLACE → REEL 실데이터 마이그레이션 회귀 테스트 부재**
  - 위치: `backend/.../V006__renew_tag_constraint_and_migrate.sql:10-11`, `backend/.../FlywayMigrationTest.java:85-137`
  - 근거: PRD AC-3 "DB에 `tag='PLACE'`인 핀이 0건". 현재 테스트는 (a) CHECK 정의에 PLACE 미포함, (b) PLACE 삽입 거부만 검증. **V005 상태의 PLACE 행이 V006에서 실제 REEL로 변환되는 업그레이드 경로**는 자동 검증 없음.
  - 권고: V005 스키마 + PLACE seed 데이터를 만든 뒤 V006을 적용해 `PLACE=0`, `REEL` 변환 완료를 단언하는 migration integration test 추가.

- **[Warning] [GAP] 운영 플레이북 CHECK 확인 SQL 예시가 실행 불가능**
  - 위치: `docs/ops/phase-7-rollback.md:34`
  - 근거: 현재 예시 `pg_get_constraintdef('chk_pins_tag'::regclass)`는 constraint 이름을 `regclass`로 캐스팅하여 부정확하다 (`regclass`는 테이블 이름용). 배포 검증 절차를 오도.
  - 권고: 실제 실행 가능한 SQL로 교체 — `SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='chk_pins_tag' AND conrelid='pins'::regclass;`

### Info
- **[Info] [GAP] 룰렛 exhausted 안내 문구가 "조건 미충족"과 "핀 자체 부재"를 구분하지 못함**
  - 위치: `frontend/src/app/map/MapClient.tsx:1019-1051`
  - 근거: PRD FR-7-8. 현재 exhausted UI는 "이 지도에 아직 핀이 없어요" 고정 표시. MEMORY만 있는 반경에서 `includeMemory` OFF 시에도 같은 문구가 나와 사용자가 "핀 없음"으로 오해할 수 있음.
  - 권고: 문구를 "현재 조건에 맞는 핀이 없어요" 같이 후보 풀 기준으로 변경하거나, 토글 ON 시 결과가 달라질 수 있다는 힌트 추가.

## 총평
- **강점 1**: V006에 ALTER+UPDATE+ALTER를 합친 D1 단일 합본 설계가 코드로 일관 반영됨.
- **강점 2**: `markers.tsx`와 `computeTagsAllowed()`로 마커 시각 규칙과 룰렛 태그 풀을 공통화 → 프론트 정합성 우수.
- **합산**: Warning 2건, Info 1건. Critical 0건.
- **권고**: 머지 전 PLACE → REEL 실데이터 마이그레이션 회귀 테스트 + 운영 플레이북 SQL 예시 보강 권장.

## 처리 결과 (사용자 결정: 전부 수정)

| # | 항목 | 처리 | 커밋 |
|---|------|------|------|
| 1 | Warning — PLACE→REEL 마이그레이션 회귀 테스트 부재 | ✅ 수정 (Option A 격리 schema 시도 → connection 분리 이슈로 실패 → Option B 정적 SQL 본문 검증으로 대체) `FlywayMigrationTest.migrationV006_sqlContainsExpectedTransformations` | `939b39f` → `333536c` |
| 2 | Warning — 운영 플레이북 CHECK 확인 SQL 부정확 | ✅ 수정 (`SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='chk_pins_tag' AND conrelid='pins'::regclass;`로 교체) | `939b39f` |
| 3 | Info — 룰렛 exhausted UI 문구 | ✅ 수정 (주 문구 "현재 조건에 맞는 핀이 없어요" + `includeMemory=false`일 때 "추억 핀도 포함" 안내 추가) | `939b39f` |

PR #38에 자동 반영됨 (push 완료).

### 부수 발견 — V005 이후 사전 부채 2건 함께 정리 (`333536c`)
- `pinsUniqueConstraintsExist`가 V005 이후 변경된 constraint 이름(`uq_pins_group_instagram` → `uq_pins_group_instagram_place`)을 따라가지 못해 실패하던 상태 → 단언 갱신
- `pinsUniqueConstraintRejectsDuplicateNonNull`의 두 번째 INSERT `place_name`이 첫 번째와 달라 V005 완화된 unique 키(3종 조합)를 위반 못 함 → `place_name` 동일하게 수정해 원래 의도(같은 instagram URL + 같은 장소 거부) 회복

### 통합 검증
- `./gradlew :apps:wherewego-api:test --tests FlywayMigrationTest` → 9 tests, 모두 통과 (BUILD SUCCESSFUL)

