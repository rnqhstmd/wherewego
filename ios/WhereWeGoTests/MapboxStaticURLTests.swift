import XCTest
@testable import WhereWeGo

// MapboxStaticURL 빌더 검증(공유 카드 배경 지도 URL). 웹 mapboxStaticUrl.ts 이식 동치.
final class MapboxStaticURLTests: XCTestCase {

    func testBuild_basicFormat() {
        let url = MapboxStaticURL.build(
            latitude: 37.5665, longitude: 126.9780,
            zoom: 15, width: 1024, height: 1280,
            styleId: "mapbox/streets-v12", token: "pktoken"
        )
        XCTAssertEqual(
            url,
            "https://api.mapbox.com/styles/v1/mapbox/streets-v12/static/"
            + "126.978000,37.566500,15,0/1024x1280?access_token=pktoken"
        )
    }

    func testBuild_sixDecimalCoords() {
        let url = MapboxStaticURL.build(
            latitude: 37.5, longitude: 127.0,
            zoom: 15, width: 1024, height: 1280,
            styleId: "mapbox/light-v11", token: "t"
        )
        XCTAssertTrue(url.contains("/static/127.000000,37.500000,15,0/"), url)
    }

    func testBuild_encodesTokenSpecialChars() {
        let url = MapboxStaticURL.build(
            latitude: 0, longitude: 0,
            zoom: 15, width: 1024, height: 1280,
            styleId: "mapbox/light-v11", token: "a+b/c=d"
        )
        XCTAssertTrue(url.hasSuffix("access_token=a%2Bb%2Fc%3Dd"), url)
    }

    func testBuild_keepsPlainTokenUnencoded() {
        let url = MapboxStaticURL.build(
            latitude: 1, longitude: 2,
            zoom: 15, width: 1024, height: 1280,
            styleId: "mapbox/streets-v12", token: "pk.eyJ123-_~"
        )
        XCTAssertTrue(url.hasSuffix("access_token=pk.eyJ123-_~"), url)
    }
}
