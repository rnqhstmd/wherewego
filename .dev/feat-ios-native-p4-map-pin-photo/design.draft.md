# 설계 초안: P4 — iOS 지도·핀·사진·방문감지

## 설계 규모
**대형** — 지도 렌더·핀 CRUD·사진·룰렛·방문감지 + Mapbox 조건 컴파일 추상화 + CoreLocation 통합. 다수 신규 모듈/View/ViewModel/API 동시 도입.

## 배경 및 목적
- 웹→SwiftUI 전환 4단계. P3(골격·인증·온보딩) 위에 커플 공유 지도 경험 포팅.
- 현재 온보딩 종착 `GroupsView`가 플레이스홀더(`Features/Group/GroupsView.swift:8`)다 → 지도 진입점으로 교체.
- 목표: Mapbox token 없이도 `xcodebuild test` 통과(플레이스홀더), token 후 실기기 E2E. 웹 MapClient/MapboxView/roulette/useVisitDetection 로직 동일 재현.

## 변경 범위

### 수정 파일
- `ios/WhereWeGo/Features/Group/GroupsView.swift` → 지도 진입점 교체
- `ios/WhereWeGo/App/AppDependencies.swift` → PinAPI/PlaceAPI/LocationService/MapConfig 등록
- `ios/project.yml` → Info.plist 권한·MAPBOX 키 + Mapbox SPM 주석
- `ios/Config/Shared.xcconfig`(+Debug/Release) → MAPBOX 키
- `ios/WhereWeGo/Info.plist` → 권한 키

### 신규 파일
**Core/Location**: LocationServiceProtocol.swift, CoreLocationService.swift, VisitDetectionEngine.swift(순수), GeoMath.swift(순수)
**Core/Map**: MapRenderer.swift(프로토콜+모델), MapboxMapRenderer.swift(#if canImport), PlaceholderMapView.swift, MapContainerView.swift
**Core/Config**: MapConfig.swift
**Features/Pin**: PinAPI.swift(+DTO), PinPhotoUploader.swift, PinTag.swift
**Features/Place**: PlaceAPI.swift(+DTO)
**Features/Map**: MapView.swift, MapViewModel.swift, PinDetailSheet.swift, PinDetailViewModel.swift, SearchPinSheet.swift, SearchPinViewModel.swift, CrosshairAddView.swift, TagFilterBar.swift, EmptyMapCard.swift, RouletteSheet.swift, RouletteViewModel.swift, VisitToastView.swift, VisitMemoSheet.swift, Roulette.swift(순수)
**Features/Photo**: PhotoPickerView.swift, SquareCropView.swift, ImageCropper.swift(순수)
**Tests**: PinAPITests, PlaceAPITests, PinDTODecodingTests, VisitDetectionEngineTests, RouletteTests, GeoMathTests, ImageCropperTests, MapConfigTests

## 적용 컨벤션
- 네이밍: `{도메인}API`+`{도메인}APIProtocol`. DTO는 백엔드 record와 동일 이름·필드 Decodable struct.
- API: `APIClient.request(_:method:body:type:)` 경유, 경로 `api/v1` 자동 prefix, `APIEnvelope<T>` 자동 언랩.
- 에러: `APIError`(code/status/message) + 도메인 `LocalizedError` enum. 401 refresh 1회 재시도.
- DI: `AppDependencies`(@MainActor) 프로토콜 주입.
- ViewModel: `@MainActor final class : ObservableObject` + `@Published`, View는 `@StateObject`. (@Observable 미사용 — P3 일관성)
- SDK graceful: `AppConfig.is{X}Configured` 단일 판단점 + placeholder 값 미설정 감지.
- 테스트 mock: `StubURLProtocol`(URLSession), 순수함수 직접 단위테스트, 프로토콜 목 in-file.
- 순수 함수 분리: 결정 규칙을 static 순수 함수로(방문감지/룰렛/크롭/분기 헬퍼).

## 상세 설계

### 1) Mapbox 추상화 (FR-1, AC-1)
- `MapRenderer.swift`(SDK 비의존): 모델 MapMarker/CameraTarget/MapEvent + `protocol MapRenderer`(setMarkers/flyTo/fitBounds/eventHandler). 항상 컴파일.
- `MapContainerView.swift`: SwiftUI 래퍼, `MapConfig.isMapboxConfigured && canImport`면 Mapbox 경로, 아니면 PlaceholderMapView. Mapbox 타입 참조는 `#if canImport(MapboxMaps)`로 감쌈.
- `MapboxMapRenderer.swift`: 전체 `#if canImport(MapboxMaps)`. MapView+PointAnnotationManager(태그별 이미지)+camera.fly+gesture 이벤트. #if 미충족 시 빈 유닛.
- `PlaceholderMapView.swift`: 항상 컴파일. 배경+"지도를 불러올 수 없어요"/EmptyMapCard. 비-지도 UI(시트/검색/룰렛)는 정상 동작.
- `MapConfig.swift`: AppConfig 미러. accessToken/styleURL 읽기 + `isConfigured`(placeholder/빈값 false) 순수함수(MapConfigTests).
- **project.yml**: SPM 미추가, 주석으로 위치 표기(Q1 확정: token 후 추가). secret token(.netrc) 없으면 resolve 실패하므로.

### 2) API 레이어
- `PinTag.swift`: `enum PinTag: String {REEL,WISH,MEMORY}`, `enum MemoSource`.
- `PinAPI.swift` DTO(AC-3): `PinSummary`(id,groupId,createdBy,createdByNickname?,placeName,address?,latitude,longitude,instagramUrl?,memo?,memoSource?,tag,createdAt,visitedAt?,memoUpdatedBy?,memoUpdatedByNickname?,photoUrl?,photoThumbnailUrl?), `PinListResponse{items,totalCount?,hasNext?}`, `UpdatePinResponse{summary,transitionedToMemoryNow}`. 좌표 Double, 날짜 String.
- `PinAPIProtocol`: list(legacy {items})/create/update/delete(204)/uploadPhoto/deletePhoto. CreatePinRequest/UpdatePinRequest(부분수정).
- 엔드포인트(PinV1Controller 검증): GET/POST/PATCH/DELETE /groups/{groupId}/pins[/{pinId}], POST/DELETE .../photo(multipart file).
- **부분 PATCH**: 백엔드 JsonNode 키부재/null/빈문자열 구분 → 변경 필드만 인코딩(JSONEncoder nil 생략). 태그만 PATCH 시 memo 키 미전송.
- **multipart**: APIClient에 upload 메서드 추가(기존 request 무변경). Bearer/401 동일.
- `PlaceAPI.swift`: PlaceItem{placeName,address?,latitude,longitude}, search(q) → GET /places/search?q=.
- 낙관적 업데이트: MapViewModel `@Published pins`. patch/remove/append(폴링 append-only BR-7).

### 3) 화면
- MapView/MapViewModel: groupId(myActiveGroup 조회) → list → pins. 필터 Set<PinTag>(기본 전체)→visiblePins. cameraCommand(소비 후 nil). 초기 카메라 granted=현재위치 zoom15/미허용=서울시청 zoom3. selectedPinId. ActiveSheet enum(동시1패널). 방문감지 오케스트레이션.
- PinDetailSheet/VM: 표시(IG https 가드 BR-3/AC-17), 태그변경(낙관 PATCH), 메모(≤500), 장소명(≤200 Should), 삭제(confirmationDialog→DELETE 낙관). MEMORY만 사진영역(BR-5/AC-9).
- SearchPinSheet/VM: search→결과→태그선택→create→flyTo.
- CrosshairAddView: 중앙좌표 7자리 반올림→태그→create(Should).
- RouletteSheet/VM, VisitToastView/VisitMemoSheet: §4,§5.

### 4) 방문감지 (FR-27~32, AC-12~15)
- `GeoMath.swift`: haversineKm, bboxContains(순수).
- `VisitDetectionEngine.swift`(순수, now 주입): LocationSample{lat,lng,accuracyMeters,speedMps?}, VisitCandidatePin. firstEnterAt 상태. evaluate(sample,wishReelPins,shownPinIds,now)→Int64?. 게이트: 정확도>50m 스킵·타이머보존(AC-12), 속도>1.4 clearAll(AC-13), BBox100m→haversine≤100→firstEnterAt누적→30초+ 최근접1개(FR-28). **PRD 50m 우선(웹 100m 완화와 다름 — Q1)**.
- `CoreLocationService.swift`+protocol: CLLocationManager 래퍼, didUpdateLocations→LocationSample, one-shot, 5초 폴링(FR-32).
- 오케스트레이션(MapViewModel): startUpdating→evaluate→detectedPinId면 VisitToast(shownPinIds 세션Set, FR-31/AC-14). "다녀왔어요"→PATCH tag=MEMORY+flyTo, transitionedToMemoryNow==true만 confetti+VisitMemoSheet, false면 안내토스트(AC-15). 1차 실패=인라인토스트·태그미변경. VisitMemoSheet(날짜 visitedAt, PATCH memo)→정보창. scenePhase background→clearAll.

### 5) 룰렛 (FR-20~24, AC-10/11)
- `Roulette.swift`(순수, RNG 주입): pickRandomWithExpansion(center,pins,tagsAllowed=[REEL,WISH],rng)→RouletteOutcome(picked/exhausted). reRollFromSamePool. withinRadius=bbox→haversine(GeoMath). MEMORY 포함 토글 시 visibleTags 교집합(computeTagsAllowed).
- RouletteViewModel: 권한 선행→one-shot→pick→결과(장소명·거리·태그)/exhausted. "지도에서 보기"→flyTo+PinDetail. "다시"→reRoll. MEMORY 토글 기본 OFF.
- 캐시 5분(Should).

### 6) 사진 (FR-16~19, AC-8/9)
- `PhotoPickerView.swift`: PHPickerViewController, filter=.images, 단일.
- `SquareCropView.swift`: 1:1 자작 크롭(드래그 offset+핀치 scale)→crop rect.
- `ImageCropper.swift`(순수): crop(image,rect), resizeAndCompress(maxEdge1600,maxBytes2MB, 품질 1.0→0.4 단계). image/jpeg(매직바이트 FFD8FF 통과). ImageCropperTests.

### 7) 진입점 라우팅
- `OnboardingRouter.swift:115` `.groups`→GroupsView를 MapView로 교체. GroupsView 대체/삭제.
- AppDependencies에 pinAPI/placeAPI/locationService 추가.

### 8) 설정 파일
- Shared.xcconfig: `MAPBOX_ACCESS_TOKEN=MAPBOX_TOKEN_NOT_SET`, `MAPBOX_STYLE_URL=mapbox:/$()/styles/mapbox/standard`(:// 이스케이프).
- project.yml properties: NSCameraUsageDescription, NSPhotoLibraryUsageDescription, MAPBOX_ACCESS_TOKEN/STYLE_URL=$(...). NSLocationWhenInUse 기존. packages에 Mapbox SPM 주석(from 11.0.0).
- Info.plist: 동일 키 추가.

### 9) 고려사항
- APIClient 204: delete는 Empty Decodable 또는 status-only 메서드 신규.
- Int64 vs Int: ActiveGroup.groupId가 Int → PinSummary.id도 Int 채택(64bit Int=Int64).
- 부분 PATCH: 변경 필드만 직렬화(키부재=미변경 보장).
- 이미지 업로드 403 GROUP_NOT_MEMBER→"권한이 없어요".

## 구현 순서
1. **[Must] 기반1 설정/Config**(병렬군A): MapConfig+Test, xcconfig/Info.plist/project.yml(권한·키·SPM주석). →AC-1,16.
2. **[Must] 기반2 순수도메인**(병렬군A): PinTag, GeoMath+Test, Roulette+Test(AC-10/11), VisitDetectionEngine+Test(AC-12~14), ImageCropper+Test. token/SDK 불요, 테스트 우선.
3. **[Must] API**(병렬군B, PinTag 의존): PinAPI+DTO+Test(AC-2/3), PlaceAPI+Test, APIClient multipart. StubURLProtocol.
4. **[Must] Mapbox 추상화**: MapRenderer/PlaceholderMapView/MapboxMapRenderer(#if)/MapContainerView.
5. **[Must] 지도 메인**: MapView/MapViewModel/TagFilterBar/EmptyMapCard. 진입점 교체. (3·4 의존)
6. **[Must] 핀 상세**: PinDetailSheet/VM. (5 의존)
7. **[Must] 검색→추가**: SearchPinSheet/VM. (3·5)
8. **[Must] 사진**: PhotoPickerView/SquareCropView/ImageCropper 연결. (6)
9. **[Must] 룰렛**: RouletteSheet/VM. (2·5)
10. **[Must] 방문감지 통합**: CoreLocationService/VisitToast/VisitMemoSheet/오케스트레이션(AC-15). (2·5·6)
11. **[Should] 보강**: 클러스터(FR-5)/장소명(FR-11)/크로스헤어(FR-15)/fitBounds(FR-26)/캐시(FR-24)/폴링(FR-32)/append-only(BR-7).
- 병렬: 1·2 독립, 3은 2후, 4는 1후. 6/7/9는 5후 다른 파일이라 병렬 가능.

## 테스트 전략 (token 없이 통과)
MapConfigTests(AC-1), PinAPITests(AC-2), PinDTODecodingTests(AC-3/15), PlaceAPITests, RouletteTests(AC-10/11), GeoMathTests, VisitDetectionEngineTests(AC-12~14), ImageCropperTests(AC-8). View 분기규칙(AC-17 IG가드/AC-9 MEMORY사진/AC-15 transitioned)은 순수 헬퍼로 분리해 단위테스트. 낙관 업데이트(AC-6/7)는 VM 단위테스트(StubURLProtocol 실패응답).

## 빌드 주의 (XcodeGen)
- token 없이 빌드: SPM 미추가(주석만), #if canImport false→MapboxMapRenderer 빈 유닛. MapContainerView/MapView 플레이스홀더만 컴파일.
- 신규 파일 sources:WhereWeGo 자동 포함. project.yml 변경 시 xcodegen generate.
- 테스트 PRODUCT_NAME=WhereWeGoTests 분리 기존. @testable import WhereWeGo.
- 시뮬레이터 런타임/ad-hoc 서명: 메모리 ios-xcodebuild-env 준수.
