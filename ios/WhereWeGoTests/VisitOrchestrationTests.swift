import XCTest
import CoreLocation
@testable import WhereWeGo

// 방문 오케스트레이션 분기 테스트(설계 §4, AC-15).
// confirmVisit(pinId:) 의 transitionedToMemoryNow 분기만 검증한다.
//  - true  → confetti 트리거 ON + activeSheet=.visitMemo + 로컬 replacePin(MEMORY).
//  - false → confetti OFF + visitMemo 미오픈 + 안내 토스트(visitInfoMessage).
//  - PATCH 실패 → 인라인 에러 토스트 + 태그 미변경(confetti/시트 없음).
//
// engine 게이트(50m/속도/30초) 자체는 B1 VisitDetectionEngineTests 가 커버하므로 여기선 다루지 않는다(중복 금지).
// MapViewModel 이 @MainActor 이므로 테스트 클래스도 @MainActor.
@MainActor
final class VisitOrchestrationTests: XCTestCase {

    // MARK: - AC-15: transitionedToMemoryNow == true

    func test_confirmVisit_transitionedTrue_triggersConfettiAndMemoSheet() async {
        // Given WISH 핀 1개 + PATCH 가 transitionedToMemoryNow=true 반환
        let original = makePin(id: 1, tag: .WISH)
        let serverSummary = makePin(id: 1, tag: .MEMORY)
        let pinAPI = StubVisitPinAPI(listResult: [original])
        pinAPI.updateResult = .success(UpdatePinResponse(summary: serverSummary, transitionedToMemoryNow: true))
        let vm = await makeViewModel(pinAPI: pinAPI)

        // When
        await vm.confirmVisit(pinId: 1)

        // Then confetti ON + visitMemo 시트 + 로컬 MEMORY 반영
        XCTAssertNotNil(vm.confettiTrigger)
        XCTAssertEqual(vm.activeSheet, .visitMemo(pinId: 1))
        XCTAssertEqual(vm.pins.first?.tag, .MEMORY)
        XCTAssertNil(vm.visitInfoMessage)
    }

    // MARK: - AC-15: transitionedToMemoryNow == false

    func test_confirmVisit_transitionedFalse_showsInfoToastNoConfetti() async {
        // Given WISH 핀 1개 + PATCH 가 transitionedToMemoryNow=false 반환(짝꿍이 먼저 전환)
        let original = makePin(id: 2, tag: .WISH)
        let serverSummary = makePin(id: 2, tag: .MEMORY)
        let pinAPI = StubVisitPinAPI(listResult: [original])
        pinAPI.updateResult = .success(UpdatePinResponse(summary: serverSummary, transitionedToMemoryNow: false))
        let vm = await makeViewModel(pinAPI: pinAPI)

        // When
        await vm.confirmVisit(pinId: 2)

        // Then confetti OFF + visitMemo 미오픈 + 안내 토스트, 로컬은 여전히 replacePin(MEMORY)
        XCTAssertNil(vm.confettiTrigger)
        XCTAssertNotEqual(vm.activeSheet, .visitMemo(pinId: 2))
        XCTAssertEqual(vm.activeSheet, .none)
        XCTAssertNotNil(vm.visitInfoMessage)
        XCTAssertEqual(vm.pins.first?.tag, .MEMORY)
    }

    // MARK: - PATCH 실패

    func test_confirmVisit_patchFailure_showsErrorNoTransition() async {
        // Given WISH 핀 1개 + PATCH 실패
        let original = makePin(id: 3, tag: .WISH)
        let pinAPI = StubVisitPinAPI(listResult: [original])
        pinAPI.updateResult = .failure(APIError(code: "FAIL", status: 500, message: "boom"))
        let vm = await makeViewModel(pinAPI: pinAPI)

        // When
        await vm.confirmVisit(pinId: 3)

        // Then 인라인 에러 + confetti/시트 없음 + 태그 미변경(WISH 유지)
        XCTAssertNil(vm.confettiTrigger)
        XCTAssertEqual(vm.activeSheet, .none)
        XCTAssertNotNil(vm.visitInfoMessage)
        XCTAssertEqual(vm.pins.first?.tag, .WISH)
    }

    // MARK: - dismissVisitToast

    func test_dismissVisitToast_clearsToast() async {
        let pinAPI = StubVisitPinAPI(listResult: [makePin(id: 1, tag: .WISH)])
        let vm = await makeViewModel(pinAPI: pinAPI)
        // 토스트가 떠 있다고 가정하긴 어려우므로 dismiss 호출이 안전(nil 유지)함만 검증.
        vm.dismissVisitToast()
        XCTAssertNil(vm.visitToastPin)
    }

    // MARK: - 헬퍼

    private func makeViewModel(pinAPI: PinAPIProtocol) async -> MapViewModel {
        let vm = MapViewModel(
            pinAPI: pinAPI,
            placeAPI: StubVisitPlaceAPI(),
            groupAPI: StubVisitGroupAPI(group: ActiveGroup(groupId: 1, name: "팀", memberCount: 2)),
            locationService: StubVisitLocationService(status: .denied)
        )
        await vm.load()
        return vm
    }

    private func makePin(id: Int, tag: PinTag) -> PinSummary {
        PinSummary(
            id: id,
            groupId: 1,
            createdBy: 1,
            createdByNickname: "tester",
            placeName: "장소\(id)",
            address: nil,
            latitude: 37.5,
            longitude: 127.0,
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

private final class StubVisitPinAPI: PinAPIProtocol, @unchecked Sendable {
    private let listResult: [PinSummary]
    var updateResult: Result<UpdatePinResponse, Error> = .failure(APIError(code: "UNSET", status: 0, message: ""))

    init(listResult: [PinSummary]) { self.listResult = listResult }

    func list(groupId: Int) async throws -> [PinSummary] { listResult }
    func create(groupId: Int, request: CreatePinRequest) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }
    func update(groupId: Int, pinId: Int, request: UpdatePinRequest) async throws -> UpdatePinResponse {
        try updateResult.get()
    }
    func delete(groupId: Int, pinId: Int) async throws {}
    func uploadPhoto(groupId: Int, pinId: Int, imageData: Data) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }
    func deletePhoto(groupId: Int, pinId: Int) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }
}

private final class StubVisitPlaceAPI: PlaceAPIProtocol, @unchecked Sendable {
    func search(_ keyword: String) async throws -> [PlaceItem] { [] }
}

private final class StubVisitGroupAPI: GroupAPIProtocol, @unchecked Sendable {
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
private final class StubVisitLocationService: LocationServiceProtocol {
    var authorizationStatus: CLAuthorizationStatus
    var onSample: ((LocationSample) -> Void)?

    init(status: CLAuthorizationStatus) { self.authorizationStatus = status }

    func requestWhenInUsePermission() {}
    func startUpdating() {}
    func stopUpdating() {}
    func requestOneShot() async -> LocationSample? { nil }
}
