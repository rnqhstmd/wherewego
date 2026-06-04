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

### P8 영역2 — 핀 상세 말풍선 오버레이 ([PR #96](https://github.com/rnqhstmd/wherewego/pull/96))

P7 머지 후 발견된 웹↔iOS 정합 차이 중 영역2(핀 상세) 재정합. 마커 탭 시 풀모달 시트(`PinDetailSheet`) → 마커에 앵커되는 말풍선(`PinBubbleView`)로 전환(웹 `SpeechBubblePopup` 정합).

- **좌표 투영 격리**: SDK 비의존 `ScreenPoint`(Double x/y) + `MapRenderer.point(for:)`를 도입하되, 실제 `mapboxMap.point(for:)` 변환은 `MapboxMapView` 단일 격리 파일 안에서만(`import MapboxMaps` 1파일 게이트 유지). stub은 nil 반환(DoD-A 빌드).
- **좌표계 정렬**: 지도(UIViewRepresentable)와 말풍선 오버레이를 `MapContainerView`의 동일 ZStack(`alignment:.topLeading` + `ignoresSafeArea` 일괄)에 배치 → `point(for:)`의 mapView 로컬좌표를 `.position`에 그대로 사용.
- **첫배치/추적 분리**: 탭 즉시 배치는 `markerTapped(pinId, screenPoint)`가 마커 화면좌표를 운반, 이후 pan/zoom 추적은 `onCameraChanged` 게이팅(선택핀 있을 때만 방출) → `cameraMoved` → distinct(1pt) → `BubbleOverlay` 관찰 격리로 MapView body 재평가 차단(QE-1).
- **화면밖 판정**: `GeoMath.isPointVisible` 순수함수로 분리(clamp 없이 숨김, 복귀 시 재표시). Windows 단위테스트 가능.
- **콘텐츠 재사용**: `PinDetailContent` 공통 뷰 추출(태그/장소명/메모/사진/삭제 액션 + `PinDetailViewModel` 재사용), `PinDetailSheet` 삭제. 시트 충돌 시 일시 숨김(`activeSheet==.none` 표시조건, selectedPinId 보존), 동일 핀 재탭 유지, 사진 작업 중 배경탭 닫기 무시.
- 코드측 완료. 꼬리 픽셀 위치·추적 부드러움·시각 정합은 DoD-B(Mac) 최종 검증.

### P8 영역1 — 핀 추가 인라인화 ([PR #97](https://github.com/rnqhstmd/wherewego/pull/97))

풀모달 시트(`AddPlaceSheet`) → 메인 지도 위 인라인 **십자선**(`CrosshairOverlay`, `allowsHitTesting(false)`) + 하단 얇은 **확정 카드**(`InlineAddPlaceCard`: 검색창+결과+주소/폴백+태그3종+여기등록+취소)로 전환. 웹 `CrosshairOverlay`/`AddPinPickerContent` 동치.

- **상태 소유 이전**: `AddPlaceViewModel` 수명을 `MapViewModel`이 소유(`isAddingPin`/`addPlaceVM`/`mapZoom`). ＋탭(`MainTabView.enterAddPin`)·`EmptyMapCard` 두 진입점이 동일 VM 공유, `exitAddPin` 시 작성 중 VM 폐기(BR-1). `AddPlaceSheet.swift` 삭제 + `ActiveSheet.addPlace` case 제거.
- **검색+콕찍기 한 흐름**(토글 없음): `inputMode` search/pinpoint, 진입 즉시 콕찍기 seed. 중심 좌표는 메인 지도 `cameraIdle` 추적(영역2 `point(for:)` 인프라와 별개).
- **역지오코딩**: 지도 이동 시 온디바이스 `CLGeocoder`(`ReverseGeocoder`)로 주소 실시간(디바운스, `enterAddPin` 측 5초 one-shot 타임아웃, 공유 `CoreLocationService` 10초 불변, 좌표 폴백). `isResolvingAddress` "주소를 찾는 중...".
- **FR-11 줌인 분기**: `MapEvent.cameraIdle`에 `zoom` 추가(`MapboxMapView`에서만 SDK 줌 읽기, MUST-1 격리 유지). `addPinMinZoom=13`/`addPinLocatedZoom=15`/`addPinFallbackZoom=14`.
- 코드측 완료, 시각/실렌더 DoD-B(Mac).

### P8 영역4 — 하단 플로팅 5탭바 ([PR #95](https://github.com/rnqhstmd/wherewego/pull/95))

하단 5탭(어디갈까·채팅·＋·알림·내정보) 플로팅 바(`FloatingTabBar`)의 시각 완성도 + 콘텐츠 가림 해소.

- **`safeAreaInset` 부착**: `MainTabView`에서 `FloatingTabBar`를 ZStack 오버레이가 아니라 `TabView.safeAreaInset(edge: .bottom)`로 부착. SwiftUI가 바 높이만큼 각 탭 safe area를 자동 예약 → 채팅 입력바·알림/내정보 스크롤·맵 오버레이가 **자동 회피**(이중 가산 소멸). `.ignoresSafeArea(.keyboard, edges: .bottom)`로 키보드 표시 시 바 고정.
- **맵 full-bleed**: `MapContainerView().ignoresSafeArea()`(배경 끝까지) vs `loadedOverlay`(내위치/룰렛)는 축소된 safe area를 따라 바 위로 자동 상승. 좌표계 분리.
- **매직넘버 SSOT**: `FloatingTabBar.Metrics` 중첩 enum(`barHeight=64`/`bottomGap=12`), `FloatingBarBackground` glass(iOS26+)/solid 분기. 신규 파일 없음.
- 코드측 완료, Liquid Glass·시각 DoD-B(Mac).
