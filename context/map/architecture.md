# map 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

```
[Next.js / Vercel]
   │
   ├─ Mapbox GL JS (3D globe + 커스텀 마커 렌더)
   ├─ Tailwind 커스텀 검색 드롭다운
   └─ Framer Motion (정보창 애니메이션)
        │
        ▼
   Spring Boot REST API
        │
        ├─ GET /api/pins?groupId=…&tag=…  ([[pin]])
        ├─ GET /api/places/search?q=…     ([[place]])
        └─ POST /api/pins                 ([[pin]] + [[tag]] 선택)
```

- 데이터 흐름: 초기 진입 시 그룹의 모든 핀을 한 번에 받아 마커로 렌더
- 마커 표현 ([[tag]] 도메인 참조):
  - PLACE → 파란 파스텔 동그라미
  - MEMORY → 핑크 파스텔 하트
  - visited 별도 표시 없음 (PRD 정책 변경으로 제거)
- 정보창 (AMOU 스타일):
  - 장소명, 메모, 원본 릴스 바로가기, 태그 변경 (PLACE ↔ MEMORY)
  - ⋮ 펼침 영역: 세그먼트 탭(태그/메모) + HLine 분리 + 우측 정렬 "삭제" 텍스트 버튼 (Phase 2.8, `colors.pinNew`)
  - 삭제 흐름: `onRequestDelete` → MapClient에서 `PinDeleteConfirm` 재사용 → 확인 시 `useOptimistic({kind:"remove",pinId})`로 마커 즉시 제거 + 팝업 닫힘 (QE-1)
  - 삭제 실패 시: useOptimistic transition 종료 시 자동 롤백 → 핀별 `deleteErrorByPinId` 키 맵으로 인라인 에러 표시
  - **방문 체크 버튼 제거** (visited 폐기)
- `useOptimistic` reducer 일반화 (Phase 2.8):
  - `{kind:"patch", pinId, patch} | {kind:"remove", pinId}` 판별 유니온
  - 태그/메모 갱신은 patch, 삭제는 remove로 처리하여 마커 인스턴스 캐시 유지 + 즉시 시각 반영
  - `revalidatePath("/map")` 미호출 (mapbox-gl 재마운트 회피, MUST-1). `revalidatePath("/pins")`만 try/catch로 fail-safe 호출하여 양쪽 라우트 정합성 확보
- 검색 UX: 입력 → 백엔드 → 카카오/Google → 결과 JSON → 커스텀 드롭다운 → 선택 → 태그 선택 → Mapbox 마커 추가
- 멤버별 핀 구분: 안 함 (정보창 내 created_by 표시만)

## 주제 문서

| 주제 | 설명 |
|------|------|
| gl-migration-plan | DOM Marker → GL symbol layer 전환 시 변경 지점 사전 분석 (Phase 2.9) |
| mapbox-token-sop | Mapbox 액세스 토큰 회전·발급·URL Restriction·폐기 SOP — 운영자 가이드 (Phase 2.10) |
| mapbox-env | Mapbox 환경변수(`NEXT_PUBLIC_MAPBOX_TOKEN`, `NEXT_PUBLIC_MAPBOX_STYLE_URL`) 형식·사용처·설정 흐름 가이드 (Phase 2.10) |

## iOS 네이티브 클라이언트 (P4, [PR #91](https://github.com/rnqhstmd/wherewego/pull/91))

웹 `MapClient.tsx`/`MapboxView.tsx` 동작을 SwiftUI 로 포팅한 iOS 네이티브 지도(`ios/WhereWeGo/Features/Map`, `Core/Map`).

- **Mapbox SDK 격리(배선 우선·토큰 나중)**: `MapRenderer` 프로토콜(SDK 비의존 모델 `MapMarker`/`CameraTarget`/`MapEvent`) 뒤로 SDK 의존을 숨긴다. `import MapboxMaps` 와 모든 Mapbox 타입 참조는 `Core/Map/MapboxMapView.swift` **단일 파일**에 `#if canImport(MapboxMaps)/#else stub`으로 격리(검증 게이트: `grep -rl "import MapboxMaps" == 1개`). `MapContainerView`(항상 컴파일)는 `MapConfig.isMapboxConfigured`로 Mapbox 뷰 vs `PlaceholderMapView` 선택. → **secret download token(.netrc) 없이도 빌드·테스트 통과**(DoD-A), 실렌더링은 token 발급 후(DoD-B).
- **styleURL**: 웹과 동일, 미설정 시 `mapbox://styles/mapbox/standard` fallback. token/style 은 xcconfig `MAPBOX_ACCESS_TOKEN`/`MAPBOX_STYLE_URL`(placeholder `MAPBOX_TOKEN_NOT_SET`).
- **마커**: 태그별 REEL/WISH/MEMORY 구분, GeoJSON `cluster:true`(radius 60/maxZoom 16/minPoints 2, 웹 clusterer.ts 동치) — #if 실구현(DoD-B). 카메라 flyTo(zoom15, 700ms)/fitBounds.
- **상태(MapViewModel, @MainActor ObservableObject)**: `pins`/`activeFilters`(태그 필터)→`visiblePins`→`markers`, `cameraCommand`/`fitBoundsCommand`(소비 후 nil), `selectedPinId`, `activeSheet`(동시 1패널). 낙관적 patch/remove + 스냅샷 롤백(웹 useOptimistic 대응), 5분 캐시 + append-only 폴링(BR-7).
- **검색→추가**: `PlaceAPI.search` → 태그 선택 → `PinAPI.create` → appendPin+flyTo. 크로스헤어 임의 좌표(중심 `cameraIdle` 추적, 7자리 반올림).
- **룰렛**(`Roulette.swift` 순수, RNG 주입): `pickRandomWithExpansion` 반경 확장 추첨(웹 roulette.ts 동치), MEMORY 포함 토글.
- **방문감지**: [[pin]] 도메인 참조 — 포그라운드 CoreLocation. `VisitDetectionEngine`(순수, now 주입): 정확도>50m 스킵·타이머 보존, 속도>1.4m/s 초기화, BBox+Haversine 100m·30초 → 최근접 1개, 세션 중복 차단. MEMORY 전환(`transitionedToMemoryNow=true`만 confetti+메모 시트).
- 진입점: 온보딩 종착(`OnboardingRouter.groups`)이 `GroupsView`(삭제) → `MapView` 로 교체.
