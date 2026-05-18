# 자기점검 결과 (Phase 2.10)

## 빌드 검증
- 백엔드: `(cd backend && ./gradlew :apps:wherewego-api:test) → exit 0` ✅
- 프론트엔드: `(cd frontend && npm run build) → exit 0` ✅ (Next.js 16.2.6, TypeScript 컴파일 성공)

## qa-manager 자기점검 결과

### Critical (CERTAIN) — 1건 [자동 수정 완료]

- **[Critical] `MapClient.tsx:690+ handleSheetClose`** — 데스크톱 `×` 닫기 시 `coordinateEditTarget` 미초기화 + `setSelectedPinId(pinId)` 복귀 누락
  - **자동 수정 완료**: `handleSheetClose` 함수 본문 첫 부분에 `coordinate-edit` 시트 가드 추가(`setSelectedPinId(coordinateEditTarget.id)` 호출 후 정상 정리). `setCoordinateEditTarget(null)` 추가. `useCallback` 의존성에 `activeSheet, coordinateEditTarget` 추가.
  - 검증: `tsc --noEmit` exit 0

### Warning — 1건 [phase-review 이월]

- **[Warning] `PinServiceIT.java`** — 좌표 수정(`coordinateProvided=true`) 서비스 레이어 IT 케이스 누락. 컨트롤러 IT(`PinV1ControllerIntegrationTest`)가 전체 스택을 커버하므로 즉각적 회귀 위험 낮음. 권장 케이스: `updatePin_coordinateOnly_updatesCoordinateAndKeepsOtherFields`.

### Info — 1건 [phase-review 이월]

- **[Info] `PinCoordinateEditPicker.tsx:13-14`** — `mapboxToken` prop 미사용 (eslint-disable로 경고 억제, "향후 reverse geocoding 확장 여지" 주석 있음). 확장 계획 미확정이면 향후 삭제 검토.

### QUESTION — 2건 [phase-review에서 사용자 확인]

- **[QUESTION] `PinPopup.tsx:119-120` 및 `PinCoordinateEditPicker.tsx:32-33`** — `Number(pin.longitude/latitude)` 방어 캐스팅. `PinSummaryResponse`에서 `number` 타입이고 JacksonConfig WRITE_BIGDECIMAL_AS_PLAIN으로 wire가 JSON number 확인됨. 방어 코드 유지(런타임 안전성) vs 제거(타입 일관성) 선택 필요.

- **[QUESTION] `MapClient.tsx:396-399`** — 좌표 확정 후 `setSelectedPinId(pinId)`를 `startOptimisticTransition` 내부에서 호출. 태그/메모 수정은 transition 외부에서 호출하는데 좌표만 내부에서 호출하는 이유 확인 필요 (popup 새 좌표 즉시 노출 의도로 보임).

## AC 충족 판정

| AC | 판정 | 근거 |
|----|------|------|
| AC-1~3 (FR-PIN-7) | 충족 | PinV1ControllerIntegrationTest: 좌표 갱신/범위 초과/비활성 멤버 케이스 모두 작성됨, test exit 0 |
| AC-4 (FR-PIN-8) | Critical 자동 수정 후 충족 | picker UX + flyTo + optimistic patch + 자동 롤백. 데스크톱 × 닫기 버그는 handleSheetClose 패치로 해소 |
| AC-5 (FR-PIN-7) | 충족 | Provided 패턴으로 좌표 외 필드 불변, IT 케이스 검증 |
| AC-6 (FR-PIN-7) | 충족 | UpdatePinRequest.toCommand: 양쪽 null이면 coordinateProvided=false |
| AC-7~9 (FR-BOT-9,10) | 코드 변경 없음 | 운영 작업(빌더 콘솔) + 실기기 E2E. phase-complete에서 사용자 수동 |
| AC-10 (FR-MAP-6) | 충족 | context/map/status.md Pretendard 항목 "완료"로 갱신 |
| AC-11 (FR-MAP-7) | 충족 | context/map/mapbox-token-sop.md 신규 작성 + status.md cross-link |
| AC-12 (FR-MAP-8) | 충족 (DevTools 검증은 사용자 수동) | globals.css body font-family `var(--font-sans), Arial, Helvetica, sans-serif` |
| AC-13~15 (FR-PIN-9 회귀) | 충족 | useOptimistic reducer 무변경, supercluster/PinPopup screenPos 동작 무변경, 페이지네이션 API 영향 없음. test exit 0 |

## 종합
- Critical 1건 자동 수정 완료
- Warning/Info/QUESTION 총 4건 phase-review로 이월
- 빌드 검증 백엔드 + 프론트엔드 모두 Green
- Phase 2.10 구현 완료. phase-review 진입 준비됨.
