# recommendation 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-REC-1 | 브라우저 위치 권한 요청 | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `useGeolocation` 훅 + `PermissionDialog` (vertical, "위치 사용 허용"/"나중에"). `navigator.permissions.query` 사전 조회 |
| FR-REC-2 | 거리 범위 자동 적용 (1km 기본, 시작 버튼 없이 즉시 추첨) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — PRD에서 명시적 범위 선택 UI 제거. 셔플 탭 즉시 `runRoulette` 진입 |
| FR-REC-3 | 그룹 핀 PLACE 태그 + Bounding Box + Haversine + 랜덤 1건 | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `roulette.ts::pickRandomWithExpansion` + Vitest 단위 13건. 5분 캐시 정책으로 stale 시 BFF 재조회 |
| FR-REC-4 | 범위 내 0건 → 다음 범위 자동 확장 (1km → 5km → 10km) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `ROULETTE_RADIUS_STEPS_KM` 순회. 10km도 0건이면 "이 지도에 아직 핀이 없어요" |
| FR-REC-5 | 추천 결과 카드 + "지도에서 보기" + "다시" 재추첨 | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `RouletteResultContent` (Noto Serif KR 16/700 가게명 + JetBrains Mono 주소 + 거리 강조 "여기서 N.Nkm"). `handleShowOnMap` → `map.flyTo` + popup 자동. `reRollFromSamePool` 동일 풀 재추첨 |
| FR-REC-6 | MEMORY 포함 옵션 토글 | ✅ | [#17](https://github.com/rnqhstmd/wherewego/pull/17) — Phase 2.6 PR-A: 결과 카드 하단 "추억 핀도 포함" 토글, `MapClient.includeMemory` 세션 단위 state, 토글 변경 후 "다시" 시 풀 재구성(`includeMemoryAtPick` boolean 분기) |

## 후속 작업

- **Phase 2.7 완료**: RouletteResultContent Vitest 2건(체크박스 콜백 + 거리 레이블 포맷) — [#20](https://github.com/rnqhstmd/wherewego/pull/20)
- **운영 UX 개선 (2026-05-20)**: `RouletteSpinContent` 안내 문구를 `{radius}km 이내 · 장소 핀 {N}개 중에서` → `{radius}km 이내에서 고르고 있어요` 로 교체. 추첨 직전 일시적으로 후보 수가 0 으로 노출되던 어색함 제거 — [#33](https://github.com/rnqhstmd/wherewego/pull/33)
- **모바일 버튼 양옆 잘림 수정 (2026-05-20)**: `RouletteResultContent` "지도에서 보기"/"다시" 버튼이 모바일 ≤ 480px 환경에서 컨테이너 밖으로 밀려나가던 문제 해소. flex item 기본 `min-width: auto` + `whiteSpace: nowrap` 조합으로 인한 강제 오버플로를 `minWidth: 0` 추가로 차단하고, `padding: "12px 0"` → `"11px 12px"` 로 좌우 여백 회복 — [#33](https://github.com/rnqhstmd/wherewego/pull/33)
