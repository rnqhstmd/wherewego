# map 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-MAP-1 | Mapbox 3D 지구본 초기 렌더 | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `MapboxView` `projection: { name: "globe" }` + `setFog` + DOM Marker 인스턴스 캐시 패턴 |
| FR-MAP-2 | 태그별 마커 시각화 (REEL=연보라 인스타 아이콘 `#C5B4E3`, WISH=민트 동그라미 `#A8E6CF`, MEMORY=핑크 하트 `#FFB3C6`) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) 초기 구현 → [#38](https://github.com/rnqhstmd/wherewego/pull/38) Phase 7에서 3종으로 확장. `lib/pin/markers.tsx` 공통 SVG 모듈 |
| FR-MAP-3 | SpeechBubblePopup 정보창 (메모 + 가게명/주소 + 날짜·작성자·⋮ + 태그 변경) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `SpeechBubblePopup` + `PinPopup` 화면 좌표 계산. ⋮ 인라인 PinTag 칩 펼침, `useOptimistic`으로 즉시 마커 갱신 |
| FR-MAP-4 | 커스텀 검색 UI + 클러스터링 (Tailwind/디자인 토큰, supercluster) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `SearchPanelContent`(debounce 300ms, 최대 5건) + `clusterer.ts` + `ClusterBanner`(localStorage 1회) |
| FR-MAP-5 | 검색 → 태그 선택 → 핀 추가 플로우 (3클릭) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `MemoTagPanelContent` + `createPinAction` (`revalidatePath` 생략, 응답 직접 반영) |
| ~~FR-MAP-X~~ | ~~방문 체크 버튼~~ — **제거됨** | — | |

## 후속 작업

- **Phase 12 완료 (2026-05-27)**: 마커 3단계 시각화 + 맵 필터 확장 — `PinMarker` / `lib/pin/markers.tsx` 3단계 분기 (WANT 0표=하늘색 `#7BB3E8` 1.0배 → WANT 1표+ 과반 미달=진보라 `#7B68EE` 1.1배 → 과반 달성=노랑 별 `#F4C842` 1.2배 + 전환 직후 0.5초 펄스 1회). MEMORY 핀에는 WANT 버튼 UI 자체 미노출. `PinPopup`에 출처 뱃지(📹/✏️) + "가고 싶어요" 토글 버튼(REEL/WISH 노출). `MapFilter`에 "발견 ▾" 드롭다운 서브 토글(모든 발견 / 관심 있는 발견, `?tag=REEL&interest=true`). 알림 → "📍 지도에서 보기" → `/map?reel_bundle={notificationId}` 처리 + 비강조 핀 `opacity: 0.3` 강조 + 상단 배너 "릴스 저장 핀 N개 표시 중 [해제]". `TagTooltip` `?` 아이콘 클릭 시 진행 다이어그램(발견→위시→추억) 모달. 상세: [pin/phase-12-pin-experience-v2.md](../pin/phase-12-pin-experience-v2.md) §마커 / §맵 필터 / §태그 진행 다이어그램 — [PR #76](https://github.com/rnqhstmd/wherewego/pull/76)


- **Phase 2.10 완료**: Pretendard 폰트 self-host (`public/fonts/PretendardVariable.woff2` + `next/font/local`로 `--font-sans` 주입 완료) + `globals.css` body `font-family`를 `var(--font-sans)` 토큰으로 연결 완료 — [#24](https://github.com/rnqhstmd/wherewego/pull/24)
- **Phase 2.10 완료**: Mapbox 토큰 회전 SOP 운영자 가이드 — [mapbox-token-sop.md](./mapbox-token-sop.md) — [#24](https://github.com/rnqhstmd/wherewego/pull/24)
- **Phase 2.9 완료**: DOM Marker → GL symbol layer 마이그레이션 사전 분석 문서화 — `PinPopup` 좌표 계산(`map.project` → `queryRenderedFeatures`), `useOptimistic` patch|remove → `source.setData`/`setFeatureState`, supercluster + GL layer 클러스터 클릭 핸들러 3개 항목 현재/전환 후 대비 + 보안 disclaimer + `status.md` 양방향 cross-link. 실제 마이그레이션은 500핀 초과 시점에 별도 Phase로 진행 — 사전 분석: [gl-migration-plan.md](./gl-migration-plan.md) — [#22](https://github.com/rnqhstmd/wherewego/pull/22)
- **Phase 2.7 완료**: 디자인 번들 컴포넌트 테스트 — PinDot/PinTag/SpeechBubblePopup Vitest 7건(토큰 결합 검증 포함) — [#20](https://github.com/rnqhstmd/wherewego/pull/20)
- **Phase 2.8 완료**: 정보창 ⋮ 메뉴 확장 (삭제 액션) — `PinPopup` footer 펼침 영역에 HLine + 우측 정렬 텍스트 버튼(`colors.pinNew`), `PinDeleteConfirm` 재사용, `useOptimistic` reducer 일반화로 마커 즉시 제거. 권한 미충족 시 인라인 에러 표시. 챗봇 경로 instagramUrl XSS 양방향 검증 동반 — [#21](https://github.com/rnqhstmd/wherewego/pull/21)
- **운영 UX 개선 (2026-05-20)**: 검색 결과 클릭 / 크로스헤어 확정 후 메모 단계 진입 시 cta 색 드롭핀 미리보기 마커 표시. `MapboxView.previewMarker` prop 도입, anchor:bottom + `@keyframes maygo-preview-pin-drop` 애니메이션. 외부 div 는 Mapbox transform 전용, 내부 div 에서만 애니메이션을 적용해 좌표 밀림 회피. 메모 저장/취소 시 자동 제거 — [#33](https://github.com/rnqhstmd/wherewego/pull/33)
- **모바일 UX 컴팩트 (2026-05-20)**: `max-width: 480px` 뷰포트에서 `SpeechBubblePopup` 너비(296→240) / 패딩 / 메모·장소·주소 폰트 / 메뉴 버튼 사이즈를 일괄 축소. 노트북(≥481px) 사이즈는 기존 유지. `useMediaQuery` 훅을 `app/map/_hooks/` → `lib/hooks/` 공용 위치로 이동 — [#33](https://github.com/rnqhstmd/wherewego/pull/33)
- **모바일 가시 영역 중앙 배치 (2026-05-20)**: 메모 단계 진입 시 `flyTo({ padding: { bottom: vh * 0.55 } })` 로 마커가 Sheet 에 가리지 않는 영역의 중앙에 위치하도록 조정. 메모 종료(취소/저장/시트 닫기/+ 탭 진입) 시점에 `resetMapPadding()` 호출로 Mapbox 영속 padding 상태를 0 으로 되돌려 다음 흐름의 `map.getCenter()` 가 시각 viewport 중앙과 일치하도록 보장 — [#33](https://github.com/rnqhstmd/wherewego/pull/33)
- **GeolocateControl 위치 보정 (2026-05-20)**: 모바일에서 `.mapboxgl-ctrl-bottom-right { bottom: 84px; right: 10px }` (`!important`) 로 ActionBar(64px) 와 시각적으로 분리. 데스크탑(≥768px) 은 16/16 으로 기본 유지. Mapbox 기본 CSS 의 우선순위를 넘기기 위해 `!important` 가 필요 — [#33](https://github.com/rnqhstmd/wherewego/pull/33)
- **+ 크로스헤어 좌표 mismatch 수정 (2026-05-20)**: 이전 검색 흐름의 `flyTo({ padding })` 잔여로 `AddPinPickerContent` 의 `map.getCenter()` 가 optical center 를 반환해 의도와 다른 위치가 저장되던 버그 해소. `handleTabChange("add")` 진입 시 `resetMapPadding()` 호출로 시각 viewport 중앙 = `map.getCenter()` 보장 — [#33](https://github.com/rnqhstmd/wherewego/pull/33)
- **iOS GeolocateControl 버튼 비활성화 버그 수정 (2026-05-23)**: `MapboxView.tsx` `map.on("load")` 내 자동 `getCurrentPosition` 호출 전 `navigator.permissions.query({name:'geolocation'})` 로 권한 상태 사전 확인. `'granted'`일 때만 자동 flyTo 실행 — iOS Safari/Chrome에서 blocking 권한 modal 후 거부 시 Mapbox `GeolocateControl._checkGeolocationSupport`가 `'denied'` 상태를 받아 버튼이 disabled 되는 버그 수정. `'prompt'`·`'denied'`는 자동 요청 건너뜀 → 버튼 활성 유지. Permissions API 미지원 구형 브라우저는 기존 동작 유지 — [PR #54](https://github.com/rnqhstmd/wherewego/pull/54)

- **Phase 10 완료 (2026-05-24)**: 장소 방문 감지 + MEMORY 자동 전환 — [PR #57](https://github.com/rnqhstmd/wherewego/pull/57)
  - `VisitToast`: PinPopup 스타일 카드 리디자인. 화면 정중앙(`top:50% + translate(-50%,-50%)`, max-width 380px), `{장소명}에 함께 방문하셨나요?` 카피 + 주소 + 메모 미리보기(있을 때만) + `written by` 표시. CTA "네, 다녀왔어요 →" / "다음에 올게요".
  - `VisitMemoSheet`: "🌸 다녀온 흔적" + "다녀온 날 · YYYY.MM.DD" + 자유 입력. 저장/건너뛰기 후 해당 핀 PinPopup 자동 오픈.
  - `MapboxView`: `forwardRef` + `useImperativeHandle`로 `triggerVisitCelebration(pinId)` 노출. `runMarkerBounceAndConfetti` = 마커 4-stage cubic-bezier bounce 900ms (`scale 1.0 → 1.5 → 1.0`) + 6개 하트 22~30px confetti 1000ms (CSS keyframe + `--dx/--dy` 위치 분산). granted 권한 시 5초 자동 폴링으로 `geolocate` 콜백 희소성 보완.
  - `MapClient`: `useMemo`로 `wishReelPins` 캐싱. `handleVisitConfirm` = `Promise.all([flyTo 1500ms (moveend 또는 안전 timeout 대기), PATCH tag=MEMORY])` → 250ms 일시 정지 → `triggerVisitCelebration` → `setVisitMemoPin`. visibilitychange 리스너로 폴링 일시 정지/재개.
  - `NotificationPanel`: 600px 고정 높이 + `bodyRef`/`listScrollRef`로 패널 닫기/다시 열기 사이 스크롤 위치 보존 (최대 50건 누적 시에도 사용자 위치 유지).
  - `NotificationItem`: "추억이 한 곳 더 쌓였어요" 통일 카피. `NotificationPinList`: "함께 만든 추억 N곳" + VISIT_DETECTED MEMORY 핀에 "● 추억" 배지.
  - 성능: BBox prefilter (`LAT_DEG_PER_METER` 기반 ±0.001도 박스) 통과 핀만 Haversine 호출 — 1000핀 환경에서 99% 컷.

- **사이트 파비콘 교체 (2026-05-20)**: App Router 컨벤션(`app/icon.png` + `app/apple-icon.png`)으로 디자인 시스템의 하트 핀 + 지구본 PNG 적용. 기존 `app/favicon.ico` (Next.js 기본 삼각형) 삭제 — [#33](https://github.com/rnqhstmd/wherewego/pull/33)
