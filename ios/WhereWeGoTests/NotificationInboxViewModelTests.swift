import XCTest
@testable import WhereWeGo

// NotificationInboxViewModel 탭 진입당 read-all 1회 + in-flight 가드 + 포그라운드 list-only 검증(설계 §7, FR-19/FR-21, BR-4).
//
// (CONSIDER) 케이스:
//  - 순차 load() 2회 → 탭 진입마다 read-all 1회씩(누적 2회, 재진입 읽음 누락 방지).
//  - 동시(concurrent) load() → in-flight 가드로 list/read-all 각 1회(cross-review #1).
//  - readAll 성공 후 unreadCount==0 낙관 갱신.
//  - onForeground() 는 list 만 호출(readAll 미호출).
//
// NotificationAPIProtocol mock 으로 호출 카운트를 집계해 검증한다(프로토콜 mock 패턴).
@MainActor
final class NotificationInboxViewModelTests: XCTestCase {

    // MARK: - 순차 load 2회 → 탭 진입마다 read-all 1회씩(재진입 읽음 누락 방지, Gemini HIGH)

    func test_load_sequentialTwice_readAllEachTime() async {
        let api = MockNotificationAPI(unreadCount: 5)
        let vm = NotificationInboxViewModel(api: api, deepLinkRouter: DeepLinkRouter())

        await vm.load()
        await vm.load()

        // 순차 load 는 매 탭 진입마다 didReadAll 리셋 → list·readAll 각각 2회(재진입 시 읽음 보장).
        XCTAssertEqual(api.listCount, 2)
        XCTAssertEqual(api.readAllCount, 2, "탭 진입(load)마다 read-all 1회씩 시도해야 한다(재진입 읽음 누락 방지).")
    }

    // MARK: - 동시 load → in-flight 가드로 list/read-all 1회(cross-review #1)

    func test_load_concurrent_inFlightGuardCallsOnce() async {
        // 동시 진입(예: MainTabView .task + scenePhase .active) → in-flight 가드로 중복 list 차단.
        let api = MockNotificationAPI(unreadCount: 5, listDelayNanos: 50_000_000)
        let vm = NotificationInboxViewModel(api: api, deepLinkRouter: DeepLinkRouter())

        async let first: Void = vm.load()
        async let second: Void = vm.load()
        _ = await (first, second)

        // 두 번째 load 는 isLoading 가드에 막혀 즉시 return → list/readAll 각 1회.
        XCTAssertEqual(api.listCount, 1, "동시 load 는 in-flight 가드로 list 1회만(cross-review #1).")
        XCTAssertEqual(api.readAllCount, 1, "동시 load 는 in-flight 가드로 read-all 1회만.")
    }

    // MARK: - readAll 성공 후 unreadCount==0 낙관 갱신

    func test_load_afterReadAllSuccess_unreadCountIsZero() async {
        let api = MockNotificationAPI(unreadCount: 7)
        let vm = NotificationInboxViewModel(api: api, deepLinkRouter: DeepLinkRouter())

        await vm.load()

        // read-all 성공 → 로컬 unreadCount 0 낙관 갱신(FR-22 빨간 점 해제).
        XCTAssertEqual(vm.unreadCount, 0)
    }

    func test_load_readAllFailure_keepsServerUnreadCount() async {
        // read-all 실패는 조용히 무시(BR-4) — unreadCount 는 서버 list 값 유지(낙관 갱신 없음).
        let api = MockNotificationAPI(unreadCount: 4, readAllError: APIError(code: "FAIL", status: 500, message: "boom"))
        let vm = NotificationInboxViewModel(api: api, deepLinkRouter: DeepLinkRouter())

        await vm.load()

        XCTAssertEqual(vm.unreadCount, 4, "read-all 실패 시 서버 unreadCount 유지(에러 미노출).")
        // 목록은 정상 로드(read-all 실패가 목록 표시를 막지 않음).
        if case .loaded = vm.loadState {} else {
            XCTFail("목록은 정상 로드돼야 한다(read-all 실패 무관).")
        }
    }

    // MARK: - onForeground 는 list 만(readAll 미호출, FR-19)

    func test_onForeground_callsListOnly_notReadAll() async {
        let api = MockNotificationAPI(unreadCount: 3)
        let vm = NotificationInboxViewModel(api: api, deepLinkRouter: DeepLinkRouter())

        await vm.onForeground()

        XCTAssertEqual(api.listCount, 1, "onForeground 는 list 재조회.")
        XCTAssertEqual(api.readAllCount, 0, "onForeground 는 read-all 을 호출하지 않아야 한다(FR-19).")
        // 서버 unreadCount 그대로 반영(낙관 0 갱신 없음).
        XCTAssertEqual(vm.unreadCount, 3)
    }

    func test_loadThenForeground_readAllStillOnce() async {
        // 진입 load(list+readAll 각 1) 후 포그라운드 복귀(list만 1) → list 누적 2회, read-all 은 1회 유지(onForeground 미호출).
        let api = MockNotificationAPI(unreadCount: 2)
        let vm = NotificationInboxViewModel(api: api, deepLinkRouter: DeepLinkRouter())

        await vm.load()
        await vm.onForeground()

        XCTAssertEqual(api.listCount, 2)
        XCTAssertEqual(api.readAllCount, 1, "onForeground 는 read-all 을 호출하지 않으므로 누적 1회 유지(FR-19).")
    }

    // MARK: - 목록 조회 실패 → .error(BR-6)

    func test_load_listFailure_setsErrorState_noReadAll() async {
        let api = MockNotificationAPI(unreadCount: 0, listError: APIError(code: "FAIL", status: 500, message: "down"))
        let vm = NotificationInboxViewModel(api: api, deepLinkRouter: DeepLinkRouter())

        await vm.load()

        if case .error = vm.loadState {} else {
            XCTFail("목록 조회 실패 시 .error 상태여야 한다(BR-6).")
        }
        // 목록 실패 시 read-all 미시도.
        XCTAssertEqual(api.readAllCount, 0)
    }

    // MARK: - flyToPin: soft delete 핀은 pending 미세팅(FR-20)

    func test_flyToPin_deletedPin_doesNotSetPending() {
        let router = DeepLinkRouter()
        let api = MockNotificationAPI(unreadCount: 0)
        let vm = NotificationInboxViewModel(api: api, deepLinkRouter: router)

        let deleted = NotificationPinItem(
            pinId: 99, placeName: "삭제됨", address: nil,
            latitude: nil, longitude: nil, deleted: true,
            instagramUrl: nil, memo: nil, tag: nil
        )
        vm.flyToPin(deleted)

        XCTAssertNil(router.pending, "soft delete 핀은 flyTo 비활성(pending 미세팅).")
    }

    func test_flyToPin_livePin_setsPendingPin() {
        let router = DeepLinkRouter()
        let api = MockNotificationAPI(unreadCount: 0)
        let vm = NotificationInboxViewModel(api: api, deepLinkRouter: router)

        let live = NotificationPinItem(
            pinId: 7, placeName: "성수 카페", address: "서울",
            latitude: 37.5, longitude: 127.0, deleted: false,
            instagramUrl: nil, memo: nil, tag: "WISH"
        )
        vm.flyToPin(live)

        XCTAssertEqual(router.pending, .pin(pinId: 7))
    }
}

// MARK: - NotificationAPIProtocol 목(호출 카운트)

private final class MockNotificationAPI: NotificationAPIProtocol, @unchecked Sendable {
    private let lock = NSLock()
    private var _listCount = 0
    private var _readAllCount = 0
    private var _detailCount = 0

    private let unreadCount: Int
    private let listError: Error?
    private let readAllError: Error?
    /// list() 응답 지연(ns). 동시 load in-flight 가드 검증용(첫 호출 진행 중 두 번째 진입 차단 확인).
    private let listDelayNanos: UInt64

    init(unreadCount: Int, listError: Error? = nil, readAllError: Error? = nil, listDelayNanos: UInt64 = 0) {
        self.unreadCount = unreadCount
        self.listError = listError
        self.readAllError = readAllError
        self.listDelayNanos = listDelayNanos
    }

    var listCount: Int { lock.lock(); defer { lock.unlock() }; return _listCount }
    var readAllCount: Int { lock.lock(); defer { lock.unlock() }; return _readAllCount }
    var detailCount: Int { lock.lock(); defer { lock.unlock() }; return _detailCount }

    func list() async throws -> NotificationListResponse {
        lock.withLock { _listCount += 1 }
        if listDelayNanos > 0 { try? await Task.sleep(nanoseconds: listDelayNanos) }
        if let listError { throw listError }
        return NotificationListResponse(items: [], unreadCount: unreadCount)
    }

    func readAll() async throws -> ReadAllResponse {
        lock.withLock { _readAllCount += 1 }
        if let readAllError { throw readAllError }
        return ReadAllResponse(updatedCount: unreadCount)
    }

    func detail(id: Int) async throws -> NotificationDetail {
        lock.withLock { _detailCount += 1 }
        return NotificationDetail(id: id, type: .MANUAL_PIN, registeredByNickname: nil, createdAt: "", pins: [])
    }
}
