import XCTest
@testable import WhereWeGo

// GeoMath 순수 함수 단위 테스트(설계 §4).
// frontend/src/app/map/_lib/roulette.test.ts(haversineKm/bbox) 동치 검증.
final class GeoMathTests: XCTestCase {

    // MARK: - haversineKm

    func test_haversineKm_seoulCityHallToGangnam_approx7to9km() {
        // Given 서울 시청 ↔ 강남역 (웹 roulette.test.ts 동일 좌표)
        let seoulCityHall = Coordinate(latitude: 37.5665, longitude: 126.978)
        let gangnam = Coordinate(latitude: 37.4979, longitude: 127.0276)
        // When
        let d = GeoMath.haversineKm(seoulCityHall, gangnam)
        // Then 약 7~9km
        XCTAssertGreaterThan(d, 7.0)
        XCTAssertLessThan(d, 9.0)
    }

    func test_haversineKm_sameCoordinate_returnsZero() {
        // Given 동일 좌표
        let p = Coordinate(latitude: 37.5, longitude: 127.0)
        // When / Then 0km
        XCTAssertEqual(GeoMath.haversineKm(p, p), 0, accuracy: 0.00001)
    }

    func test_haversineKm_isSymmetric() {
        // Given 두 좌표
        let a = Coordinate(latitude: 37.5, longitude: 127.0)
        let b = Coordinate(latitude: 37.6, longitude: 127.1)
        // When / Then 대칭(a→b == b→a)
        XCTAssertEqual(GeoMath.haversineKm(a, b), GeoMath.haversineKm(b, a), accuracy: 0.00001)
    }

    // MARK: - bboxContains

    func test_bboxContains_pointInside_returnsTrue() {
        // Given 100m 박스 중심에서 매우 가까운 점(~30m)
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        let near = Coordinate(latitude: 37.5002, longitude: 127.0002)
        // When / Then 박스 안 → true
        XCTAssertTrue(GeoMath.bboxContains(center: center, point: near, radiusMeters: 100))
    }

    func test_bboxContains_pointOutsideLatitude_returnsFalse() {
        // Given 위도축으로 박스 밖(~222m, 0.002도)
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        let far = Coordinate(latitude: 37.502, longitude: 127.0)
        // When / Then 위도 delta(100m≈0.0009도) 초과 → false
        XCTAssertFalse(GeoMath.bboxContains(center: center, point: far, radiusMeters: 100))
    }

    func test_bboxContains_pointOutsideLongitude_returnsFalse() {
        // Given 경도축으로 박스 밖
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        let far = Coordinate(latitude: 37.5, longitude: 127.003)
        // When / Then 경도 delta 초과 → false
        XCTAssertFalse(GeoMath.bboxContains(center: center, point: far, radiusMeters: 100))
    }

    func test_bboxContains_largeRadiusContainsKmPoint() {
        // Given 10km 박스(룰렛 반경) 내 ~0.7km 핀
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        let inside = Coordinate(latitude: 37.505, longitude: 127.005)
        // When / Then 10km 박스 안 → true
        XCTAssertTrue(GeoMath.bboxContains(center: center, point: inside, radiusMeters: 10_000))
    }
}
