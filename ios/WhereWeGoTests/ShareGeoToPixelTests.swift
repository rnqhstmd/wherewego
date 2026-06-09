import XCTest
@testable import WhereWeGo

// ShareGeoToPixel(Web Mercator 투영) 검증. 웹 geoToPixel.ts 이식 동치.
final class ShareGeoToPixelTests: XCTestCase {

    private let apiW = 1024.0
    private let apiH = 1280.0

    func testCenterMapsToImageCenter() {
        let p = ShareGeoToPixel.apiPixel(
            pinLat: 37.5, pinLng: 127.0,
            centerLat: 37.5, centerLng: 127.0,
            zoom: 15, apiW: apiW, apiH: apiH
        )
        XCTAssertEqual(p.x, apiW / 2, accuracy: 0.0001)
        XCTAssertEqual(p.y, apiH / 2, accuracy: 0.0001)
    }

    func testEastPinMovesRight() {
        let p = ShareGeoToPixel.apiPixel(
            pinLat: 37.5, pinLng: 127.001,
            centerLat: 37.5, centerLng: 127.0,
            zoom: 15, apiW: apiW, apiH: apiH
        )
        XCTAssertGreaterThan(p.x, apiW / 2)
        XCTAssertEqual(p.y, apiH / 2, accuracy: 0.001)
    }

    func testNorthPinMovesUp() {
        let p = ShareGeoToPixel.apiPixel(
            pinLat: 37.501, pinLng: 127.0,
            centerLat: 37.5, centerLng: 127.0,
            zoom: 15, apiW: apiW, apiH: apiH
        )
        // 북쪽일수록 이미지 위(y 작아짐).
        XCTAssertLessThan(p.y, apiH / 2)
        XCTAssertEqual(p.x, apiW / 2, accuracy: 0.001)
    }

    func testEastOffsetMatchesMercatorMath() {
        let p = ShareGeoToPixel.apiPixel(
            pinLat: 0, pinLng: 0.01,
            centerLat: 0, centerLng: 0,
            zoom: 15, apiW: apiW, apiH: apiH
        )
        let scale = 256.0 * pow(2.0, 15.0)
        let expectedDx = (0.01 / 360.0) * scale
        XCTAssertEqual(p.x, apiW / 2 + expectedDx, accuracy: 0.01)
    }

    func testClampLat() {
        XCTAssertEqual(ShareGeoToPixel.clampLat(90), 84.9, accuracy: 0.0001)
        XCTAssertEqual(ShareGeoToPixel.clampLat(-90), -84.9, accuracy: 0.0001)
        XCTAssertEqual(ShareGeoToPixel.clampLat(10), 10, accuracy: 0.0001)
    }
}
