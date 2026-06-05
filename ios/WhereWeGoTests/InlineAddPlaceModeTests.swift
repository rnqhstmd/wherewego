import XCTest
import CoreLocation
@testable import WhereWeGo

// 인라인 핀 추가 모드 순수 로직 테스트(P8 영역1, 설계 §구현 순서 9).
// MapViewModel 의 enterAddPin/exitAddPin 상태 전이, applyAddPinEntry 줌인/seed 배타(MUST-2),
// 프로그래매틱 idle 카운터로 검색 결과 보존(MUST-1), isCreating 중 종료/취소(MUST-3),
// mapZoom 시드/가정값(MUST-4), 마커 탭 차단(BR-2)을 검증한다.
//
// 줌인 분기 중 권한 허용 경로는 async one-shot Task 를 띄우므로(즉시 단언 불가) denied/notDetermined
// 의 동기 결과(cameraCommand) 와 줌≥13 의 seed(onMapMoved) 동기 상태만 결정적으로 단언한다.
// (권한 허용+one-shot flyTo 의 실제 카메라 이동은 DoD-B 통합 검증 영역.)
//
// VM 이 @MainActor 이므로 테스트 클래스도 @MainActor. Stub 은 기존 MapViewModelTests 패턴 재사용.
@MainActor
final class InlineAddPlaceModeTests: XCTestCase {

    // MARK: - enterAddPin/exitAddPin 상태 전이

    func test_enterAddPin_activatesModeAndCreatesAddPlaceVM() {
        let vm = makeViewModel()
        XCTAssertFalse(vm.isAddingPin)
        XCTAssertNil(vm.addPlaceVM)

        vm.enterAddPin()

        XCTAssertTrue(vm.isAddingPin, "enterAddPin 시 인라인 모드 활성(AC-11).")
        XCTAssertNotNil(vm.addPlaceVM, "enterAddPin 시 AddPlaceViewModel 생성.")
    }

    func test_enterAddPin_whenAlreadyActive_isNoop() {
        let vm = makeViewModel()
        vm.enterAddPin()
        let firstVM = vm.addPlaceVM

        vm.enterAddPin()   // 중복 진입

        XCTAssertTrue(firstVM === vm.addPlaceVM, "이미 활성이면 addPlaceVM 을 재생성하지 않는다.")
    }

    func test_exitAddPin_deactivatesAndClearsAddPlaceVM() {
        let vm = makeViewModel()
        vm.enterAddPin()
        XCTAssertTrue(vm.isAddingPin)

        vm.exitAddPin()

        XCTAssertFalse(vm.isAddingPin, "exitAddPin 시 인라인 모드 비활성(AC-12).")
        XCTAssertNil(vm.addPlaceVM, "exitAddPin 시 작성 중 VM 폐기(BR-1).")
    }

    func test_exitAddPin_whenInactive_isNoop() {
        let vm = makeViewModel()
        vm.exitAddPin()   // 활성 아닌 상태에서 호출
        XCTAssertFalse(vm.isAddingPin)
        XCTAssertNil(vm.addPlaceVM)
    }

    // MARK: - MUST-4: mapZoom 시드/가정값

    func test_applyInitialCamera_seedsMapZoom_whenDenied() async {
        // 권한 거부 → 서울시청 zoom3 카메라 + mapZoom 시드(idle 도착 전).
        let vm = makeViewModel(location: StubLocationService(status: .denied))
        await vm.load()
        XCTAssertEqual(vm.mapZoom, MapViewModel.seoulCityHall.zoom, "초기 카메라 명령 줌으로 mapZoom 시드(MUST-4).")
    }

    func test_cameraIdle_updatesMapZoom() {
        let vm = makeViewModel()
        vm.handle(.cameraIdle(centerLat: 37.5, centerLng: 127.0, zoom: 16))
        XCTAssertEqual(vm.mapZoom, 16, "cameraIdle 은 항상 최신 줌을 보유(MUST-4).")
    }

    // MARK: - MUST-2: applyAddPinEntry 줌인/seed 배타(권한·줌별)

    func test_enterAddPin_whenZoomBelowMin_andDenied_bumpsZoomOnlyToFallback() throws {
        // 줌 8(<13) + 권한 거부 → 현재 중심 유지하며 zoom14 로만 bump(웹 동치, FR-11 우선순위 3).
        let vm = makeViewModel(location: StubLocationService(status: .denied))
        // mapCenter/mapZoom 시드(idle).
        vm.handle(.cameraIdle(centerLat: 37.5446, centerLng: 127.0557, zoom: 8))

        vm.enterAddPin()

        // accuracy 버전 XCTAssertEqual 은 Double(비옵셔널)을 요구하므로 cameraCommand 를 먼저 unwrap.
        let command = try XCTUnwrap(vm.cameraCommand)
        XCTAssertEqual(command.latitude, 37.5446, accuracy: 1e-7, "현재 중심 유지.")
        XCTAssertEqual(command.longitude, 127.0557, accuracy: 1e-7)
        XCTAssertEqual(command.zoom, MapViewModel.addPinFallbackZoom, "거부 시 zoom14 로만 올림(FR-11).")
    }

    func test_enterAddPin_whenZoomBelowMin_andNotDetermined_bumpsZoomOnlyToFallback() throws {
        // 줌 8(<13) + 권한 미결정(Q7) → 권한 요청 후 다이얼로그 대기 없이 즉시 zoom14 bump.
        let location = StubLocationService(status: .notDetermined)
        let vm = makeViewModel(location: location)
        vm.handle(.cameraIdle(centerLat: 35.1, centerLng: 129.0, zoom: 8))

        vm.enterAddPin()

        XCTAssertTrue(location.didRequestPermission, "notDetermined 시 권한 요청(Q7).")
        // accuracy 버전 XCTAssertEqual 은 Double(비옵셔널)을 요구하므로 cameraCommand 를 먼저 unwrap.
        let command = try XCTUnwrap(vm.cameraCommand)
        XCTAssertEqual(command.zoom, MapViewModel.addPinFallbackZoom, "응답 대기 없이 즉시 zoom14(Q7).")
        XCTAssertEqual(command.latitude, 35.1, accuracy: 1e-7, "현재 중심 유지.")
    }

    func test_enterAddPin_whenZoomAtLeastMin_seedsPinpointWithoutZoomBump() {
        // 줌 15(≥13) → 줌인 없이 현재 중심으로 초기 콕찍기 seed(FR-9). cameraCommand 변화 없음.
        let vm = makeViewModel(location: StubLocationService(status: .denied))
        vm.handle(.cameraIdle(centerLat: 37.5446, centerLng: 127.0557, zoom: 15))
        vm.cameraCommand = nil   // idle 이후 카메라 명령 없음 상태로.

        vm.enterAddPin()

        XCTAssertNil(vm.cameraCommand, "줌 충분 시 진입 줌인 flyTo 가 없어야 한다(MUST-2 배타).")
        // seedInitialPinpoint → onMapMoved → 콕찍기 중심 확정(FR-9).
        XCTAssertEqual(vm.addPlaceVM?.inputMode, .pinpoint, "진입 즉시 콕찍기 모드로 seed.")
        XCTAssertEqual(vm.addPlaceVM?.pinpointCenter, Coordinate(latitude: 37.5446, longitude: 127.0557))
    }

    // MARK: - P8 영역4 후속: speed-dial 진입 모드(콕찍기/검색) 분리

    func test_enterAddPin_searchMode_doesNotSeedPinpoint() {
        // 검색 모드 진입: 콕찍기 seed/줌인 없이 inputMode 가 .search 유지(십자선 미표시 근거).
        // 줌≥13(콕찍기였다면 seedInitialPinpoint 가 도는 조건)에서도 검색 모드는 seed 하지 않음을 확인.
        let vm = makeViewModel(location: StubLocationService(status: .denied))
        vm.handle(.cameraIdle(centerLat: 37.5, centerLng: 127.0, zoom: 15))
        vm.cameraCommand = nil

        vm.enterAddPin(mode: .search)

        XCTAssertTrue(vm.isAddingPin, "검색 모드도 인라인 추가 모드는 활성.")
        XCTAssertEqual(vm.addPlaceVM?.inputMode, .search, "검색 모드 진입 시 .search 유지(콕찍기 seed 없음 → 십자선 미표시).")
        XCTAssertNil(vm.addPlaceVM?.pinpointCenter, "검색 모드는 콕찍기 중심을 만들지 않는다.")
        XCTAssertNil(vm.cameraCommand, "검색 모드는 진입 줌인/카메라 이동이 없다.")
    }

    func test_enterAddPin_defaultMode_isPinpoint() {
        // 인자 없는 enterAddPin() 은 콕찍기 기본(기존 호출 호환).
        let vm = makeViewModel(location: StubLocationService(status: .denied))
        vm.handle(.cameraIdle(centerLat: 37.5, centerLng: 127.0, zoom: 15))

        vm.enterAddPin()

        XCTAssertEqual(vm.addPlaceVM?.inputMode, .pinpoint, "기본 모드는 콕찍기(seed 수행).")
    }

    func test_enterAddPin_closesAddMenu() {
        // speed-dial 펼친 상태에서 모드 진입 → 메뉴를 닫는다.
        let vm = makeViewModel()
        vm.isAddMenuExpanded = true

        vm.enterAddPin(mode: .search)

        XCTAssertFalse(vm.isAddMenuExpanded, "모드 진입 시 speed-dial 메뉴를 닫는다.")
    }

    func test_enterAddPin_whenZoomBelowMin_seoulCityHall_bumpsZoomToFallback() {
        // 줌3(<13, 서울시청 전국뷰) 진입 → zoom14 bump(FR-11). mapZoom 이 private(set) 이라 nil 직접 주입 불가하므로
        // cameraIdle(zoom:3)로 동치 검증(가정값 addPinAssumeZoomWhenUnknown=3 과 동일 경로, MUST-4/AC-20).
        let vm = makeViewModel(location: StubLocationService(status: .denied))
        // 줌은 주지 않고 중심만 시드하기 위해 cameraIdle 로 중심+줌3 을 넣되, 줌 nil 상황 모사는 어려우므로
        // 가정값 경로는 "mapZoom 이 13 미만이면 줌인 시도"의 표면을 cameraIdle(zoom: 3)로 검증한다.
        vm.handle(.cameraIdle(centerLat: 37.5, centerLng: 127.0, zoom: 3))

        vm.enterAddPin()

        XCTAssertEqual(vm.cameraCommand?.zoom, MapViewModel.addPinFallbackZoom,
                       "줌3(<13) 진입 시 zoom14 bump(FR-11, 서울시청 전국뷰 진입 정합).")
    }

    // MARK: - MUST-1: 프로그래매틱 idle 로 검색 결과 보존(AC-17)

    func test_searchSelection_thenProgrammaticIdle_preservesSelection() {
        // 줌≥13 진입(줌인 없음) → 검색 선택 → flyTo(프로그래매틱) → 그 idle 에서 onMapMoved 스킵(검색 보존).
        let vm = makeViewModel(location: StubLocationService(status: .denied))
        vm.handle(.cameraIdle(centerLat: 37.5, centerLng: 127.0, zoom: 15))
        vm.enterAddPin()

        let place = PlaceItem(placeName: "성수동 카페", address: "서울 성동구", latitude: 37.5446, longitude: 127.0557)
        vm.addPlaceVM?.selectResult(place)
        XCTAssertEqual(vm.addPlaceVM?.inputMode, .search)
        // 검색 결과 선택 → 메인 flyTo(프로그래매틱 idle 1건 예약 + mapZoom 갱신).
        vm.flyTo(lat: place.latitude, lng: place.longitude, zoom: MapViewModel.pinFocusZoom)

        // flyTo 로 발생한 프로그래매틱 cameraIdle → onMapMoved 스킵되어 검색 선택 보존(MUST-1/AC-17).
        vm.handle(.cameraIdle(centerLat: place.latitude, centerLng: place.longitude, zoom: MapViewModel.pinFocusZoom))

        XCTAssertEqual(vm.addPlaceVM?.inputMode, .search, "프로그래매틱 idle 은 콕찍기로 전환하지 않는다(AC-17).")
        XCTAssertEqual(vm.addPlaceVM?.selectedPlace, place, "검색 선택 좌표 보존.")
    }

    func test_searchSelection_thenUserDragIdle_switchesToPinpoint() {
        // 검색 선택 후 '수동' 드래그 idle(프로그래매틱 카운터 0) → 2차 안전망 통과해 콕찍기 전환.
        let vm = makeViewModel(location: StubLocationService(status: .denied))
        vm.handle(.cameraIdle(centerLat: 37.5, centerLng: 127.0, zoom: 15))
        vm.enterAddPin()

        let place = PlaceItem(placeName: "카페", address: nil, latitude: 37.5446, longitude: 127.0557)
        vm.addPlaceVM?.selectResult(place)
        vm.flyTo(lat: place.latitude, lng: place.longitude, zoom: MapViewModel.pinFocusZoom)
        // 검색 flyTo 의 프로그래매틱 idle 1건 소비.
        vm.handle(.cameraIdle(centerLat: place.latitude, centerLng: place.longitude, zoom: MapViewModel.pinFocusZoom))
        XCTAssertEqual(vm.addPlaceVM?.inputMode, .search)

        // 이제 진짜 사용자 드래그(프로그래매틱 카운터 0) → 콕찍기 전환(PRD 101행 엣지).
        vm.handle(.cameraIdle(centerLat: 36.0, centerLng: 128.0, zoom: MapViewModel.pinFocusZoom))

        XCTAssertEqual(vm.addPlaceVM?.inputMode, .pinpoint, "수동 드래그는 콕찍기로 전환(AC-17 엣지).")
        XCTAssertNil(vm.addPlaceVM?.selectedPlace, "콕찍기 전환 시 검색 선택 초기화(AC-10).")
    }

    // MARK: - MUST-3: 진행 작업 취소(AC-19)

    func test_debouncerCancel_invalidatesPendingWork() {
        // Debouncer.cancel() 이 generation 을 올려 예약된 work 의 내부 action 발화를 무효화한다(MUST-3 핵심).
        // 수동 트리거 스케줄러: work 를 보관했다가 직접 실행해 "취소 후 발화해도 action 미실행"을 카운트로 검증.
        var stored: (() -> Void)?
        let debouncer = Debouncer(interval: 0.3, scheduler: { _, work in stored = work })

        var actionCount = 0
        debouncer.call { actionCount += 1 }
        debouncer.cancel()    // 발화 전 취소(generation += 1).
        stored?()             // 보관된 work 를 뒤늦게 실행 → 토큰 불일치로 내부 action 스킵.

        XCTAssertEqual(actionCount, 0, "cancel 후 발화한 work 는 action 을 실행하지 않는다(MUST-3).")
    }

    func test_cancelPendingWork_clearsModeWithDebouncedGeocodePending() {
        // 콕찍기 디바운스 역지오 예약 상태에서 exitAddPin → cancelPendingWork 가 안전하게 모드를 종료한다.
        let vm = makeViewModel(location: StubLocationService(status: .denied))
        vm.handle(.cameraIdle(centerLat: 37.5, centerLng: 127.0, zoom: 15))
        vm.enterAddPin()
        vm.addPlaceVM?.onMapMoved(center: Coordinate(latitude: 37.6, longitude: 127.1))

        vm.exitAddPin()

        XCTAssertFalse(vm.isAddingPin)
        XCTAssertNil(vm.addPlaceVM, "진행 작업 취소 후 VM 폐기(MUST-3).")
    }

    // MARK: - BR-2: 인라인 모드 중 마커 탭 차단(AC-13)

    func test_markerTapped_whileAddingPin_isBlocked() async {
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()
        vm.enterAddPin()

        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: 100, y: 200)))

        XCTAssertNil(vm.selectedPinId, "인라인 모드 중 마커 탭은 selectedPinId 를 설정하지 않는다(BR-2/AC-13).")
    }

    func test_markerTapped_whenNotAddingPin_selectsPin() async {
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()

        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: 100, y: 200)))

        XCTAssertEqual(vm.selectedPinId, 1, "일반 모드에선 마커 탭이 selectedPinId 를 설정.")
    }

    // MARK: - 헬퍼

    private func makeViewModel(
        pinAPI: PinAPIProtocol? = nil,
        placeAPI: PlaceAPIProtocol? = nil,
        groupAPI: GroupAPIProtocol? = nil,
        location: LocationServiceProtocol? = nil
    ) -> MapViewModel {
        MapViewModel(
            pinAPI: pinAPI ?? StubPinAPI(listResult: .success([])),
            placeAPI: placeAPI ?? StubPlaceAPI(),
            groupAPI: groupAPI ?? StubGroupAPI(group: ActiveGroup(groupId: 1, name: "팀", memberCount: 2)),
            locationService: location ?? StubLocationService(status: .denied)
        )
    }

    private func makePin(
        id: Int,
        tag: PinTag,
        latitude: Double = 37.5,
        longitude: Double = 127.0
    ) -> PinSummary {
        PinSummary(
            id: id,
            groupId: 1,
            createdBy: 1,
            createdByNickname: "tester",
            placeName: "장소\(id)",
            address: nil,
            latitude: latitude,
            longitude: longitude,
            instagramUrl: nil,
            memo: nil,
            memoSource: nil,
            tag: tag,
            createdAt: "2026-01-01T00:00:00Z",
            visitedAt: nil,
            memoUpdatedBy: nil,
            memoUpdatedByNickname: nil,
            photoUrl: nil,
            photoThumbnailUrl: nil
        )
    }
}

// MARK: - In-file 프로토콜 목(MapViewModelTests 패턴)

private final class StubPinAPI: PinAPIProtocol, @unchecked Sendable {
    enum ListOutcome {
        case success([PinSummary])
        case failure(Error)
    }

    private let listResult: ListOutcome

    init(listResult: ListOutcome) {
        self.listResult = listResult
    }

    func list(groupId: Int) async throws -> [PinSummary] {
        switch listResult {
        case .success(let pins): return pins
        case .failure(let error): throw error
        }
    }

    func create(groupId: Int, request: CreatePinRequest) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }

    func update(groupId: Int, pinId: Int, request: UpdatePinRequest) async throws -> UpdatePinResponse {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }

    func delete(groupId: Int, pinId: Int) async throws {}

    func uploadPhoto(groupId: Int, pinId: Int, imageData: Data) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }

    func deletePhoto(groupId: Int, pinId: Int) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }
}

private final class StubPlaceAPI: PlaceAPIProtocol, @unchecked Sendable {
    func search(_ keyword: String) async throws -> [PlaceItem] { [] }
}

private final class StubGroupAPI: GroupAPIProtocol, @unchecked Sendable {
    private let group: ActiveGroup?
    init(group: ActiveGroup?) { self.group = group }
    func myActiveGroup() async throws -> ActiveGroup? { group }
    func previewBySlug(slug: String) async throws -> InvitePreview { InvitePreview(token: "stub", groupName: "stub", inviterNickname: nil, expiresAt: nil) }
    func acceptInvite(token: String) async throws -> InviteAccept { InviteAccept(groupId: 0) }
    func issueInviteLink(groupId: Int) async throws -> InviteLink { InviteLink(token: "stub", slug: nil, shareUrl: nil) }
    func leaveGroup(groupId: Int) async throws {}
}

@MainActor
private final class StubLocationService: LocationServiceProtocol {
    var authorizationStatus: CLAuthorizationStatus
    var onSample: ((LocationSample) -> Void)?
    private let oneShot: LocationSample?
    /// requestWhenInUsePermission 호출 여부(Q7 검증).
    private(set) var didRequestPermission = false

    init(status: CLAuthorizationStatus, oneShot: LocationSample? = nil) {
        self.authorizationStatus = status
        self.oneShot = oneShot
    }

    func requestWhenInUsePermission() { didRequestPermission = true }
    func startUpdating() {}
    func stopUpdating() {}

    func requestOneShot() async -> LocationSample? { oneShot }
}
