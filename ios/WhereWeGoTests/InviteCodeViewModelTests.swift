import XCTest
@testable import WhereWeGo

// InviteCodeViewModel 검증(IC-2). slug 2단계 합류(preview→확인→accept) + 에러코드 매핑.
//  - preview: slug → previewBySlug 호출(트림) → pendingGroupName 세팅.
//  - confirmJoin: preview 가 준 token 으로 accept(입력 slug 가 아님) → onJoined(groupId).
//  - 에러: 없음/만료(404/410)·이미멤버(409)·정원초과(409) 별 메시지.
//  - dismissConfirm 후에도 토큰 유지되어 합류 가능(confirmationDialog 자동닫힘 경로).
@MainActor
final class InviteCodeViewModelTests: XCTestCase {

    private func preview(_ name: String = "여행 모임", token: String = "tkn-1") -> InviteLinkPreview {
        InviteLinkPreview(token: token, groupName: name, inviterNickname: nil, expiresAt: nil)
    }

    // MARK: - preview(1단계)

    func test_preview_success_setsPendingGroupName_andTrimsSlug() async {
        let api = StubInviteAPI(preview: preview("여행 모임", token: "tkn-1"))
        let vm = InviteCodeViewModel(groupAPI: api, prefill: "  Abc123Xy  ")

        await vm.preview()

        XCTAssertEqual(vm.pendingGroupName, "여행 모임")
        XCTAssertNil(vm.errorMessage)
        XCTAssertEqual(api.previewedSlug, "Abc123Xy")   // 공백 트림
        XCTAssertFalse(vm.isLoading)
    }

    func test_preview_notFound_showsExpiredMessage_noPending() async {
        let api = StubInviteAPI(previewError: APIError(code: "INVITE_LINK_NOT_FOUND", status: 404, message: "x"))
        let vm = InviteCodeViewModel(groupAPI: api, prefill: "zzz")

        await vm.preview()

        XCTAssertNil(vm.pendingGroupName)
        XCTAssertEqual(vm.errorMessage, "잘못된 코드이거나 만료되었어요")
    }

    func test_preview_expired_showsExpiredMessage() async {
        let api = StubInviteAPI(previewError: APIError(code: "INVITE_LINK_EXPIRED", status: 410, message: "x"))
        let vm = InviteCodeViewModel(groupAPI: api, prefill: "zzz")

        await vm.preview()

        XCTAssertEqual(vm.errorMessage, "잘못된 코드이거나 만료되었어요")
    }

    // MARK: - confirmJoin(2단계)

    func test_confirmJoin_usesPreviewToken_notInputSlug_andReturnsGroupId() async {
        let api = StubInviteAPI(preview: preview(token: "tkn-99"), accept: InviteAccept(groupId: 42))
        let vm = InviteCodeViewModel(groupAPI: api, prefill: "InputSlug")

        await vm.preview()
        var joinedId: Int?
        await vm.confirmJoin(onJoined: { joinedId = $0 })

        XCTAssertEqual(joinedId, 42)
        XCTAssertEqual(api.acceptedToken, "tkn-99")   // 입력 slug 가 아니라 preview 토큰
        XCTAssertNil(vm.pendingGroupName)
        XCTAssertNil(vm.errorMessage)
    }

    func test_confirmJoin_alreadyMember_showsMessage_noJoin() async {
        let api = StubInviteAPI(
            preview: preview(),
            acceptError: APIError(code: "GROUP_ALREADY_MEMBER", status: 409, message: "x")
        )
        let vm = InviteCodeViewModel(groupAPI: api, prefill: "code")

        await vm.preview()
        var joinedId: Int?
        await vm.confirmJoin(onJoined: { joinedId = $0 })

        XCTAssertNil(joinedId)
        XCTAssertEqual(vm.errorMessage, "이미 이 그룹의 멤버예요")
        XCTAssertNil(vm.pendingGroupName)
    }

    func test_confirmJoin_capacityExceeded_showsMessage() async {
        let api = StubInviteAPI(
            preview: preview(),
            acceptError: APIError(code: "GROUP_CAPACITY_EXCEEDED", status: 409, message: "x")
        )
        let vm = InviteCodeViewModel(groupAPI: api, prefill: "code")

        await vm.preview()
        await vm.confirmJoin(onJoined: { _ in })

        XCTAssertEqual(vm.errorMessage, "그룹 정원이 가득 찼어요")
    }

    func test_confirmJoin_unknownError_showsGenericMessage() async {
        let api = StubInviteAPI(
            preview: preview(),
            acceptError: APIError(code: "SERVER", status: 500, message: "boom")
        )
        let vm = InviteCodeViewModel(groupAPI: api, prefill: "code")

        await vm.preview()
        await vm.confirmJoin(onJoined: { _ in })

        XCTAssertEqual(vm.errorMessage, "합류하지 못했어요. 잠시 후 다시 시도해주세요")
    }

    func test_dismissConfirm_keepsToken_confirmJoinStillWorks() async {
        // confirmationDialog 가 "합류하기" 탭 시 자동닫힘(dismissConfirm)을 먼저 호출해도,
        // 토큰이 유지되어 뒤이은 confirmJoin 이 정상 합류해야 한다(토큰 유실 회귀 방지).
        let api = StubInviteAPI(preview: preview(token: "tkn-7"), accept: InviteAccept(groupId: 7))
        let vm = InviteCodeViewModel(groupAPI: api, prefill: "code")

        await vm.preview()
        vm.dismissConfirm()
        var joinedId: Int?
        await vm.confirmJoin(onJoined: { joinedId = $0 })

        XCTAssertEqual(joinedId, 7)
        XCTAssertEqual(api.acceptedToken, "tkn-7")
    }

    // MARK: - canSubmit

    func test_canSubmit_emptyOrWhitespace_false() {
        let vm = InviteCodeViewModel(groupAPI: StubInviteAPI(), prefill: "   ")
        XCTAssertFalse(vm.canSubmit)
    }

    func test_canSubmit_nonEmpty_true() {
        let vm = InviteCodeViewModel(groupAPI: StubInviteAPI(), prefill: "Abc123Xy")
        XCTAssertTrue(vm.canSubmit)
    }
}

// MARK: - 목

/// GroupAPIProtocol stub — previewBySlug/acceptInvite 호출 추적 + 결과/에러 주입. 나머지는 no-op.
/// (다른 11개 스텁은 previewBySlug 를 익스텐션 기본 구현으로 흡수하므로 무수정)
private final class StubInviteAPI: GroupAPIProtocol, @unchecked Sendable {
    private let previewResult: InviteLinkPreview?
    private let previewError: Error?
    private let acceptResult: InviteAccept
    private let acceptError: Error?

    private(set) var previewedSlug: String?
    private(set) var acceptedToken: String?

    init(
        preview: InviteLinkPreview? = nil,
        previewError: Error? = nil,
        accept: InviteAccept = InviteAccept(groupId: 0),
        acceptError: Error? = nil
    ) {
        self.previewResult = preview
        self.previewError = previewError
        self.acceptResult = accept
        self.acceptError = acceptError
    }

    func previewBySlug(slug: String) async throws -> InviteLinkPreview {
        previewedSlug = slug
        if let previewError { throw previewError }
        guard let previewResult else {
            throw APIError(code: "NO_STUB", status: 0, message: "preview 미설정")
        }
        return previewResult
    }

    func acceptInvite(token: String) async throws -> InviteAccept {
        acceptedToken = token
        if let acceptError { throw acceptError }
        return acceptResult
    }

    // 미사용 — 프로토콜 정합 stub.
    func myActiveGroup() async throws -> ActiveGroup? { nil }
    func listMyGroups() async throws -> [GroupSummary] { [] }
    func issueInviteLink(groupId: Int) async throws -> InviteLink { InviteLink(token: "x", slug: nil, shareUrl: nil) }
    func leaveGroup(groupId: Int) async throws {}
    func listMembers(groupId: Int) async throws -> [GroupMemberItem] { [] }
    func updateGroupName(groupId: Int, name: String) async throws {}
    func deleteGroup(groupId: Int) async throws {}
}
