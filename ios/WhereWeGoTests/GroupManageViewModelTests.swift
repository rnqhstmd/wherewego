import XCTest
@testable import WhereWeGo

// GroupManageViewModel 검증(D단계, IA 재설계 §3.4).
//  - load: 멤버 목록 로드 → loaded 상태 + members 반영.
//  - isOwner: 멤버 목록의 내 userId(isOwner) 로 방장/비방장 판정.
//  - rename: updateGroupName 성공 → onRenamed 콜백 + groupNameDraft 갱신.
//  - delete: deleteGroup 성공 → onExit 콜백 / 실패(403 등) → errorMessage.
//  - leave: leaveGroup 성공 → onExit 콜백.
//
// currentUser.id 는 GET /users/me 응답으로 채운다(StubURLProtocol). me() 응답의 id 를 바꿔 방장/비방장을 검증한다.
@MainActor
final class GroupManageViewModelTests: XCTestCase {

    private let baseURL = URL(string: "http://localhost:8080")!

    override func setUp() {
        super.setUp()
        StubURLProtocol.resetRequestCount()
    }

    override func tearDown() {
        StubURLProtocol.handler = nil
        StubURLProtocol.errorToThrow = nil
        super.tearDown()
    }

    // MARK: - load

    func test_load_populatesMembers_andLoadedState() async {
        let members = [
            GroupMemberItem(userId: 1, nickname: "보승", joinedAt: "2026-01-01T00:00:00Z", isOwner: true, profileImageUrl: nil),
            GroupMemberItem(userId: 2, nickname: "지은", joinedAt: "2026-02-01T00:00:00Z", isOwner: false, profileImageUrl: nil),
        ]
        let vm = await makeViewModel(api: StubGroupManageAPI(members: members), currentUserId: 2)

        await vm.load()

        XCTAssertEqual(vm.loadState, .loaded)
        XCTAssertEqual(vm.members.count, 2)
        XCTAssertEqual(vm.members.first?.nickname, "보승")
    }

    func test_load_failure_setsErrorState() async {
        let api = StubGroupManageAPI(members: [], listError: APIError(code: "SERVER", status: 500, message: "boom"))
        let vm = await makeViewModel(api: api, currentUserId: 1)

        await vm.load()

        if case .error = vm.loadState {} else {
            XCTFail("listMembers 실패 시 loadState 는 .error 여야 한다")
        }
        XCTAssertTrue(vm.members.isEmpty)
    }

    // MARK: - isOwner

    func test_isOwner_trueWhenCurrentUserIsOwner() async {
        let members = [
            GroupMemberItem(userId: 1, nickname: "보승", joinedAt: nil, isOwner: true, profileImageUrl: nil),
            GroupMemberItem(userId: 2, nickname: "지은", joinedAt: nil, isOwner: false, profileImageUrl: nil),
        ]
        // 내 id == 1(방장).
        let vm = await makeViewModel(api: StubGroupManageAPI(members: members), currentUserId: 1)
        await vm.load()

        XCTAssertTrue(vm.isOwner)
    }

    func test_isOwner_falseWhenCurrentUserIsNotOwner() async {
        let members = [
            GroupMemberItem(userId: 1, nickname: "보승", joinedAt: nil, isOwner: true, profileImageUrl: nil),
            GroupMemberItem(userId: 2, nickname: "지은", joinedAt: nil, isOwner: false, profileImageUrl: nil),
        ]
        // 내 id == 2(비방장).
        let vm = await makeViewModel(api: StubGroupManageAPI(members: members), currentUserId: 2)
        await vm.load()

        XCTAssertFalse(vm.isOwner)
    }

    func test_isOwner_falseWhenMembersNotLoaded() async {
        let vm = await makeViewModel(api: StubGroupManageAPI(members: []), currentUserId: 1)
        // load 전 — 멤버 미로드 → 안전 측 false.
        XCTAssertFalse(vm.isOwner)
    }

    // MARK: - rename

    func test_rename_success_callsOnRenamed_andUpdatesDraft() async {
        let api = StubGroupManageAPI(members: [])
        var renamedCalled = false
        let vm = await makeViewModel(
            api: api,
            currentUserId: 1,
            onRenamed: { renamedCalled = true }
        )

        await vm.rename("새 그룹명")

        XCTAssertTrue(renamedCalled)
        XCTAssertEqual(vm.groupNameDraft, "새 그룹명")
        XCTAssertEqual(api.renamedTo, "새 그룹명")
        XCTAssertNil(vm.errorMessage)
    }

    func test_rename_emptyName_isIgnored() async {
        let api = StubGroupManageAPI(members: [])
        var renamedCalled = false
        let vm = await makeViewModel(api: api, currentUserId: 1, onRenamed: { renamedCalled = true })

        await vm.rename("   ")

        XCTAssertFalse(renamedCalled)
        XCTAssertNil(api.renamedTo)
    }

    func test_rename_failure_setsError() async {
        let api = StubGroupManageAPI(members: [], renameError: APIError(code: "GROUP_NAME_INVALID", status: 400, message: "bad"))
        var renamedCalled = false
        let vm = await makeViewModel(api: api, currentUserId: 1, onRenamed: { renamedCalled = true })

        await vm.rename("xyz")

        XCTAssertFalse(renamedCalled)
        XCTAssertNotNil(vm.errorMessage)
    }

    // MARK: - delete

    func test_delete_success_callsOnExit() async {
        let api = StubGroupManageAPI(members: [])
        var exitCalled = false
        let vm = await makeViewModel(api: api, currentUserId: 1, onExit: { exitCalled = true })

        await vm.delete()

        XCTAssertTrue(exitCalled)
        XCTAssertTrue(api.deleteCalled)
        XCTAssertNil(vm.errorMessage)
    }

    func test_delete_notOwner403_setsError_noExit() async {
        let api = StubGroupManageAPI(members: [], deleteError: APIError(code: "GROUP_OWNER_REQUIRED", status: 403, message: "방장만"))
        var exitCalled = false
        let vm = await makeViewModel(api: api, currentUserId: 2, onExit: { exitCalled = true })

        await vm.delete()

        XCTAssertFalse(exitCalled)
        XCTAssertNotNil(vm.errorMessage)
    }

    // MARK: - leave

    func test_leave_success_callsOnExit() async {
        let api = StubGroupManageAPI(members: [])
        var exitCalled = false
        let vm = await makeViewModel(api: api, currentUserId: 2, onExit: { exitCalled = true })

        await vm.leave()

        XCTAssertTrue(exitCalled)
        XCTAssertTrue(api.leaveCalled)
        XCTAssertNil(vm.errorMessage)
    }

    func test_leave_failure_setsError_noExit() async {
        let api = StubGroupManageAPI(members: [], leaveError: APIError(code: "SERVER", status: 500, message: "boom"))
        var exitCalled = false
        let vm = await makeViewModel(api: api, currentUserId: 2, onExit: { exitCalled = true })

        await vm.leave()

        XCTAssertFalse(exitCalled)
        XCTAssertNotNil(vm.errorMessage)
    }

    // MARK: - 초대 코드 발급/복사(IC-2)

    func test_issueInvite_success_setsCodeAndShareUrl() async {
        let api = StubGroupManageAPI(
            members: [],
            issueResult: InviteLink(token: "tkn", slug: "Abc123Xy", shareUrl: "https://wherewego.app/invite/Abc123Xy")
        )
        let vm = await makeViewModel(api: api, currentUserId: 1)

        await vm.issueInvite()

        XCTAssertEqual(vm.inviteCode, "Abc123Xy")
        XCTAssertEqual(vm.inviteShareUrl, "https://wherewego.app/invite/Abc123Xy")
        XCTAssertNil(vm.errorMessage)
    }

    func test_issueInvite_failure_setsError_noCode() async {
        let api = StubGroupManageAPI(members: [], issueError: APIError(code: "SERVER", status: 500, message: "boom"))
        let vm = await makeViewModel(api: api, currentUserId: 1)

        await vm.issueInvite()

        XCTAssertNil(vm.inviteCode)
        XCTAssertNotNil(vm.errorMessage)
    }

    func test_copyInviteCode_copiesSlug_setsCopied() async {
        let api = StubGroupManageAPI(
            members: [],
            issueResult: InviteLink(token: "tkn", slug: "Abc123Xy", shareUrl: nil)
        )
        let vm = await makeViewModel(api: api, currentUserId: 1)
        await vm.issueInvite()

        var copied: String?
        vm.copyInviteCode { copied = $0 }

        XCTAssertEqual(copied, "Abc123Xy")
        XCTAssertTrue(vm.inviteCopied)
    }

    // MARK: - 헬퍼

    /// currentUserId 를 GET /users/me 응답으로 채운 CurrentUser + 주입 stub 으로 VM 생성.
    private func makeViewModel(
        api: StubGroupManageAPI,
        currentUserId: Int,
        groupName: String = "여행팀",
        onRenamed: @escaping () -> Void = {},
        onExit: @escaping () -> Void = {}
    ) async -> GroupManageViewModel {
        StubURLProtocol.handler = { _ in
            (200, Data("""
            {"meta":{"result":"SUCCESS"},"data":{"id":\(currentUserId),"nickname":"u","profileImageUrl":null}}
            """.utf8))
        }
        let session = StubURLProtocol.makeSession()
        let client = APIClient(baseURL: baseURL, tokens: DummyTokens(), session: session)
        let authAPI = AuthAPI(client: client)
        let currentUser = CurrentUser(authAPI: authAPI)
        await currentUser.load()   // id = currentUserId 로 채움(방장 판정 키)
        return GroupManageViewModel(
            groupAPI: api,
            currentUser: currentUser,
            groupId: 9,
            groupName: groupName,
            onRenamed: onRenamed,
            onExit: onExit
        )
    }
}

// MARK: - 목

private actor DummyTokens: TokenStore {
    func accessToken() async -> String? { "access-1" }
    func refresh() async throws {}
}

/// GroupAPIProtocol stub — 그룹관리 3메서드 호출 추적 + 에러 주입. 나머지는 no-op.
private final class StubGroupManageAPI: GroupAPIProtocol, @unchecked Sendable {
    private let members: [GroupMemberItem]
    private let listError: Error?
    private let renameError: Error?
    private let deleteError: Error?
    private let leaveError: Error?
    private let issueResult: InviteLink?
    private let issueError: Error?

    private(set) var renamedTo: String?
    private(set) var deleteCalled = false
    private(set) var leaveCalled = false

    init(
        members: [GroupMemberItem],
        listError: Error? = nil,
        renameError: Error? = nil,
        deleteError: Error? = nil,
        leaveError: Error? = nil,
        issueResult: InviteLink? = nil,
        issueError: Error? = nil
    ) {
        self.members = members
        self.listError = listError
        self.renameError = renameError
        self.deleteError = deleteError
        self.leaveError = leaveError
        self.issueResult = issueResult
        self.issueError = issueError
    }

    func listMembers(groupId: Int) async throws -> [GroupMemberItem] {
        if let listError { throw listError }
        return members
    }

    func updateGroupName(groupId: Int, name: String) async throws {
        if let renameError { throw renameError }
        renamedTo = name
    }

    func deleteGroup(groupId: Int) async throws {
        if let deleteError { throw deleteError }
        deleteCalled = true
    }

    func leaveGroup(groupId: Int) async throws {
        if let leaveError { throw leaveError }
        leaveCalled = true
    }

    // 미사용 — 프로토콜 정합 stub.
    func myActiveGroup() async throws -> ActiveGroup? { nil }
    func listMyGroups() async throws -> [GroupSummary] { [] }
    func acceptInvite(token: String) async throws -> InviteAccept { InviteAccept(groupId: 0) }
    func issueInviteLink(groupId: Int) async throws -> InviteLink {
        if let issueError { throw issueError }
        return issueResult ?? InviteLink(token: "stub", slug: nil, shareUrl: nil)
    }
}
