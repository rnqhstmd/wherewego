# 확정 설계서: P4 — iOS 지도·핀·사진·방문감지

> 브랜치 `feat/ios-native-p4-map-pin-photo` (base: develop). PRD: `prd.md`. design-critic MUST-ADDRESS 4건 반영(2차 확정).

## 설계 규모
**대형** — 지도 렌더·핀 CRUD·사진·룰렛·방문감지 + Mapbox 조건 컴파일 격리 + CoreLocation 통합 + APIClient multipart 리팩터.

## 확정 결정
- 정확도 게이트 = **50m** (PRD/AC-12 일치).
- **단일 PR (Must+Should)**, Must 먼저 구현 후 Should 같은 PR 마지막.
- Mapbox SDK = **v11** (`from: "11.0.0"`, project.yml 주석값).
- style fallback = **`mapbox://styles/mapbox/standard`** (웹 동일).

## 배경 및 목적
- 웹→SwiftUI 전환 4단계. P3(골격·인증·온보딩) 위에 커플 공유 지도 경험 포팅.
- 현재 온보딩 종착 `GroupsView`가 플레이스홀더(`ios/WhereWeGo/Features/Group/GroupsView.swift:4-14`) → 지도 진입점 교체.
- 목표: Mapbox token 없이도 `xcodebuild build/test` 통과(플레이스홀더), token 후 실기기 E2E. 웹 MapClient/MapboxView/roulette/useVisitDetection 동일 재현.

---

## 비판 반영 (design-critic MUST-ADDRESS)

### MUST-1 — Mapbox 타입 단일 파일 격리 + 검증 가능 규칙
P3 Kakao는 SPM 상존+무조건 import, 런타임만 graceful. Mapbox는 SPM 미추가→모듈 부재→`#if canImport(MapboxMaps)` 컴파일타임 false(신규 메커니즘). 격리 규칙:
- **규칙1**: `MapboxMaps` import/타입 참조 코드는 **`MapboxMapView.swift` 1개 파일**에만. 파일 전체를 `#if canImport(MapboxMaps) import MapboxMaps … #else <동일 시그니처 stub> #endif`로 감쌈. `#else`는 동일 이름(`MapboxMapView`, `MapboxMapRenderer`) stub 제공(PlaceholderMapView 렌더 + no-op MapRenderer).
- **규칙2**: `MapContainerView`(항상 컴파일, `#if` 없음)는 Mapbox 타입 이름 미참조. `MapRenderer` 프로토콜·모델·`MapboxMapView` 타입(양 분기 제공)만 사용.
- **규칙3**: `MapRenderer` 채택 SDK 구현체·인스턴스화는 `#if` 안에서만. ViewModel/View는 프로토콜 타입으로만.
- **검증 게이트**: `grep -rl "import MapboxMaps" ios/WhereWeGo` == `MapboxMapView.swift` 1개.

| 심볼 | 파일 | token 없이 컴파일 |
|------|------|------|
| MapRenderer, MapMarker, CameraTarget, MapEvent | MapRenderer.swift | 예 |
| MapContainerView | MapContainerView.swift | 예 |
| PlaceholderMapView | PlaceholderMapView.swift | 예 |
| MapboxMapView/MapboxMapRenderer (stub) | MapboxMapView.swift #else | 예 |
| MapboxMapView/MapboxMapRenderer (실구현) | MapboxMapView.swift #if | **아니오(token 후)** |
| MockMapRenderer | MapRendererMocks.swift(테스트) | 예 |

### MUST-2 — UpdatePinRequest custom encode + 인코딩 테스트
Swift JSONEncoder는 nil 옵셔널을 `{"memo":null}`로 내보냄. 백엔드 `PinV1Dto.java`는 키부재==null==미변경(우연 동작). `UpdatePinRequest`를 **custom `encode(to:)`**로 구현 → 설정 필드 키만 직렬화, 미설정 키 생략. **`PinUpdateEncodingTests`**: `UpdatePinRequest(tag만)` → JSONSerialization dict → 키집합==`["tag"]` 단언(memo/placeName 키 부재). 메모만/태그+메모 케이스 추가.

### MUST-3 — multipart 업로드 401 refresh 재시도(공통 헬퍼)
`APIClient` 401 재시도는 request/send 내부, Content-Type application/json 고정(`:61`). multipart 부적합. **`performAuthorized(_ build:()->URLRequest)` private 헬퍼 추출**(토큰 주입+401→`tokens.refresh()`→1회 재시도, build 재호출로 동일 요청 재전송). `request<T>` 공개 동작 불변(내부만 위임). `upload<T>(path,fileData,fileName,fieldName,mimeType,type)` 신설 — performAuthorized 경유, boundary 1회 생성 후 재시도 시 동일 body. 테스트: PinAPITests에 401→refresh200→재시도200 시퀀스(StubURLProtocol.requestCount 단언).

### MUST-4 — P4 완료 정의(DoD) 분리
token 없으면 Mapbox 렌더링 코드는 컴파일 검증조차 안 됨. "P4 머지=지도 렌더" 거짓 기대 차단(아래 DoD 섹션).

### CONSIDER
- **MapRenderer 프로토콜 정당화**: `MockMapRenderer`(테스트 `MapRendererMocks.swift`)로 `MapViewModelTests`(cameraCommand 소비·마커 동기화·낙관 patch/remove 롤백 AC-6/7). 프로토콜=SDK구현체1+Mock1.
- 수용 리스크: BigDecimal→Double(7자리 반올림 안전), Long→Int(iOS17 64bit, `ActiveGroup.groupId:Int` 선례). id/createdBy/memoUpdatedBy/pinId/groupId 모두 Int 통일.
- GroupsView→MapView 교체: `OnboardingRouter.swift:115-117` `.groups`→`MapView(dependencies:)`. NavigationStack 내 전체화면 safe area/네비바(navigationBarBackButtonHidden 승계) 주의.

---

## P4 완료 정의(DoD)

### (A) CI/자동 게이트 — token 없이 달성, **P4 머지 게이트**
1. token 미설정 `xcodebuild build` 성공(#else stub 컴파일, AC-1).
2. `xcodebuild test` 전체 통과(순수/API/VM).
3. `grep -rl "import MapboxMaps" ios/WhereWeGo` == `MapboxMapView.swift` 1개(MUST-1).
4. 단위테스트 그린: MapConfigTests(AC-1), PinAPITests(AC-2 + multipart 401), PinDTODecodingTests(AC-3), PinUpdateEncodingTests(MUST-2), PlaceAPITests, RouletteTests(AC-10/11), GeoMathTests, VisitDetectionEngineTests(AC-12~14), ImageCropperTests(AC-8), MapViewModelTests(AC-6/7).
5. NSCameraUsageDescription·NSPhotoLibraryUsageDescription project.yml 존재(AC-16).

### (B) token 후 별도 검증 — **머지 게이트 아님(체크리스트)**
secret token(.netrc)+public token 발급 → project.yml SPM 주석 해제 → `xcodegen generate` → build로 `#if` 분기 컴파일. 실기기: 마커 렌더(태그색/하트)·flyTo·fitBounds·클러스터·제스처, 핀 CRUD/사진/방문감지 E2E. 코드는 "작성 완료(컴파일 검증 token 후)" 상태로 머지.

---

## 변경 범위

### 수정 파일
- `ios/WhereWeGo/Core/Networking/APIClient.swift` → `performAuthorized` 추출(MUST-3) + `upload` 추가. `request<T>` 공개 동작 불변.
- `ios/WhereWeGo/Features/Group/GroupsView.swift` → 삭제/MapView 대체.
- `ios/WhereWeGo/App/OnboardingRouter.swift:115-117` → `.groups`→`MapView(dependencies: dependencies)`.
- `ios/WhereWeGo/App/AppDependencies.swift:32-36` → pinAPI/placeAPI/locationService 등록(client 주입).
- `ios/project.yml` → properties 권한·MAPBOX 키, packages Mapbox SPM 주석(from 11.0.0).
- `ios/Config/Shared.xcconfig`(+Debug/Release) → MAPBOX_ACCESS_TOKEN·MAPBOX_STYLE_URL.
- `ios/WhereWeGo/Info.plist` → 권한·MAPBOX 키 동기.

### 신규 파일
**Core/Location**: LocationServiceProtocol.swift, CoreLocationService.swift, VisitDetectionEngine.swift(순수), GeoMath.swift(순수)
**Core/Map**: MapRenderer.swift(프로토콜+모델), MapboxMapView.swift(**#if/#else 단일 격리**), PlaceholderMapView.swift, MapContainerView.swift(#if 없음)
**Core/Config**: MapConfig.swift
**Features/Pin**: PinAPI.swift(+DTO, UpdatePinRequest custom encode), PinTag.swift
**Features/Place**: PlaceAPI.swift(+DTO)
**Features/Map**: MapView.swift, MapViewModel.swift, PinDetailSheet.swift, PinDetailViewModel.swift, SearchPinSheet.swift, SearchPinViewModel.swift, CrosshairAddView.swift, TagFilterBar.swift, EmptyMapCard.swift, RouletteSheet.swift, RouletteViewModel.swift, VisitToastView.swift, VisitMemoSheet.swift, Roulette.swift(순수)
**Features/Photo**: PhotoPickerView.swift, SquareCropView.swift, ImageCropper.swift(순수)
**Tests(WhereWeGoTests)**: PinAPITests(multipart 401), PlaceAPITests, PinDTODecodingTests, PinUpdateEncodingTests(MUST-2), VisitDetectionEngineTests, RouletteTests, GeoMathTests, ImageCropperTests, MapConfigTests, MapViewModelTests(AC-6/7), MapRendererMocks.swift(MockMapRenderer)

---

## 적용 컨벤션
- 네이밍: `{도메인}API`+`{도메인}APIProtocol`. DTO=백엔드 record 동일 이름·필드 Decodable struct.
- API: `APIClient.request(_:method:body:type:)` 경유, `api/v1` 자동 prefix, `APIEnvelope<T>` 언랩. 401 refresh 1회.
- 에러: `APIError`(code/status/message) + 도메인 `LocalizedError` enum.
- DI: `AppDependencies`(@MainActor) 프로토콜 주입.
- ViewModel: `@MainActor final class : ObservableObject` + `@Published`, View `@StateObject`. @Observable 미사용.
- SDK graceful: `AppConfig.is{X}Configured` + placeholder 값. **Mapbox는 추가로 `#if canImport` 컴파일 게이트(신규).**
- 테스트 mock: StubURLProtocol(makeSession/handler/requestCount), 순수함수 직접 단위테스트.
- 순수 함수 분리: 결정 규칙 static 순수 함수(방문/룰렛/크롭/분기 헬퍼).
- xcconfig 이스케이프: `://`→`/$()/`(Debug.xcconfig:4 선례).

---

## 상세 설계

### 1) Mapbox 추상화 (FR-1, AC-1) — MUST-1
- `MapRenderer.swift`(항상 컴파일): MapMarker(id,lat,lng,tag)/CameraTarget(lat,lng,zoom,durationMs)/MapEvent(markerTapped/clusterTapped/cameraIdle) + `protocol MapRenderer`(setMarkers/flyTo/fitBounds/eventHandler).
- `MapContainerView.swift`(#if 없음): `MapConfig.isMapboxConfigured`면 MapboxMapView, 아니면 PlaceholderMapView. Mapbox 타입 이름 미참조.
- `MapboxMapView.swift`(#if/#else 단일): #if=import MapboxMaps + MapboxMapView(UIViewRepresentable, MapView+PointAnnotationManager 태그별 이미지+camera.fly+gesture)+MapboxMapRenderer. #else=동일 이름 stub.
- `PlaceholderMapView.swift`: 배경+"지도를 불러올 수 없어요"/EmptyMapCard. 비-지도 UI 정상.
- `MapConfig.swift`: AppConfig 미러. accessToken/styleURL + isMapboxConfigured(placeholder/빈값 false) 순수함수. style fallback=standard. (MapConfigTests)
- project.yml: SPM 미추가, 주석(`# MapboxMaps from "11.0.0"`). token 후 활성.

### 2) API 레이어
- `PinTag.swift`: enum PinTag{REEL,WISH,MEMORY}, enum MemoSource.
- `PinAPI.swift` DTO(AC-3): PinSummary(id:Int,groupId:Int,createdBy:Int,createdByNickname:String?,placeName:String,address:String?,latitude:Double,longitude:Double,instagramUrl:String?,memo:String?,memoSource:MemoSource?,tag:PinTag,createdAt:String,visitedAt:String?,memoUpdatedBy:Int?,memoUpdatedByNickname:String?,photoUrl:String?,photoThumbnailUrl:String?), PinListResponse{items,totalCount?,hasNext?}, UpdatePinResponse{summary,transitionedToMemoryNow}.
- PinAPIProtocol: list(legacy{items})/create/update/delete(204)/uploadPhoto/deletePhoto. CreatePinRequest/UpdatePinRequest(custom encode MUST-2).
- 엔드포인트(PinV1Controller): GET/POST/PATCH/DELETE /groups/{groupId}/pins[/{pinId}], POST/DELETE .../photo(multipart file, image/jpeg).
- multipart: APIClient.upload(performAuthorized, 401 재시도). 기존 request 무변경.
- `PlaceAPI.swift`: PlaceItem{placeName,address?,latitude,longitude}, search(q)→GET /places/search?q=.
- 낙관적: MapViewModel.@Published pins. patch/remove/append(BR-7).

### 3) 화면
- MapView/MapViewModel: groupId(myActiveGroup)→list→pins. 필터 Set<PinTag>(기본 전체)→visiblePins. cameraCommand(소비 후 nil). 초기카메라 granted=현재위치 zoom15/미허용=서울시청 zoom3. selectedPinId. ActiveSheet enum(동시1패널). 방문감지 오케스트레이션. MapRenderer 프로토콜 타입만.
- PinDetailSheet/VM: 표시(IG https 가드 AC-17), 태그변경(낙관 PATCH), 메모(≤500), 장소명(≤200 Should), 삭제(confirmationDialog→DELETE 낙관). MEMORY만 사진영역(AC-9).
- SearchPinSheet/VM: search→결과→태그선택→create→flyTo.
- CrosshairAddView: 중앙좌표 7자리 반올림→태그→create(Should).

### 4) 방문감지 (FR-27~32, AC-12~15)
- GeoMath.swift: haversineKm, bboxContains(순수).
- VisitDetectionEngine.swift(순수, now 주입): LocationSample{lat,lng,accuracyMeters,speedMps?}, VisitCandidatePin. firstEnterAt 상태. evaluate(sample,wishReelPins,shownPinIds,now)→Int?. 게이트: **정확도>50m 스킵·타이머보존(AC-12)**, 속도>1.4 clearAll(AC-13), BBox100m→haversine≤100→firstEnterAt 누적→30초+ 최근접1개.
- CoreLocationService.swift+protocol: CLLocationManager 래퍼(LocationPermView 권한 패턴), didUpdateLocations→LocationSample, one-shot, 5초 폴링(FR-32).
- 오케스트레이션(MapViewModel): startUpdating→evaluate→detectedPinId면 VisitToast(shownPinIds 세션Set, AC-14). "다녀왔어요"→PATCH tag=MEMORY+flyTo, transitionedToMemoryNow==true만 confetti+VisitMemoSheet, false면 안내토스트(AC-15). 1차 실패=인라인토스트·태그미변경. VisitMemoSheet(날짜 visitedAt, PATCH memo)→정보창. scenePhase background→clearAll.

### 5) 룰렛 (FR-20~24, AC-10/11)
- Roulette.swift(순수, RNG 주입): pickRandomWithExpansion(center,pins,tagsAllowed=[REEL,WISH],rng)→RouletteOutcome(picked/exhausted). reRollFromSamePool. withinRadius=bbox→haversine. MEMORY 토글 시 visibleTags 교집합(computeTagsAllowed).
- RouletteViewModel: 권한 선행→one-shot→pick→결과/exhausted. "지도에서 보기"→flyTo+PinDetail. "다시"→reRoll. MEMORY 토글 기본 OFF.
- 캐시 5분(Should).

### 6) 사진 (FR-16~19, AC-8/9)
- PhotoPickerView.swift: PHPickerViewController, filter=.images, 단일.
- SquareCropView.swift: 1:1 자작 크롭(드래그 offset+핀치 scale)→crop rect.
- ImageCropper.swift(순수): crop(image,rect), resizeAndCompress(maxEdge1600,maxBytes2MB,품질 1.0→0.4). image/jpeg(매직 FFD8FF). ImageCropperTests.
- 업로드: PinAPI.uploadPhoto→APIClient.upload(401 재시도).

### 7) 진입점 라우팅
- OnboardingRouter.swift:115-117 `.groups`→MapView(dependencies:). GroupsView 대체/삭제. NavigationStack 전체화면 safe area/네비바 주의.
- AppDependencies(:32-36)에 pinAPI/placeAPI/locationService 추가.

### 8) 설정
- Shared.xcconfig: MAPBOX_ACCESS_TOKEN=MAPBOX_TOKEN_NOT_SET, MAPBOX_STYLE_URL=mapbox:/$()/styles/mapbox/standard.
- project.yml properties: NSCameraUsageDescription, NSPhotoLibraryUsageDescription, MAPBOX_ACCESS_TOKEN/STYLE_URL=$(...). NSLocationWhenInUse 기존. packages Mapbox 주석(from 11.0.0).
- Info.plist: 동일 키.

### 9) 고려사항
- APIClient 204: delete는 기존 send 204 처리 경유(performAuthorized 후 동일).
- Int 통일, BigDecimal→Double 수용 리스크.
- 업로드 403 GROUP_NOT_MEMBER→"권한이 없어요".

---

## 구현 순서 (단일 PR, Must 우선)
1. **[Must] 설정/Config**: MapConfig+Test, xcconfig/Info.plist/project.yml(권한·키·SPM 주석). →AC-1,16. (token 불요)
2. **[Must] 순수도메인**: PinTag, GeoMath+Test, Roulette+Test(AC-10/11), VisitDetectionEngine+Test(50m, AC-12~14), ImageCropper+Test. (token/SDK 불요, 테스트 우선)
3. **[Must] APIClient 리팩터+API**: performAuthorized 추출(MUST-3)+upload. PinAPI+DTO(custom encode MUST-2)+PinUpdateEncodingTests+PinAPITests(multipart 401 AC-2). PlaceAPI+Test. PinDTODecodingTests(AC-3). (token 불요)
4. **[Must] Mapbox 격리**: MapRenderer, PlaceholderMapView, MapContainerView(#if 없음), MapboxMapView.swift(#if/#else 단일), MapRendererMocks. → stub/Mock/컨테이너 컴파일 검증 완료, #if 실구현은 "작성완료(token 후 검증)".
5. **[Must] 지도 메인**: MapView/MapViewModel/TagFilterBar/EmptyMapCard + MapViewModelTests(MockMapRenderer AC-6/7). 진입점 교체. (3·4)
6. **[Must] 핀 상세**: PinDetailSheet/VM. (5)
7. **[Must] 검색→추가**: SearchPinSheet/VM. (3·5)
8. **[Must] 사진**: PhotoPickerView/SquareCropView/ImageCropper 연결. (6)
9. **[Must] 룰렛**: RouletteSheet/VM. (2·5)
10. **[Must] 방문감지 통합**: CoreLocationService/VisitToast/VisitMemoSheet/오케스트레이션(AC-15). (2·5·6)
11. **[Should] 보강(PR 마지막)**: 클러스터(FR-5)/장소명(FR-11)/크로스헤어(FR-15)/fitBounds(FR-26)/캐시(FR-24)/폴링(FR-32)/append-only(BR-7).
- 병렬: 1·2 독립, 3은 2후, 4는 1후. 6/7/9는 5후 병렬 가능.

## 테스트 전략 (token 없이 통과 — DoD-A)
- 순수/Config: MapConfigTests(AC-1), GeoMathTests, RouletteTests(AC-10/11), VisitDetectionEngineTests(50m: accuracy60 미초기화 AC-12/speed2.0 초기화 AC-13/30초+세션차단 AC-14), ImageCropperTests(AC-8).
- API: PinAPITests(AC-2 + **multipart 401: 401→refresh200→재시도200, requestCount 단언**), PlaceAPITests, PinDTODecodingTests(AC-3 전 필드).
- 인코딩(MUST-2): PinUpdateEncodingTests — tag만 설정 시 JSON 키집합==["tag"].
- VM(MockMapRenderer): MapViewModelTests — 낙관 patch/remove 롤백(AC-6/7), cameraCommand 소비(flyTo/fitBounds 기록), 마커 동기화.
- View 분기: AC-17/AC-9/AC-15는 순수 헬퍼 분리 단위테스트.
- 타깃 WhereWeGoTests, @testable import WhereWeGo.

## 빌드 주의 (XcodeGen)
- token 없이: SPM 미추가(주석), #if false→MapboxMapView #else stub만 컴파일.
- grep -rl "import MapboxMaps" ios/WhereWeGo == 1개(DoD-A).
- 신규 파일 sources:WhereWeGo 자동. project.yml 변경 시 xcodegen generate.
- 테스트 PRODUCT_NAME=WhereWeGoTests 분리 기존. 시뮬레이터 런타임/ad-hoc 서명 메모리 ios-xcodebuild-env 준수.
