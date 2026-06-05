import XCTest
import CoreLocation
@testable import WhereWeGo

// RouletteViewModel 단위 테스트(설계 §5, AC-10/11).
// - 후보 0개 → exhausted(AC-10).
// - picked 결과 메타(장소명/거리) 반영.
// - "지도에서 보기" → flyTo(pinId) + selectedPinId(AC-11).
// - MEMORY 토글 → tagsAllowed 확장으로 MEMORY 핀 후보 편입.
//
// RNG 는 SeededGenerator 주입으로 결정성 확보. LocationService/PinAPI/PlaceAPI/GroupAPI 는 in-file 목.
// MapViewModel·RouletteViewModel 모두 @MainActor 이므로 테스트 클래스도 @MainActor.
@MainActor
final class RouletteViewModelTests: XCTestCase {

    // MARK: - AC-10: 후보 0개 → exhausted

    func test_spin_noCandidates_returnsExhausted() async {
        // Given REEL/WISH 핀 없음(MEMORY 1개만, 기본 풀 제외)
        let mapVM = await makeMapViewModel(pins: [makePin(id: 1, tag: .MEMORY, lat: 37.5, lng: 127.0)])
        let location = StubLocationService(
            status: .authorizedWhenInUse,
            oneShot: LocationSample(latitude: 37.5, longitude: 127.0, accuracyMeters: 10, speedMps: 0)
        )
        let vm = RouletteViewModel(mapViewModel: mapVM, locationService: location, makeRNG: seeded(1))

        // When
        await vm.spin()

        // Then 기본 풀(REEL/WISH) 후보 0건 → exhausted(AC-10)
        XCTAssertEqual(vm.state, .exhausted)
    }

    func test_spin_emptyPins_returnsExhausted() async {
        let mapVM = await makeMapViewModel(pins: [])
        let location = StubLocationService(
            status: .authorizedWhenInUse,
            oneShot: LocationSample(latitude: 37.5, longitude: 127.0, accuracyMeters: 10, speedMps: 0)
        )
        let vm = RouletteViewModel(mapViewModel: mapVM, locationService: location, makeRNG: seeded(1))

        await vm.spin()

        XCTAssertEqual(vm.state, .exhausted)
    }

    // MARK: - picked 결과

    func test_spin_candidateInRadius_returnsResultWithMeta() async {
        // Given 근처 WISH 핀
        let mapVM = await makeMapViewModel(pins: [
            makePin(id: 7, tag: .WISH, lat: 37.505, lng: 127.005, placeName: "성수 카페")
        ])
        let location = StubLocationService(
            status: .authorizedWhenInUse,
            oneShot: LocationSample(latitude: 37.5, longitude: 127.0, accuracyMeters: 10, speedMps: 0)
        )
        let vm = RouletteViewModel(mapViewModel: mapVM, locationService: location, makeRNG: seeded(1))

        // When
        await vm.spin()

        // Then 결과 핀/장소명 반영, 거리 라벨 산출
        guard case let .result(pin, placeName, _, _, distanceKm, _, _) = vm.state else {
            return XCTFail("expected result, got \(vm.state)")
        }
        XCTAssertEqual(pin.pinId, 7)
        XCTAssertEqual(placeName, "성수 카페")
        XCTAssertGreaterThan(distanceKm, 0)
        XCTAssertNotNil(vm.distanceLabel)
    }

    // MARK: - AC-11: "지도에서 보기" → flyTo + selectedPinId

    func test_showOnMap_setsCameraAndSelectedPin() async {
        let mapVM = await makeMapViewModel(pins: [
            makePin(id: 9, tag: .REEL, lat: 37.505, lng: 127.005)
        ])
        let location = StubLocationService(
            status: .authorizedWhenInUse,
            oneShot: LocationSample(latitude: 37.5, longitude: 127.0, accuracyMeters: 10, speedMps: 0)
        )
        let vm = RouletteViewModel(mapViewModel: mapVM, locationService: location, makeRNG: seeded(1))
        await vm.spin()
        mapVM.cameraCommand = nil // 추첨 후 카메라 명령 소비 시뮬레이션

        // When
        vm.showOnMap()

        // Then 카메라 이동 + 정보창 선택(AC-11)
        XCTAssertEqual(mapVM.selectedPinId, 9)
        XCTAssertEqual(mapVM.cameraCommand?.latitude, 37.505)
        XCTAssertEqual(mapVM.cameraCommand?.longitude, 127.005)
        XCTAssertEqual(mapVM.cameraCommand?.zoom, MapViewModel.pinFocusZoom)
    }

    // MARK: - MEMORY 토글(AC-11)

    func test_spin_memoryToggleOn_includesMemoryPin() async {
        // Given MEMORY 핀만 + 토글 ON
        let mapVM = await makeMapViewModel(pins: [
            makePin(id: 5, tag: .MEMORY, lat: 37.505, lng: 127.005, placeName: "추억 장소")
        ])
        let location = StubLocationService(
            status: .authorizedWhenInUse,
            oneShot: LocationSample(latitude: 37.5, longitude: 127.0, accuracyMeters: 10, speedMps: 0)
        )
        let vm = RouletteViewModel(mapViewModel: mapVM, locationService: location, makeRNG: seeded(1))
        vm.includeMemory = true

        // When
        await vm.spin()

        // Then MEMORY 핀 후보 편입 → picked
        guard case let .result(pin, _, _, _, _, _, _) = vm.state else {
            return XCTFail("expected result, got \(vm.state)")
        }
        XCTAssertEqual(pin.pinId, 5)
        XCTAssertEqual(pin.tag, .MEMORY)
    }

    func test_spin_memoryToggleOff_excludesMemoryPin() async {
        // Given MEMORY 핀만 + 토글 OFF(기본)
        let mapVM = await makeMapViewModel(pins: [
            makePin(id: 5, tag: .MEMORY, lat: 37.505, lng: 127.005)
        ])
        let location = StubLocationService(
            status: .authorizedWhenInUse,
            oneShot: LocationSample(latitude: 37.5, longitude: 127.0, accuracyMeters: 10, speedMps: 0)
        )
        let vm = RouletteViewModel(mapViewModel: mapVM, locationService: location, makeRNG: seeded(1))

        // When
        await vm.spin()

        // Then 기본 풀 제외 → exhausted(AC-10)
        XCTAssertEqual(vm.state, .exhausted)
    }

    // MARK: - 위치 거부

    func test_spin_locationUnavailable_setsLocationError() async {
        let mapVM = await makeMapViewModel(pins: [makePin(id: 1, tag: .WISH, lat: 37.5, lng: 127.0)])
        let location = StubLocationService(status: .denied, oneShot: nil)
        let vm = RouletteViewModel(mapViewModel: mapVM, locationService: location, makeRNG: seeded(1))

        await vm.spin()

        guard case .locationError = vm.state else {
            return XCTFail("expected locationError, got \(vm.state)")
        }
    }

    // MARK: - 헬퍼

    private func seeded(_ seed: UInt64) -> () -> RandomNumberGenerator {
        { SeededGenerator(seed: seed) }
    }

    private func makeMapViewModel(pins: [PinSummary]) async -> MapViewModel {
        let vm = MapViewModel(
            pinAPI: StubPinAPI(listResult: pins),
            placeAPI: StubPlaceAPI(),
            groupAPI: StubGroupAPI(group: ActiveGroup(groupId: 1, name: "팀", memberCount: 2)),
            locationService: StubLocationService(status: .denied)
        )
        await vm.load()
        return vm
    }

    private func makePin(
        id: Int,
        tag: PinTag,
        lat: Double,
        lng: Double,
        placeName: String = "장소"
    ) -> PinSummary {
        PinSummary(
            id: id,
            groupId: 1,
            createdBy: 1,
            createdByNickname: "tester",
            placeName: placeName,
            address: "주소",
            latitude: lat,
            longitude: lng,
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

// MARK: - In-file 목

private final class StubPinAPI: PinAPIProtocol, @unchecked Sendable {
    private let listResult: [PinSummary]

    init(listResult: [PinSummary]) {
        self.listResult = listResult
    }

    func list(groupId: Int) async throws -> [PinSummary] { listResult }
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
    func createGroup(name: String) async throws -> GroupCreated { GroupCreated(groupId: 0, name: name) }
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

    init(status: CLAuthorizationStatus, oneShot: LocationSample? = nil) {
        self.authorizationStatus = status
        self.oneShot = oneShot
    }

    func requestWhenInUsePermission() {}
    func startUpdating() {}
    func stopUpdating() {}
    func requestOneShot() async -> LocationSample? { oneShot }
}
