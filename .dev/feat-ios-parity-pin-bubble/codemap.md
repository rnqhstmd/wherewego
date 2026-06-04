## 코드 맵: P8 영역2 — 핀 상세 말풍선 팝업 (iOS ↔ 웹 정합)

> 목표: iOS 핀 상세를 풀 모달 시트(`PinDetailSheet`) → 마커에 붙는 말풍선 오버레이(신규 `PinBubbleView`)로 전환, 웹 `SpeechBubblePopup` 동치.
> 공통 선행: 좌표→화면점 투영(`point(for:)`)을 `MapRenderer`/`MapboxMapView`에 노출 + 메인 지도 ZStack 오버레이.

### 핵심 파일 (수정 대상 — iOS)
- ios/WhereWeGo/Features/Map/PinBubbleView.swift → **신규**: 말풍선 컨테이너(꼬리 Path + .position 앵커 + detailVM 소유 + 배경탭 BR-3). PinDetailContent 래핑
- ios/WhereWeGo/Features/Map/PinDetailContent.swift → **신규**: 공통 콘텐츠 뷰(header/tag/placeName/memo/photo/instagram/delete + 편집버퍼/다이얼로그/picker/cropper @State + onRequestClose). PinDetailSheet 콘텐츠 이관
- ios/WhereWeGo/Core/Location/GeoMath.swift → isPointVisible(_:in:margin:) 순수함수 추가(화면밖 판정, AC-14 Windows 단위테스트). bboxContains 패턴 동형
- ios/WhereWeGo/Core/Map/MapRenderer.swift → SDK 비의존 지도 프로토콜(MapMarker/CameraTarget/MapEvent). **`point(for: coord) -> CGPoint?` 투영 메서드 추가 지점**
- ios/WhereWeGo/Core/Map/MapboxMapView.swift → `import MapboxMaps` 단일 격리 파일(#if). `mapboxMap.point(for:)` 실구현 + onCameraChanged마다 화면좌표 갱신. (grep 격리 게이트 1개 유지 필수)
- ios/WhereWeGo/Core/Map/MapContainerView.swift → 메인 지도 컨테이너(항상 컴파일). 말풍선 ZStack 오버레이 부착 위치
- ios/WhereWeGo/Features/Map/MapView.swift → `selectedPinId` → `PinDetailSheet` 표시 분기 (시트 제거 → 오버레이로 교체)
- ios/WhereWeGo/Features/Map/PinDetailSheet.swift → 풀 모달 시트 (제거 또는 PinBubbleView 컨텐츠로 대체)
- ios/WhereWeGo/Features/Map/PinDetailViewModel.swift → 상세 액션(changeTag/saveMemo/savePlaceName/deletePin) — 재사용
- ios/WhereWeGo/Features/Map/MapViewModel.swift → `selectedPinId`/`activeSheet` 상태 소유. 동시 1패널 규칙

### 참조 파일 (웹 레퍼런스 / iOS 보조)
- frontend/src/app/map/_components/PinPopup.tsx:123-138 → 웹 핀 팝업 컨테이너, `map.project([lng,lat])` 화면좌표 추적(move/zoom 갱신)
- frontend/src/components/ui/SpeechBubblePopup.tsx:193-204,497-521 → 말풍선(꼬리 포함) 시각 기준
- frontend/src/app/map/MapClient.tsx:2058-2090 → 핀 선택 → 팝업 렌더링 흐름
- ios/WhereWeGo/Core/Map/PlaceholderMapView.swift → Mapbox 미설정 폴백(point(for:) stub 필요 — DoD-A 빌드 유지)
- ios/WhereWeGoTests/PinDetailViewModelTests.swift → 기존 상세 로직 테스트(회귀 기준)
- ios/WhereWeGoTests/MapRendererMocks.swift → MapRenderer mock(테스트 확장 지점)

### 설정
- ios/WhereWeGo/Core/Config/MapConfig.swift → `isMapboxConfigured` 분기(실렌더 DoD-B, Windows 빌드 제약 → Mac 검증)
