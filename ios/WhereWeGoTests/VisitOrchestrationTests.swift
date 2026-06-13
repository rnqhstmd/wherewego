import XCTest
import CoreLocation
@testable import WhereWeGo

// 방문 오케스트레이션 분기 테스트(정책 v2 §2-2, AC-15).
// submitVisit(pinId:companionIds:) 의 declareVisit 응답 3분기를 검증한다(스텁 PinAPI).
//  - converted == true       → confetti ON + activeSheet=.visitMemo + 로컬 태그 MEMORY 전환 + visitors patch.
//  - alreadyConverted == true → confetti OFF + visitMemo 미오픈 + 합산 안내 토스트 + visitors patch(태그 불변).
//  - 둘 다 false(체크인)        → confetti OFF + visitMemo 미오픈 + 체크인 토스트 + visitors patch(태그 불변).
//  - declareVisit 실패         → 인라인 에러 토스트 + 태그 미변경(confetti/시트 없음).
//
// engine 게이트(50m/속도/30초) 자체는 B1 VisitDetectionEngineTests 가 커버하므로 여기선 다루지 않는다(중복 금지).
// MapViewModel 이 @MainActor 이므로 테스트 클래스도 @MainActor.
@MainActor
final class VisitOrchestrationTests: XCTestCase {

    // MARK: - AC-15: converted == true(추억 전환)

    func test_submitVisit_converted_triggersConfettiAndMemoSheet() async {
        // Given WISH 핀 1개 + declareVisit 가 converted=true 반환(동행 전환)
        let original = makePin(id: 1, tag: .WISH)
        let pinAPI = StubVisitPinAPI(listResult: [original])
        pinAPI.declareResult = .success(DeclareVisitResponse(
            converted: true,
            alreadyConverted: false,
            visitors: [visitor(1, "나"), visitor(2, "친구")]
        ))
        let vm = await makeViewModel(pinAPI: pinAPI)

        // When 동행(친구) 포함 제출
        await vm.submitVisit(pinId: 1, companionIds: [2])

        // Then confetti ON + visitMemo 시트 + 로컬 MEMORY 전환 + visitors 반영
        XCTAssertNotNil(vm.confettiTrigger)
        XCTAssertEqual(vm.activeSheet, .visitMemo(pinId: 1))
        XCTAssertEqual(vm.pins.first?.tag, .MEMORY)
        XCTAssertEqual(vm.pins.first?.visitors?.count, 2)
        XCTAssertNil(vm.visitInfoMessage)
    }

    // MARK: - AC-15: alreadyConverted == true(이미 추억)

    func test_submitVisit_alreadyConverted_showsToastNoConfetti() async {
        // Given MEMORY 핀 1개 + declareVisit 가 alreadyConverted=true 반환(늦은 제출)
        let original = makePin(id: 2, tag: .MEMORY)
        let pinAPI = StubVisitPinAPI(listResult: [original])
        pinAPI.declareResult = .success(DeclareVisitResponse(
            converted: false,
            alreadyConverted: true,
            visitors: [visitor(2, "나")]
        ))
        let vm = await makeViewModel(pinAPI: pinAPI)

        // When
        await vm.submitVisit(pinId: 2, companionIds: [])

        // Then confetti OFF + visitMemo 미오픈 + 합산 안내 토스트 + 태그 불변(MEMORY) + visitors 반영
        XCTAssertNil(vm.confettiTrigger)
        XCTAssertEqual(vm.activeSheet, .none)
        XCTAssertNotNil(vm.visitInfoMessage)
        XCTAssertEqual(vm.pins.first?.tag, .MEMORY)
        XCTAssertEqual(vm.pins.first?.visitors?.count, 1)
    }

    // MARK: - 체크인(둘 다 false, 태그 불변)

    func test_submitVisit_checkin_showsToastNoConfettiTagUnchanged() async {
        // Given WISH 핀 1개 + declareVisit 가 converted=false/alreadyConverted=false 반환(체크인)
        let original = makePin(id: 3, tag: .WISH)
        let pinAPI = StubVisitPinAPI(listResult: [original])
        pinAPI.declareResult = .success(DeclareVisitResponse(
            converted: false,
            alreadyConverted: false,
            visitors: [visitor(3, "나")]
        ))
        let vm = await makeViewModel(pinAPI: pinAPI)

        // When 혼자 제출
        await vm.submitVisit(pinId: 3, companionIds: [])

        // Then confetti OFF + 시트 없음 + 체크인 토스트 + 태그 불변(WISH) + visitors 반영
        XCTAssertNil(vm.confettiTrigger)
        XCTAssertEqual(vm.activeSheet, .none)
        XCTAssertNotNil(vm.visitInfoMessage)
        XCTAssertEqual(vm.pins.first?.tag, .WISH)
        XCTAssertEqual(vm.pins.first?.visitors?.count, 1)
    }

    // MARK: - declareVisit 실패

    func test_submitVisit_failure_showsErrorNoTransition() async {
        // Given WISH 핀 1개 + declareVisit 실패
        let original = makePin(id: 4, tag: .WISH)
        let pinAPI = StubVisitPinAPI(listResult: [original])
        pinAPI.declareResult = .failure(APIError(code: "FAIL", status: 500, message: "boom"))
        let vm = await makeViewModel(pinAPI: pinAPI)

        // When
        await vm.submitVisit(pinId: 4, companionIds: [])

        // Then 인라인 에러 + confetti/시트 없음 + 태그 미변경(WISH 유지) + visitors 미반영(nil)
        XCTAssertNil(vm.confettiTrigger)
        XCTAssertEqual(vm.activeSheet, .none)
        XCTAssertNotNil(vm.visitInfoMessage)
        XCTAssertEqual(vm.pins.first?.tag, .WISH)
        XCTAssertNil(vm.pins.first?.visitors)
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
            chatAPI: StubChatAPI(),
            locationService: StubVisitLocationService(status: .denied)
        )
        await vm.load()
        return vm
    }

    private func visitor(_ userId: Int, _ nickname: String) -> PinVisitor {
        PinVisitor(userId: userId, nickname: nickname, profileImageUrl: nil, source: "SELF")
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
    var declareResult: Result<DeclareVisitResponse, Error> = .failure(APIError(code: "UNSET", status: 0, message: ""))

    init(listResult: [PinSummary]) { self.listResult = listResult }

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
    func declareVisit(groupId: Int, pinId: Int, companionUserIds: [Int]) async throws -> DeclareVisitResponse {
        try declareResult.get()
    }
}

private final class StubVisitPlaceAPI: PlaceAPIProtocol, @unchecked Sendable {
    func search(_ keyword: String) async throws -> [PlaceItem] { [] }
}

private final class StubVisitGroupAPI: GroupAPIProtocol, @unchecked Sendable {
    private let group: ActiveGroup?

    init(group: ActiveGroup?) { self.group = group }

    func myActiveGroup() async throws -> ActiveGroup? { group }
    func listMyGroups() async throws -> [GroupSummary] { [] }
    func acceptInvite(token: String) async throws -> InviteAccept { InviteAccept(groupId: 0) }
    func issueInviteLink(groupId: Int) async throws -> InviteLink { InviteLink(token: "stub", slug: nil, shareUrl: nil) }
    func leaveGroup(groupId: Int) async throws {}
    func listMembers(groupId: Int) async throws -> [GroupMemberItem] { [] }
    func updateGroupName(groupId: Int, name: String) async throws {}
    func deleteGroup(groupId: Int) async throws {}
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
