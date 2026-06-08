import XCTest
@testable import WhereWeGo

// DMListViewModel 단위 테스트(설계 §4·§10, FR-1/6/7/10, AC-1/4/6/9).
// - load 성공 → .loaded(rooms) / 그룹0개 → .loaded([])(빈, 에러 아님) / load 실패(목록 없음) → .error.
// - hasUnread 가 rooms.unread 반영(배지 소스, FR-10/AC-9).
// - refresh 무음 갱신(unread→false 반영, FR-6/AC-4) / refresh 실패 시 기존 목록 유지.
//
// StubChatAPI(BotChatViewModelTests.swift 공유 정의)의 roomsResult 를 주입해 결정성을 확보한다.
// BotRoomSummary 는 Decodable 만 보유(메모리 init 없음) → JSON 경유 생성(makeRoom 헬퍼).
@MainActor
final class DMListViewModelTests: XCTestCase {

    // MARK: - ①: load 성공 → .loaded(rooms)

    func test_load_success_setsLoadedRooms() async {
        let chatAPI = StubChatAPI()
        chatAPI.roomsResult = .success([
            makeRoom(groupId: 1, groupName: "팀A", lastPreview: "안녕", unread: false),
            makeRoom(groupId: 2, groupName: "팀B", lastPreview: nil, unread: false)
        ])
        let vm = DMListViewModel(chatAPI: chatAPI)

        await vm.load()

        guard case let .loaded(rooms) = vm.loadState else {
            return XCTFail("load 성공 시 .loaded 여야 한다.")
        }
        XCTAssertEqual(rooms.map(\.groupId), [1, 2])
        XCTAssertEqual(rooms.first?.groupName, "팀A")
    }

    // MARK: - ②: 그룹 0개 → .loaded([])(빈, 에러 아님)

    func test_load_emptyGroups_setsLoadedEmptyNotError() async {
        let chatAPI = StubChatAPI()
        chatAPI.roomsResult = .success([])
        let vm = DMListViewModel(chatAPI: chatAPI)

        await vm.load()

        // 그룹 0개는 빈 목록(View 빈 상태 분기) — 에러가 아니다(AC-1/AC-6).
        XCTAssertEqual(vm.loadState, .loaded([]))
    }

    // MARK: - ③: load 실패(목록 없음) → .error

    func test_load_failure_whenNoList_setsError() async {
        let chatAPI = StubChatAPI()
        chatAPI.roomsResult = .failure(APIError(code: "SERVER_ERROR", status: 500, message: "boom"))
        let vm = DMListViewModel(chatAPI: chatAPI)

        await vm.load()

        guard case .error = vm.loadState else {
            return XCTFail("목록 없이 load 실패 시 .error 여야 한다(AC-6).")
        }
    }

    // MARK: - ④: hasUnread 가 rooms.unread 반영(FR-10/AC-9)

    func test_hasUnread_reflectsRoomsUnread() async {
        let chatAPI = StubChatAPI()
        // unread 방 1개 이상 → hasUnread true.
        chatAPI.roomsResult = .success([
            makeRoom(groupId: 1, groupName: "읽음", lastPreview: "끝", unread: false),
            makeRoom(groupId: 2, groupName: "안읽음", lastPreview: "봇 결과", unread: true)
        ])
        let vm = DMListViewModel(chatAPI: chatAPI)

        // 로드 전(.idle)에는 배지 없음.
        XCTAssertFalse(vm.hasUnread)

        await vm.load()
        XCTAssertTrue(vm.hasUnread, "안 읽은 방이 있으면 hasUnread 가 true(FR-10/AC-9).")
    }

    func test_hasUnread_falseWhenAllRead() async {
        let chatAPI = StubChatAPI()
        chatAPI.roomsResult = .success([
            makeRoom(groupId: 1, groupName: "읽음", lastPreview: "끝", unread: false)
        ])
        let vm = DMListViewModel(chatAPI: chatAPI)

        await vm.load()
        XCTAssertFalse(vm.hasUnread, "모두 읽었으면 hasUnread 가 false(AC-9).")
    }

    // MARK: - ⑤: refresh 무음 갱신(unread→false 반영, FR-6/AC-4)

    func test_refresh_silentlyUpdatesRoomsAndUnread() async {
        let chatAPI = StubChatAPI()
        // 최초 로드: 안 읽은 방.
        chatAPI.roomsResult = .success([
            makeRoom(groupId: 1, groupName: "팀A", lastPreview: "봇 결과", unread: true)
        ])
        let vm = DMListViewModel(chatAPI: chatAPI)
        await vm.load()
        XCTAssertTrue(vm.hasUnread)

        // 방을 보고 나오면(백엔드 읽음처리) → refresh 가 읽음 상태로 갱신(AC-4).
        chatAPI.roomsResult = .success([
            makeRoom(groupId: 1, groupName: "팀A", lastPreview: "봇 결과", unread: false)
        ])
        await vm.refresh()

        guard case let .loaded(rooms) = vm.loadState else {
            return XCTFail("refresh 후 .loaded 여야 한다.")
        }
        XCTAssertEqual(rooms.first?.unread, false)
        XCTAssertFalse(vm.hasUnread, "읽음 갱신 후 배지 해제(FR-6/AC-4/AC-9).")
    }

    // MARK: - ⑥: refresh 실패 시 기존 목록 유지

    func test_refresh_failure_keepsExistingRooms() async {
        let chatAPI = StubChatAPI()
        chatAPI.roomsResult = .success([
            makeRoom(groupId: 1, groupName: "팀A", lastPreview: "안녕", unread: false)
        ])
        let vm = DMListViewModel(chatAPI: chatAPI)
        await vm.load()

        // 포그라운드 refresh 가 실패해도 기존 목록을 .error 로 덮지 않는다(무음 갱신).
        chatAPI.roomsResult = .failure(APIError(code: "SERVER_ERROR", status: 500, message: "boom"))
        await vm.refresh()

        guard case let .loaded(rooms) = vm.loadState else {
            return XCTFail("refresh 실패 시 기존 .loaded 목록을 유지해야 한다.")
        }
        XCTAssertEqual(rooms.map(\.groupId), [1])
    }

    // MARK: - ⑦: 미로드 상태(.idle) refresh 실패 → .error surface(스피너 고정 방지)

    func test_refresh_failure_whenNoList_surfacesError() async {
        let chatAPI = StubChatAPI()
        chatAPI.roomsResult = .failure(APIError(code: "SERVER_ERROR", status: 500, message: "boom"))
        let vm = DMListViewModel(chatAPI: chatAPI)

        // 무음 갱신이라도 기존 목록이 없으면(.idle) 실패를 .error 로 노출 — .idle 스피너 무한 고정 방지.
        await vm.refresh()

        guard case .error = vm.loadState else {
            return XCTFail("미로드 상태 refresh 실패 시 .error 여야 한다(스피너 고정 방지).")
        }
    }

    // MARK: - 헬퍼

    /// BotRoomSummary 는 Decodable 만 보유(메모리 init 없음) → JSON 경유 생성.
    /// lastSenderType/lastAt 은 nil(미지정) 기본 — 목록 상태/배지 검증에 불필요한 필드는 생략한다.
    private func makeRoom(
        groupId: Int,
        groupName: String,
        lastPreview: String?,
        unread: Bool
    ) -> BotRoomSummary {
        // String.init 오버로드가 보간 안에서 ambiguous → 보간 클로저로 명시(Swift 6, makePlaceCard 선례).
        let previewStr = lastPreview.map { "\"\($0)\"" } ?? "null"
        let json = """
        {"roomId":null,"groupId":\(groupId),"groupName":"\(groupName)","lastPreview":\(previewStr),"lastSenderType":null,"unread":\(unread),"lastAt":null}
        """
        return try! JSONDecoder().decode(BotRoomSummary.self, from: Data(json.utf8))
    }
}
