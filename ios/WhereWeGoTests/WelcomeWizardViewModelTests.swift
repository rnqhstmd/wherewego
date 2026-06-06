import XCTest
@testable import WhereWeGo

// WelcomeWizardViewModel 발급/공유 전환 검증(설계 §6, FR-14~18, AC-19~21, BR-5).
//
// FR-14: slug 코드만 공유, link.shareUrl 미사용.
// AC-19: copyCode 가 slug 를 클립보드로 복사(copied=true).
// AC-20/BR-5: shareMessage 에 slug 포함, "http"/"://" 미포함(URL 없음).
// AC-21: issue 결과 slug==nil → errorMessage "초대 링크를 만들지 못했어요".
// M3: expiresLabel KST 포맷(2026-06-05T15:30:00Z → "코드 유효기간: 6월 6일까지").
//
// GroupAPIProtocol mock 의 issueInviteLink 반환값을 제어해 발급 결과를 주입한다(MyInfoViewModelTests 패턴).
@MainActor
final class WelcomeWizardViewModelTests: XCTestCase {

    // MARK: - FR-14: slug 세팅 + shareUrl 미사용

    func test_loadInviteLink_setsSlug_ignoresShareUrl() async {
        // Given issue 가 slug 와 shareUrl 을 모두 반환.
        let link = InviteLink(token: "tok-1", slug: "ABC123", shareUrl: "https://wherewego.app/invite/ABC123", expiresAt: nil)
        let vm = makeViewModel(issueResult: .success(link))

        // When 위저드 진입(그룹 보유 → 스텝2 + 자동 발급).
        await vm.start()

        // Then slug 가 세팅되고 shareUrl(URL)은 어디에도 반영되지 않음(FR-14).
        XCTAssertEqual(vm.slug, "ABC123")
        XCTAssertNil(vm.errorMessage)
        XCTAssertEqual(vm.shareMessage?.contains("ABC123"), true)
        XCTAssertEqual(vm.shareMessage?.contains("wherewego.app"), false)
    }

    // MARK: - AC-20/BR-5: shareMessage 에 slug 포함 + URL 미포함

    func test_shareMessage_containsSlug_withoutUrl() async {
        let link = InviteLink(token: "tok-1", slug: "JOIN42", shareUrl: "https://x.test/i/JOIN42", expiresAt: nil)
        let vm = makeViewModel(issueResult: .success(link))

        await vm.start()

        let message = vm.shareMessage
        XCTAssertNotNil(message)
        XCTAssertEqual(message?.contains("JOIN42"), true)
        XCTAssertEqual(message?.contains("http"), false)
        XCTAssertEqual(message?.contains("://"), false)
    }

    func test_shareMessage_nilWhenSlugMissing() {
        // slug 미세팅(발급 전) → shareMessage nil.
        let vm = makeViewModel(issueResult: .success(InviteLink(token: "t", slug: "S", shareUrl: nil)))
        XCTAssertNil(vm.shareMessage)
    }

    // MARK: - AC-19: copyCode 가 slug 복사

    func test_copyCode_copiesSlug_setsCopied() async {
        let link = InviteLink(token: "tok-1", slug: "CODE99", shareUrl: nil, expiresAt: nil)
        let vm = makeViewModel(issueResult: .success(link))
        await vm.start()

        var copied: String?
        vm.copyCode { copied = $0 }

        XCTAssertEqual(copied, "CODE99")
        XCTAssertTrue(vm.copied)
    }

    func test_copyCode_noopWhenSlugMissing() {
        // slug 부재 → 복사/플래그 변동 없음.
        let vm = makeViewModel(issueResult: .success(InviteLink(token: "t", slug: "S", shareUrl: nil)))

        var copied: String?
        vm.copyCode { copied = $0 }

        XCTAssertNil(copied)
        XCTAssertFalse(vm.copied)
    }

    // MARK: - AC-21: issue slug==nil → errorMessage

    func test_loadInviteLink_slugNil_setsError() async {
        // Given issue 가 slug==nil 로 응답(발급 실패 케이스).
        let link = InviteLink(token: "tok-1", slug: nil, shareUrl: "https://x.test/i/abc", expiresAt: nil)
        let vm = makeViewModel(issueResult: .success(link))

        await vm.start()

        // Then 공유 불가 → 에러 문구, slug 미세팅(FR-17/AC-21).
        XCTAssertNil(vm.slug)
        XCTAssertEqual(vm.errorMessage, "초대 링크를 만들지 못했어요")
    }

    func test_loadInviteLink_issueThrows_setsError() async {
        // 발급 호출 자체 실패 → 동일 에러 문구.
        let vm = makeViewModel(issueResult: .failure(APIError(code: "HTTP_500", status: 500, message: "boom")))

        await vm.start()

        XCTAssertNil(vm.slug)
        XCTAssertEqual(vm.errorMessage, "초대 링크를 만들지 못했어요")
    }

    // MARK: - M3/FR-18: expiresLabel KST 포맷

    func test_expiresLabel_kstFormat() async {
        // 2026-06-05T15:30:00Z(UTC) → KST 2026-06-06 00:30 → "6월 6일까지".
        let link = InviteLink(token: "t", slug: "S", shareUrl: nil, expiresAt: "2026-06-05T15:30:00Z")
        let vm = makeViewModel(issueResult: .success(link))

        await vm.start()

        XCTAssertEqual(vm.expiresLabel, "코드 유효기간: 6월 6일까지")
    }

    func test_expiresLabel_nilWhenExpiresMissing() async {
        let link = InviteLink(token: "t", slug: "S", shareUrl: nil, expiresAt: nil)
        let vm = makeViewModel(issueResult: .success(link))

        await vm.start()

        XCTAssertNil(vm.expiresLabel)
    }

    // MARK: - 헬퍼

    /// issueInviteLink 결과를 주입한 WelcomeWizardViewModel.
    /// initialGroup 을 전달해 start 가 재조회 없이 즉시 스텝2 + 발급으로 진입하도록 한다.
    private func makeViewModel(issueResult: StubGroupAPI.IssueOutcome) -> WelcomeWizardViewModel {
        let group = ActiveGroup(groupId: 7, name: "여행팀", memberCount: 2)
        return WelcomeWizardViewModel(
            groupAPI: StubGroupAPI(issueResult: issueResult),
            initialGroup: group
        )
    }
}

// MARK: - 목

/// issueInviteLink 반환만 제어하는 GroupAPIProtocol mock(발급/공유 검증 전용).
private final class StubGroupAPI: GroupAPIProtocol, @unchecked Sendable {
    enum IssueOutcome {
        case success(InviteLink)
        case failure(Error)
    }
    private let issueResult: IssueOutcome
    init(issueResult: IssueOutcome) { self.issueResult = issueResult }

    func myActiveGroup() async throws -> ActiveGroup? { nil }
    func listMyGroups() async throws -> [GroupSummary] { [] }
    func createGroup(name: String) async throws -> GroupCreated {
        GroupCreated(groupId: 0, name: name)
    }
    func previewBySlug(slug: String) async throws -> InvitePreview {
        InvitePreview(token: "stub", groupName: "stub", inviterNickname: nil, expiresAt: nil)
    }
    func acceptInvite(token: String) async throws -> InviteAccept { InviteAccept(groupId: 0) }
    func issueInviteLink(groupId: Int) async throws -> InviteLink {
        switch issueResult {
        case .success(let link): return link
        case .failure(let error): throw error
        }
    }
    func leaveGroup(groupId: Int) async throws {}
}
