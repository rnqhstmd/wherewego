import XCTest
@testable import WhereWeGo

// MyInfoViewModel 활성 그룹 섹션 조건부 노출 + 챗봇 연동 부재 검증(설계 §8, FR-25/FR-27, AC-10/AC-11).
//
// AC-10: activeGroup==nil → shouldShowGroupSection==false; ActiveGroup 보유 → true.
// AC-11: VM 에 챗봇 연동 관련 상태/메서드 부재(표면 검증 — 공개 API 가 닉네임·그룹·계정에 한정).
//
// AuthAPI 는 구체 클래스(프로토콜 없음)라 mock 할 수 없으므로 StubURLProtocol 기반 APIClient 로 만든
// 실제 AuthAPI 를 주입하되, me() 응답을 스텁이 제어해 네트워크/Keychain 부작용을 제거한다.
// AC-10 의 그룹 분기는 GroupAPIProtocol mock(myActiveGroup 반환값 제어)으로 검증한다(지시 명시).
@MainActor
final class MyInfoViewModelTests: XCTestCase {

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

    // MARK: - AC-10: 활성 그룹 보유 여부 → 그룹 섹션 노출

    func test_shouldShowGroupSection_falseWhenNoActiveGroup() async {
        // Given groupAPI mock 이 nil(활성 그룹 없음) 반환.
        let vm = makeViewModel(groupResult: .success(nil))

        // When 진입 로드(me + myActiveGroup).
        await vm.load()

        // Then 그룹 섹션 미노출(AC-10).
        XCTAssertNil(vm.activeGroup)
        XCTAssertFalse(vm.shouldShowGroupSection)
    }

    func test_shouldShowGroupSection_trueWhenActiveGroupPresent() async {
        // Given groupAPI mock 이 ActiveGroup 반환.
        let group = ActiveGroup(groupId: 9, name: "여행팀", memberCount: 4)
        let vm = makeViewModel(groupResult: .success(group))

        // When 진입 로드.
        await vm.load()

        // Then 그룹 섹션 노출(AC-10).
        XCTAssertEqual(vm.activeGroup?.groupId, 9)
        XCTAssertEqual(vm.activeGroup?.name, "여행팀")
        XCTAssertTrue(vm.shouldShowGroupSection)
    }

    func test_shouldShowGroupSection_derivesFromActiveGroupDirectly() {
        // shouldShowGroupSection 은 activeGroup != nil 의 순수 파생(load 무관).
        let vm = makeViewModel(groupResult: .success(nil))
        XCTAssertFalse(vm.shouldShowGroupSection)

        vm.activeGroup = ActiveGroup(groupId: 1, name: "팀", memberCount: 2)
        XCTAssertTrue(vm.shouldShowGroupSection)

        vm.activeGroup = nil
        XCTAssertFalse(vm.shouldShowGroupSection)
    }

    func test_leaveGroup_clearsActiveGroup_hidesSection() async {
        // 그룹 탈퇴 성공 → activeGroup=nil → 섹션 미렌더(AC-10 연계, FR-25).
        let group = ActiveGroup(groupId: 9, name: "여행팀", memberCount: 4)
        let vm = makeViewModel(groupResult: .success(group))
        await vm.load()
        XCTAssertTrue(vm.shouldShowGroupSection)

        await vm.leaveGroup()

        XCTAssertNil(vm.activeGroup)
        XCTAssertFalse(vm.shouldShowGroupSection)
    }

    // MARK: - AC-11: 챗봇 연동 상태/메서드 부재(표면 검증)

    func test_noChatbotIntegrationState() {
        // AC-11/FR-27: 웹 SettingsClient "챗봇 연동" 섹션 미이식.
        // VM 공개 표면이 닉네임·그룹·계정에 한정됨을 표면 검증한다.
        // (Mirror 로 저장 프로퍼티 라벨을 훑어 chatbot/connect 류 식별자 부재를 확인 — 회귀 시 실패.)
        let vm = makeViewModel(groupResult: .success(nil))
        let labels = Mirror(reflecting: vm).children.compactMap { $0.label?.lowercased() }
        for label in labels {
            XCTAssertFalse(label.contains("chatbot"), "챗봇 연동 상태가 존재하면 안 된다(AC-11): \(label)")
            XCTAssertFalse(label.contains("integration"), "연동 상태가 존재하면 안 된다(AC-11): \(label)")
        }
        // 공개 게시 상태는 nickname/activeGroup/isBusy/errorMessage 로 한정(챗봇 무관).
        XCTAssertNil(vm.errorMessage)
        XCTAssertFalse(vm.isBusy)
    }

    // MARK: - 헬퍼

    /// GroupAPIProtocol mock 의 myActiveGroup 결과를 주입한 MyInfoViewModel.
    /// authAPI 는 StubURLProtocol 기반 실제 AuthAPI(me() 는 빈 응답 → load 에서 try? 로 흡수되어
    /// nickname=currentUser.nickname 폴백). sessionStore/currentUser 는 부작용 없는 실제 인스턴스.
    private func makeViewModel(groupResult: MockGroupAPI.Outcome) -> MyInfoViewModel {
        // me() 가 호출돼도 그룹 분기에 영향 없도록 항상 실패(404) 응답 → load 의 try? 가 흡수.
        StubURLProtocol.handler = { _ in
            (404, Data("""
            {"meta":{"result":"FAIL","errorCode":"NOT_FOUND","message":"n/a"},"data":null}
            """.utf8))
        }
        let session = StubURLProtocol.makeSession()
        let client = APIClient(baseURL: baseURL, tokens: DummyTokens(), session: session)
        let authAPI = AuthAPI(client: client)
        return MyInfoViewModel(
            authAPI: authAPI,
            groupAPI: MockGroupAPI(result: groupResult),
            sessionStore: SessionStore(tokens: KeychainTokenStore(baseURL: baseURL, session: session)),
            currentUser: CurrentUser(authAPI: authAPI)
        )
    }
}

// MARK: - 목

private actor DummyTokens: TokenStore {
    func accessToken() async -> String? { "access-1" }
    func refresh() async throws {}
}

private final class MockGroupAPI: GroupAPIProtocol, @unchecked Sendable {
    enum Outcome {
        case success(ActiveGroup?)
        case failure(Error)
    }
    private let result: Outcome
    init(result: Outcome) { self.result = result }

    func myActiveGroup() async throws -> ActiveGroup? {
        switch result {
        case .success(let group): return group
        case .failure(let error): throw error
        }
    }
    func createGroup(name: String) async throws -> GroupCreated { GroupCreated(groupId: 0, name: name) }
    func previewBySlug(slug: String) async throws -> InvitePreview { InvitePreview(token: "stub", groupName: "stub", inviterNickname: nil, expiresAt: nil) }
    func acceptInvite(token: String) async throws -> InviteAccept { InviteAccept(groupId: 0) }
    func issueInviteLink(groupId: Int) async throws -> InviteLink { InviteLink(token: "stub", slug: nil, shareUrl: nil) }
    func leaveGroup(groupId: Int) async throws {}
}
