# Phase 7 — 태그 3종 리뉴얼 기술 설계 (v2)

## v2 변경 요약 (사용자 결정 + critic 보강)

| 항목 | 결정 |
|------|------|
| D1 마이그레이션 | Expand-Contract 3단계 → **단일 합본 V006** (단일 트랜잭션 ALTER+UPDATE+ALTER) |
| D2 fromSelection | **REEL 유지**. 의미 모호성은 glossary 문서로 해소 |
| D3 백엔드 REEL 정책 | API는 enum 검증만, **REEL 등록 제한은 UI에서만** 강제 |
| D4 룰렛 토글 버그 | Phase 7에 흡수. `computeTagsAllowed` 헬퍼 도입. git log: commit 874f5bc, PR #17에서 호출처 하드코딩 잔존 |
| M1 fallback 가드 | **유지**(사용자 선택). 알 수 없는 enum → WISH 폴백 |
| M2 down-migration | 운영 플레이북에 단일 트랜잭션 SQL 명시. WISH 의미 손실 경고 |
| C1 공통 SVG | `frontend/src/lib/pin/markers.ts` 신규 — PinDot/MapboxView/PinTag 단일 소스 |
| C2 칩 글리프 | 이모지 폐기, 3종 모두 인라인 SVG(9~10px) 통일 |
| C3 색상 클래스 | Tailwind v4 `@theme` 토큰 클래스(`bg-pin-reel/10` 등) |
| C4 장식 분배 | gate/login=reel, onboarding/groups/spin=wish, GlobeBg=3종 혼용, MobileTopNav 색상만 갱신 |

## 설계 규모
**중형.** 신규 파일 3개 + 수정 파일 ~28개. 신규 의존성 없음.

---

## 아키텍처 개요

```
[트래픽 차단 ~5~10초 윈도우]
    ↓
V006 단일 트랜잭션:
  ① ALTER chk_pins_tag → IN (PLACE,REEL,WISH,MEMORY)
  ② UPDATE pins SET tag='REEL' WHERE tag='PLACE'
  ③ ALTER chk_pins_tag → IN (REEL,WISH,MEMORY)
    ↓
새 코드(REEL/WISH/MEMORY) 활성화
    ↓
[트래픽 복구]
```

Postgres 17에서 DDL+DML이 동일 트랜잭션 안에서 실행되며, Flyway는 마이그레이션 단위가 트랜잭션 단위(기본 동작). 부분 적용 위험 없음.

**PRD 수용 기준 조정:**
- AC-3(`tag='PLACE' 0건`) + AC-4(`CHECK에 PLACE 미포함`)는 V006 적용 직후 동시 충족
- AC-9(V006 후 구코드 정상 동작)는 단일 합본 배포 모델에서 **의미 무효** (구코드 공존 없음) — 폐기 항목으로 명시

---

## 데이터베이스 변경

### V006__renew_tag_constraint_and_migrate.sql (신규)

경로: `backend/apps/wherewego-api/src/main/resources/db/migration/V006__renew_tag_constraint_and_migrate.sql`

```sql
-- Phase 7: PLACE → REEL/WISH/MEMORY 태그 리뉴얼 (단일 합본 배포)
-- 단일 Flyway 트랜잭션 안에서 다음 3 작업이 원자적으로 실행됨.
-- 부분 적용 시 Flyway가 전체 롤백.

-- 1) CHECK 제약 일시 확장
ALTER TABLE pins DROP CONSTRAINT IF EXISTS chk_pins_tag;
ALTER TABLE pins ADD CONSTRAINT chk_pins_tag
    CHECK (tag IN ('PLACE', 'REEL', 'WISH', 'MEMORY'));

-- 2) 기존 PLACE 핀을 REEL로 일괄 변환 (~2,500건 예상, <1초)
UPDATE pins SET tag = 'REEL' WHERE tag = 'PLACE';

-- 3) CHECK 제약 최종 축소
ALTER TABLE pins DROP CONSTRAINT IF EXISTS chk_pins_tag;
ALTER TABLE pins ADD CONSTRAINT chk_pins_tag
    CHECK (tag IN ('REEL', 'WISH', 'MEMORY'));
```

### 롤백 down-migration (운영 플레이북, Flyway 추가 스크립트 아님)

```sql
-- 롤백: Phase 7 변경 되돌리기 (V005 상태로 복귀, 단일 트랜잭션)
BEGIN;

ALTER TABLE pins DROP CONSTRAINT IF EXISTS chk_pins_tag;
ALTER TABLE pins ADD CONSTRAINT chk_pins_tag
    CHECK (tag IN ('PLACE', 'REEL', 'WISH', 'MEMORY'));

-- REEL → PLACE (의미 보존), WISH → PLACE (의미 손실, 운영 경고)
UPDATE pins SET tag = 'PLACE' WHERE tag IN ('REEL', 'WISH');

ALTER TABLE pins DROP CONSTRAINT IF EXISTS chk_pins_tag;
ALTER TABLE pins ADD CONSTRAINT chk_pins_tag
    CHECK (tag IN ('PLACE', 'MEMORY'));

DELETE FROM flyway_schema_history WHERE version = '006';

COMMIT;
```

**의미 손실 경고**: V006 적용 후~롤백 사이에 등록된 WISH 핀은 PLACE로 흡수되어 사용자 의도("설렘") 손실. REEL→PLACE는 둘 다 챗봇 경로로 의미 보존.

---

## 백엔드 변경

### PinTag.java
```java
package com.wherewego.domain.pin;

/**
 * Pin 의미 구분. V006 이후 pins.tag CHECK (REEL, WISH, MEMORY).
 * - REEL  : 카카오톡 챗봇 경로로 등록된 핀 (자동 추출 + 후보 카드 선택 둘 다 포함)
 * - WISH  : 웹 직접 등록 — 가보고 싶은 곳 (설렘)
 * - MEMORY: 웹 직접 등록 — 다녀온 의미 있는 곳 (추억)
 *
 * REEL 정책: UI(MemoTagPanelContent)에서만 웹 등록 제한. 백엔드 API는 enum 검증만.
 */
public enum PinTag {
    REEL,
    WISH,
    MEMORY
}
```

### Pin.java (D2)
- `autoFromInstagram()` (`Pin.java:105`): `PinTag.PLACE` → `PinTag.REEL`
- `fromSelection()` (`Pin.java:123`): `PinTag.PLACE` → `PinTag.REEL` (사용자 결정 D2 — 챗봇 경로 분류)
- `createFromUser()` (`Pin.java:132`): 호출자가 태그 지정, 변경 없음
- JavaDoc(`:94`, `:111`)의 "tag=PLACE" → "tag=REEL"

### PinV1Controller.java (D3)
- 변경 없음. enum 검증만 유지 (`PinTag.valueOf(tag)`).
- `?tag=PLACE` 쿼리는 자동으로 `IllegalArgumentException → 400 Bad Request`.

### 영향받는 다른 코드
PinTag 참조는 enum 변경만으로 자동 호환 (시그니처에 PinTag만 사용). 컴파일 에러 발생 지점: `Pin.java:105, 123`의 PLACE 하드코딩 두 곳뿐.

### 테스트 동기화
| 파일 | 변경 |
|------|------|
| `PinTest.java` | PLACE 참조 → REEL/WISH/MEMORY 분기. autoFromInstagram/fromSelection 기본값 REEL 단언 |
| `PinUpdateCommandTest.java` | PLACE → WISH(또는 REEL) 치환 |
| `PinServiceIT.java` | listGroupPins 필터 단언 PLACE → WISH/REEL 분리 |
| `PinV1DtoTest.java` | 자동 등록 케이스 REEL, 웹 등록 케이스 WISH |
| `PinRepositoryIT.java` | PLACE 필터 → WISH/REEL 시나리오 |
| `PinV1ControllerIntegrationTest.java`, `PinCreateIT.java` | 페이로드 REEL/WISH/MEMORY로 갱신, **D3 검증: REEL 직접 POST 201 응답 케이스 1건 추가** |
| `PlaceSelectionHandler` 관련 | 챗봇 후보 카드 선택 → REEL 저장 |

---

## 프론트엔드 변경

### A. 신규 — `frontend/src/lib/pin/markers.ts` (C1 공통 SVG 모듈)

```ts
export type PinKind = "reel" | "wish" | "memory";

export const PIN_COLORS: Record<PinKind, string> = {
  reel: "var(--color-pin-reel)",
  wish: "var(--color-pin-wish)",
  memory: "var(--color-pin-memory)",
};

// vanilla DOM 삽입용 string 반환
export function getReelSvgString(size: number, color?: string): string;
export function getWishSvgString(size: number, color?: string): string;
export function getMemorySvgString(w: number, h: number, color?: string): string;

// React 컴포넌트 (PinDot/PinTag 공용)
export function ReelGlyph(props: { size: number; color?: string }): JSX.Element;
export function WishGlyph(props: { size: number; color?: string }): JSX.Element;
export function MemoryGlyph(props: { w: number; h: number; color?: string }): JSX.Element;
```

**시각 명세:**
- REEL: 둥근 정사각형 외곽선 + 중앙 렌즈 원 + 우상단 점 (인스타그램 글리프)
- WISH: 단순 채워진 원 (기존 PLACE 마커 형태 재사용)
- MEMORY: 하트 path (기존 MEMORY 마커 path 재사용)
- 색상 미지정 시 CSS 변수(`var(--color-pin-*)`)로 Tailwind v4 토큰과 통합

### B. 타입 + 디자인 토큰

**`frontend/src/lib/api/types.ts`:**
```ts
export type PinTag = "REEL" | "WISH" | "MEMORY";
```

**`frontend/src/lib/design/tokens.ts`:**
- 제거: `pinPlace: "#7BB3E8"`
- 추가: `pinReel: "#C5B4E3"`, `pinWish: "#A8E6CF"`
- 수정: `pinMemory: "#F4A8B0"` → `"#FFB3C6"`

**`frontend/src/app/globals.css` `@theme`:**
```css
@theme {
  --color-pin-reel: #C5B4E3;
  --color-pin-wish: #A8E6CF;
  --color-pin-memory: #FFB3C6;
  /* --color-pin-place 제거 */
}
```

Tailwind v4가 `bg-pin-reel/10`, `text-pin-reel`, `border-pin-reel/30` 등 유틸리티 자동 생성 (C3 의존).

### C. PinDot.tsx (C1 + M1)
```tsx
type PinDotType = "reel" | "wish" | "memory";

export function PinDot({ type, size = 24 }: { type: PinDotType; size?: number }) {
  switch (type) {
    case "reel":   return <ReelGlyph size={size} />;
    case "wish":   return <WishGlyph size={size} />;
    case "memory": return <MemoryGlyph w={size} h={size} />;
    default:       return <WishGlyph size={size} />; // M1 fallback
  }
}
```

### D. MapboxView.renderPinDotInto (M1 + C1)
```ts
function renderPinDotInto(el: HTMLElement, tag: string) {
  switch (tag) {
    case "REEL":   el.innerHTML = getReelSvgString(32); break;
    case "WISH":   el.innerHTML = getWishSvgString(32); break;
    case "MEMORY": el.innerHTML = getMemorySvgString(32, 30); break;
    default:       el.innerHTML = getWishSvgString(32); break; // M1 fallback
  }
}
```

### E. PinTag.tsx (C2 — 이모지 폐기, SVG 통일)
```tsx
const TAG_META: Record<PinTag, { label: string; Glyph: (p: { size: number }) => JSX.Element }> = {
  REEL:   { label: "발견", Glyph: (p) => <ReelGlyph size={p.size} /> },
  WISH:   { label: "설렘", Glyph: (p) => <WishGlyph size={p.size} /> },
  MEMORY: { label: "추억", Glyph: (p) => <MemoryGlyph w={p.size} h={p.size} /> },
};

export function PinTag({ tag }: { tag: PinTag }) {
  const meta = TAG_META[tag] ?? TAG_META.WISH; // M1 fallback
  const k = tag.toLowerCase();
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs
                      bg-pin-${k}/10 text-pin-${k} border border-pin-${k}/30`}>
      <meta.Glyph size={10} />
      {meta.label}
    </span>
  );
}
```

### F. MemoTagPanelContent.tsx (FR-7-6)
```tsx
const SELECTABLE_TAGS: PinTag[] = ["WISH", "MEMORY"]; // REEL 제외
```

### G. 룰렛 + MapClient.tsx (D4 토글 정합화)

**roulette.ts:**
```ts
export function pickRandomWithExpansion(opts: {
  tagsAllowed?: PinTag[]; ...
}): RouletteOutcome {
  const tags = opts.tagsAllowed ?? ["REEL", "WISH"]; // 기본 풀
  ...
}
```

**MapClient.tsx (D4 헬퍼):**
```ts
function computeTagsAllowed(includeMemory: boolean): PinTag[] {
  return includeMemory ? ["REEL", "WISH", "MEMORY"] : ["REEL", "WISH"];
}
```
- 호출처 정합화:
  - `:599-602` `handleRouletteTap` → `void runRoulette(geoState.coords, computeTagsAllowed(includeMemory));`
  - `:651-654` 동일 패턴
  - `:884-905` `handleReRoll` 동일 패턴

git log 컨텍스트: Phase 2.6 PR-A(commit 874f5bc, PR #17)에서 룰렛 MEMORY 토글 도입 시 호출처 하드코딩 잔존. 함수 시그니처는 토글 수용 가능하게 설계됨.

### H. 핀 목록 UI (C3 토큰 클래스, M1)

**`TagFilter.tsx` (FR-7-10):**
- 탭: "전체 / 발견 / 설렘 / 추억" (4종)
- 활성 탭 색상: `bg-pin-reel/15 text-pin-reel` 등 토큰 클래스
- props: `reelCount`, `wishCount`, `memoryCount` (placeCount 제거)

**`PinEditDialog.tsx` (FR-7-9, C3):**
- 라디오 3종 (REEL/WISH/MEMORY 모두 선택 가능, FR-7-9)
- 색상: `bg-pin-reel/10 border-pin-reel text-pin-reel` (Tailwind v4 토큰)

**`PinCard.tsx` (C3 + M1):**
```ts
const TAG_STYLES: Record<PinTag, string> = {
  REEL:   "bg-pin-reel/10 text-pin-reel border-pin-reel/30",
  WISH:   "bg-pin-wish/10 text-pin-wish border-pin-wish/30",
  MEMORY: "bg-pin-memory/10 text-pin-memory border-pin-memory/30",
};
const style = TAG_STYLES[pin.tag] ?? TAG_STYLES.WISH; // M1 fallback
```

### I. PinPopup.tsx
- `:391` `pinType={pin.tag === "MEMORY" ? "memory" : "place"}` → 3종 분기
- `:249-270` tagPanel — REEL/WISH/MEMORY 3종 칩 (FR-7-9와 일관)

### J. RouletteResultContent.tsx (FR-7-13, Could)
```ts
const dotType: PinDotType =
  pin.tag === "MEMORY" ? "memory" : pin.tag === "REEL" ? "reel" : "wish";
```

### K. 장식용 PinDot 분배 (C4 분배표)

| 호출처 | 라인 | v2 분배 | 근거 |
|---|---|---|---|
| `app/gate/page.tsx` | 170-172, 298 | **pinReel** | 인사 화면 보라-핑크 페어 |
| `app/login/LoginClient.tsx` | 134-136, 201 | **pinReel** | gate와 일관 |
| `app/onboarding/group-start/GroupStartClient.tsx` | 83 | **pinWish** | 도입 화면 시작감 |
| `app/groups/GroupsClient.tsx` | 115 | **pinWish** | 그룹 시작 맥락 |
| `components/ui/GlobeBg.tsx` | 105-107 | **pinWish(주)+pinReel+pinMemory** 3종 혼용 | 글로벌 다양성 |
| `app/map/_components/RouletteSpinContent.tsx` | 38-51 | **pinWish** | 회전 시 시각 부담 최소 |
| `app/map/_components/MobileTopNav.tsx` | 83 | **pinMemory** (#FFB3C6, 토큰 갱신만) | 색상만 갱신 |

---

## 테스트 전략

### 백엔드
- PinTest, PinUpdateCommandTest, PinServiceIT, PinV1DtoTest, PinRepositoryIT: PLACE 인용 → REEL/WISH 분리
- **D3 추가 케이스**: `PinV1ControllerIntegrationTest`에 "REEL 직접 POST → 201" 케이스 1건
- `PlaceSelectionHandler` 관련: 챗봇 카드 선택 → REEL 저장

### 프론트엔드
- `PinTag.test.tsx`: 3종 레이블/글리프 + **M1 fallback 케이스 1건** (`tag="PLACE" as any → WishGlyph + "설렘"`)
- `PinDot.test.tsx`: 3종 타입 + **M1 fallback 케이스**
- `roulette.test.ts`: 기본 풀 `["REEL","WISH"]`, 토글 ON `["REEL","WISH","MEMORY"]`, exhausted (AC-8), WISH 포함 검증 (AC-6)
- `computeTagsAllowed` 단위 테스트 (D4 정합화)

### E2E / 수동 검증
- AC-1: 챗봇 등록 → DB tag='REEL'
- AC-2: 웹 등록 패널 → "설렘"/"추억" 2종만
- AC-3/4: V006 후 PLACE 0건 + CHECK에서 PLACE 미포함
- AC-5: 지도 마커 3종 시각 구분
- AC-6/7/8: 룰렛 토글 OFF/ON/exhausted
- AC-10: PinEditDialog REEL/WISH/MEMORY 모두 선택 가능
- AC-11: TagFilter 4종 탭

---

## 컨텍스트 문서 갱신

### `context/tag/glossary.md`
- 2종 → 3종 갱신
- **D2 보강**: "REEL: 카카오톡 챗봇 경로로 등록된 핀. 자동 추출(릴스 URL)과 후보 카드 선택 둘 다 포함."
- **D3 보강**: "REEL은 챗봇 전용 권장 태그지만 백엔드 API가 강제하지 않음. 웹 UI에서만 선택지 제외. PinEditDialog에서는 3종 모두 허용. 정책 vs 검증의 단순성 우선."

### `context/tag/architecture.md`
- 마이그레이션: Expand-Contract → **단일 합본 V006**으로 교체
- 토큰 표 3종 갱신
- 등록 경로별 기본 태그 매핑

### `context/tag/status.md`
- FR-TAG-7 ~ FR-TAG-11 ✅ (Phase 7 PR 머지 후)

---

## 변경 범위 요약

**신규 (2개 파일 + 1 문서):**
- `backend/.../db/migration/V006__renew_tag_constraint_and_migrate.sql`
- `frontend/src/lib/pin/markers.ts`
- 운영 플레이북 down-migration SQL (문서)

**수정 (~28개):**
- Backend (~7): PinTag.java, Pin.java, 테스트 5종 + V1Controller JavaDoc
- Frontend 코어 (~8): types.ts, tokens.ts, globals.css, PinDot.tsx, PinTag.tsx, MapboxView.tsx, MemoTagPanelContent.tsx, roulette.ts
- Frontend 핀목록 (3): TagFilter, PinEditDialog, PinCard
- Frontend MapClient (1): D4 헬퍼 + 호출처 3곳
- Frontend 룰렛/팝업 (3): RouletteResultContent, RouletteSpinContent, PinPopup
- Frontend 장식 (6): gate, login, GroupStartClient, GroupsClient, GlobeBg, MobileTopNav
- 테스트 (4): PinTag.test, PinDot.test, roulette.test, useGroupPinSync.test (픽스처 갱신)
- 컨텍스트 (3): glossary.md, architecture.md, status.md

---

## 구현 순서 (12단계, 병렬 그룹 5개)

**의존성 그래프:**
- 그룹 A (의존 없음, 병렬): 1, 2, 3
- 그룹 B (A 완료 후, 병렬): 4
- 그룹 C (4 완료 후): 5, 6
- 그룹 D (5 완료 후, 병렬): 7, 8, 9, 12
- 그룹 E (마지막): 10, 11

1. **[Must]** V006 단일 합본 마이그레이션 작성
2. **[Must]** PinTag enum 3종 + Pin.java 기본값 REEL + 백엔드 테스트 갱신 (V1Controller 테스트의 D3 케이스 포함)
3. **[Must]** 프론트엔드 타입(types.ts) + 디자인 토큰(tokens.ts, globals.css @theme)
4. **[Must]** `lib/pin/markers.ts` 공통 SVG 모듈 (C1)
5. **[Must]** PinDot.tsx + PinTag.tsx 3종 + MapboxView.renderPinDotInto + **M1 fallback**
6. **[Must]** 룰렛 — roulette.ts 기본 풀 + MapClient.computeTagsAllowed (D4) + 호출처 3곳 정합화
7. **[Must]** MemoTagPanelContent.tsx 웹 등록 2종 (FR-7-6)
8. **[Should]** 핀 목록 — TagFilter/PinEditDialog/PinCard (C3 토큰 클래스, M1 fallback)
9. **[Should]** 장식용 PinDot 분배 (C4 분배표)
10. **[Should]** 프론트엔드 테스트 — PinTag.test, PinDot.test, roulette.test (fallback 케이스 포함), useGroupPinSync.test 픽스처
11. **[Should]** 컨텍스트 문서 갱신 — glossary(D2/D3 보강), architecture, status
12. **[Could]** RouletteResultContent + RouletteSpinContent + PinPopup 3종 분기

---

## 배포 가이드

### 단일 시점 배포 절차
1. 사전: 새 코드 빌드 + 스테이징 검증, V006 SQL 리뷰, DB 백업 스냅샷
2. 배포 윈도우 (5~10초 다운타임):
   - 트래픽 차단 (LB/리버스 프록시 maintenance)
   - V006 Flyway 마이그레이션 (단일 트랜잭션, <1초)
   - 새 코드 배포/재시작
   - 헬스체크 통과 확인
   - 트래픽 복구
3. 사후 확인:
   - `SELECT tag, COUNT(*) FROM pins GROUP BY tag` → REEL/WISH/MEMORY만 (AC-3/4)
   - 챗봇 신규 등록 1건 → tag='REEL' (AC-1)
   - 웹 등록 패널 "설렘/추억" 2종 (AC-2)
   - 지도 마커 3종 (AC-5)
   - 룰렛 토글 OFF/ON (AC-6/7)
   - 5분 모니터링 — M1 fallback 발동 여부

### 롤백 절차 (M2)
1. 트래픽 차단
2. 운영 플레이북 down-migration SQL 실행 (단일 트랜잭션)
   - **경고**: WISH 핀 → PLACE 흡수, 의미 손실
3. 구코드 배포/재시작
4. 헬스체크 통과
5. 트래픽 복구

### 운영 플레이북 추가 항목
- V006 사본
- down-migration SQL + WISH 손실 경고
- 배포 윈도우 체크리스트
- M1 fallback 관찰 가이드 (배포 직후 5분)
