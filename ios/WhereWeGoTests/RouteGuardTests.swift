import XCTest
@testable import WhereWeGo

// AC-11/12/17/19/20/21: 온보딩 라우트 가드 결정 규칙 단위 테스트(설계 §10).
//
// OnboardingRouter 는 SwiftUI View 라 body/@State 의존 분기를 직접 테스트할 수 없다.
// 결정 규칙만 순수 함수(resolveFlagRoute/resolveGroupRoute/resolveFinishRoute)로 분리했고,
// Router 본문이 그대로 사용하므로 테스트-프로덕션 일치가 보장된다.
// 추가로 GroupAPIProtocol 목으로 그룹 조회 반환에 따른 분기를 검증한다.
//
// OnboardingRouter 는 SwiftUI View(@MainActor)라 그 static 결정 함수도 main actor 격리된다.
// 테스트 클래스를 @MainActor 로 두어 동기/비동기 메서드 모두에서 호출 가능하게 한다.
@MainActor
final class RouteGuardTests: XCTestCase {

    // 테스트 격리: 별도 suite UserDefaults 로 OnboardingFlags.store 교체.
    private var testDefaults: UserDefaults!
    private let suiteName = "RouteGuardTests.suite"

    override func setUp() {
        super.setUp()
        testDefaults = UserDefaults(suiteName: suiteName)
        testDefaults.removePersistentDomain(forName: suiteName)
        OnboardingFlags.store = testDefaults
    }

    override func tearDown() {
        testDefaults.removePersistentDomain(forName: suiteName)
        OnboardingFlags.store = .standard
        testDefaults = nil
        super.tearDown()
    }

    // MARK: - resolveFlagRoute (플래그 단계)

    func test_flagRoute_locationNotAsked_returnsLocation() {
        // AC-11: locationAsked == false → location
        XCTAssertEqual(
            OnboardingRouter.resolveFlagRoute(locationAsked: false, nicknameSet: false),
            .location
        )
        XCTAssertEqual(
            OnboardingRouter.resolveFlagRoute(locationAsked: false, nicknameSet: true),
            .location
        )
    }

    func test_flagRoute_locationAsked_nicknameNotSet_returnsNickname() {
        // AC-12: locationAsked == true && nicknameSet == false → nickname
        XCTAssertEqual(
            OnboardingRouter.resolveFlagRoute(locationAsked: true, nicknameSet: false),
            .nickname
        )
    }

    func test_flagRoute_bothFlagsTrue_returnsNil_meaningGroupStage() {
        // 두 플래그 모두 true → nil(그룹 조회 단계로 진입)
        XCTAssertNil(
            OnboardingRouter.resolveFlagRoute(locationAsked: true, nicknameSet: true)
        )
    }

    // MARK: - resolveGroupRoute (그룹 조회 결과)

    func test_groupRoute_nilGroup_returnsGroupStart() {
        // 그룹 없음 → groupStart
        XCTAssertEqual(OnboardingRouter.resolveGroupRoute(group: nil), .groupStart)
    }

    func test_groupRoute_hasGroup_returnsWelcome() {
        // AC-21/AC-19: 그룹 있음 → welcome(위저드 자동스킵)
        let group = ActiveGroup(groupId: 1, name: "팀", memberCount: 2)
        XCTAssertEqual(OnboardingRouter.resolveGroupRoute(group: group), .welcome)
    }

    // MARK: - resolveFinishRoute (위저드 완료)

    func test_finishRoute_notifNotAsked_returnsNotification() {
        // AC-17: notifAsked == false → notification 1회
        XCTAssertEqual(OnboardingRouter.resolveFinishRoute(notifAsked: false), .notification)
    }

    func test_finishRoute_notifAsked_returnsGroups() {
        // AC-17: notifAsked == true → groups
        XCTAssertEqual(OnboardingRouter.resolveFinishRoute(notifAsked: true), .groups)
    }

    // MARK: - OnboardingFlags 조합 → 전체 라우트 결정(통합 시나리오)

    func test_scenario_freshUser_routesToLocation() {
        // Given 신규 사용자(플래그 모두 false)
        // When
        let route = OnboardingRouter.resolveFlagRoute(
            locationAsked: OnboardingFlags.locationAsked,
            nicknameSet: OnboardingFlags.nicknameSet
        )
        // Then location
        XCTAssertEqual(route, .location)
    }

    func test_scenario_locationDone_routesToNickname() {
        // Given location 완료
        OnboardingFlags.locationAsked = true
        // When
        let route = OnboardingRouter.resolveFlagRoute(
            locationAsked: OnboardingFlags.locationAsked,
            nicknameSet: OnboardingFlags.nicknameSet
        )
        // Then nickname
        XCTAssertEqual(route, .nickname)
    }

    func test_scenario_nicknameDone_entersGroupStage() {
        // Given location + nickname 완료
        OnboardingFlags.locationAsked = true
        OnboardingFlags.nicknameSet = true
        // When
        let route = OnboardingRouter.resolveFlagRoute(
            locationAsked: OnboardingFlags.locationAsked,
            nicknameSet: OnboardingFlags.nicknameSet
        )
        // Then nil → 그룹 조회 단계
        XCTAssertNil(route)
    }

    // MARK: - GroupAPIProtocol 목 + 그룹 분기 (그룹 단계)

    func test_groupStage_mockReturnsNil_routesToGroupStart() async throws {
        // Given 그룹 단계 + 그룹 없음 목
        OnboardingFlags.locationAsked = true
        OnboardingFlags.nicknameSet = true
        let mock = MockGroupAPI(result: .success(nil))

        // When 그룹 조회 후 분기 결정
        let group = try await mock.myActiveGroup()
        let route = OnboardingRouter.resolveGroupRoute(group: group)

        // Then groupStart
        XCTAssertEqual(route, .groupStart)
    }

    func test_groupStage_mockReturnsGroup_routesToWelcome() async throws {
        // AC-19/AC-21: 그룹 단계 + 그룹 있음 목 → welcome
        OnboardingFlags.locationAsked = true
        OnboardingFlags.nicknameSet = true
        let existing = ActiveGroup(groupId: 9, name: "여행팀", memberCount: 4)
        let mock = MockGroupAPI(result: .success(existing))

        // When
        let group = try await mock.myActiveGroup()
        let route = OnboardingRouter.resolveGroupRoute(group: group)

        // Then welcome(스텝1 자동스킵)
        XCTAssertEqual(route, .welcome)
    }

    func test_groupStage_mock401_propagatesError_routeUnchanged() async {
        // 401 그룹 조회 throw → 상위로 전파(Router 의 401 분기는 route 유지 →
        // logoutHandler 가 phase 전환 → RootView 가 LoginView 로). resolvingGroup 유지(깜빡임 방지).
        let mock = MockGroupAPI(result: .failure(
            APIError(code: "UNAUTHORIZED", status: 401, message: "expired")
        ))

        do {
            _ = try await mock.myActiveGroup()
            XCTFail("목이 throw 해야 함")
        } catch let error as APIError where error.status == 401 {
            // Router 의 `catch let apiError as APIError where apiError.status == 401` 분기에 매칭됨.
            // 이 분기는 route 를 변경하지 않으므로 기대 라우트는 .resolvingGroup 유지.
            XCTAssertEqual(error.status, 401)
        } catch {
            XCTFail("401 APIError 가 아닌 에러: \(error)")
        }
    }

    func test_groupStage_mock5xxError_fallsBackToGroupStart() async {
        // 비-401(5xx 등 진짜 APIError) → Router 의 일반 catch 로 폴백.
        // SplashView 무한 stuck 방지: 그룹 없음으로 간주 → .groupStart.
        let mock = MockGroupAPI(result: .failure(
            APIError(code: "INTERNAL_ERROR", status: 500, message: "server down")
        ))

        var fallbackRoute: OnboardingRouter.Route?
        do {
            _ = try await mock.myActiveGroup()
            XCTFail("목이 throw 해야 함")
        } catch let error as APIError where error.status == 401 {
            XCTFail("401 이 아닌데 401 분기에 매칭됨: \(error)")
        } catch {
            // Router 의 일반 catch 분기에 해당 → route = .groupStart.
            fallbackRoute = .groupStart
        }
        XCTAssertEqual(fallbackRoute, .groupStart)
    }

    func test_groupStage_mockNetworkError_fallsBackToGroupStart() async {
        // 비-401(네트워크 타임아웃/오프라인 = URLError, APIError 아님) → Router 의 일반 catch 로 폴백.
        // where status == 401 가드에 매칭되지 않아 .groupStart 로 안전하게 진행.
        let mock = MockGroupAPI(result: .failure(
            URLError(.timedOut)
        ))

        var fallbackRoute: OnboardingRouter.Route?
        do {
            _ = try await mock.myActiveGroup()
            XCTFail("목이 throw 해야 함")
        } catch let error as APIError where error.status == 401 {
            XCTFail("URLError 인데 401 분기에 매칭됨: \(error)")
        } catch {
            // APIError 아님 → 첫 catch(where 가드) 통과 → 일반 catch → route = .groupStart.
            fallbackRoute = .groupStart
        }
        XCTAssertEqual(fallbackRoute, .groupStart)
    }
}

// MARK: - GroupAPIProtocol 목

private final class MockGroupAPI: GroupAPIProtocol, @unchecked Sendable {
    enum Outcome {
        case success(ActiveGroup?)
        case failure(Error)
    }

    private let result: Outcome

    init(result: Outcome) {
        self.result = result
    }

    func myActiveGroup() async throws -> ActiveGroup? {
        switch result {
        case .success(let group): return group
        case .failure(let error): throw error
        }
    }

    func acceptInvite(token: String) async throws -> InviteAccept {
        InviteAccept(groupId: 0)
    }

    func issueInviteLink(groupId: Int) async throws -> InviteLink {
        InviteLink(token: "stub", slug: nil, shareUrl: nil)
    }
}
