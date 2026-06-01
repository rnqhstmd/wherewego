import XCTest
@testable import WhereWeGo

// VisitDetectionEngine 순수 평가 단위 테스트(설계 §4, AC-12~14).
// frontend/src/app/map/_hooks/useVisitDetection.test.ts 동치 + now 주입(시계 결정성).
// 정확도 게이트는 설계 확정값 50m(웹 운영 100m 와 다름 — coder 보고에 명시).
final class VisitDetectionEngineTests: XCTestCase {

    // 서울시청 기준 ~30m 내(100m 박스 안) 후보 핀.
    private let center = (lat: 37.5665, lng: 126.9780)
    private func nearPin(_ id: Int) -> VisitCandidatePin {
        // 위도 +0.0002 ≈ 22m → 100m 반경 안.
        VisitCandidatePin(pinId: id, latitude: center.lat + 0.0002, longitude: center.lng)
    }
    private func sample(accuracy: Double, speed: Double? = nil) -> LocationSample {
        LocationSample(latitude: center.lat, longitude: center.lng, accuracyMeters: accuracy, speedMps: speed)
    }

    // MARK: - AC-12 정확도 게이트(50m)

    func test_accuracyAbove50m_skipsEvaluationAndPreservesFirstEnterAt() {
        // Given 엔진. now=0 정확도 양호(20m) 진입 → firstEnterAt 누적.
        let engine = VisitDetectionEngine()
        let pins = [nearPin(1)]
        _ = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [], now: 0)

        // When now=15 에 정확도 미달(60m) 샘플 → 평가 스킵, firstEnterAt 보존(초기화 X)
        let skipped = engine.evaluate(sample: sample(accuracy: 60), wishReelPins: pins, shownPinIds: [], now: 15)
        XCTAssertNil(skipped)

        // Then now=30 정확도 양호 재평가 → 첫 진입(now=0) 기준 30초 경과로 감지(보존 확인)
        let detected = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [], now: 30)
        XCTAssertEqual(detected, 1)
    }

    // MARK: - AC-13 속도 게이트(1.4 m/s)

    func test_speedAbove1_4_clearsAllFirstEnterAt() {
        // Given now=0 진입으로 firstEnterAt 누적
        let engine = VisitDetectionEngine()
        let pins = [nearPin(1), nearPin(2)]
        _ = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [], now: 0)

        // When now=10 에 speed=2.0 (이동 중) → clearAll, nil
        let moving = engine.evaluate(sample: sample(accuracy: 20, speed: 2.0), wishReelPins: pins, shownPinIds: [], now: 10)
        XCTAssertNil(moving)

        // Then now=35 양호 재평가 → firstEnterAt 이 비워졌으므로 now=35 부터 재누적,
        //      35-35=0 < 30 → 미감지(전부 clear 됨을 증명).
        let after = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [], now: 35)
        XCTAssertNil(after)
    }

    func test_speedNil_passesGate() {
        // Given speed nil(iOS 일부 디바이스) — 게이트 통과(안전 fallback)
        let engine = VisitDetectionEngine()
        let pins = [nearPin(1)]
        _ = engine.evaluate(sample: sample(accuracy: 20, speed: nil), wishReelPins: pins, shownPinIds: [], now: 0)
        // When now=30 재평가
        let detected = engine.evaluate(sample: sample(accuracy: 20, speed: nil), wishReelPins: pins, shownPinIds: [], now: 30)
        // Then 통과 → 30초 경과 감지
        XCTAssertEqual(detected, 1)
    }

    // MARK: - AC-14 30초 머무름 + 세션 중복 차단

    func test_dwell30s_detectsNearestPin() {
        // Given now=0 진입
        let engine = VisitDetectionEngine()
        let pins = [nearPin(1)]
        let enter = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [], now: 0)
        XCTAssertNil(enter) // 진입 즉시는 미감지

        // When now=30 재평가
        let detected = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [], now: 30)
        // Then 해당 pinId 반환
        XCTAssertEqual(detected, 1)
    }

    func test_dwellBelow30s_returnsNil() {
        // Given now=0 진입
        let engine = VisitDetectionEngine()
        let pins = [nearPin(1)]
        _ = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [], now: 0)
        // When now=29 (30초 미만)
        let result = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [], now: 29)
        // Then 미감지
        XCTAssertNil(result)
    }

    func test_shownPinIds_blocksDuplicateDetection() {
        // Given now=0 진입 후 now=30 감지 가능 상태
        let engine = VisitDetectionEngine()
        let pins = [nearPin(1)]
        _ = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [], now: 0)
        // When shownPinIds 에 1 포함 → 세션 중복 차단
        let result = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [1], now: 30)
        // Then 미반환
        XCTAssertNil(result)
    }

    func test_nearestPinReturnedFirst() {
        // Given 두 후보(가까운 1, 더 먼 2) now=0 동시 진입
        let engine = VisitDetectionEngine()
        let nearer = VisitCandidatePin(pinId: 1, latitude: center.lat + 0.0001, longitude: center.lng) // ~11m
        let farther = VisitCandidatePin(pinId: 2, latitude: center.lat + 0.0005, longitude: center.lng) // ~55m
        let pins = [farther, nearer]
        _ = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [], now: 0)
        // When now=30
        let detected = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [], now: 30)
        // Then 최근접(1) 반환
        XCTAssertEqual(detected, 1)
    }

    func test_pinOutsideProximity_notDetected() {
        // Given ~222m(0.002도) 떨어진 핀 — 100m 밖
        let engine = VisitDetectionEngine()
        let far = VisitCandidatePin(pinId: 9, latitude: center.lat + 0.002, longitude: center.lng)
        _ = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: [far], shownPinIds: [], now: 0)
        // When now=30
        let result = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: [far], shownPinIds: [], now: 30)
        // Then BBox/haversine 컷 → 미감지
        XCTAssertNil(result)
    }

    // MARK: - clear

    func test_clearFirstEnterAt_resetsSinglePin() {
        // Given now=0 진입
        let engine = VisitDetectionEngine()
        let pins = [nearPin(1)]
        _ = engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [], now: 0)
        // When 해당 핀 clear
        engine.clearFirstEnterAt(pinId: 1)
        // Then now=30 재평가해도 now=30 재누적 → 미감지
        XCTAssertNil(engine.evaluate(sample: sample(accuracy: 20), wishReelPins: pins, shownPinIds: [], now: 30))
    }
}
