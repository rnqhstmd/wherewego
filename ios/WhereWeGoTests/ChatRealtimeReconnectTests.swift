import XCTest
@testable import WhereWeGo

// 재연결 정책(BR-8) 순수 로직 결정적 테스트(설계 §4).
// ReconnectPolicy 는 now/sleep 무관 순수 카운터·간격 스케줄 — 시퀀스만 검증한다.
//
// BR-8 시퀀스: 즉시 1회(delay 0) → 5초 × 3회 → 총 4회 후 nil(중단·.disconnected).
//             연결 성공 시 reset() 으로 카운터 초기화.
final class ChatRealtimeReconnectTests: XCTestCase {

    // MARK: - 시퀀스(즉시 1회 → 5초 × 3회 → 중단)

    func test_nextDelay_firstAttempt_isImmediate() {
        var policy = ReconnectPolicy()
        // 첫 시도는 즉시(0초).
        XCTAssertEqual(policy.nextDelay(), 0)
    }

    func test_nextDelay_sequence_immediateThenThreeFiveSecond() {
        var policy = ReconnectPolicy()
        // 즉시 1회 → 5초 × 3회.
        XCTAssertEqual(policy.nextDelay(), 0)
        XCTAssertEqual(policy.nextDelay(), 5)
        XCTAssertEqual(policy.nextDelay(), 5)
        XCTAssertEqual(policy.nextDelay(), 5)
    }

    func test_nextDelay_afterFourAttempts_returnsNil() {
        var policy = ReconnectPolicy()
        // 4회(즉시 1 + 5초 3) 소비.
        for _ in 0..<ReconnectPolicy.maxAttempts {
            XCTAssertNotNil(policy.nextDelay())
        }
        // 5번째부터는 중단(nil → .disconnected).
        XCTAssertNil(policy.nextDelay())
        // 중단 상태는 멱등(계속 nil).
        XCTAssertNil(policy.nextDelay())
    }

    func test_maxAttempts_isFour() {
        // BR-8: 즉시 1 + 5초 간격 3 = 총 4회.
        XCTAssertEqual(ReconnectPolicy.maxAttempts, 4)
        XCTAssertEqual(ReconnectPolicy.retryInterval, 5)
    }

    // MARK: - reset(연결 성공 시 카운터 초기화)

    func test_reset_afterPartialAttempts_restartsSequence() {
        var policy = ReconnectPolicy()
        // 2회 소비(즉시 + 5초).
        _ = policy.nextDelay()
        _ = policy.nextDelay()

        // 연결 성공 → reset.
        policy.reset()

        // 다시 즉시부터 시작.
        XCTAssertEqual(policy.nextDelay(), 0)
        XCTAssertEqual(policy.nextDelay(), 5)
    }

    func test_reset_afterExhaustion_allowsNewCycle() {
        var policy = ReconnectPolicy()
        // 전부 소진(4회 + nil).
        for _ in 0..<ReconnectPolicy.maxAttempts { _ = policy.nextDelay() }
        XCTAssertNil(policy.nextDelay())

        // reset 후 새 사이클 — 다시 4회 가능.
        policy.reset()
        var delays: [TimeInterval] = []
        while let delay = policy.nextDelay() { delays.append(delay) }
        XCTAssertEqual(delays, [0, 5, 5, 5])
    }

    // MARK: - attempt 누적(외부 관찰용)

    func test_attempt_incrementsPerDelay_stopsAtMax() {
        var policy = ReconnectPolicy()
        XCTAssertEqual(policy.attempt, 0)
        _ = policy.nextDelay()
        XCTAssertEqual(policy.attempt, 1)
        _ = policy.nextDelay()
        _ = policy.nextDelay()
        _ = policy.nextDelay()
        XCTAssertEqual(policy.attempt, ReconnectPolicy.maxAttempts)
        // 소진 후 nextDelay 는 attempt 를 더 올리지 않는다.
        _ = policy.nextDelay()
        XCTAssertEqual(policy.attempt, ReconnectPolicy.maxAttempts)
    }
}
