# PR 비즈니스 맥락: Phase 2.10 — 잔여 후속 통합

## 배경

Phase 2.8(핀 도메인 UX) · Phase 2.9(페이지네이션 API + GL 사전 분석)가 완료되면서 MVP 기능의 핵심은 동작하고 있다. Phase 2.10은 MVP 운영 단계에서 남겨둔 세 가지 잔여 항목을 **단일 PR로 통합 정리**하여, Phase 3.0(Group N인 확장 등 비즈니스 정책 확장)으로 넘어가기 전에 기술 부채를 해소한다.

3개 도메인을 묶은 이유: 각 항목이 독립적으로는 작으나 함께 모아 일괄 마감하는 것이 운영적으로 효율적이며, 운영자/리뷰어 입장에서 "Phase 2.10 = 잔여 후속 통합" 단일 마일스톤으로 인식하기 쉽다.

## 요구사항 (3개 도메인)

### ① 핀 도메인 [FR-PIN]

- **FR-PIN-7**: `PATCH /api/v1/groups/{groupId}/pins/{pinId}`에 `latitude`/`longitude` 필드 추가하여 핀 좌표 변경 가능. `coordinateProvided` 단일 플래그 + 범위 검증(-90~90, -180~180), `PIN_COORDINATE_INVALID` 에러. 활성 그룹 멤버만 수정.
- **FR-PIN-8**: 지도 `PinPopup` ⋮ 메뉴에 "좌표 수정" 진입점. 기존 picker UX 재사용 시트(`PinCoordinateEditPicker`) 분리 구현. `useOptimistic patch` 즉시 마커 이동 + 실패 시 자동 롤백.
- **FR-PIN-9**: Phase 2.8 ~ 2.9 회귀 없음 (useOptimistic, supercluster, screenPos, 페이지네이션 API).

### ② 챗봇 도메인 [FR-BOT]

- **FR-BOT-9**: 카카오 i 오픈빌더 콘솔에서 PLACE_SELECTION 버튼 `action="message"` + `extra.placeId` 매핑 실제 설정 완료 (운영 작업, 코드 변경 없음).
- **FR-BOT-10**: PLACE_SELECTION Phase 2.7 IT 5케이스 회귀 통과 + 카카오톡 실기기 1회 수동 E2E 검증.

### ③ 지도/인프라 도메인 [FR-MAP]

- **FR-MAP-6**: `context/map/status.md` Pretendard 항목 사실 정합화.
- **FR-MAP-7**: `context/map/mapbox-token-sop.md` Mapbox 토큰 회전 SOP 운영자 가이드 신설. **post-review 후속**: `context/map/mapbox-env.md` 환경변수 가이드도 추가 신설.
- **FR-MAP-8**: `frontend/src/app/globals.css` body `font-family`를 `var(--font-sans), Arial, Helvetica, sans-serif`로 변경하여 Pretendard 실제 적용.

### 제외 (의식적)

- 삭제 핀 복원 기능 — 사용자 결정으로 본 Phase에서 제거.
- Noto Serif KR / Gowun Batang / JetBrains Mono Google Fonts CDN → self-host 전환 — 별도 Phase.
- DOM Marker → GL symbol layer 마이그레이션 — 그룹 핀 500건 미도달 시 Phase 2.9 결정 유지.

## 설계 결정

- **`coordinateProvided` 단일 플래그**: 좌표는 위/경도가 분리 불가능한 의미 단위이므로, 기존 4쌍 Provided 컨벤션과 다른 단일 플래그로 묶음. Javadoc + 설계서 §2.1에 의도 명시.
- **`PinCoordinateEditPicker` 별도 컴포넌트**: 신규 등록 picker(`AddPinPickerContent`)와 별도. 흐름 단계 수 차이(2단계 vs 1단계), reverse geocoding 리스크, 콜백 시그니처 차이, 신규 등록 플로우 무영향 보장.
- **M5(picker 진입 시 popup 닫기)**: 좌표 수정 진입 시 `selectedPinId=null`로 popup 언마운트 → picker 집중 + screenPos 깜빡임 차단. 완료/취소/실패 시 `setSelectedPinId(pinId)` 재노출.
- **flyTo 진입**: 좌표 수정 진입 시 `flyTo(기존 핀, zoom=16)`으로 viewport 고정 → 마커 깜빡임 최소화.
- **단일 PR 통합**: 3개 도메인이 기능적으로 독립적이나 사용자 의식적 결정. PR 본문 도메인별 섹션 분리로 리뷰 가독성 확보.

## Audit Summary

- 총 16건 (CRITICAL: 0, HIGH: 4, MEDIUM: 5, LOW: 4) — Trust Ledger 1라운드 + 자기점검 이월 반영
- **[HIGH/RISK] `coordinateError` 미표시 버그 → 해소** (PinPopup `useEffect`로 coordinateError 수신 시 expanded=true 자동 펼침)
- [HIGH/GAP] FR-BOT-9 빌더 콘솔 설정 증적 → 본 PR 본문 기록 필요
- [HIGH/GAP] FR-BOT-10 카카오톡 실기기 E2E 검증 절차/결과 → 본 PR 본문 기록 필요
- [HIGH/DOC-INTEGRITY] `context/*/status.md` `[PR-LINK]` 3곳 placeholder → 머지 후 실 PR 번호로 일괄 교체
- [MEDIUM] PinServiceIT 좌표 수정 케이스 미존재 (컨트롤러 IT가 DB 영속 검증으로 커버, 회귀 위험 낮음)

자세한 항목은 `.dev/feat-phase-2-10/trust-ledger.md` 참조.

## 운영 작업 증적 (FR-BOT-9, FR-BOT-10)

> **본 PR 본문에서 작성자(운영자/머지 담당)가 직접 기록해야 하는 항목**:
> 1. 카카오 i 오픈빌더 콘솔에서 PLACE_SELECTION 버튼이 `action="message"` + `extra.placeId` 매핑으로 설정되었음을 보여주는 스크린샷 또는 설정 항목 텍스트
> 2. 카카오톡 실기기 1회 E2E 검증의 절차(검색→복수 결과 카드→장소 선택)와 결과(핀 정상 등록 + 완료 알림 수신)

## 머지 후 후속 (자동 처리 X, 작업자 수동)

- `context/pin/status.md`, `context/map/status.md`, `context/chatbot/status.md`의 `[PR-LINK]` 3곳을 실 PR 번호로 일괄 교체
- (선택) Mapbox 대시보드에서 운영 토큰의 URL Restriction 실제 적용 여부 점검 (Trust Ledger MEDIUM)
- (선택) `PinServiceIT`에 좌표 수정 케이스 추가 여부 정책 결정 (Trust Ledger MEDIUM)
