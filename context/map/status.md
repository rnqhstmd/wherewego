# map 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-MAP-1 | Mapbox 3D 지구본 초기 렌더 | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `MapboxView` `projection: { name: "globe" }` + `setFog` + DOM Marker 인스턴스 캐시 패턴 |
| FR-MAP-2 | 태그별 마커 시각화 (PLACE=파란 동그라미 `#7BB3E8`, MEMORY=핑크 하트 SVG `#F4A8B0`) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `renderPinDotInto` + `PinDot` 컴포넌트 |
| FR-MAP-3 | SpeechBubblePopup 정보창 (메모 + 가게명/주소 + 날짜·작성자·⋮ + 태그 변경) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `SpeechBubblePopup` + `PinPopup` 화면 좌표 계산. ⋮ 인라인 PinTag 칩 펼침, `useOptimistic`으로 즉시 마커 갱신 |
| FR-MAP-4 | 커스텀 검색 UI + 클러스터링 (Tailwind/디자인 토큰, supercluster) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `SearchPanelContent`(debounce 300ms, 최대 5건) + `clusterer.ts` + `ClusterBanner`(localStorage 1회) |
| FR-MAP-5 | 검색 → 태그 선택 → 핀 추가 플로우 (3클릭) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `MemoTagPanelContent` + `createPinAction` (`revalidatePath` 생략, 응답 직접 반영) |
| ~~FR-MAP-X~~ | ~~방문 체크 버튼~~ — **제거됨** | — | |

## 후속 작업

- Pretendard 폰트 self-host 전환 (현재 CDN, `frontend/public/fonts/README.md`)
- Mapbox 토큰 회전 SOP 운영자 가이드 (`.env.local.example`에 안내, 대시보드 URL Restriction 별도 작업)
- 정보창 ⋮ 메뉴 확장 (삭제 등 추가 액션) — 현재는 태그 변경만
- DOM Marker → GL symbol layer 마이그레이션 (500핀 초과 시)
- 디자인 번들 컴포넌트 테스트 (Vitest + Testing Library) — Phase 후속
