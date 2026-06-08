import XCTest
@testable import WhereWeGo

// IA 재설계 §1: GroupContext 상태 전이 검증(GM-2 iOS 그룹 다중화).
//  - bootstrap: listMyGroups → groups, lastGroupId 복원(유효 시) / 무효 시 nil(레벨0).
//  - enterGroup/switchGroup/backToList 전이 + onGroupChanged 트리거.
//  - lastGroupId UserDefaults persist(복원 보장, AC-3).
// UserDefaults 는 테스트 격리를 위해 임시 suite 를 주입한다(OnboardingFlags.store 교체 패턴 동치).
@MainActor
final class GroupContextTests: XCTestCase {

    private var store: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "GroupContextTests.\(UUID().uuidString)"
        store = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        store.removePersistentDomain(forName: suiteName)
        store = nil
        suiteName = nil
        super.tearDown()
    }

    // MARK: - bootstrap

    func test_bootstrap_loadsGroups() async {
        // listMyGroups 결과가 groups 에 반영된다.
        let api = StubGroupListAPI(groups: [
            makeSummary(1, "여행팀"),
            makeSummary(2, "맛집팀")
        ])
        let context = GroupContext(groupAPI: api, store: store)

        await context.bootstrap()

        XCTAssertEqual(context.groups.map(\.groupId), [1, 2])
    }

    func test_bootstrap_noLastGroupId_currentIsNil() async {
        // lastGroupId 미저장 → currentGroupId nil(그룹 목록 레벨0 진입).
        let api = StubGroupListAPI(groups: [makeSummary(1, "여행팀")])
        let context = GroupContext(groupAPI: api, store: store)

        await context.bootstrap()

        XCTAssertNil(context.currentGroupId)
    }

    func test_bootstrap_validLastGroupId_restoresCurrent() async {
        // 이전 세션에서 그룹2 진입(lastGroupId=2) → bootstrap 시 목록에 존재하므로 currentGroupId=2 복원(AC-3).
        store.set(2, forKey: "lastGroupId")
        let api = StubGroupListAPI(groups: [
            makeSummary(1, "여행팀"),
            makeSummary(2, "맛집팀")
        ])
        let context = GroupContext(groupAPI: api, store: store)

        await context.bootstrap()

        XCTAssertEqual(context.currentGroupId, 2)
    }

    func test_bootstrap_invalidLastGroupId_currentIsNil() async {
        // lastGroupId=99 가 목록에 없음(탈퇴/삭제) → currentGroupId nil(레벨0 폴백).
        store.set(99, forKey: "lastGroupId")
        let api = StubGroupListAPI(groups: [makeSummary(1, "여행팀")])
        let context = GroupContext(groupAPI: api, store: store)

        await context.bootstrap()

        XCTAssertNil(context.currentGroupId)
    }

    func test_bootstrap_apiFailure_keepsEmptyAndNil() async {
        // listMyGroups 실패 → groups 빈 채, currentGroupId nil(무손상 폴백, 그룹 목록 빈 상태).
        let api = StubGroupListAPI(groups: [], error: APIError(code: "X", status: 500, message: "oops"))
        let context = GroupContext(groupAPI: api, store: store)

        await context.bootstrap()

        XCTAssertTrue(context.groups.isEmpty)
        XCTAssertNil(context.currentGroupId)
    }

    // MARK: - enter/switch/backToList 전이

    func test_enterGroup_setsCurrentAndPersistsLast_andTriggersChange() async {
        var changedTo: [Int] = []
        let api = StubGroupListAPI(groups: [makeSummary(1, "여행팀")])
        let context = GroupContext(groupAPI: api, store: store, onGroupChanged: { changedTo.append($0) })

        context.enterGroup(1)

        XCTAssertEqual(context.currentGroupId, 1)
        XCTAssertEqual(store.object(forKey: "lastGroupId") as? Int, 1, "enterGroup 은 lastGroupId 를 persist 해야 한다(AC-3)")
        XCTAssertEqual(changedTo, [1], "enterGroup 은 onGroupChanged(지도 재로드)를 트리거해야 한다")
    }

    func test_switchGroup_changesCurrent_andTriggersChange() async {
        var changedTo: [Int] = []
        let api = StubGroupListAPI(groups: [makeSummary(1, "A"), makeSummary(2, "B")])
        let context = GroupContext(groupAPI: api, store: store, onGroupChanged: { changedTo.append($0) })

        context.enterGroup(1)
        context.switchGroup(2)

        XCTAssertEqual(context.currentGroupId, 2)
        XCTAssertEqual(store.object(forKey: "lastGroupId") as? Int, 2)
        XCTAssertEqual(changedTo, [1, 2])
    }

    func test_switchGroup_sameGroup_isNoOp() async {
        var changeCount = 0
        let api = StubGroupListAPI(groups: [makeSummary(1, "A")])
        let context = GroupContext(groupAPI: api, store: store, onGroupChanged: { _ in changeCount += 1 })

        context.enterGroup(1)
        context.switchGroup(1)   // 동일 그룹 → no-op(불필요 재로드 방지)

        XCTAssertEqual(context.currentGroupId, 1)
        XCTAssertEqual(changeCount, 1, "동일 그룹 switch 는 onGroupChanged 를 다시 트리거하지 않는다")
    }

    func test_backToList_clearsCurrent_butKeepsLast() async {
        let api = StubGroupListAPI(groups: [makeSummary(1, "A")])
        let context = GroupContext(groupAPI: api, store: store)

        context.enterGroup(1)
        context.backToList()

        XCTAssertNil(context.currentGroupId, "backToList 는 currentGroupId 를 nil(레벨0)로 한다(AC-4)")
        XCTAssertEqual(store.object(forKey: "lastGroupId") as? Int, 1, "backToList 는 lastGroupId 를 유지해야 한다(탭 복귀 시 직행, AC-3)")
    }

    // MARK: - lastGroupId persist 복원(세션 간)

    func test_lastGroupId_persistsAcrossContexts() async {
        // 세션1: 그룹2 진입 → lastGroupId 저장. 세션2(새 GroupContext): bootstrap 시 그 값을 복원.
        let api1 = StubGroupListAPI(groups: [makeSummary(1, "A"), makeSummary(2, "B")])
        let context1 = GroupContext(groupAPI: api1, store: store)
        context1.enterGroup(2)

        let api2 = StubGroupListAPI(groups: [makeSummary(1, "A"), makeSummary(2, "B")])
        let context2 = GroupContext(groupAPI: api2, store: store)
        await context2.bootstrap()

        XCTAssertEqual(context2.currentGroupId, 2)
    }

    // MARK: - 헬퍼

    private func makeSummary(_ id: Int, _ name: String) -> GroupSummary {
        // 같은 모듈(@testable) 이므로 자동 합성 memberwise init 사용(id 는 computed 라 미포함).
        GroupSummary(groupId: id, name: name, createdAt: nil, memberCount: 3)
    }
}

// MARK: - 그룹 목록 stub(listMyGroups 전용)

private final class StubGroupListAPI: GroupAPIProtocol, @unchecked Sendable {
    private let groups: [GroupSummary]
    private let error: Error?

    init(groups: [GroupSummary], error: Error? = nil) {
        self.groups = groups
        self.error = error
    }

    func myActiveGroup() async throws -> ActiveGroup? { nil }
    func listMyGroups() async throws -> [GroupSummary] {
        if let error { throw error }
        return groups
    }
    func acceptInvite(token: String) async throws -> InviteAccept { InviteAccept(groupId: 0) }
    func issueInviteLink(groupId: Int) async throws -> InviteLink { InviteLink(token: "stub", slug: nil, shareUrl: nil) }
    func leaveGroup(groupId: Int) async throws {}
}
