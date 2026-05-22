# tag 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

- 저장 방식: `pins.tag` enum 컬럼 (`REEL` | `WISH` | `MEMORY`). 별도 테이블 없음 (MVP 단순화)
- 입력 경로:
  - [[chatbot]] 자동 등록 → 항상 `REEL` (`Pin.autoFromInstagram()` / `Pin.fromSelection()` 기본값)
  - [[map]] 웹 직접 등록 UI → 사용자가 `WISH` / `MEMORY` 선택 (REEL 미노출)
  - **백엔드 API는 enum 검증만 — REEL의 등록 경로 제한은 UI 차원** (D3 정책)
- 수정 경로: 핀 편집 다이얼로그 / 지도 팝업에서 `REEL` / `WISH` / `MEMORY` 3종 모두 선택 가능 ([[pin]] FR-PIN-4, FR-TAG-9)
- 시각화:
  - REEL → Mapbox custom marker: 인스타그램 스타일 SVG (둥근 정사각형 외곽선 + 중앙 렌즈 원 + 우상단 점). Hex: `#C5B4E3` (연보라)
  - WISH → Mapbox custom marker: 채워진 원. Hex: `#A8E6CF` (민트)
  - MEMORY → Mapbox custom marker: 하트. Hex: `#FFB3C6` (핑크)
- 디자인 토큰: `frontend/src/lib/design/tokens.ts` (`pinReel`/`pinWish`/`pinMemory`) + `frontend/src/app/globals.css @theme` (`--color-pin-reel`/`--color-pin-wish`/`--color-pin-memory`) 양쪽 동기
- 공통 SVG 모듈: `frontend/src/lib/pin/markers.tsx` — PinDot React 컴포넌트와 MapboxView vanilla DOM이 단일 소스 공유
- 향후 확장: 그룹 커스텀 태그(예: "맛집", "카페")는 v2에서 검토. 도입 시 `tag_definitions` 테이블 + `pin_tags` 매핑(N:M)으로 마이그레이션

## 마이그레이션 이력

| Flyway | 적용 시점 | 내용 |
|--------|----------|------|
| V001 | Phase 0 | `pins.tag` 컬럼 + `chk_pins_tag CHECK (tag IN ('PLACE','MEMORY'))` 최초 정의 |
| **V006** | **Phase 7** | **단일 합본 마이그레이션**: ALTER(CHECK 확장 PLACE/REEL/WISH/MEMORY) → UPDATE(PLACE→REEL 일괄 변환) → ALTER(CHECK 축소 REEL/WISH/MEMORY). 단일 트랜잭션, ~2,500핀 <1초, 무중단 5~10초 윈도우 |

## 주제 문서

| 주제 | 설명 |
|------|------|
| (없음 — MVP 단순) | |
