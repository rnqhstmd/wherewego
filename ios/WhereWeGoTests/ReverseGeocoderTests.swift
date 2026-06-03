import XCTest
@testable import WhereWeGo

// ReverseGeocoder 좌표 폴백 + Debouncer 동작 검증(설계 §5, FR-14/BR-3, AC-5/AC-9).
//
// CLGeocoder 는 실디바이스/네트워크 의존이라 reverseGeocode(_:) 자체는 테스트 제외(설계 §5 명시).
// 순수 로직(coordinateFallback)과 Debouncer(주입 스케줄러)만 결정적으로 검증한다.
@MainActor
final class ReverseGeocoderTests: XCTestCase {

    // MARK: - AC-9: coordinateFallback(순수, 소수 4자리 반올림)

    func test_coordinateFallback_roundsToFourDecimals() {
        // String(format: "%.4f") 실제 출력: 37.12345 → "37.1234", 127.56789 → "127.5679".
        // 37.12345 는 부동소수로 37.123449...로 저장돼 5번째 자리가 5 미만 → 반내림("37.1234").
        XCTAssertEqual(
            ReverseGeocoder.coordinateFallback(lat: 37.12345, lng: 127.56789),
            "위도 37.1234, 경도 127.5679"
        )
    }

    func test_coordinateFallback_padsToFourDecimals() {
        // String(format: "%.4f"): 37.5 → "37.5000"(소수 4자리 고정 패딩). 정수부만 있어도 4자리 0 패딩.
        XCTAssertEqual(
            ReverseGeocoder.coordinateFallback(lat: 37.5, lng: 127.0),
            "위도 37.5000, 경도 127.0000"
        )
    }

    func test_coordinateFallback_negativeCoordinates() {
        // 남반구/서반구 좌표(음수)도 동일하게 %.4f 4자리 포맷.
        XCTAssertEqual(
            ReverseGeocoder.coordinateFallback(lat: -33.86888, lng: 151.20930),
            "위도 -33.8689, 경도 151.2093"
        )
    }

    // MARK: - AC-5: Debouncer 300ms 내 연속 호출 → 마지막 1회만 실행

    func test_debouncer_consecutiveCalls_executesOnlyLastAction() {
        // 수동 트리거 스케줄러: scheduler 가 예약된 work 들을 즉시 실행하지 않고 보관했다가
        // 테스트가 일괄 flush 한다. Debouncer 의 generation 토큰으로 마지막 예약만 action 을 호출하는지 검증.
        var pendingWorks: [() -> Void] = []
        let debouncer = Debouncer(
            interval: 0.3,
            scheduler: { _, work in pendingWorks.append(work) }
        )

        // 300ms 안에 연속 3회 호출(각자 다른 action). 각 호출은 work 1개를 큐에 적재.
        var executionLog: [Int] = []
        debouncer.call { executionLog.append(1) }
        debouncer.call { executionLog.append(2) }
        debouncer.call { executionLog.append(3) }

        // 아직 어떤 action 도 실행되지 않음(스케줄러가 보관만 함).
        XCTAssertTrue(executionLog.isEmpty)
        XCTAssertEqual(pendingWorks.count, 3, "call 3회 → work 3개 예약")

        // 예약된 work 들을 등록 순서대로 모두 flush(asyncAfter 발화 시뮬레이션).
        for work in pendingWorks { work() }

        // generation 토큰: 마지막(3번째) call 의 action 만 실행. 1·2번째는 stale 토큰으로 무효화.
        XCTAssertEqual(executionLog, [3], "300ms 내 3회 연속 → 마지막 action 1회만 실행(AC-5).")
    }

    func test_debouncer_separateBursts_eachExecutesOnce() {
        // 한 버스트 flush 후 새 호출 → 새 버스트는 다시 마지막 1회 실행(generation 누적 증가 검증).
        var pendingWorks: [() -> Void] = []
        let debouncer = Debouncer(
            interval: 0.3,
            scheduler: { _, work in pendingWorks.append(work) }
        )

        var executionLog: [String] = []

        // 1차 버스트(2회) → flush
        debouncer.call { executionLog.append("a1") }
        debouncer.call { executionLog.append("a2") }
        let firstBurst = pendingWorks
        pendingWorks = []
        for work in firstBurst { work() }
        XCTAssertEqual(executionLog, ["a2"], "1차 버스트 마지막만 실행")

        // 2차 버스트(2회) → flush
        debouncer.call { executionLog.append("b1") }
        debouncer.call { executionLog.append("b2") }
        let secondBurst = pendingWorks
        for work in secondBurst { work() }
        XCTAssertEqual(executionLog, ["a2", "b2"], "2차 버스트도 마지막만 실행")
    }

    func test_debouncer_singleCall_executesThatAction() {
        // 단일 호출(연발 없음) → 그 action 1회 실행.
        var pendingWorks: [() -> Void] = []
        let debouncer = Debouncer(
            interval: 0.3,
            scheduler: { _, work in pendingWorks.append(work) }
        )

        var executed = false
        debouncer.call { executed = true }
        XCTAssertFalse(executed)
        for work in pendingWorks { work() }
        XCTAssertTrue(executed)
    }
}
