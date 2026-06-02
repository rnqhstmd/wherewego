# Phase 7 Trust Ledger

## QA Manager 리뷰

### CERTAIN
- **[Warning] backend/.../support/error/ErrorType.java:54** — `PIN_TAG_INVALID` 메시지 "태그는 PLACE 또는 MEMORY 중 하나여야 합니다." 잔존. API 응답에 노출되어 사용자 혼란 유발 가능.
- **[Warning] frontend/src/app/globals.css:18** — 코멘트에 `bg-pin-place` 레퍼런스 잔존.
- **[Warning] frontend/src/app/map/_lib/roulette.ts:4,87** — 주석이 "1km→5km→10km 자동 확장" 기술하나 실제 `ROULETTE_RADIUS_STEPS_KM=[10]` 단일 반경. **사전 부채** (Phase 7 무관).
- **[Info] frontend/src/components/ui/PinTag.tsx:37** — 시각 속성(border-radius/padding/font)이 인라인 style, 색상은 토큰 클래스 — C3 부분 적용. 기능 영향 없음.

### QUESTION
- **Q1**: 룰렛 단일 반경(10km) vs 주석의 3단계 확장 불일치. Phase 7 무관 사전 부채로 보이나 의도 확인 필요.

### 스펙 충족도
- FR-7-1 ~ FR-7-13 모두 ✅
- AC-1 ~ AC-11 모두 ✅ (AC-9는 D1 단일 합본 결정으로 의미 무효 처리, 폐기 명시됨)

### 사용자 결정 + critic 보강 반영
- D1 단일 합본, D2 fromSelection REEL, D3 백엔드 enum 검증만, D4 룰렛 정합화: 모두 ✅
- M1 fallback, M2 down-migration, C1 공통 SVG, C2 SVG 통일, C3 토큰 클래스, C4 장식 분배: 모두 ✅

---

## Security Auditor 통합 감사

### CRITICAL
- 없음

### HIGH
- **[정책/HIGH] H1**: `backend/.../support/error/ErrorType.java:54` `PIN_TAG_INVALID` 메시지가 구 enum `PLACE 또는 MEMORY` 노출. API 응답 → 클라이언트 혼란, 잘못된 재시도 유발 가능.
  - 권고: 메시지를 `"태그는 REEL, WISH, MEMORY 중 하나여야 합니다."`로 갱신
- **[GAP/HIGH] H2**: 운영 플레이북 파일이 저장소에 없음. design.md 인라인 SQL만 존재. PR 머지 후 운영 시 참조 경로 불분명.
  - 권고: down-migration SQL (WISH 손실 경고 포함) + 배포 체크리스트를 `docs/ops/` 또는 `.dev/` 아래 파일로 커밋
- **[ASSUMPTION/HIGH] H3**: `FlywayMigrationTest.pinsTagCheckConstraintRejectsInvalidValue`가 `"INVALID"` 거부만 검증. PLACE 거부(AC-4 핵심 계약) 회귀 보호 없음.
  - 권고: `pinsTagCheckConstraintRejectsPlaceValue()` 테스트 추가 — PLACE 삽입 시 `DataIntegrityViolationException` 단언

### MEDIUM
- **[정책/MEDIUM] M-Sec-1**: `globals.css:18` 주석 `bg-pin-place` 잔존 → 토큰 예시 갱신
- **[GAP/MEDIUM] M-Sec-2**: `FlywayMigrationTest.pinsCheckConstraintsExist`가 제약 이름만 확인, 정의 내용(허용 값 집합) 미검증. V006 SQL 오타 회귀 보호 부족
- **[GAP/MEDIUM] M-Sec-3**: `MemoTagPanelContent` instagramUrl 검증 시 `PIN_INSTAGRAM_URL_INVALID` 서버 에러 코드 매핑 누락 → 사용자에게 제네릭 메시지
- **[ASSUMPTION/MEDIUM] M-Sec-4**: M1 fallback 발동 시 감지 수단 없음 (로깅/Sentry 훅 부재)
- **[GAP/MEDIUM] M-Sec-5**: `PinEditDialog` 빈 주소 저장 시 정책(기존 유지)이 사용자 가시성 낮음 — 사전 부채

### LOW/INFO
- `roulette.ts` 단일 반경 코멘트 (사전 부채, QA Warning과 중복)
- `PinV1ApiSpec` `createPin`/`updatePin` description에 허용 태그 값 명시 누락
- `PinTag.test.tsx` M1 fallback 테스트의 `as PinTag` 캐스팅 의도 주석 필요

### 검증 매트릭스 (요약)
- DB 무결성: V006 단일 트랜잭션 ✅, PLACE 거부 자동 회귀 ⚠️ (H3)
- API 정책: D3 enum 검증만 ✅, PIN_TAG_INVALID 메시지 ⚠️ (H1)
- XSS: markers.tsx + MapboxView innerHTML ✅ (외부 입력 미전달, 상수/CSS var만 보간)
- 롤백: down-migration SQL 존재 ⚠️ (H2 — 인라인만), WISH 손실 경고 ✅
- M1 fallback: 발동 감지 ⚠️ (M-Sec-4)

### PRD-구현 불일치 (의도된 조정)
- PRD AC-9 (V006 후 구코드 정상 동작): 설계 D1 단일 합본 배포 결정으로 폐기 — 설계서에 명시됨. PRD 자체에는 미반영 (의도된 GAP)
