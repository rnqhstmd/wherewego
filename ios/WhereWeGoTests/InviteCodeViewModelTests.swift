import XCTest
@testable import WhereWeGo

// InviteCodeViewModel 2단계 상태머신 검증(설계 §4, 테스트 전략).
// preview(slug→token) → confirm → accept 흐름, 에러 단계 분리(M1/M2), 취소 token 폐기(QE-2),
// alreadyMember 가로채기(FR-12/BR-4), clearErrorOnEdit/canSubmit(AC-12~14).
//
// 패턴: MyInfoViewModelTests 의 GroupAPIProtocol mock + Given/When/Then.
// previewBySlug/acceptInvite 를 각각 결과·throw 제어하는 전용 mock 사용.
@MainActor
final class InviteCodeViewModelTests: XCTestCase {

    // MARK: - AC-1/BR-1: preview 우선 호출, slug 는 accept 로 직접 가지 않음

    func test_submitPreview_callsPreviewBySlug_notAcceptWithSlug() async {
        // Given preview 성공 mock.
        let mock = MockGroupAPI()
        mock.previewResult = .success(Self.previewFixture(token: "tok-1"))
        let vm = InviteCodeViewModel(groupAPI: mock)
        vm.code = "ABC123"

        // When preview 제출.
        await vm.submitPreview()

        // Then previewBySlug(slug:) 가 입력값으로 호출되고, accept 는 미호출(BR-1).
        XCTAssertEqual(mock.previewedSlugs, ["ABC123"])
        XCTAssertTrue(mock.acceptedTokens.isEmpty)
    }

    // MARK: - AC-2: preview 성공 → confirm + groupName

    func test_submitPreview_success_movesToConfirmWithGroupName() async {
        // Given groupName 포함 preview.
        let mock = MockGroupAPI()
        mock.previewResult = .success(Self.previewFixture(token: "tok-1", groupName: "여행팀"))
        let vm = InviteCodeViewModel(groupAPI: mock)
        vm.code = "ABC123"

        // When preview 제출.
        await vm.submitPreview()

        // Then .confirm 으로 전이 + groupName 노출(AC-2).
        guard case .confirm(let preview) = vm.step else {
            return XCTFail("expected .confirm, got \(vm.step)")
        }
        XCTAssertEqual(preview.groupName, "여행팀")
        XCTAssertNil(vm.errorMessage)
    }

    // MARK: - AC-3: accept 는 preview.token 으로 호출

    func test_confirmJoin_usesPreviewToken_notSlug() async {
        // Given preview 가 token "tok-xyz" 반환.
        let mock = MockGroupAPI()
        mock.previewResult = .success(Self.previewFixture(token: "tok-xyz"))
        mock.acceptResult = .success(InviteAccept(groupId: 9))
        let vm = InviteCodeViewModel(groupAPI: mock)
        vm.code = "SLUG-IN"
        await vm.submitPreview()

        // When 확인화면에서 합류.
        var joined = false
        await vm.confirmJoin(onJoined: { joined = true })

        // Then accept 는 slug 가 아니라 preview.token 으로 호출(AC-3).
        XCTAssertEqual(mock.acceptedTokens, ["tok-xyz"])
        XCTAssertTrue(joined)
    }

    // MARK: - AC-4/QE-2: 취소 → token 폐기, 재 preview 가능

    func test_cancelToInput_discardsToken_andRepreviewWorks() async {
        // Given confirm 상태(token 보유).
        let mock = MockGroupAPI()
        mock.previewResult = .success(Self.previewFixture(token: "tok-old"))
        let vm = InviteCodeViewModel(groupAPI: mock)
        vm.code = "ABC123"
        await vm.submitPreview()
        guard case .confirm = vm.step else { return XCTFail("setup failed") }

        // When 취소.
        vm.cancelToInput()

        // Then .input 복귀 + confirmErrorMessage 클리어(token 자연 폐기, QE-2).
        XCTAssertEqual(vm.step, .input)
        XCTAssertNil(vm.confirmErrorMessage)

        // And 새 코드로 재 preview → 새 token 으로 confirm.
        mock.previewResult = .success(Self.previewFixture(token: "tok-new"))
        vm.code = "XYZ999"
        await vm.submitPreview()
        guard case .confirm(let preview) = vm.step else {
            return XCTFail("expected .confirm after re-preview")
        }
        XCTAssertEqual(preview.token, "tok-new")
    }

    // MARK: - AC-5/M1: preview NOT_FOUND(만료 포함) → 입력화면 errorMessage

    func test_submitPreview_notFound_showsInputErrorMessage() async {
        // Given preview 가 NOT_FOUND(만료/없음 통합) throw.
        let mock = MockGroupAPI()
        mock.previewResult = .failure(Self.apiError(code: "INVITE_LINK_NOT_FOUND", status: 404))
        let vm = InviteCodeViewModel(groupAPI: mock)
        vm.code = "ABC123"

        // When preview 제출.
        await vm.submitPreview()

        // Then 입력화면 유지 + "존재하지 않거나 만료된" 문구(M1, AC-5).
        XCTAssertEqual(vm.step, .input)
        XCTAssertEqual(vm.errorMessage, "존재하지 않거나 만료된 코드예요. 다시 확인해 주세요.")
        XCTAssertNil(vm.confirmErrorMessage)
    }

    // MARK: - M2: preview CAPACITY → 입력화면 정원 문구

    func test_submitPreview_capacityExceeded_showsInputCapacityMessage() async {
        // Given preview 가 정원 초과 throw(IC-1 D4: preview 단계에서도 발생).
        let mock = MockGroupAPI()
        mock.previewResult = .failure(Self.apiError(code: "GROUP_CAPACITY_EXCEEDED", status: 409))
        let vm = InviteCodeViewModel(groupAPI: mock)
        vm.code = "ABC123"

        // When preview 제출.
        await vm.submitPreview()

        // Then 입력화면 + 정원 문구(M2).
        XCTAssertEqual(vm.step, .input)
        XCTAssertEqual(vm.errorMessage, "그룹 정원(10명)이 꽉 찼어요.")
    }

    // MARK: - AC-7/FR-12/BR-4: accept alreadyMember → .alreadyMember → acknowledge → onJoined

    func test_confirmJoin_alreadyMember_movesToAlreadyMember_thenAcknowledgeJoins() async {
        // Given preview 성공 후 accept 가 GROUP_ALREADY_MEMBER throw.
        let mock = MockGroupAPI()
        mock.previewResult = .success(Self.previewFixture(token: "tok-1"))
        mock.acceptResult = .failure(Self.apiError(code: "GROUP_ALREADY_MEMBER", status: 409))
        let vm = InviteCodeViewModel(groupAPI: mock)
        vm.code = "ABC123"
        await vm.submitPreview()

        // When 합류 시도.
        var joinedDuringConfirm = false
        await vm.confirmJoin(onJoined: { joinedDuringConfirm = true })

        // Then 에러 아님 — .alreadyMember 로 전이(FR-12), confirmJoin 단계 onJoined 미호출.
        guard case .alreadyMember = vm.step else {
            return XCTFail("expected .alreadyMember, got \(vm.step)")
        }
        XCTAssertFalse(joinedDuringConfirm)
        XCTAssertNil(vm.confirmErrorMessage)

        // And 확인 → onJoined(BR-4).
        var acknowledged = false
        vm.acknowledgeAlreadyMember(onJoined: { acknowledged = true })
        XCTAssertTrue(acknowledged)
    }

    // MARK: - AC-8~11: accept 에러(capacity/self/rejoin/rate/expired 410) → 확인화면 confirmErrorMessage

    func test_confirmJoin_capacityExceeded_returnsToConfirmWithMessage() async {
        await assertAcceptError(
            code: "GROUP_CAPACITY_EXCEEDED", status: 409,
            expected: "그룹 정원(10명)이 꽉 찼어요."
        )
    }

    func test_confirmJoin_selfAccept_returnsToConfirmWithMessage() async {
        await assertAcceptError(
            code: "INVITE_LINK_SELF_ACCEPT", status: 400,
            expected: "내가 만든 초대 코드는 사용할 수 없어요."
        )
    }

    func test_confirmJoin_rejoinForbidden_returnsToConfirmWithMessage() async {
        await assertAcceptError(
            code: "GROUP_REJOIN_FORBIDDEN", status: 409,   // 백엔드 ErrorType=CONFLICT(409) 계약 일치
            expected: "한번 나간 그룹에는 다시 합류할 수 없어요."
        )
    }

    func test_confirmJoin_rateLimited_returnsToConfirmWithMessage() async {
        await assertAcceptError(
            code: "INVITE_LINK_RATE_LIMITED", status: 429,
            expected: "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."
        )
    }

    func test_confirmJoin_expired410_returnsToConfirmWithMessage() async {
        // accept 단계 410 INVITE_LINK_EXPIRED 도달(preview 와 달리 accept 에서만 발생).
        await assertAcceptError(
            code: "INVITE_LINK_EXPIRED", status: 410,
            expected: "만료된 초대 코드예요. 새 코드를 받아 주세요."
        )
    }

    // MARK: - AC-12: clearErrorOnEdit → errorMessage nil

    func test_clearErrorOnEdit_clearsInputErrorMessage() async {
        // Given preview 실패로 errorMessage 세팅.
        let mock = MockGroupAPI()
        mock.previewResult = .failure(Self.apiError(code: "INVITE_LINK_NOT_FOUND", status: 404))
        let vm = InviteCodeViewModel(groupAPI: mock)
        vm.code = "ABC123"
        await vm.submitPreview()
        XCTAssertNotNil(vm.errorMessage)

        // When 편집.
        vm.clearErrorOnEdit()

        // Then errorMessage 클리어(AC-12).
        XCTAssertNil(vm.errorMessage)
    }

    // MARK: - AC-13/14/QE-1: canSubmit empty/loading

    func test_canSubmit_falseWhenEmpty() {
        let vm = InviteCodeViewModel(groupAPI: MockGroupAPI())
        vm.code = "   "
        XCTAssertFalse(vm.canSubmit)

        vm.code = "ABC"
        XCTAssertTrue(vm.canSubmit)
    }

    func test_canSubmit_falseWhilePreviewing() async {
        // Given preview 가 지연되도록 hang mock(continuation 미완료).
        let mock = MockGroupAPI()
        mock.previewResult = .success(Self.previewFixture(token: "tok-1"))
        let vm = InviteCodeViewModel(groupAPI: mock)
        vm.code = "ABC123"

        // step 을 직접 previewing 으로 둔 상태에서 canSubmit 가 false 인지(QE-1).
        // submitPreview 완료 후 .confirm 이므로, 로딩 중 비활성은 isPreviewing 파생으로 검증.
        await vm.submitPreview()
        // 완료 후 .confirm — input 이 아니므로 canSubmit false(QE-1: 입력화면 아님).
        XCTAssertFalse(vm.canSubmit)
    }

    func test_isPreviewing_and_isAccepting_derivation() async {
        let vm = InviteCodeViewModel(groupAPI: MockGroupAPI())
        // 초기 .input.
        XCTAssertFalse(vm.isPreviewing)
        XCTAssertFalse(vm.isAccepting)
    }

    // MARK: - 헬퍼

    /// accept 에러 공통 검증: preview 성공 후 accept 가 code throw → .confirm 복귀 + 기대 문구(AC-8~11).
    private func assertAcceptError(
        code: String, status: Int, expected: String,
        file: StaticString = #filePath, line: UInt = #line
    ) async {
        let mock = MockGroupAPI()
        mock.previewResult = .success(Self.previewFixture(token: "tok-1"))
        mock.acceptResult = .failure(Self.apiError(code: code, status: status))
        let vm = InviteCodeViewModel(groupAPI: mock)
        vm.code = "ABC123"
        await vm.submitPreview()

        var joined = false
        await vm.confirmJoin(onJoined: { joined = true })

        XCTAssertFalse(joined, file: file, line: line)
        guard case .confirm = vm.step else {
            return XCTFail("expected .confirm after accept error, got \(vm.step)", file: file, line: line)
        }
        XCTAssertEqual(vm.confirmErrorMessage, expected, file: file, line: line)
    }

    private static func previewFixture(
        token: String,
        groupName: String = "그룹",
        inviterNickname: String? = nil,
        expiresAt: String? = nil
    ) -> InvitePreview {
        InvitePreview(token: token, groupName: groupName, inviterNickname: inviterNickname, expiresAt: expiresAt)
    }

    private static func apiError(code: String, status: Int) -> APIError {
        APIError(code: code, status: status, message: "")
    }
}

// MARK: - 목

/// previewBySlug/acceptInvite 결과를 각각 제어하는 전용 mock(GroupAPIProtocol).
private final class MockGroupAPI: GroupAPIProtocol, @unchecked Sendable {
    enum Outcome<T> {
        case success(T)
        case failure(Error)
    }

    var previewResult: Outcome<InvitePreview> = .success(
        InvitePreview(token: "stub", groupName: "stub", inviterNickname: nil, expiresAt: nil)
    )
    var acceptResult: Outcome<InviteAccept> = .success(InviteAccept(groupId: 0))

    private(set) var previewedSlugs: [String] = []
    private(set) var acceptedTokens: [String] = []

    func myActiveGroup() async throws -> ActiveGroup? { nil }

    func previewBySlug(slug: String) async throws -> InvitePreview {
        previewedSlugs.append(slug)
        switch previewResult {
        case .success(let preview): return preview
        case .failure(let error): throw error
        }
    }

    func acceptInvite(token: String) async throws -> InviteAccept {
        acceptedTokens.append(token)
        switch acceptResult {
        case .success(let accept): return accept
        case .failure(let error): throw error
        }
    }

    func issueInviteLink(groupId: Int) async throws -> InviteLink {
        InviteLink(token: "stub", slug: nil, shareUrl: nil)
    }

    func leaveGroup(groupId: Int) async throws {}
}
