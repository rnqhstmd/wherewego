# Trust Ledger — Phase 2.10 (잔여 후속 통합)

> 리뷰 대상: `ee67048 feat: phase 2.10 잔여 후속 통합 완료` 단일 커밋
> Mechanical Gate: backend build ✅ / frontend build ✅ / backend test ✅
> 리뷰 라운드: 1회차

---

## 통합 감사 (review)

### CRITICAL
- (해당 없음)

### HIGH

- **[RISK/HIGH] `coordinateError`가 expanded=false 상태에서 표시되지 않는다 — 실제 버그 검증됨**
  - 근거: `PinPopup.tsx:246, 339` `footer = expanded ? (...) : null`. `coordinateError` 렌더는 footer 내부 (`:326-337`). `expanded` 초기값 `false` (`:88`). 좌표 수정 흐름에서 popup 언마운트→재마운트 시 `expanded` 리셋되어 footer null. `setCoordinateErrorByPinId` 저장값이 사용자에게 표시되지 않음. PRD AC-4("실패 시 자동 롤백 + 인라인 에러") 미충족.
  - 권고: `handleConfirmCoordinateEdit` 실패 분기에서 toast로 표시하거나, popup 재노출 시 `expanded=true`를 강제하는 메커니즘. 또는 PinPopup이 `coordinateError` 존재 시 외부 영역(footer 외)에 렌더하도록 조정.

- **[GAP/HIGH] FR-BOT-9/AC-7 운영 작업(빌더 콘솔 시나리오 설정) 증적 부재**
  - 근거: `context/chatbot/status.md:28` "Phase 2.10 완료" 기재. PR 본문에 빌더 콘솔 설정 화면/항목 텍스트 기록 여부 미확인.
  - 권고: phase-complete 단계에서 PR 본문에 콘솔 설정 증적 기록 또는 별도 운영 작업으로 분리.

- **[GAP/HIGH] FR-BOT-10/AC-8 카카오톡 실기기 E2E 검증 증적 부재**
  - 근거: PRD AC-8 — "검증 절차와 결과가 PR 본문에 기록된다". 현재 PR 미생성 상태.
  - 권고: phase-complete 단계에서 실기기 E2E 절차/결과를 PR 본문에 기록.

- **[DOC-INTEGRITY/HIGH] context 문서 3곳의 `[PR-LINK]` 플레이스홀더 미치환**
  - 근거: `context/pin/status.md:20,21,29`, `context/map/status.md:23,24`, `context/chatbot/status.md:28` 전부 `[PR-LINK]`. 다른 Phase는 `[#21]`, `[#22]` 등 실 번호로 연결되어 있음.
  - 권고: PR 머지 후 실제 PR 번호로 일괄 교체 (이번 phase-complete 산출물).

### MEDIUM

- **[ASSUMPTION/MEDIUM] `NEXT_PUBLIC_MAPBOX_TOKEN` URL Restriction 실제 적용 여부 미검증**
  - 근거: SOP §2 명시. PRD §3.2 제외("운영자 직접 수행"). Phase 6 trust-ledger에서도 동일 항목 MEDIUM.
  - 권고: 운영자 작업 — Mapbox 대시보드에서 적용 여부 확인 후 미적용 시 SOP §2 절차 수행.

- **[GAP/MEDIUM] `PinServiceIT`에 좌표 수정 서비스 레이어 IT 케이스 부재**
  - 근거: 자기점검 이월 Warning. 설계서 §8 영역A에서 컨트롤러 IT 5케이스만 명시 → 설계 결정. 컨트롤러 IT가 DB 영속 검증 포함하므로 회귀 위험 낮음.
  - 권고: (옵션) 서비스 IT `updatePin_coordinateOnly_updatesCoordinateAndKeepsOtherFields` 추가 또는 결정 명시.

- **[ASSUMPTION/MEDIUM] picker 표시값(`toFixed(6)`) vs DB 저장값(scale=7) 불일치**
  - 근거: `PinCoordinateEditPicker.tsx:83` `coord.lat.toFixed(6)` 표시. DB는 `BigDecimal(10,7)`. JS double precision 안전성은 OK이나 표시 6자리/저장 7자리 UX 혼선 가능.
  - 권고: 표시값을 7자리로 통일 또는 문서화.

- **[POLICY/MEDIUM] `PinCoordinateEditPicker` `mapboxToken` prop 미사용 + eslint-disable**
  - 근거: 자기점검 이월 Info. 설계서 §3.4 "향후 reverse geocoding 확장 여지" 명시.
  - 권고: 차후 reverse geocoding 추가 전까지 `_mapboxToken` 네이밍 컨벤션 권고 (이번 Phase 범위 외).

- **[DOC-INTEGRITY/MEDIUM] `mapbox-token-sop.md`가 존재하지 않는 `frontend/.env.local.example` 참조**
  - 근거: `mapbox-token-sop.md:55` "환경 변수 샘플: `frontend/.env.local.example`". Glob 검색 결과 파일 미존재.
  - 권고: `.env.local.example` 생성 또는 SOP 해당 라인 제거/대체. NFR-4(운영자 단독 수행) 충족 영향.

### LOW

- **[POLICY/LOW] `coordinateProvided` 단일 플래그 컨벤션** — Javadoc + 설계서로 문서화 충분. 차후 ADR 권고.
- **[GAP/LOW] `PinV1ApiSpec` `updatePin` description 누락** — placeName/address 수정 항목이 Phase 2.8 이후 description에 미반영. 기능 영향 없음, API 문서 정합성만.
- **[ASSUMPTION/LOW] Tailwind v4 preflight reset 미검증** — 설계서 §4 fallback 제시. AC-12 DevTools 수동 검증 필요.
- **[ASSUMPTION/LOW] `PinSummaryResponse.latitude/longitude` 타입 명세 확인** — wire에서 `number`로 수신되나 `types.ts` 선언 확인 권고.

---

## QA 리뷰 결과

### Critical (즉시 수정)
- (해당 없음)

### Warning
- **[Warning] `MapClient.tsx:386-435` `handleConfirmCoordinateEdit`에서 `setSelectedPinId(pinId)`를 `startOptimisticTransition` 내부에서 호출** — 태그/메모 핸들러는 transition 외부 호출. 자기점검 QUESTION [4]와 동일 맥락. 의도된 UX(마커 이동 직후 popup 즉시 노출)일 수 있으므로 사용자 확인 필요.
- **[Warning] `PinServiceIT.java`** — 좌표 수정 서비스 레이어 IT 케이스 누락. (자기점검 이월, MEDIUM에도 기록)

### Info
- **[Info] `PinCoordinateEditPicker.tsx:83`** — 좌표 표시 `📍` 이모지 하드코딩 (디자인 토큰 비일관)
- **[Info] `[PR-LINK]` placeholder** — HIGH로 격상

### QUESTION (사용자 확인 필요 — 자기점검 이월 항목 재거론)
- **Q1**: 좌표 확정 후 `setSelectedPinId(pinId)` 호출을 `startOptimisticTransition` 내부 vs 외부 어디서 할지 → 의도된 UX인지 확인
- **Q2**: `Number(pin.longitude/latitude)` 방어 캐스팅 유지 vs 제거

### AC 충족 판정 (전체 충족)
- AC-1~6 (FR-PIN-7): 충족 (Controller IT 5케이스 통과)
- AC-4 (FR-PIN-8): 충족 (단, 실패 인라인 에러 표시는 [HIGH] coordinateError 버그로 부분 미충족)
- AC-7~9 (FR-BOT-9,10): 코드 변경 없음, PR 본문 증적 필요
- AC-10~12 (FR-MAP-6,7,8): 충족
- AC-13~15 (FR-PIN-9 회귀): 충족

---

## 머지 전 필수 해소

1. [HIGH/RISK] `coordinateError` 표시 버그 — 사용자 결정 후 코드 수정 또는 UX 정책 갱신
2. [HIGH/GAP] PR 본문에 FR-BOT-9/10 증적 기록 (phase-complete에서 처리)
3. [HIGH/DOC] `[PR-LINK]` placeholder 치환 (PR 머지 후 처리)

## 머지 후 모니터링
- Mapbox URL Restriction 실제 적용 여부
- `frontend/.env.local.example` 생성
- `PinV1ApiSpec` description 보강
- `PinServiceIT` 좌표 케이스 추가 결정
