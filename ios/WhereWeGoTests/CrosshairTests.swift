import XCTest
@testable import WhereWeGo

// 크로스헤어 임의 좌표 7자리 반올림 순수 헬퍼 테스트(설계 §3 FR-15, DoD-A).
// frontend/src/app/map/MapClient.tsx:781~782 `Number(lat.toFixed(7))` 동치.
// 백엔드 좌표 검증(scale ≤ 7) 대비 — Mapbox center 의 15+자리를 7자리로 줄인다.
final class CrosshairTests: XCTestCase {

    func test_roundCoordinate_truncatesToSevenDecimals() {
        // Mapbox center 동치 — 15+자리 입력.
        let rounded = MapViewModel.roundCoordinate(37.123456789012345)
        // 7자리로 반올림.
        XCTAssertEqual(rounded, 37.1234568, accuracy: 1e-12)
    }

    func test_roundCoordinate_roundsHalfUp() {
        // 8번째 자리 5 → 7번째 자리 올림.
        XCTAssertEqual(MapViewModel.roundCoordinate(127.00000005), 127.0000001, accuracy: 1e-12)
    }

    func test_roundCoordinate_negativeValue() {
        // 음수 경도도 동일하게 7자리 반올림.
        let rounded = MapViewModel.roundCoordinate(-122.419415987654)
        XCTAssertEqual(rounded, -122.419416, accuracy: 1e-12)
    }

    func test_roundCoordinate_alreadyShortValueUnchanged() {
        // 7자리 이하 입력은 값 보존.
        XCTAssertEqual(MapViewModel.roundCoordinate(37.5), 37.5, accuracy: 1e-12)
        XCTAssertEqual(MapViewModel.roundCoordinate(127.123456), 127.123456, accuracy: 1e-12)
    }

    func test_roundCoordinate_zero() {
        XCTAssertEqual(MapViewModel.roundCoordinate(0), 0, accuracy: 1e-12)
    }
}
