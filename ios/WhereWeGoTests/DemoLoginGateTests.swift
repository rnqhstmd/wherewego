import XCTest
@testable import WhereWeGo

// 설계 §10: DemoLoginGateState 순수 로직 — 5회 연속 탭 도달 / 간격 초과 리셋 / 해제 후 재카운트.
// now 주입으로 결정적 테스트(AppConfigTests 의 순수 함수 검증 스타일).
final class DemoLoginGateTests: XCTestCase {

    /// 기준 시각 — 0.5초 간격(임계 2초 이내)으로 연속 탭 시뮬레이션.
    private let t0 = Date(timeIntervalSince1970: 1_000)

    private func tap(_ state: inout DemoLoginGateState, after seconds: TimeInterval) -> Bool {
        state.registerTap(now: t0.addingTimeInterval(seconds))
    }

    // MARK: - 5회 연속 탭 도달

    func test_fiveConsecutiveTaps_unlocks() {
        var state = DemoLoginGateState()
        // 4회까지는 false.
        XCTAssertFalse(tap(&state, after: 0.0))
        XCTAssertFalse(tap(&state, after: 0.5))
        XCTAssertFalse(tap(&state, after: 1.0))
        XCTAssertFalse(tap(&state, after: 1.5))
        // 5회째 true(게이트 해제).
        XCTAssertTrue(tap(&state, after: 2.0))
    }

    func test_fourTaps_doesNotUnlock() {
        var state = DemoLoginGateState()
        XCTAssertFalse(tap(&state, after: 0.0))
        XCTAssertFalse(tap(&state, after: 0.4))
        XCTAssertFalse(tap(&state, after: 0.8))
        XCTAssertFalse(tap(&state, after: 1.2))
        XCTAssertEqual(state.tapCount, 4)
    }

    // MARK: - 간격 초과 리셋

    func test_intervalExceeded_resetsCount() {
        var state = DemoLoginGateState()
        XCTAssertFalse(tap(&state, after: 0.0))
        XCTAssertFalse(tap(&state, after: 0.5))
        XCTAssertFalse(tap(&state, after: 1.0))
        // 직전 탭(1.0s)에서 임계(2초) 초과한 3.5s → 카운트 1로 리셋.
        XCTAssertFalse(tap(&state, after: 3.5))
        XCTAssertEqual(state.tapCount, 1)
        // 리셋 후 4회 더 채워야 해제(총 5회 연속).
        XCTAssertFalse(tap(&state, after: 4.0))
        XCTAssertFalse(tap(&state, after: 4.5))
        XCTAssertFalse(tap(&state, after: 5.0))
        XCTAssertTrue(tap(&state, after: 5.5))
    }

    func test_intervalExactlyAtThreshold_keepsCounting() {
        var state = DemoLoginGateState()
        XCTAssertFalse(tap(&state, after: 0.0))
        // 정확히 임계(2초)는 초과가 아니므로 연속 유지(> 비교).
        XCTAssertFalse(tap(&state, after: 2.0))
        XCTAssertEqual(state.tapCount, 2)
    }

    // MARK: - 해제 후 재카운트

    func test_afterUnlock_countResetsForNextGate() {
        var state = DemoLoginGateState()
        for i in 0..<4 {
            XCTAssertFalse(tap(&state, after: Double(i) * 0.5))
        }
        XCTAssertTrue(tap(&state, after: 2.0))
        // 해제 직후 카운트는 0 으로 초기화 — 다음 탭은 다시 1부터.
        XCTAssertEqual(state.tapCount, 0)
        XCTAssertFalse(tap(&state, after: 2.5))
        XCTAssertEqual(state.tapCount, 1)
    }
}
