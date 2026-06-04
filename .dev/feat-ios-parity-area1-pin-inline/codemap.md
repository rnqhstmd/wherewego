## 코드 맵: P8 영역1 — 핀 추가 인라인화

> ＋ 별도 풀시트(시트 내 독립 지도 인스턴스) → 메인 지도 위 **중앙 고정 십자선 + 얇은 하단 확정 카드**.
> 웹 정합 기준: `CrosshairOverlay` + `AddPinPickerContent`. 중심 좌표 = 메인 지도 `cameraIdle` 재사용. 검색은 하단 카드 통합.
> 설계 결정: AddPlaceViewModel 소유권 시트→MapViewModel 이전. ActiveSheet.addPlace 제거+isAddingPin. cameraIdle에 zoom 추가. AddPlaceSheet 삭제.

### 핵심 파일 (수정/신규)
- ios/WhereWeGo/Features/Map/AddPlaceSheet.swift → 현재 ＋ 별도 풀시트(독립 지도+검색+좌표+태그+여기등록). **삭제 대상**(UI는 InlineAddPlaceCard로 이식)
- ios/WhereWeGo/Features/Map/AddPlaceViewModel.swift → 추가 로직(onMapMoved/createPin/search/selectResult/resolveAddress/validatePinInput). **보존, 소유처만 MapViewModel로**. (선택)isResolvingAddress 추가
- ios/WhereWeGo/Features/Map/MapViewModel.swift → ActiveSheet(.addPlace 제거), 신규 isAddingPin/addPlaceVM/mapZoom, enterAddPin/exitAddPin, handle(.cameraIdle) zoom 분기, applyAddPinEntryZoom(FR-11)
- ios/WhereWeGo/Features/Map/MapView.swift → loadedOverlay에 십자선+확정카드 조건부 삽입, .sheet(addPlace) 제거, onChange(didCreate)→exitAddPin
- ios/WhereWeGo/Core/Map/MapboxMapView.swift → cameraIdle에 zoom 추가(cameraState.zoom), MUST-1 격리(#if/#else stub 동기)
- ios/WhereWeGo/Core/Map/MapRenderer.swift → MapEvent.cameraIdle(centerLat:centerLng:zoom:) 시그니처 확장, CameraTarget/MapMarker 정의(SDK 비의존 코어)

### 신규 파일
- ios/WhereWeGo/Features/Map/CrosshairOverlay.swift → 중앙 고정 십자선 오버레이(가로/세로선+중앙점, allowsHitTesting(false), 웹 CrosshairOverlay.tsx 동치)
- ios/WhereWeGo/Features/Map/InlineAddPlaceCard.swift → 하단 확정 카드(검색창+결과목록+주소/폴백+태그3종+여기등록+취소, AddPlaceViewModel 바인딩)
- ios/WhereWeGoTests/InlineAddPlaceModeTests.swift → 진입/종료/줌인 분기 순수 로직 테스트(신규)

### 참조 파일
- ios/WhereWeGo/App/MainTabView.swift → ＋ 탭 액션(showAddPlace 시트 → enterAddPin), .sheet 제거, onChange(selection)→exitAddPin(BR-1)
- ios/WhereWeGo/App/FloatingTabBar.swift → 탭바 높이 상수(하단 카드 bottom padding 기준, QE-2/AC-14)
- ios/WhereWeGo/Features/Map/EmptyMapCard.swift → 빈 지도 카드 추가 진입점(onAddPin→enterAddPin)
- ios/WhereWeGo/Core/Location/ReverseGeocoder.swift → reverseGeocode/coordinateFallback(하단 카드 주소·폴백)
- ios/WhereWeGo/Core/Location/CoreLocationService.swift → 위치 권한 상태 + one-shot(FR-11 줌인, oneShotTimeoutSeconds=10)
- ios/WhereWeGo/Features/Place/PlaceAPI.swift → PlaceItem(검색 결과 DTO, 카드 결과 행 바인딩)
- ios/WhereWeGo/Features/Pin/PinTag.swift → PinTag enum(REEL/WISH/MEMORY 대문자 case)
- ios/WhereWeGo/Core/Config/MapConfig.swift → isMapboxConfigured/styleURL(플레이스홀더 폴백 판단)
- ios/WhereWeGo/Core/Map/PlaceholderMapView.swift → 토큰 미설정 폴백(십자선 표시되나 cameraIdle 미발생, DoD-B)
- frontend/src/app/map/_components/CrosshairOverlay.tsx → 웹 인라인 십자선(정합 기준, size28/arm10/thickness2/dot3)
- frontend/src/app/map/_components/AddPinPickerContent.tsx → 웹 얇은 하단 카드(정합 기준, loading "주소를 찾는 중...")
- frontend/src/app/map/MapClient.tsx → 웹 지도 메인(getCenter+moveend 중심 추적, 줌<13 줌인 1043-1066)

### 설정
- .dev/feat-ios-nav-redesign/frontend-parity-findings.md → P8 4영역 분석 원본(영역1 상세)
- ios/project.yml → sources: path WhereWeGo(디렉토리 포함 → 신규 .swift는 xcodegen 재생성만, 수정 불필요)
