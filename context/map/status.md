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

## 후속 작업 (Phase 2.6)

- Pretendard 폰트 self-host 전환 (현재 CDN, `frontend/public/fonts/README.md`)
- Mapbox 토큰 회전 SOP 운영자 가이드 (`.env.local.example`에 안내, 대시보드 URL Restriction 별도 작업)
- **Phase 2.9 완료**: DOM Marker → GL symbol layer 마이그레이션 사전 분석 문서화 — `PinPopup` 좌표 계산(`map.project` → `queryRenderedFeatures`), `useOptimistic` patch|remove → `source.setData`/`setFeatureState`, supercluster + GL layer 클러스터 클릭 핸들러 3개 항목 현재/전환 후 대비 + 보안 disclaimer + `status.md` 양방향 cross-link. 실제 마이그레이션은 500핀 초과 시점에 별도 Phase로 진행 — 사전 분석: [gl-migration-plan.md](./gl-migration-plan.md) — [#22](https://github.com/rnqhstmd/wherewego/pull/22)
- **Phase 2.7 완료**: 디자인 번들 컴포넌트 테스트 — PinDot/PinTag/SpeechBubblePopup Vitest 7건(토큰 결합 검증 포함) — [#20](https://github.com/rnqhstmd/wherewego/pull/20)
- **Phase 2.8 완료**: 정보창 ⋮ 메뉴 확장 (삭제 액션) — `PinPopup` footer 펼침 영역에 HLine + 우측 정렬 텍스트 버튼(`colors.pinNew`), `PinDeleteConfirm` 재사용, `useOptimistic` reducer 일반화로 마커 즉시 제거. 권한 미충족 시 인라인 에러 표시. 챗봇 경로 instagramUrl XSS 양방향 검증 동반 — [#21](https://github.com/rnqhstmd/wherewego/pull/21)
