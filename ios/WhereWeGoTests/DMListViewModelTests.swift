import XCTest
@testable import WhereWeGo

// DMListViewModel 단위 테스트(GC-2 FR-GC2-1). 그룹 채팅 목록(GET /chat/groups) 소비.
// - load 성공 → .loaded(rooms) / 그룹0개 → .loaded([])(빈, 에러 아님) / load 실패(목록 없음) → .error.
// - hasUnread 가 rooms.hasUnread 반영(배지 소스).
// - refresh 무음 갱신(hasUnread→false 반영) / refresh 실패 시 기존 목록 유지.
//
// StubChatAPI(BotChatViewModelTests.swift 공유)의 groupRoomsResult 를 주입해 결정성을 확보한다.
// GroupRoomSummary 는 Decodable 만 보유 → JSON 경유 생성(makeRoom 헬퍼). currentUser 는 makeCurrentUser()(id=1).
@MainActor
final class DMListViewModelTests: XCTestCase {

    func test_load_success_setsLoadedRooms() async {
        let chatAPI = StubChatAPI()
        chatAPI.groupRoomsResult = .success([
            makeRoom(groupId: 1, groupName: "팀A", lastPreview: "안녕", hasUnread: false),
            makeRoom(groupId: 2, groupName: "팀B", lastPreview: nil, hasUnread: false)
        ])
        let vm = DMListViewModel(chatAPI: chatAPI, currentUser: makeCurrentUser())

        await vm.load()

        guard case let .loaded(rooms) = vm.loadState else {
            return XCTFail("load 성공 시 .loaded 여야 한다.")
        }
        XCTAssertEqual(rooms.map(\.groupId), [1, 2])
        XCTAssertEqual(rooms.first?.groupName, "팀A")
    }

    func test_load_emptyGroups_setsLoadedEmptyNotError() async {
        let chatAPI = StubChatAPI()
        chatAPI.groupRoomsResult = .success([])
        let vm = DMListViewModel(chatAPI: chatAPI, currentUser: makeCurrentUser())

        await vm.load()

        XCTAssertEqual(vm.loadState, .loaded([]))
    }

    func test_load_failure_whenNoList_setsError() async {
        let chatAPI = StubChatAPI()
        chatAPI.groupRoomsResult = .failure(APIError(code: "SERVER_ERROR", status: 500, message: "boom"))
        let vm = DMListViewModel(chatAPI: chatAPI, currentUser: makeCurrentUser())

        await vm.load()

        guard case .error = vm.loadState else {
            return XCTFail("목록 없이 load 실패 시 .error 여야 한다.")
        }
    }

    func test_hasUnread_reflectsRoomsUnread() async {
        let chatAPI = StubChatAPI()
        chatAPI.groupRoomsResult = .success([
            makeRoom(groupId: 1, groupName: "읽음", lastPreview: "끝", hasUnread: false),
            makeRoom(groupId: 2, groupName: "안읽음", lastPreview: "릴스 링크", hasUnread: true)
        ])
        let vm = DMListViewModel(chatAPI: chatAPI, currentUser: makeCurrentUser())

        XCTAssertFalse(vm.hasUnread)
        await vm.load()
        XCTAssertTrue(vm.hasUnread, "안 읽은 방이 있으면 hasUnread 가 true.")
    }

    func test_hasUnread_falseWhenAllRead() async {
        let chatAPI = StubChatAPI()
        chatAPI.groupRoomsResult = .success([
            makeRoom(groupId: 1, groupName: "읽음", lastPreview: "끝", hasUnread: false)
        ])
        let vm = DMListViewModel(chatAPI: chatAPI, currentUser: makeCurrentUser())

        await vm.load()
        XCTAssertFalse(vm.hasUnread)
    }

    func test_refresh_silentlyUpdatesRoomsAndUnread() async {
        let chatAPI = StubChatAPI()
        chatAPI.groupRoomsResult = .success([
            makeRoom(groupId: 1, groupName: "팀A", lastPreview: "릴스 링크", hasUnread: true)
        ])
        let vm = DMListViewModel(chatAPI: chatAPI, currentUser: makeCurrentUser())
        await vm.load()
        XCTAssertTrue(vm.hasUnread)

        chatAPI.groupRoomsResult = .success([
            makeRoom(groupId: 1, groupName: "팀A", lastPreview: "릴스 링크", hasUnread: false)
        ])
        await vm.refresh()

        guard case let .loaded(rooms) = vm.loadState else {
            return XCTFail("refresh 후 .loaded 여야 한다.")
        }
        XCTAssertEqual(rooms.first?.hasUnread, false)
        XCTAssertFalse(vm.hasUnread, "읽음 갱신 후 배지 해제.")
    }

    func test_refresh_failure_keepsExistingRooms() async {
        let chatAPI = StubChatAPI()
        chatAPI.groupRoomsResult = .success([
            makeRoom(groupId: 1, groupName: "팀A", lastPreview: "안녕", hasUnread: false)
        ])
        let vm = DMListViewModel(chatAPI: chatAPI, currentUser: makeCurrentUser())
        await vm.load()

        chatAPI.groupRoomsResult = .failure(APIError(code: "SERVER_ERROR", status: 500, message: "boom"))
        await vm.refresh()

        guard case let .loaded(rooms) = vm.loadState else {
            return XCTFail("refresh 실패 시 기존 .loaded 목록을 유지해야 한다.")
        }
        XCTAssertEqual(rooms.map(\.groupId), [1])
    }

    func test_refresh_failure_whenNoList_surfacesError() async {
        let chatAPI = StubChatAPI()
        chatAPI.groupRoomsResult = .failure(APIError(code: "SERVER_ERROR", status: 500, message: "boom"))
        let vm = DMListViewModel(chatAPI: chatAPI, currentUser: makeCurrentUser())

        await vm.refresh()

        guard case .error = vm.loadState else {
            return XCTFail("미로드 상태 refresh 실패 시 .error 여야 한다(스피너 고정 방지).")
        }
    }

    // MARK: - 헬퍼

    /// GroupRoomSummary 는 Decodable 만 보유 → JSON 경유 생성. roomId/lastAt 은 nil 기본.
    private func makeRoom(
        groupId: Int,
        groupName: String,
        lastPreview: String?,
        hasUnread: Bool,
        lastSenderUserId: Int? = nil
    ) -> GroupRoomSummary {
        let previewStr = lastPreview.map { "\"\($0)\"" } ?? "null"
        let senderStr = lastSenderUserId.map { "\($0)" } ?? "null"
        let json = """
        {"roomId":null,"groupId":\(groupId),"groupName":"\(groupName)","lastPreview":\(previewStr),"lastSenderUserId":\(senderStr),"hasUnread":\(hasUnread),"lastAt":null}
        """
        return try! JSONDecoder().decode(GroupRoomSummary.self, from: Data(json.utf8))
    }
}
