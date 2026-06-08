import XCTest
import Combine
import CoreGraphics
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

    // MARK: - AC-14: isPointVisible 화면밖 판정 순수함수(경계값)

    func test_isPointVisible_insideBounds_returnsTrue() {
        let size = CGSize(width: 390, height: 844)
        XCTAssertTrue(GeoMath.isPointVisible(ScreenPoint(x: 100, y: 200), in: size))
        XCTAssertTrue(GeoMath.isPointVisible(ScreenPoint(x: 195, y: 422), in: size))
    }

    func test_isPointVisible_onBoundary_returnsTrue() {
        let size = CGSize(width: 390, height: 844)
        // 경계(0/size)는 닫힌 구간 포함.
        XCTAssertTrue(GeoMath.isPointVisible(ScreenPoint(x: 0, y: 0), in: size))
        XCTAssertTrue(GeoMath.isPointVisible(ScreenPoint(x: 390, y: 844), in: size))
        XCTAssertTrue(GeoMath.isPointVisible(ScreenPoint(x: 0, y: 844), in: size))
    }

    func test_isPointVisible_outsideBounds_returnsFalse() {
        let size = CGSize(width: 390, height: 844)
        XCTAssertFalse(GeoMath.isPointVisible(ScreenPoint(x: -1, y: 200), in: size))
        XCTAssertFalse(GeoMath.isPointVisible(ScreenPoint(x: 391, y: 200), in: size))
        XCTAssertFalse(GeoMath.isPointVisible(ScreenPoint(x: 100, y: -1), in: size))
        XCTAssertFalse(GeoMath.isPointVisible(ScreenPoint(x: 100, y: 845), in: size))
    }

    func test_isPointVisible_withMargin_extendsBounds() {
        let size = CGSize(width: 390, height: 844)
        // margin 만큼 영역을 넓혀 경계 밖도 보이게.
        XCTAssertTrue(GeoMath.isPointVisible(ScreenPoint(x: -10, y: 200), in: size, margin: 20))
        XCTAssertFalse(GeoMath.isPointVisible(ScreenPoint(x: -30, y: 200), in: size, margin: 20))
    }

    func test_isPointVisible_zeroSize_returnsFalse() {
        // 투영 기준 미확보(.zero) — 항상 false(화면밖 처리로 숨김).
        XCTAssertFalse(GeoMath.isPointVisible(ScreenPoint(x: 0, y: 0), in: .zero))
    }

    // MARK: - markerTapped: 화면좌표 즉시 세팅(MUST-ADDRESS②)

    func test_markerTapped_setsSelectedPinAndScreenPointImmediately() async {
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()
        vm.updateMapSize(CGSize(width: 390, height: 844))

        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: 100, y: 200)))

        XCTAssertEqual(vm.selectedPinId, 1)
        XCTAssertEqual(vm.selectedPinScreenPoint, ScreenPoint(x: 100, y: 200))
    }

    func test_markerTapped_offScreenPoint_keepsScreenPointNil() async {
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()
        vm.updateMapSize(CGSize(width: 390, height: 844))

        // 화면 밖(x 음수) → screenPoint nil(D-3 clamp 없음, 숨김). 선택 자체는 유지.
        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: -50, y: 200)))

        XCTAssertEqual(vm.selectedPinId, 1)
        XCTAssertNil(vm.selectedPinScreenPoint)
    }

    func test_markerTapped_sameId_doesNotChange_D2() async {
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()
        vm.updateMapSize(CGSize(width: 390, height: 844))

        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: 100, y: 200)))
        // 동일 핀 재탭(다른 좌표) → 재탭 가드(D-2): 좌표를 흔들지 않는다.
        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: 250, y: 400)))

        XCTAssertEqual(vm.selectedPinId, 1)
        XCTAssertEqual(vm.selectedPinScreenPoint, ScreenPoint(x: 100, y: 200))
    }

    func test_markerTapped_sameId_whenScreenPointNil_recoversCoordinate_G2() async {
        // 프로그래밍 선택 등으로 selectedPinId 는 설정됐으나 selectedPinScreenPoint == nil 인 좀비 상태에서,
        // 동일 핀 재탭은 재탭 가드를 우회해 좌표를 복구해야 한다(G2 안전망).
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()
        vm.updateMapSize(CGSize(width: 390, height: 844))

        // 화면밖 좌표로 첫 탭 → 선택은 되지만 screenPoint 는 nil(화면밖 숨김, AC-14).
        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: -50, y: 200)))
        XCTAssertEqual(vm.selectedPinId, 1)
        XCTAssertNil(vm.selectedPinScreenPoint)

        // 동일 핀 재탭(화면 안 좌표) → screenPoint == nil 예외로 가드 우회, 좌표 복구.
        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: 100, y: 200)))
        XCTAssertEqual(vm.selectedPinId, 1)
        XCTAssertEqual(vm.selectedPinScreenPoint, ScreenPoint(x: 100, y: 200))
    }

    // MARK: - cameraMoved: 추적 갱신(distinct + 화면밖, MUST-ADDRESS③④)

    func test_cameraMoved_updatesScreenPointWhenSelected() async {
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()
        vm.updateMapSize(CGSize(width: 390, height: 844))
        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: 100, y: 200)))

        // 카메라 이동으로 선택핀 화면좌표 갱신.
        vm.handle(.cameraMoved(screenPoint: ScreenPoint(x: 150, y: 250)))

        XCTAssertEqual(vm.selectedPinScreenPoint, ScreenPoint(x: 150, y: 250))
    }

    func test_cameraMoved_offScreen_setsScreenPointNil() async {
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()
        vm.updateMapSize(CGSize(width: 390, height: 844))
        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: 100, y: 200)))

        // 추적 중 화면밖으로 나가면 nil(숨김, AC-14).
        vm.handle(.cameraMoved(screenPoint: ScreenPoint(x: 500, y: 200)))

        XCTAssertNil(vm.selectedPinScreenPoint)
    }

    func test_cameraMoved_whenNotSelected_isIgnored() async {
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()
        vm.updateMapSize(CGSize(width: 390, height: 844))

        // 선택 없음 → cameraMoved 무시(게이팅 b 방어).
        vm.handle(.cameraMoved(screenPoint: ScreenPoint(x: 150, y: 250)))

        XCTAssertNil(vm.selectedPinId)
        XCTAssertNil(vm.selectedPinScreenPoint)
    }

    func test_cameraMoved_distinctSamePoint_doesNotRepublish() async {
        // QE-1 (b): 동일(또는 1pt 미만 차이) ScreenPoint 재방출 시 selectedPinScreenPoint 를 불필요하게 재세팅하지 않는다.
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()
        vm.updateMapSize(CGSize(width: 390, height: 844))
        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: 150.2, y: 250.4)))
        XCTAssertEqual(vm.selectedPinScreenPoint, ScreenPoint(x: 150.2, y: 250.4))

        // markerTapped 이후 추가 objectWillChange 발화 횟수만 센다(추적 시점 한정).
        var publishCount = 0
        let cancellable = vm.objectWillChange.sink { _ in publishCount += 1 }
        defer { cancellable.cancel() }

        // 동일 위치 + 1pt 미만 차이(반올림 동일) → distinct 로 set 생략, 발화 없음.
        vm.handle(.cameraMoved(screenPoint: ScreenPoint(x: 150.2, y: 250.4)))
        vm.handle(.cameraMoved(screenPoint: ScreenPoint(x: 150.4, y: 250.1)))

        XCTAssertEqual(publishCount, 0, "distinct(1pt 반올림) 동일 좌표는 재발화하지 않아야 함")
        // 값은 최초 세팅 유지.
        XCTAssertEqual(vm.selectedPinScreenPoint, ScreenPoint(x: 150.2, y: 250.4))

        // 1pt 이상 차이는 정상 갱신(distinct 가 변화는 통과).
        vm.handle(.cameraMoved(screenPoint: ScreenPoint(x: 160, y: 260)))
        XCTAssertGreaterThan(publishCount, 0, "유의미한 좌표 변화는 갱신/발화되어야 함")
        XCTAssertEqual(vm.selectedPinScreenPoint, ScreenPoint(x: 160, y: 260))
    }

    // MARK: - 선택핀 삭제 시 selectedPinId/screenPoint nil(AC-7)

    func test_deleteSelectedPin_clearsSelectionAndScreenPoint() async throws {
        let pinAPI = StubPinAPI(listResult: .success([
            makePin(id: 1, tag: .WISH),
            makePin(id: 2, tag: .REEL)
        ]))
        pinAPI.deleteResult = .success(())
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()
        vm.updateMapSize(CGSize(width: 390, height: 844))
        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: 100, y: 200)))

        try await vm.deletePinOptimistic(pinId: 1)

        XCTAssertNil(vm.selectedPinId)
        XCTAssertNil(vm.selectedPinScreenPoint)
    }

    // MARK: - 표시조건 파생: activeSheet 시 selectedPinId 보존(D-4/AC-9/11)

    func test_selectedPinPreserved_whenActiveSheet() async {
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()
        vm.updateMapSize(CGSize(width: 390, height: 844))
        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: 100, y: 200)))

        // 시트 충돌(예: visitMemo) → 말풍선은 일시 숨김(표시조건은 View 에서 파생) 이지만 선택 상태는 보존.
        // (P8: .addPlace 인라인 전환·룰렛 탭 분리로 ActiveSheet 는 .visitMemo 만 남음 — 충돌 시트로 검증.)
        vm.activeSheet = .visitMemo(pinId: 1)

        // 표시 파생 false(시트 떠 있음) 이지만 selectedPin 은 유지(시트 닫으면 복귀).
        XCTAssertFalse(vm.selectedPin != nil && vm.activeSheet == .none)
        XCTAssertEqual(vm.selectedPinId, 1)
        XCTAssertNotNil(vm.selectedPin)

        // 시트 닫으면 표시 파생 true 로 복귀.
        vm.activeSheet = .none
        XCTAssertTrue(vm.selectedPin != nil && vm.activeSheet == .none)
    }

    // MARK: - clearSelectedPinScreenPoint

    func test_clearSelectedPinScreenPoint_resetsScreenPoint() async {
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()
        vm.updateMapSize(CGSize(width: 390, height: 844))
        vm.handle(.markerTapped(pinId: 1, screenPoint: ScreenPoint(x: 100, y: 200)))

        vm.clearSelectedPinScreenPoint()

        XCTAssertNil(vm.selectedPinScreenPoint)
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

    // MARK: - 그룹 전환 switchTo 줌 연출(C단계 D-3, FR-C3/C5, AC-C4/C5)

    func test_switchTo_swapsToNewGroupPins_andClearsSelection() async {
        // Given 활성 그룹1 핀 로드 + 마커 선택, 그룹2는 다른 핀.
        let pinAPI = StubPinAPI(listResult: .success([]))
        pinAPI.pinsByGroup = [
            1: [makePin(id: 11, tag: .WISH)],
            2: [makePin(id: 21, tag: .REEL), makePin(id: 22, tag: .MEMORY)]
        ]
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()   // StubGroupAPI 기본 활성 그룹 1 → 그룹1 핀
        vm.updateMapSize(CGSize(width: 390, height: 844))
        vm.handle(.markerTapped(pinId: 11, screenPoint: ScreenPoint(x: 100, y: 200)))
        XCTAssertEqual(vm.selectedPinId, 11)

        // When 그룹2로 전환
        await vm.switchTo(groupId: 2)

        // Then 핀 원자 교체 + 구 그룹 선택 해제 + 최종 .loaded(전면 .loading 거치지 않음).
        XCTAssertEqual(Set(vm.pins.map(\.id)), [21, 22])
        XCTAssertNil(vm.selectedPinId)
        XCTAssertEqual(vm.loadState, .loaded)
    }

    func test_switchTo_sameGroup_isNoOp() async {
        // Given 활성 그룹1 로드(groupId=1 확정).
        let pinAPI = StubPinAPI(listResult: .success([makePin(id: 1, tag: .WISH)]))
        let vm = makeViewModel(pinAPI: pinAPI)
        await vm.load()
        let callsAfterLoad = pinAPI.listCallCount

        // When 같은 그룹(1)으로 전환 시도 → 가드로 no-op
        await vm.switchTo(groupId: 1)

        // Then 재조회 없음(불필요 네트워크 방지)
        XCTAssertEqual(pinAPI.listCallCount, callsAfterLoad)
    }

    func test_switchTo_whenLocationDeniedWithPins_usesFitBounds() async {
        // Given 위치 거부 + 그룹2에 핀 존재.
        let pinAPI = StubPinAPI(listResult: .success([]))
        pinAPI.pinsByGroup = [2: [makePin(id: 21, tag: .REEL)]]
        let vm = makeViewModel(pinAPI: pinAPI, location: StubLocationService(status: .denied))

        // When 그룹2로 전환(groupId 초기 nil → 가드 통과)
        await vm.switchTo(groupId: 2)

        // Then 권한 거부 시 새 그룹 핀 bounds 로 줌인(FR-C5)
        XCTAssertEqual(vm.fitBoundsCommand?.map(\.id), [21])
    }

    func test_switchTo_whenLocationDeniedNoPins_fallsBackToSeoul() async {
        // Given 위치 거부 + 그룹2 핀 없음.
        let pinAPI = StubPinAPI(listResult: .success([]))
        let vm = makeViewModel(pinAPI: pinAPI, location: StubLocationService(status: .denied))

        // When 전환
        await vm.switchTo(groupId: 2)

        // Then 핀 없으면 기존 폴백(서울시청) 카메라, fitBounds 미설정
        XCTAssertEqual(vm.cameraCommand?.latitude, MapViewModel.seoulCityHall.latitude)
        XCTAssertEqual(vm.cameraCommand?.zoom, MapViewModel.seoulCityHall.zoom)
        XCTAssertNil(vm.fitBoundsCommand)
    }

    func test_switchTo_fetchFailure_setsErrorAndClearsPins() async {
        // Given list 실패 목.
        let pinAPI = StubPinAPI(listResult: .failure(APIError(code: "FAIL", status: 500, message: "boom")))
        let vm = makeViewModel(pinAPI: pinAPI)

        // When 전환(groupId 초기 nil → 가드 통과)
        await vm.switchTo(groupId: 2)

        // Then 에러 상태 + 핀 비움
        if case .error = vm.loadState {} else { XCTFail("fetch 실패 시 loadState 는 .error 여야 함") }
        XCTAssertTrue(vm.pins.isEmpty)
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
    /// groupId 별 핀 목록(switchTo 전환 테스트용). 키가 있으면 listResult 보다 우선 반환한다.
    var pinsByGroup: [Int: [PinSummary]] = [:]
    /// list 호출 횟수(switchTo 동일 그룹 no-op 검증용).
    private(set) var listCallCount = 0
    /// 마지막으로 list 요청된 groupId.
    private(set) var lastListedGroupId: Int?

    init(listResult: ListOutcome) {
        self.listResult = listResult
    }

    func list(groupId: Int) async throws -> [PinSummary] {
        listCallCount += 1
        lastListedGroupId = groupId
        if let pins = pinsByGroup[groupId] {
            return pins
        }
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

    func listMyGroups() async throws -> [GroupSummary] { [] }

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
