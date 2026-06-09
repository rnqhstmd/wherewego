import XCTest
// ShareExtension/Logic 은 project.yml 에서 WhereWeGoTests 소스로도 포함된다(dual-membership) →
// ShareViewModel 등을 별도 import 없이 직접 사용한다.

// ShareViewModel 단위 테스트(설계 §4·§5, FR7). 선택 검증·다중 전송 호출 수·부분 실패·로그인 필요.
@MainActor
final class ShareViewModelTests: XCTestCase {

    private func groups(_ pairs: [(Int, String)]) -> [ShareGroup] {
        pairs.map { ShareGroup(groupId: $0.0, groupName: $0.1) }
    }

    func test_load_populatesGroups() async {
        let g = groups([(1, "여행"), (2, "맛집")])
        let vm = ShareViewModel(api: StubShareAPI(rooms: g), sharedURL: "https://x")
        await vm.load()
        XCTAssertEqual(vm.state, .loaded(g))
    }

    func test_load_empty() async {
        let vm = ShareViewModel(api: StubShareAPI(rooms: []), sharedURL: "https://x")
        await vm.load()
        XCTAssertEqual(vm.state, .empty)
    }

    func test_load_unauthorized_loginRequired() async {
        let api = StubShareAPI(roomsError: ShareAPIError(code: "NO_TOKEN", status: 401, message: "x"))
        let vm = ShareViewModel(api: api, sharedURL: "https://x")
        await vm.load()
        XCTAssertEqual(vm.state, .loginRequired)
    }

    func test_load_serverError_errorState() async {
        let api = StubShareAPI(roomsError: ShareAPIError(code: "SERVER", status: 500, message: "boom"))
        let vm = ShareViewModel(api: api, sharedURL: "https://x")
        await vm.load()
        if case .error = vm.state {} else { XCTFail("서버 에러는 .error 여야 한다") }
    }

    func test_canSend_requiresSelection() async {
        let vm = ShareViewModel(api: StubShareAPI(rooms: groups([(1, "여행")])), sharedURL: "https://x")
        await vm.load()
        XCTAssertFalse(vm.canSend)        // 기본 빈 선택(D2)
        vm.toggle(1)
        XCTAssertTrue(vm.canSend)
        vm.toggle(1)
        XCTAssertFalse(vm.canSend)        // 토글 해제
    }

    func test_send_callsEachSelectedGroup() async {
        let api = StubShareAPI(rooms: groups([(1, "여행"), (2, "맛집"), (3, "가족")]))
        let vm = ShareViewModel(api: api, sharedURL: "https://reel")
        await vm.load()
        vm.toggle(1); vm.toggle(3)
        await vm.send()

        XCTAssertEqual(Set(api.sentGroupIds), [1, 3])          // 선택한 그룹에만 전송(순서 무관)
        XCTAssertEqual(api.sentTexts.count, 2)
        XCTAssertTrue(api.sentTexts.allSatisfy { $0 == "https://reel" })
        XCTAssertEqual(vm.state, .result(success: 2, failed: []))
    }

    func test_send_partialFailure_reportsFailedGroupName() async {
        let api = StubShareAPI(rooms: groups([(1, "여행"), (2, "맛집")]), failGroupIds: [2])
        let vm = ShareViewModel(api: api, sharedURL: "https://x")
        await vm.load()
        vm.toggle(1); vm.toggle(2)
        await vm.send()
        XCTAssertEqual(vm.state, .result(success: 1, failed: ["맛집"]))
    }
}

private final class StubShareAPI: ShareAPIClientProtocol, @unchecked Sendable {
    private let rooms: [ShareGroup]
    private let roomsError: Error?
    private let failGroupIds: Set<Int>
    private let lock = NSLock()
    private var _sentGroupIds: [Int] = []
    private var _sentTexts: [String] = []

    var sentGroupIds: [Int] { lock.withLock { _sentGroupIds } }
    var sentTexts: [String] { lock.withLock { _sentTexts } }

    init(rooms: [ShareGroup] = [], roomsError: Error? = nil, failGroupIds: Set<Int> = []) {
        self.rooms = rooms
        self.roomsError = roomsError
        self.failGroupIds = failGroupIds
    }

    func botRooms() async throws -> [ShareGroup] {
        if let roomsError { throw roomsError }
        return rooms
    }

    func sendBotMessage(groupId: Int, text: String) async throws {
        // Swift 6: async 컨텍스트에서 lock()/unlock() 직접 호출 불가 → 스코프 락(withLock) 사용.
        lock.withLock {
            _sentGroupIds.append(groupId)
            _sentTexts.append(text)
        }
        if failGroupIds.contains(groupId) {
            throw ShareAPIError(code: "FAIL", status: 500, message: "x")
        }
    }
}
