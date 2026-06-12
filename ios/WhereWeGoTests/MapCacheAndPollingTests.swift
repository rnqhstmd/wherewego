import XCTest
import CoreLocation
@testable import WhereWeGo

// 핀 목록 캐시(FR-24)·append-only 폴링 병합(BR-7) 단위 테스트(설계 구현순서 11 Should, DoD-A).
// 토큰 무관 — VM 순수 로직. 시각(now)은 주입해 결정적으로 TTL 경과를 시뮬레이션한다.
//
// - FR-24: isPinsCacheStale / refreshPinsIfStale — TTL(5분) 경과 시에만 재조회.
// - BR-7: mergeAppendOnly / reloadPinsAppendOnly — id 기준 신규 핀만 추가, 기존 로컬 낙관 보존.
@MainActor
final class MapCacheAndPollingTests: XCTestCase {

    // MARK: - FR-24: 캐시 stale 판정

    func test_cacheStale_beforeLoad_isStale() {
        let vm = makeViewModel()
        // 미로드 상태(lastFetchedAt nil) → stale.
        XCTAssertTrue(vm.isPinsCacheStale)
    }

    func test_cacheStale_freshWithinTTL_isNotStale() async {
        let clock = MutableClock(start: Date(timeIntervalSince1970: 1_000))
        let vm = makeViewModel(now: clock.now)
        await vm.load()

        // 4분 경과(< 5분 TTL) → fresh.
        clock.advance(by: 4 * 60)
        XCTAssertFalse(vm.isPinsCacheStale)
    }

    func test_cacheStale_afterTTL_isStale() async {
        let clock = MutableClock(start: Date(timeIntervalSince1970: 1_000))
        let vm = makeViewModel(now: clock.now)
        await vm.load()

        // 5분 경과(== TTL) → stale.
        clock.advance(by: 5 * 60)
        XCTAssertTrue(vm.isPinsCacheStale)
    }

    func test_refreshPinsIfStale_whenFresh_doesNotRefetch() async {
        let clock = MutableClock(start: Date(timeIntervalSince1970: 1_000))
        let pinAPI = StubListPinAPI(sequence: [
            [makePin(id: 1, tag: .WISH)],
            [makePin(id: 1, tag: .WISH), makePin(id: 2, tag: .REEL)]
        ])
        let vm = makeViewModel(pinAPI: pinAPI, now: clock.now)
        await vm.load()
        XCTAssertEqual(pinAPI.listCallCount, 1)

        // 1분 경과(fresh) → 재조회 없음.
        clock.advance(by: 60)
        await vm.refreshPinsIfStale()
        XCTAssertEqual(pinAPI.listCallCount, 1)
        XCTAssertEqual(vm.pins.map(\.id), [1])
    }

    func test_refreshPinsIfStale_whenStale_refetchesAndAppends() async {
        let clock = MutableClock(start: Date(timeIntervalSince1970: 1_000))
        let pinAPI = StubListPinAPI(sequence: [
            [makePin(id: 1, tag: .WISH)],
            [makePin(id: 1, tag: .WISH), makePin(id: 2, tag: .REEL)]
        ])
        let vm = makeViewModel(pinAPI: pinAPI, now: clock.now)
        await vm.load()

        // 6분 경과(stale) → 재조회 + 신규 핀(2) append.
        clock.advance(by: 6 * 60)
        await vm.refreshPinsIfStale()
        XCTAssertEqual(pinAPI.listCallCount, 2)
        XCTAssertEqual(Set(vm.pins.map(\.id)), [1, 2])
        // 재조회 후 lastFetchedAt 갱신 → 다시 fresh.
        XCTAssertFalse(vm.isPinsCacheStale)
    }

    // MARK: - BR-7: append-only 병합

    func test_mergeAppendOnly_addsOnlyNewIds() async {
        let vm = makeViewModel(pinAPI: StubListPinAPI(sequence: [[makePin(id: 1, tag: .WISH)]]))
        await vm.load()

        // 서버 응답: 기존 1(수정됨) + 신규 2.
        vm.mergeAppendOnly([
            makePin(id: 1, tag: .MEMORY),
            makePin(id: 2, tag: .REEL)
        ])

        // 신규 2 만 추가. 기존 1 은 로컬(WISH) 보존(수정 무시).
        XCTAssertEqual(Set(vm.pins.map(\.id)), [1, 2])
        XCTAssertEqual(vm.pins.first { $0.id == 1 }?.tag, .WISH)
    }

    func test_mergeAppendOnly_doesNotRemoveLocalPins() async {
        let vm = makeViewModel(pinAPI: StubListPinAPI(sequence: [[
            makePin(id: 1, tag: .WISH),
            makePin(id: 2, tag: .REEL)
        ]]))
        await vm.load()

        // 서버에 1 만 남은(2 삭제) 응답 → 로컬 2 는 낙관 우선으로 보존.
        vm.mergeAppendOnly([makePin(id: 1, tag: .WISH)])

        XCTAssertEqual(Set(vm.pins.map(\.id)), [1, 2])
    }

    func test_mergeAppendOnly_emptyFetch_keepsLocal() async {
        let vm = makeViewModel(pinAPI: StubListPinAPI(sequence: [[makePin(id: 1, tag: .WISH)]]))
        await vm.load()

        vm.mergeAppendOnly([])

        XCTAssertEqual(vm.pins.map(\.id), [1])
    }

    // MARK: - 헬퍼

    private func makeViewModel(
        pinAPI: PinAPIProtocol? = nil,
        now: @escaping () -> Date = { Date() }
    ) -> MapViewModel {
        MapViewModel(
            pinAPI: pinAPI ?? StubListPinAPI(sequence: [[]]),
            placeAPI: StubCachePlaceAPI(),
            groupAPI: StubCacheGroupAPI(group: ActiveGroup(groupId: 1, name: "팀", memberCount: 2)),
            chatAPI: StubChatAPI(),
            locationService: StubCacheLocationService(),
            pollInterval: 30,
            now: now
        )
    }

    private func makePin(
        id: Int,
        tag: PinTag,
        latitude: Double = 37.5,
        longitude: Double = 127.0
    ) -> PinSummary {
        PinSummary(
            id: id,
            groupId: 1,
            createdBy: 1,
            createdByNickname: "tester",
            placeName: "장소\(id)",
            address: nil,
            latitude: latitude,
            longitude: longitude,
            instagramUrl: nil,
            memo: nil,
            memoSource: nil,
            tag: tag,
            createdAt: "2026-01-01T00:00:00Z",
            visitedAt: nil,
            memoUpdatedBy: nil,
            memoUpdatedByNickname: nil,
            photoUrl: nil,
            photoThumbnailUrl: nil
        )
    }
}

// MARK: - 결정적 시각 주입(TTL 경과 시뮬레이션)

private final class MutableClock {
    private var current: Date
    init(start: Date) { self.current = start }
    func advance(by seconds: TimeInterval) { current = current.addingTimeInterval(seconds) }
    /// MapViewModel 에 주입할 시각 클로저. clock 을 strong 캡처(테스트 스코프 수명 동안 유지).
    var now: () -> Date { { self.current } }
}

// MARK: - In-file 목(MapViewModelTests 패턴)

/// list 호출마다 sequence 의 다음 결과를 반환(캐시/폴링 재조회 검증). 끝나면 마지막을 반복.
private final class StubListPinAPI: PinAPIProtocol, @unchecked Sendable {
    private let sequence: [[PinSummary]]
    private(set) var listCallCount = 0

    init(sequence: [[PinSummary]]) {
        self.sequence = sequence.isEmpty ? [[]] : sequence
    }

    func list(groupId: Int) async throws -> [PinSummary] {
        let index = min(listCallCount, sequence.count - 1)
        listCallCount += 1
        return sequence[index]
    }

    func create(groupId: Int, request: CreatePinRequest) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }

    func update(groupId: Int, pinId: Int, request: UpdatePinRequest) async throws -> UpdatePinResponse {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }

    func delete(groupId: Int, pinId: Int) async throws {}

    func uploadPhoto(groupId: Int, pinId: Int, imageData: Data) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }

    func deletePhoto(groupId: Int, pinId: Int) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }
}

private final class StubCachePlaceAPI: PlaceAPIProtocol, @unchecked Sendable {
    func search(_ keyword: String) async throws -> [PlaceItem] { [] }
}

private final class StubCacheGroupAPI: GroupAPIProtocol, @unchecked Sendable {
    private let group: ActiveGroup?
    init(group: ActiveGroup?) { self.group = group }

    func myActiveGroup() async throws -> ActiveGroup? { group }
    func listMyGroups() async throws -> [GroupSummary] { [] }
    func acceptInvite(token: String) async throws -> InviteAccept { InviteAccept(groupId: 0) }
    func issueInviteLink(groupId: Int) async throws -> InviteLink { InviteLink(token: "stub", slug: nil, shareUrl: nil) }
    func leaveGroup(groupId: Int) async throws {}
    func listMembers(groupId: Int) async throws -> [GroupMemberItem] { [] }
    func updateGroupName(groupId: Int, name: String) async throws {}
    func deleteGroup(groupId: Int) async throws {}
}

@MainActor
private final class StubCacheLocationService: LocationServiceProtocol {
    var authorizationStatus: CLAuthorizationStatus = .denied
    var onSample: ((LocationSample) -> Void)?

    func requestWhenInUsePermission() {}
    func startUpdating() {}
    func stopUpdating() {}
    func requestOneShot() async -> LocationSample? { nil }
}
