import XCTest
import CoreLocation
@testable import WhereWeGo

// AddPlaceViewModel 검색↔콕찍기 전환 검증(설계 §4, FR-13/FR-14, AC-8).
//
// AC-8: query 가 비어있지 않은 상태에서 onMapMoved(center:) 호출 시
//        query=="" + inputMode==.pinpoint 로 콕찍기 전환.
//
// 디바운스 역지오(reverseGeocode)는 테스트에서 부작용이 없도록 "보관만 하는" 수동 스케줄러를 주입한다
// (Debouncer 가 work 를 큐에 적재만 하고 실행하지 않음 → CLGeocoder 호출 안 됨). onMapMoved 직후의
// 동기 상태(query/inputMode/selectedPlace/results)만 결정적으로 검증한다.
// MapViewModel 의존은 기존 테스트(MapViewModelTests/RouteGuardTests)의 in-file 프로토콜 목 패턴으로 최소 구성.
@MainActor
final class AddPlaceViewModelTests: XCTestCase {

    // MARK: - AC-8: 검색어 입력 상태에서 콕찍기 전환 → 검색어 초기화

    func test_onMapMoved_whenQueryNotEmpty_clearsQueryAndEntersPinpoint() {
        let vm = makeViewModel()

        // Given 검색어 입력 상태(비어있지 않음).
        vm.query = "성수동 카페"
        XCTAssertEqual(vm.inputMode, .search)
        XCTAssertFalse(vm.query.isEmpty)

        // When 지도 드래그(cameraIdle) → 콕찍기 전환.
        vm.onMapMoved(center: Coordinate(latitude: 37.5446, longitude: 127.0557))

        // Then 검색어 초기화(AC-8) + 콕찍기 모드.
        XCTAssertEqual(vm.query, "", "콕찍기 전환 시 검색어를 비워야 한다(AC-8).")
        XCTAssertEqual(vm.inputMode, .pinpoint)
    }

    func test_onMapMoved_resetsSearchSelectionState() {
        let vm = makeViewModel()
        vm.query = "검색어"

        // When 콕찍기 전환.
        vm.onMapMoved(center: Coordinate(latitude: 35.1, longitude: 129.0))

        // Then 검색 관련 상태 초기화(selectedPlace/results/didSearch).
        XCTAssertNil(vm.selectedPlace)
        XCTAssertTrue(vm.results.isEmpty)
        XCTAssertEqual(vm.inputMode, .pinpoint)
        // 콕찍기 중심 좌표 추적(확정 카드 좌표 표시 근거).
        XCTAssertEqual(vm.pinpointCenter, Coordinate(latitude: 35.1, longitude: 129.0))
    }

    func test_onMapMoved_emptyQueryStays_stillEntersPinpoint() {
        // 검색어가 이미 빈 상태에서 드래그해도 콕찍기 모드로 정상 전환.
        let vm = makeViewModel()
        XCTAssertTrue(vm.query.isEmpty)

        vm.onMapMoved(center: Coordinate(latitude: 37.0, longitude: 127.0))

        XCTAssertEqual(vm.query, "")
        XCTAssertEqual(vm.inputMode, .pinpoint)
    }

    func test_onMapMoved_doesNotTriggerGeocodeSynchronously() {
        // 보관만 하는 스케줄러 → 역지오 work 가 즉시 실행되지 않으므로 resolvedAddress 는 동기 시점에 nil.
        // (디바운스 발화는 DoD-B 통합/실디바이스 검증 영역. 본 테스트는 onMapMoved 동기 계약만 본다.)
        let vm = makeViewModel()
        vm.query = "x"

        vm.onMapMoved(center: Coordinate(latitude: 37.0, longitude: 127.0))

        XCTAssertNil(vm.resolvedAddress, "디바운스 미발화 시 동기 시점 resolvedAddress 는 nil.")
    }

    // MARK: - 초기 카메라 seed(＋시트 기본 카메라가 대서양으로 뜨던 결함 수정)

    func test_initialCameraTarget_usesMainMapCenter_whenAvailable() {
        let mapVM = makeMapViewModel()
        // 메인 지도 카메라 멈춤(cameraIdle) → mapCenter 채움.
        mapVM.handle(.cameraIdle(centerLat: 37.5446, centerLng: 127.0557))
        let vm = makeViewModel(mapViewModel: mapVM)

        let target = vm.initialCameraTarget
        XCTAssertEqual(target.latitude, 37.5446, accuracy: 1e-7, "메인 지도 중심을 초기 카메라로 사용해야 한다.")
        XCTAssertEqual(target.longitude, 127.0557, accuracy: 1e-7)
        XCTAssertEqual(target.zoom, MapViewModel.pinFocusZoom, "초기 줌은 콕찍기 시가지 레벨(pinFocusZoom).")
    }

    func test_initialCameraTarget_fallsBackToSeoul_whenMapCenterNil() {
        // 메인 지도 cameraIdle 가 한 번도 없던 상태(mapCenter == nil) → 서울시청 폴백(대서양 기본 카메라 회피).
        let vm = makeViewModel()

        let target = vm.initialCameraTarget
        XCTAssertEqual(target.latitude, 37.5, accuracy: 1e-7, "mapCenter 없으면 서울시청으로 폴백해야 한다.")
        XCTAssertEqual(target.longitude, 127.0, accuracy: 1e-7)
    }

    // MARK: - 헬퍼

    /// 콕찍기 디바운스 work 를 보관만 하는(실행 안 하는) 스케줄러를 주입한 AddPlaceViewModel.
    /// → onMapMoved 의 동기 상태만 부작용 없이 검증 가능(CLGeocoder 비호출).
    /// mapViewModel 미지정 시 기본 stub 으로 구성(초기 카메라 테스트는 mapCenter 조작 위해 직접 주입).
    private func makeViewModel(mapViewModel: MapViewModel? = nil) -> AddPlaceViewModel {
        // scheduler 가 work 를 무시(보관/실행 안 함) → 디바운스 발화 차단.
        let inertDebouncer = Debouncer(interval: 0.3, scheduler: { _, _ in })
        return AddPlaceViewModel(
            mapViewModel: mapViewModel ?? makeMapViewModel(),
            reverseGeocoder: ReverseGeocoder(),
            debouncer: inertDebouncer
        )
    }

    /// 초기 카메라 seed 테스트에서 mapCenter(cameraIdle)를 조작하기 위해 분리한 MapViewModel 팩토리.
    private func makeMapViewModel() -> MapViewModel {
        MapViewModel(
            pinAPI: StubPinAPI(),
            placeAPI: StubPlaceAPI(),
            groupAPI: StubGroupAPI(group: ActiveGroup(groupId: 1, name: "팀", memberCount: 2)),
            locationService: StubLocationService()
        )
    }
}

// MARK: - In-file 프로토콜 목(MapViewModelTests/RouteGuardTests 패턴)

private final class StubPinAPI: PinAPIProtocol, @unchecked Sendable {
    func list(groupId: Int) async throws -> [PinSummary] { [] }
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
    func acceptInvite(token: String) async throws -> InviteAccept { InviteAccept(groupId: 0) }
    func issueInviteLink(groupId: Int) async throws -> InviteLink { InviteLink(token: "stub", slug: nil, shareUrl: nil) }
    func leaveGroup(groupId: Int) async throws {}
}

@MainActor
private final class StubLocationService: LocationServiceProtocol {
    var authorizationStatus: CLAuthorizationStatus = .denied
    var onSample: ((LocationSample) -> Void)?
    func requestWhenInUsePermission() {}
    func startUpdating() {}
    func stopUpdating() {}
    func requestOneShot() async -> LocationSample? { nil }
}
