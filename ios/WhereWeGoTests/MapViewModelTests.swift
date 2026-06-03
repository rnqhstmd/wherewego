import XCTest
import CoreLocation
@testable import WhereWeGo

// MapViewModel 단위 테스트(설계 §3, CONSIDER, DoD-A).
// - AC-4: 태그 필터 → visiblePins/markers 반영.
// - AC-6: applyTagOptimistic 즉시 반영 + 실패 시 복원.
// - AC-7: deletePinOptimistic 즉시 제거 + 실패 시 복원.
// - cameraCommand: flyTo(pinId:) 시 해당 좌표/zoom15 설정.
//
// 의존(PinAPI/PlaceAPI/GroupAPI/LocationService)은 in-file 프로토콜 목으로 주입한다(RouteGuardTests 패턴).
// LocationService 목은 LocationServiceProtocol 이 @MainActor 이므로 @MainActor 로 둔다.
// VM 이 @MainActor 이므로 테스트 클래스도 @MainActor.
@MainActor
final class MapViewModelTests: XCTestCase {

    // MARK: - AC-4: 태그 필터

    func test_visiblePins_filtersByActiveTags() async {
        // Given REEL/WISH/MEMORY 핀 각 1개 로드
        let pinAPI = StubPinAPI(listResult: .success([
            makePin(id: 1, tag: .REEL),
            makePin(id: 2, tag: .WISH),
            makePin(id: 3, tag: .MEMORY)
        ]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()

        // When WISH 제거
        vm.activeFilters.remove(.WISH)

        // Then visiblePins/markers 에서 WISH(id 2) 제외
        XCTAssertEqual(Set(vm.visiblePins.map(\.id)), [1, 3])
        XCTAssertEqual(Set(vm.markers.map(\.id)), [1, 3])
        XCTAssertFalse(vm.markers.contains { $0.tag == .WISH })

        // When WISH 복귀
        vm.activeFilters.insert(.WISH)

        // Then 다시 포함
        XCTAssertEqual(Set(vm.visiblePins.map(\.id)), [1, 2, 3])
        XCTAssertEqual(Set(vm.markers.map(\.id)), [1, 2, 3])
    }

    func test_activeFilters_defaultsToAllOn() {
        let vm = makeViewModel()
        XCTAssertEqual(vm.activeFilters, [.REEL, .WISH, .MEMORY])
    }

    // MARK: - AC-6: applyTagOptimistic

    func test_applyTagOptimistic_success_keepsServerSummary() async throws {
        // Given WISH 핀 1개 로드 + 성공 PATCH 목(서버는 MEMORY 로 전환된 summary 반환)
        let original = makePin(id: 1, tag: .WISH)
        let pinAPI = StubPinAPI(listResult: .success([original]))
        pinAPI.updateResult = .success(
            UpdatePinResponse(summary: makePin(id: 1, tag: .MEMORY), transitionedToMemoryNow: true)
        )
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()

        // When 태그 → MEMORY 낙관 PATCH
        try await vm.applyTagOptimistic(pinId: 1, tag: .MEMORY)

        // Then 서버 summary 반영(MEMORY 유지)
        XCTAssertEqual(vm.pins.first?.tag, .MEMORY)
    }

    func test_applyTagOptimistic_failure_restoresSnapshot() async {
        // Given WISH 핀 1개 + 실패 PATCH 목
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        pinAPI.updateResult = .failure(APIError(code: "FAIL", status: 500, message: "boom"))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()

        // When 태그 → MEMORY 낙관 PATCH(실패)
        do {
            try await vm.applyTagOptimistic(pinId: 1, tag: .MEMORY)
            XCTFail("PATCH 실패 시 throw 해야 함")
        } catch {
            // Then 원래 태그(WISH)로 복원
            XCTAssertEqual(vm.pins.first?.tag, .WISH)
        }
    }

    // MARK: - AC-7: deletePinOptimistic

    func test_deletePinOptimistic_success_removesPin() async throws {
        let pinAPI = StubPinAPI(listResult: .success([
            makePin(id: 1, tag: .WISH),
            makePin(id: 2, tag: .REEL)
        ]))
        pinAPI.deleteResult = .success(())
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()

        try await vm.deletePinOptimistic(pinId: 1)

        XCTAssertEqual(vm.pins.map(\.id), [2])
    }

    func test_deletePinOptimistic_failure_restoresSnapshot() async {
        let pinAPI = StubPinAPI(listResult: .success([
            makePin(id: 1, tag: .WISH),
            makePin(id: 2, tag: .REEL)
        ]))
        pinAPI.deleteResult = .failure(APIError(code: "FAIL", status: 500, message: "boom"))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()

        do {
            try await vm.deletePinOptimistic(pinId: 1)
            XCTFail("DELETE 실패 시 throw 해야 함")
        } catch {
            // 복원: 두 핀 모두 유지
            XCTAssertEqual(Set(vm.pins.map(\.id)), [1, 2])
        }
    }

    // MARK: - cameraCommand: flyTo

    func test_flyToPinId_setsCameraCommandToPinCoordinateZoom15() async {
        let pin = makePin(id: 7, tag: .WISH, latitude: 35.123, longitude: 129.456)
        let pinAPI = StubPinAPI(listResult: .success([pin]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()

        // 로드 시 초기 카메라(서울시청) 설정됨 → 소비 시뮬레이션
        vm.cameraCommand = nil

        vm.flyTo(pinId: 7)

        let command = vm.cameraCommand
        XCTAssertEqual(command?.latitude, 35.123)
        XCTAssertEqual(command?.longitude, 129.456)
        XCTAssertEqual(command?.zoom, MapViewModel.pinFocusZoom)
    }

    func test_flyToLatLng_setsCameraCommand() {
        let vm = makeViewModel()
        vm.flyTo(lat: 37.5, lng: 127.0, zoom: 12)
        XCTAssertEqual(vm.cameraCommand?.latitude, 37.5)
        XCTAssertEqual(vm.cameraCommand?.longitude, 127.0)
        XCTAssertEqual(vm.cameraCommand?.zoom, 12)
    }

    // MARK: - 마커 동기화(MockMapRenderer 활용)

    func test_markers_syncToMockRenderer() async {
        let pinAPI = StubPinAPI(listResult: .success([
            makePin(id: 1, tag: .REEL),
            makePin(id: 2, tag: .WISH)
        ]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()

        // 선언적 바인딩: VM.markers 를 렌더러에 전달했을 때 정확히 동기화되는지 검증.
        let renderer = MockMapRenderer()
        renderer.setMarkers(vm.markers)

        XCTAssertEqual(renderer.setMarkersCalls.count, 1)
        XCTAssertEqual(Set(renderer.setMarkersCalls[0].map(\.id)), [1, 2])
    }

    // MARK: - 초기 카메라(FR-2)

    func test_load_whenLocationDenied_setsSeoulCityHallCamera() async {
        let location = StubLocationService(status: .denied)
        let vm = makeViewModel(location: location)
        await vm.load()

        XCTAssertEqual(vm.cameraCommand?.latitude, MapViewModel.seoulCityHall.latitude)
        XCTAssertEqual(vm.cameraCommand?.longitude, MapViewModel.seoulCityHall.longitude)
        XCTAssertEqual(vm.cameraCommand?.zoom, MapViewModel.seoulCityHall.zoom)
    }

    func test_load_whenLocationGranted_setsCurrentLocationCamera() async {
        let location = StubLocationService(
            status: .authorizedWhenInUse,
            oneShot: LocationSample(latitude: 35.1, longitude: 129.0, accuracyMeters: 10, speedMps: 0)
        )
        let vm = makeViewModel(location: location)
        await vm.load()

        XCTAssertEqual(vm.cameraCommand?.latitude, 35.1)
        XCTAssertEqual(vm.cameraCommand?.longitude, 129.0)
        XCTAssertEqual(vm.cameraCommand?.zoom, MapViewModel.currentLocationZoom)
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

// MARK: - In-file 프로토콜 목(RouteGuardTests 패턴)

private final class StubPinAPI: PinAPIProtocol, @unchecked Sendable {
    enum ListOutcome {
        case success([PinSummary])
        case failure(Error)
    }

    private let listResult: ListOutcome
    var updateResult: Result<UpdatePinResponse, Error> = .failure(APIError(code: "UNSET", status: 0, message: ""))
    var deleteResult: Result<Void, Error> = .success(())

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
        try updateResult.get()
    }

    func delete(groupId: Int, pinId: Int) async throws {
        try deleteResult.get()
    }

    func uploadPhoto(groupId: Int, pinId: Int, imageData: Data) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }

    func deletePhoto(groupId: Int, pinId: Int) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }
}

private final class StubPlaceAPI: PlaceAPIProtocol, @unchecked Sendable {
    func search(_ keyword: String) async throws -> [PlaceItem] {
        []
    }
}

private final class StubGroupAPI: GroupAPIProtocol, @unchecked Sendable {
    private let group: ActiveGroup?

    init(group: ActiveGroup?) {
        self.group = group
    }

    func myActiveGroup() async throws -> ActiveGroup? {
        group
    }

    func acceptInvite(token: String) async throws -> InviteAccept {
        InviteAccept(groupId: 0)
    }

    func issueInviteLink(groupId: Int) async throws -> InviteLink {
        InviteLink(token: "stub", slug: nil, shareUrl: nil)
    }

    func leaveGroup(groupId: Int) async throws {}
}

@MainActor
private final class StubLocationService: LocationServiceProtocol {
    var authorizationStatus: CLAuthorizationStatus
    var onSample: ((LocationSample) -> Void)?
    private let oneShot: LocationSample?

    init(status: CLAuthorizationStatus, oneShot: LocationSample? = nil) {
        self.authorizationStatus = status
        self.oneShot = oneShot
    }

    func requestWhenInUsePermission() {}
    func startUpdating() {}
    func stopUpdating() {}

    func requestOneShot() async -> LocationSample? {
        oneShot
    }
}
