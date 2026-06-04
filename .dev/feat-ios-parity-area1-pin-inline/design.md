# 설계: P8 영역1 — iOS 핀 추가 인라인화

## 확정 결정 (Q&A, 2026-06-04)
1. **AddPlaceSheet.swift 삭제** + `InlineAddPlaceCard.swift` 신규 (죽은 코드 방지, AC-15 확실 충족)
2. **`AddPlaceViewModel.isResolvingAddress` Published 추가** (로직 불변 + 플래그 set만 — AC-B3 "주소를 찾는 중...")
3. **one-shot 타임아웃 = `enterAddPin` 측 5초 자체 적용** (`Task.sleep(5s)` race, 공유 `CoreLocationService` 10초 불변 → PRD FR-11 정합)
4. **줌 상수 신규 명명**: `addPinMinZoom=13` / `addPinLocatedZoom=15` / `addPinFallbackZoom=14` (static let, 의미 분리)
5. **애니메이션**: 십자선 `.transition(.opacity)` + 카드 `.transition(.move(edge:.bottom))` + `.easeOut(0.2)` (기존 토스트 톤 일관)

> design-critic 비판 검토 병행(agentId af3cf5d3d380f4752). 완료 시 MUST-ADDRESS는 구현에 반영.

## 설계 규모
**대형** — 진입 흐름 전면 전환(풀시트 → 메인 지도 인라인 오버레이), 상태 소유권 이전(시트 로컬 → MapViewModel), 신규 오버레이 2종, FR-11 줌인 분기 위한 SDK 줌 노출 추가, 4개 파일 수정 + 3개 신규 + 1개 삭제.

## 변경 범위

**신규 생성 파일 (3):**
| 파일 | 책임 |
|------|------|
| `ios/WhereWeGo/Features/Map/CrosshairOverlay.swift` | 중앙 고정 십자선 오버레이(가로/세로선+중앙점, `allowsHitTesting(false)`). 웹 `CrosshairOverlay.tsx` 동치. FR-3/AC-2 |
| `ios/WhereWeGo/Features/Map/InlineAddPlaceCard.swift` | 하단 얇은 확정 카드(검색창+결과목록+주소/폴백+태그3종+여기등록+취소). `@ObservedObject AddPlaceViewModel`. FR-4/FR-10/AC-3 |
| `ios/WhereWeGoTests/InlineAddPlaceModeTests.swift` | 진입/종료/줌인 분기 순수 로직 테스트 |

**수정 대상 파일 (5):**
| 파일:심볼 | 변경 요지 |
|------|------|
| `MapViewModel.swift` : `ActiveSheet`, 신규 `isAddingPin`/`addPlaceVM`/`mapZoom`, `enterAddPin()`/`exitAddPin()`, `handle(_:)`, `applyAddPinEntryZoom()` | 인라인 모드 상태 소유 + AddPlaceViewModel 인스턴스 보유 + cameraIdle 분기 + FR-11 줌인. AC-5/7/11/13/16 |
| `MapView.swift` : `loadedOverlay`, `.sheet(addPlace)` 제거, `onChange` | 십자선·확정 카드 오버레이 삽입 + 시트 제거. EmptyMapCard 콜백 변경. AC-2/3/4 |
| `MainTabView.swift` : `showAddPlace`, `.sheet`, `onPlusTap`, `onChange(selection)` | 시트 제거 → 인라인 모드 토글 + 탭 전환 시 종료. AC-1/4/12 |
| `MapboxMapView.swift` : `MapEvent.cameraIdle`, `Coordinator.onMapIdle`, stub | cameraIdle에 zoom 추가(FR-11/AC-16). MUST-1 격리(이 파일에서만 SDK 줌 읽기) |
| `MapRenderer.swift` : `enum MapEvent.cameraIdle` | `cameraIdle(centerLat:centerLng:zoom:)`로 확장 |

**제거:** `AddPlaceSheet.swift` — 독립 `MapContainerView` + `mapCameraCommand`/`mapFitBoundsCommand` @State + 풀시트 구조 제거. UI는 `InlineAddPlaceCard.swift`로 이식 → **파일 삭제**.

## 적용 컨벤션 (탐색으로 파악, 신규 코드는 이를 따름)
- **네이밍**: 타입 PascalCase, 멤버 camelCase, 상수 `static let`. `PinTag` case는 `REEL`/`WISH`/`MEMORY` 대문자(`PinTag.swift:9`). 파일 1개=1 주요 타입.
- **레이어**: View(struct) ← ViewModel(`@MainActor final class ObservableObject`) ← Core 추상(프로토콜). `@Published private(set)` + 메서드 mutation. 생성자 주입.
- **카메라 흐름(B2 계약)**: ViewModel `@Published var cameraCommand: CameraTarget?` 설정 → `MapboxMapView`가 `updateUIView`에서 소비 후 `DispatchQueue.main.async { nil }` 리셋(1회 소비).
- **상태 주석**: 각 `@Published`/메서드에 FR/AC/BR 추적 주석(예: `// AC-8 — 콕찍기 시작 시 검색어 초기화`). 신규 코드 동일.
- **에러 처리**: `enum MapError: LocalizedError`, `MapViewModel.message(for:)` 한국어 매핑 재사용.
- **MUST-1 격리**: `import MapboxMaps`는 `MapboxMapView.swift` 1파일에만. `MapEvent` 시그니처 변경 시 `#if`/`#else` 양쪽 stub 동기 필수.
- **색/폰트 토큰**: `WGColor.*`, `WGFont.sans/serif/mono/emo(_:)`.

## 상태 모델

**핵심: `AddPlaceViewModel` 인스턴스 수명을 `MapViewModel`이 소유.**
현재 `AddPlaceSheet`가 `@StateObject`로 생성·소유(`AddPlaceSheet.swift:17,30`) → 시트 닫히면 소멸. 인라인 전환 후 시트가 없으므로 소유처 이전. `MapViewModel` 보유 시 ＋탭/EmptyMapCard 두 진입점이 동일 VM 공유(`MainTabView.swift:76` 주입), `cameraIdle` 핸들러가 직접 `addPlaceVM.onMapMoved` 호출.

```swift
/// 인라인 핀 추가 모드 활성(FR-1/2, AC-11). true 시 십자선+확정 카드 표시.
@Published private(set) var isAddingPin = false
/// 인라인 추가 VM(검색/콕찍기/생성 로직, AddPlaceSheet 에서 이관). 진입 시 생성, 종료 시 nil(작성중 폐기, BR-1).
@Published private(set) var addPlaceVM: AddPlaceViewModel?
/// cameraIdle 최신 줌(FR-11 진입 판단).
@Published private(set) var mapZoom: Double?
```

**`ActiveSheet.addPlace` 제거.** 인라인은 시트 아닌 오버레이 → 시트 상호배제 모델에서 제외(AC-11). `isAddingPin` vs `roulette`/`visitMemo` 상호배제는 `enterAddPin()`/시트 트리거에서 명시 처리(BR-6). `EmptyMapCard.onAddPin`은 `enterAddPin()` 호출로 변경.

**수명 흐름:**
1. `enterAddPin()`: `addPlaceVM = AddPlaceViewModel(mapViewModel: self)` → `isAddingPin = true` → `applyAddPinEntryZoom()`(FR-11) → `seedInitialPinpoint()`(FR-9 현재 중심 역지오).
2. 활성 중: `handle(.cameraIdle)` → `addPlaceVM?.onMapMoved(center:)`.
3. `exitAddPin()`: `isAddingPin = false` → `addPlaceVM = nil`(작성중 폐기, BR-1).
4. `addPlaceVM.didCreate` 관찰 → `exitAddPin()`(createPin 내부가 이미 appendPin/flyTo, `AddPlaceViewModel.swift:172-173`).

> 순환참조: `AddPlaceViewModel.mapViewModel`은 weak(`:56`). MapViewModel→addPlaceVM(strong)→mapViewModel(weak), cycle 없음.

## 컴포넌트 설계

### ① CrosshairOverlay (신규, FR-3/AC-2)
웹 `CrosshairOverlay.tsx`(size28/arm10/thickness2/dot3) 1:1 이식. ZStack 가로선·세로선·중앙점, `WGColor.cta`. `.allowsHitTesting(false)` 터치 통과(AC-2). 화면 정중앙 = 지도 중심(웹 getCenter 동치, offset 불필요 — 십자선 대칭이라 기존 `mappin`의 `offset(y:-14)` 보정 제거).

### ② InlineAddPlaceCard (신규, FR-4/FR-10/AC-3)
`AddPlaceSheet`의 searchBar(67-91)+resultsList(94-119)+confirmCard(182-219)+placeSummary+tagToggle+submitButton을 그대로 이식, 컨테이너만 시트→하단 카드.
```swift
struct InlineAddPlaceCard: View {
    @ObservedObject var viewModel: AddPlaceViewModel   // MapViewModel 소유, 여기선 관찰
    let onSelectResult: (PlaceItem) -> Void            // 검색 선택 → MapViewModel.cameraCommand flyTo(AC-9)
    let onCancel: () -> Void                           // 취소 → MapViewModel.exitAddPin()
    @State private var selectedTag: PinTag = .WISH
}
```
**바인딩:** 검색창→`$viewModel.query`+`.onSubmit{ search() }`(AC-8). 결과→`viewModel.results` ForEach, 행 탭→`onSelectResult`(AC-9). 주소/폴백→`viewModel.confirmTitle`/`confirmAddress`+`isResolvingAddress` 시 "주소를 찾는 중..."(BR-4). 태그→`selectedTag`. 여기등록→`createPin(tag:)`(`canConfirm` 게이트, AC-7). 취소→`onCancel`. 에러→`viewModel.errorMessage` 배너.
**검색 좌표(AC-9):** 카드가 직접 카메라 안 만지고 `onSelectResult` 위임 → MapView에서 `addPlaceVM?.selectResult(place)` + `mapViewModel.flyTo`(메인 cameraCommand, B2 계약).

## 카메라/좌표 흐름
- **진입(FR-9):** `initialCameraTarget`(`AddPlaceViewModel.swift:226-233`, mapCenter 우선+서울 폴백) 재사용. 인라인은 메인 지도 그대로 → 카메라 강제이동 없이 현재 중심으로 `onMapMoved` 1회 호출해 역지오 트리거. `mapCenter` nil이면 역지오 스킵.
- **드래그(FR-5/6, AC-5/6):**
```swift
case .cameraIdle(let lat, let lng, let zoom):
    mapZoom = zoom
    mapCenter = Coordinate(latitude: lat, longitude: lng)   // 기존 유지(방문감지)
    if isAddingPin { addPlaceVM?.onMapMoved(center: Coordinate(latitude: lat, longitude: lng)) }  // AC-5
```
디바운스300ms/resolveAddress/coordinateFallback은 `onMapMoved` 내부 재사용(AC-6). `query=""`/`selectedPlace=nil` 보존(AC-10).
- **검색 flyTo(FR-10/AC-9):** `onSelectResult`에서 `flyTo(lat:lng:zoom: pinFocusZoom)` → 메인 cameraCommand.
- **FR-11 줌<13(AC-16):** `applyAddPinEntryZoom()` in `enterAddPin`. 웹 MapClient.tsx:1043-1066 동치.
```swift
private func applyAddPinEntryZoom() {
    guard let zoom = mapZoom, zoom < Self.addPinMinZoom else { return }
    switch locationService.authorizationStatus {
    case .authorizedWhenInUse, .authorizedAlways:
        Task { @MainActor in
            if let s = await requestOneShotWithTimeout(5) { flyTo(lat:s.lat,lng:s.lng,zoom:Self.addPinLocatedZoom) }
            else { bumpZoomOnly(to: Self.addPinFallbackZoom) }
        }
    case .notDetermined: /* 권한 요청 후 동일, 실패 시 14 */ ...
    default: bumpZoomOnly(to: Self.addPinFallbackZoom)   // 거부 → 중심 유지 14
    }
}
// bumpZoomOnly = flyTo(mapCenter.lat, mapCenter.lng, zoom). mapCenter nil이면 no-op.
```
- **MapEvent.cameraIdle 확장(MUST-1):** `MapRenderer.swift`에 `cameraIdle(centerLat:centerLng:zoom:)`. `MapboxMapView`에서 `cameraState.zoom` 전달(이미 center 읽는 경로 `:62-69`). `#else` stub은 cameraIdle 미발생 → 시그니처만 정합. `MapViewModelTests`/`AddPlaceViewModelTests`의 `handle(.cameraIdle(...))` 호출부 zoom 인자 추가(시그니처 정합, 회귀 아님).

## AddPlaceSheet 처리
**파일 삭제 + UI 이식.** 제거 대상(AC-15): 독립 `MapContainerView`(`:154-159`), `mapCameraCommand`/`mapFitBoundsCommand` @State(`:22-24`), `handleMapEvent`(`:175-178`), `mapSection`(`:151-172`). 나머지 UI는 `InlineAddPlaceCard`로 이식(NavigationStack/toolbar/dismiss는 시트 전용이라 제거). 파일에 남는 것 없음 → 삭제. `AddPlaceViewModel` 변경 없음(소유처만 이전) + `isResolvingAddress`만 추가.

## 의존성/영향도
- 새 의존성 없음. `project.yml`은 `sources: path WhereWeGo` 디렉토리 포함 → 신규 .swift는 `xcodegen generate`만(project.yml 수정 불필요).
- `MapEvent.cameraIdle` 확장 → `MapboxMapView`(#if/#else), `MapViewModel.handle`, `MapViewModelTests`/`AddPlaceViewModelTests` zoom 인자 추가(컴파일 영향, 동작 회귀 없음).
- `MainTabTests`: `onPlusTap` 모델 `showAddPlace=true`→`enterAddPin()`. selection 불변 테스트는 통과(본문 변수 갱신 필요).
- `MapView`/`MainTabView`의 `.sheet(addPlace)`/`addPlaceSheetBinding`(`MapView.swift:129-136`) 제거. roulette/visitMemo 유지.
- 백엔드 API·createPin 구조 동일. 핀/그룹 데이터 영향 없음.

## 구현 순서
```
1. [Must] MapEvent.cameraIdle 에 zoom 추가 (의존 없음)
   - MapRenderer.swift: case cameraIdle(centerLat:centerLng:zoom:)
   - MapboxMapView.swift: #if onMapIdle 에서 cameraState.zoom 전달 + #else stub 시그니처 정합
2. [Must] CrosshairOverlay.swift 신규 (의존 없음) — 웹 동치, allowsHitTesting(false)
3. [Must] MapViewModel 인라인 상태/메서드 (의존 1)
   - isAddingPin/addPlaceVM/mapZoom, enterAddPin/exitAddPin, handle(.cameraIdle) zoom+분기
   - applyAddPinEntryZoom(FR-11)+줌 상수, seedInitialPinpoint(FR-9), handle(.markerTapped) guard !isAddingPin(BR-2)
4. [Must] AddPlaceViewModel.isResolvingAddress 추가 (의존 없음) — onMapMoved에서 true, resolveAddress 완료/폐기 시 false
5. [Must] InlineAddPlaceCard.swift 신규 (의존 3,4) — UI 이식 + 바인딩 + onSelectResult/onCancel
6. [Must] MapView 오버레이 통합 (의존 2,3,5)
   - loadedOverlay에 isAddingPin 조건부 CrosshairOverlay+InlineAddPlaceCard
   - .sheet(addPlace)+addPlaceSheetBinding 제거, EmptyMapCard onAddPin→enterAddPin
   - onChange(didCreate)→exitAddPin, bottom padding=FloatingTabBar 높이(QE-2/AC-14)
7. [Must] MainTabView 시트 제거 + 토글 (의존 3)
   - onPlusTap→enterAddPin, .sheet(showAddPlace)+showAddPlace 제거
   - onChange(selection)→exitAddPin(BR-1/AC-12), 룰렛 진입 시 exitAddPin 선행(BR-6)
8. [Must] AddPlaceSheet.swift 삭제 (의존 5,6,7) — 참조 제거 확인 후(AC-15)
9. [Must] 테스트 갱신/추가 (의존 1,3,7)
   - MapViewModelTests/AddPlaceViewModelTests: handle(.cameraIdle) zoom 인자
   - MainTabTests: onPlusTap 모델 갱신
   - InlineAddPlaceModeTests 신규: enterAddPin/exitAddPin 전이, applyAddPinEntryZoom 분기(주입 줌/권한별), BR-2 가드
10. [Should] QE-1 전환 애니메이션 (의존 6) — 십자선 opacity, 카드 move(.bottom), easeOut(0.2)
```
**병렬 배치:** B1={1,2,4}(파일 배타, 동시) → B2={3}(1후) → B3={5}(3,4후) → B4={6,7}(둘 다 enter/exitAddPin 참조, VM 시그니처 합의 후 병렬·파일 배타) → B5={8} → B6={9} → B7={10}.

## 수용 기준 매핑
| AC | 충족 설계 요소 |
|----|--------------|
| AC-1 | `MainTabView.onPlusTap → enterAddPin()`, `.sheet(showAddPlace)` 제거 |
| AC-2 | `CrosshairOverlay`(allowsHitTesting(false)) + `MapView.loadedOverlay` `if isAddingPin` |
| AC-3 | `InlineAddPlaceCard`(검색창+주소/좌표+태그3종+여기등록+취소) + 조건부 삽입 |
| AC-4 | `MapView` addPlaceSheetBinding·`.sheet` 제거 + `MainTabView` `.sheet(showAddPlace)` 제거 |
| AC-5 | `handle(.cameraIdle)` 내 `if isAddingPin { addPlaceVM?.onMapMoved }` |
| AC-6 | `AddPlaceViewModel.onMapMoved`/`resolveAddress`/`coordinateFallback` 재사용 |
| AC-7 | 여기등록 → `createPin(tag:)`; `onChange(didCreate)→exitAddPin()` |
| AC-8 | 검색창 `$viewModel.query` + `.onSubmit{ search() }` |
| AC-9 | `onSelectResult` → `selectResult` + `mapViewModel.flyTo`(cameraCommand) |
| AC-10 | `onMapMoved` 내 `query=""`/`selectedPlace=nil` 보존 |
| AC-11 | `ActiveSheet.addPlace` 제거 + `@Published isAddingPin` |
| AC-12 | `MainTabView.onChange(selection) → exitAddPin()` |
| AC-13 | `handle(.markerTapped)` `guard !isAddingPin` |
| AC-14 | `InlineAddPlaceCard` bottom padding = FloatingTabBar 높이 |
| AC-15 | `AddPlaceSheet.swift` 삭제 |
| AC-16 | `applyAddPinEntryZoom()`(줌<13 분기+권한+flyTo) + `MapEvent.cameraIdle.zoom` |

(AC-B1~10은 DoD-B Mac 시각·런타임 검증으로 이연.)

## 탐색 추가 항목 (코드맵 반영됨)
- `MapRenderer.swift`(MapEvent/CameraTarget 정의), `MapConfig.swift`(폴백 판단), `PlaceAPI.swift`(PlaceItem), `PinTag.swift`(REEL/WISH/MEMORY), `PlaceholderMapView.swift`(토큰 미설정 폴백), `AddPlaceViewModelTests.swift`/`MainTabTests.swift`(갱신 대상).

---

## 설계 보강 (design-critic MUST-ADDRESS 4건 해소 + Q6/Q7 확정)

> **근본 전략**: `MapViewModel`에 **프로그래매틱 카메라 이동 추적**(`pendingProgrammaticIdle`) 도입 — "내가 명령한 flyTo(검색/줌인/seed)로 인한 cameraIdle"과 "사용자 드래그 cameraIdle"을 ViewModel 레벨에서 구분. SDK는 둘을 동일 `onMapIdle`로 올리므로(MapboxMapView.swift:79-84 flyTo=프로그래매틱) ViewModel 카운터가 유일한 구분점. B2 계약(cameraCommand 1회 소비)과 대칭.
> **Q6 확정 (a)**: `createPin`을 VM 보유 `createTask`로 변경 + `appendPin`/`flyTo` 직전 `Task.isCancelled` 가드 → 탭 전환 포함 모든 종료 경로에서 "취소했는데 생성" 완전 차단. 호출부는 `viewModel.createPin(tag:)`(비-async)로.
> **Q7 확정 (a)**: `notDetermined`(권한 미결정) 시 즉시 `zoom14` bump, 권한 허용은 다음 진입/내위치 버튼에서 반영(다이얼로그 응답 대기로 인한 카메라 지연/점프 회피, iOS 권한 모델 정합).

### MapViewModel 추가 상태 (종합)
```swift
@Published private(set) var isAddingPin = false
@Published private(set) var addPlaceVM: AddPlaceViewModel?
@Published private(set) var mapZoom: Double?          // MUST-4: idle 전 시드 경로 보강
private var pendingProgrammaticIdle = 0               // MUST-1
private var seedOnNextProgrammaticIdle = false         // MUST-2
private var userDraggedSinceEntry = false              // MUST-2
```
`AddPlaceViewModel`: `@Published private(set) var isResolvingAddress`(BR-4), `private var createTask: Task<Void,Never>?`(Q6), `func cancelPendingWork()`(MUST-3). `Debouncer`: `func cancel()`(generation+1, MUST-3).

### MUST-1 — 검색 flyTo가 콕찍기로 덮어쓰는 문제
- `flyTo`류(인라인 모드 중)에서 `pendingProgrammaticIdle += 1` + `mapZoom` 즉시 갱신.
- `handle(.cameraIdle)`: `pendingProgrammaticIdle>0`이면 1 감소 후 `return`(onMapMoved 스킵, mapCenter/mapZoom은 갱신). 2차 안전망: `inputMode==.search && selectedPlace != nil`이면 보류.
- 검색 후 **수동** 드래그는 카운터 0 + 진짜 사용자 idle → 콕찍기 전환(PRD 101행 엣지 충족). **AC-17**.

### MUST-2 — 진입 경쟁(줌인 flyTo + seed 중복/늦은 점프)
- `enterAddPin → applyAddPinEntry`: 줌≥13(가정값 포함)이면 flyTo 없음 → `seedInitialPinpoint()` 직접 1회. 줌<13이면 `seedOnNextProgrammaticIdle=true` + `applyAddPinEntryZoom`(줌인 flyTo) → 그 idle에서 seed 1회 수행.
- → **초기 역지오 1회 수렴**. one-shot 늦은 도착 시 `userDraggedSinceEntry==true`면 줌인 flyTo 스킵(강제 점프 제거). **AC-18**.

### MUST-3 — exitAddPin Task 취소("취소했는데 생성")
- `InlineAddPlaceCard` 취소 버튼 `.disabled(viewModel.isCreating)`(BR-3 일관).
- `exitAddPin`: `addPlaceVM?.cancelPendingWork()`(=`debouncer.cancel()` + `createTask?.cancel()`) → `addPlaceVM=nil` + 플래그 리셋.
- `Debouncer.cancel()`=`generation += 1`(기존 토큰 가드 `ReverseGeocoder.swift:74-81` 활용, 새 핸들 불필요).
- Q6(a): `createPin`을 `createTask` 보유 + `appendPin`/`flyTo` 직전 `Task.isCancelled` 가드 → 탭 전환 포함 완전 차단. **AC-19**.

### MUST-4 — mapZoom 초기 nil로 FR-11 무력화
- `applyInitialCamera`(load 경로) 및 `flyTo`에서 `mapZoom`을 idle 도착 전 시드(명령 줌=곧 렌더 줌). 서울 zoom3 초기 상태 → `mapZoom=3` 보장.
- 끝내 nil이면 `static let addPinAssumeZoomWhenUnknown: Double = 3`(<13 → 줌인 시도 보장) 폴백. → 서울시청 zoom3 진입 시 FR-11 정상 평가. **AC-20**.

### handle(.cameraIdle) 최종형
```swift
case .cameraIdle(let lat, let lng, let zoom):
    mapCenter = Coordinate(latitude: lat, longitude: lng)   // 항상(기존 방문감지)
    mapZoom = zoom                                          // 항상(MUST-4)
    guard isAddingPin else { return }
    if pendingProgrammaticIdle > 0 {                        // MUST-1
        pendingProgrammaticIdle -= 1
        if seedOnNextProgrammaticIdle {                     // MUST-2
            seedOnNextProgrammaticIdle = false
            addPlaceVM?.onMapMoved(center: Coordinate(latitude: lat, longitude: lng))
        }
        return
    }
    userDraggedSinceEntry = true                            // MUST-2
    guard addPlaceVM?.inputMode != .search || addPlaceVM?.selectedPlace == nil else { return }  // MUST-1 2차
    addPlaceVM?.onMapMoved(center: Coordinate(latitude: lat, longitude: lng))  // 사용자 드래그(AC-5)
```

### 신규 AC 종합
- **AC-17 [FR-10]**: 프로그래매틱 이동 시 `onMapMoved` 스킵(검색 결과 보존).
- **AC-18 [FR-9/11]**: 초기 역지오 1회 수렴 + userDragged 시 늦은 줌인 스킵.
- **AC-19 [BR-3]**: isCreating 중 취소 비활성 + exitAddPin이 Debouncer/createTask 취소.
- **AC-20 [FR-11]**: mapZoom idle 전 시드 + nil 가정값 폴백.

### 구현 순서 반영(변경분)
- **3단계 확장**: `pendingProgrammaticIdle`/`seedOnNextProgrammaticIdle`/`userDraggedSinceEntry` 추가, `flyTo`류 카운터+mapZoom 증가, `handle(.cameraIdle)` 3분기, `enterAddPin→applyAddPinEntry`(줌인/seed 배타), `applyAddPinEntryZoom`에 userDragged 가드 + notDetermined 즉시14(Q7), `applyInitialCamera` mapZoom 시드, `addPinAssumeZoomWhenUnknown` 상수, `requestOneShotWithTimeout(seconds:5)` 헬퍼.
- **3.5단계 신규**: `Debouncer.cancel()` + `AddPlaceViewModel.cancelPendingWork()` + `createTask` 래핑(Q6a). (`ReverseGeocoder.swift`/`AddPlaceViewModel.swift` 배타)
- **6단계**: `onChange(of: viewModel.addPlaceVM?.didCreate)` 관찰, 취소 `disabled(isCreating)`.
- **9단계**: `InlineAddPlaceModeTests`에 MUST-1~4 케이스 추가.
