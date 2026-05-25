# tag 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- 🚧 진행 중 — 로컬 작업 완료, 커밋/PR 대기
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-TAG-1 | `pins.tag` enum 컬럼 (PLACE/MEMORY) NOT NULL | ✅ | [#1](https://github.com/rnqhstmd/wherewego/pull/1) — V001 schema `tag VARCHAR(10) NOT NULL` + `chk_pins_tag CHECK (tag IN ('PLACE','MEMORY'))` + `idx_pins_group_tag` 인덱스. JPA `PinTag` enum (`Pin.java:67`). |
| FR-TAG-2 | 챗봇 자동 등록 시 PLACE 기본값 적용 | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — `Pin.autoFromInstagram()`/`Pin.fromSelection()`이 `PinTag.PLACE` 고정 (`Pin.java:102, 119`) |
| FR-TAG-3 | 웹 직접 등록 시 PLACE/MEMORY 선택 UI | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `MemoTagPanelContent` + `createPinAction` (Phase 6 핀 직접 등록 웹 API와 함께) |
| FR-TAG-4 | 핀 상세에서 태그 변경 | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `SpeechBubblePopup` ⋮ 인라인 `PinTag` 칩 + `useOptimistic` 즉시 마커 갱신 (`map` 도메인 FR-MAP-2 참조) |
| FR-TAG-5 | 지도 마커 시각 구분 (파란 동그라미 vs 핑크 하트) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `renderPinDotInto` + `PinDot` (`#7BB3E8` PLACE / `#F4A8B0` MEMORY) (`map` 도메인 FR-MAP-2 참조) |
| FR-TAG-6 | 태그별 필터 토글 (옵션, MVP 후반) | ✅ | [#17](https://github.com/rnqhstmd/wherewego/pull/17) — Phase 2.6 PR-A 룰렛 "추억 핀도 포함" 토글(`MapClient.includeMemory`) + Phase 2.7 RouletteResultContent Vitest 검증 — [#20](https://github.com/rnqhstmd/wherewego/pull/20). 지도 일반 마커는 항상 PLACE+MEMORY 동시 렌더(별도 필터 미적용). |
| FR-TAG-7 | enum 3종 확장 — PLACE→REEL+WISH 분리, MEMORY 유지. Flyway 마이그레이션(기존 PLACE 핀 → REEL 일괄 변환), `chk_pins_tag` 제약 갱신 | ✅ | [#38](https://github.com/rnqhstmd/wherewego/pull/38) — Phase 7 (V006 단일 합본) |
| FR-TAG-8 | 챗봇 자동 등록 기본값 PLACE→REEL 변경 (`Pin.autoFromInstagram()` / `Pin.fromSelection()`) | ✅ | [#38](https://github.com/rnqhstmd/wherewego/pull/38) |
| FR-TAG-9 | 웹 직접 등록 UI — REEL 제외, WISH/MEMORY 2종 선택 (REEL은 챗봇 전용). 핀 편집 다이얼로그는 3종 모두 허용 (소급 수정 가능) | ✅ | [#38](https://github.com/rnqhstmd/wherewego/pull/38) |
| FR-TAG-10 | 지도 마커 3종 시각 구분 — REEL(하늘색 `#7BB3E8` 동그라미) / WISH(노랑 `#F4C842` 별모양) / MEMORY(핑크 `#FFB3C6` 하트) | ✅ | [#38](https://github.com/rnqhstmd/wherewego/pull/38) |
| FR-TAG-11 | 룰렛 후보 풀 갱신 — PLACE→REEL+WISH 포함, MEMORY 제외 기본 (기존 `includeMemory` 토글 로직 연동). MapClient 토글 부분버그(Phase 2.6 PR #17 잔존) 정합화 | ✅ | [#38](https://github.com/rnqhstmd/wherewego/pull/38) |
| FR-TAG-12 | 지도 좌하단 태그 필터 버튼 — 깔때기 아이콘 클릭 시 팝오버(전체/추억/위시/발견 체크박스). 기본 전체 선택. 체크 해제된 태그는 지도 마커뿐 아니라 룰렛 후보 풀에서도 제외(`computeTagsAllowed(includeMemory, visibleTags)` 교집합). 필터링 중 버튼 우상단에 점 인디케이터 표시 | ✅ | [#61](https://github.com/rnqhstmd/wherewego/pull/61) — `TagFilterButton.tsx` + `MapClient.visibleTags` 상태 |
