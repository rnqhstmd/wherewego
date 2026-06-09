import XCTest
@testable import WhereWeGo

// MyInfoViewModel 회귀 검증(설계 §8 / IA 재설계 D단계 내정보 축소, FR-23~27, AC-11).
//
// IA 재설계: 그룹(활성그룹·탈퇴)은 지도 탭 ⋯ 그룹관리(GroupManageView)로 이전됐다.
//  → MyInfoViewModel 에서 activeGroup·shouldShowGroupSection·leaveGroup·groupAPI 의존이 제거됨.
//  본 테스트는 (1) groupAPI 없이 생성·load 가 정상 동작하고 (2) 그룹/챗봇 상태가 표면에서 사라졌는지를 검증한다.
//
// AuthAPI 는 구체 클래스(프로토콜 없음)라 mock 할 수 없으므로 StubURLProtocol 기반 APIClient 로 만든
// 실제 AuthAPI 를 주입하되, me() 응답을 스텁이 제어해 네트워크/Keychain 부작용을 제거한다.
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

    // MARK: - load: 닉네임만 로드(그룹 조회 제거 회귀)

    func test_load_setsNicknameFromMe() async {
        // Given me() 가 닉네임을 반환.
        StubURLProtocol.handler = { _ in
            (200, Data("""
            {"meta":{"result":"SUCCESS"},"data":{"id":7,"nickname":"보승","profileImageUrl":null}}
            """.utf8))
        }
        let vm = makeViewModel()

        // When 진입 로드(me 만 — 그룹 조회 없음).
        await vm.load()

        // Then 닉네임 반영, 에러 없음(그룹 조회 부작용 부재).
        XCTAssertEqual(vm.nickname, "보승")
        XCTAssertNil(vm.errorMessage)
        XCTAssertFalse(vm.isBusy)
    }

    func test_load_meFailure_keepsNoError() async {
        // me() 실패(404) → try? 로 흡수, 닉네임 폴백. 그룹 조회 제거로 에러 메시지가 생기지 않아야 한다.
        StubURLProtocol.handler = { _ in
            (404, Data("""
            {"meta":{"result":"FAIL","errorCode":"NOT_FOUND","message":"n/a"},"data":null}
            """.utf8))
        }
        let vm = makeViewModel()

        await vm.load()

        // 그룹 조회가 제거됐으므로 어떤 경로에서도 errorMessage 가 세팅되지 않는다(회귀 핵심).
        XCTAssertNil(vm.errorMessage)
        XCTAssertFalse(vm.isBusy)
    }

    // MARK: - 그룹/챗봇 상태 부재(표면 검증 — 회귀 시 실패)

    func test_noGroupOrChatbotState() {
        // IA 재설계 D단계: 그룹(activeGroup·shouldShowGroupSection·leaveGroup)·챗봇 연동 상태가 표면에서 제거됨.
        // Mirror 로 저장 프로퍼티 라벨을 훑어 group/chatbot/integration 류 식별자 부재를 확인한다.
        let vm = makeViewModel()
        let labels = Mirror(reflecting: vm).children.compactMap { $0.label?.lowercased() }
        for label in labels {
            XCTAssertFalse(label.contains("group"), "그룹 상태가 존재하면 안 된다(D단계 축소): \(label)")
            XCTAssertFalse(label.contains("activegroup"), "활성 그룹 상태가 존재하면 안 된다(D단계 축소): \(label)")
            XCTAssertFalse(label.contains("chatbot"), "챗봇 연동 상태가 존재하면 안 된다(AC-11): \(label)")
            XCTAssertFalse(label.contains("integration"), "연동 상태가 존재하면 안 된다(AC-11): \(label)")
        }
        // 공개 게시 상태는 nickname/isBusy/errorMessage 로 한정(그룹·챗봇 무관).
        XCTAssertNil(vm.errorMessage)
        XCTAssertFalse(vm.isBusy)
    }

    // MARK: - 헬퍼

    /// groupAPI 의존이 제거된 MyInfoViewModel(D단계). authAPI 는 StubURLProtocol 기반 실제 AuthAPI.
    /// sessionStore/currentUser 는 부작용 없는 실제 인스턴스.
    private func makeViewModel() -> MyInfoViewModel {
        let session = StubURLProtocol.makeSession()
        let client = APIClient(baseURL: baseURL, tokens: DummyTokens(), session: session)
        let authAPI = AuthAPI(client: client)
        return MyInfoViewModel(
            authAPI: authAPI,
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
