import XCTest
@testable import WhereWeGo

// AC-1 증명(설계 §1·§8): MapConfig 의 순수 함수 isConfigured/resolveStyleURL 검증.
// xcconfig 의 /$()/ 이스케이프는 빌드 시 Info.plist 에서 "mapbox://styles/mapbox/standard" 로 복원되므로,
// 여기서는 "복원 후 값"을 입력으로 가정하고 placeholder 판단·style 폴백을 검증한다.
final class MapConfigTests: XCTestCase {

    // MARK: - isConfigured: Mapbox token 판단

    func test_isConfigured_placeholder_returnsFalse() {
        // Given placeholder
        XCTAssertFalse(MapConfig.isConfigured(token: "MAPBOX_TOKEN_NOT_SET"))
    }

    func test_isConfigured_empty_returnsFalse() {
        XCTAssertFalse(MapConfig.isConfigured(token: ""))
    }

    func test_isConfigured_whitespaceOnly_returnsFalse() {
        XCTAssertFalse(MapConfig.isConfigured(token: "   "))
    }

    func test_isConfigured_realToken_returnsTrue() {
        // Given 실제 public token 형태
        XCTAssertTrue(MapConfig.isConfigured(token: "pk.eyJhbGciOiJ"))
    }

    // MARK: - resolveStyleURL: 폴백/통과

    func test_resolveStyleURL_nil_fallsBackToStandard() {
        // Given nil
        XCTAssertEqual(
            MapConfig.resolveStyleURL(nil),
            "mapbox://styles/mapbox/standard"
        )
    }

    func test_resolveStyleURL_empty_fallsBackToStandard() {
        // Given 빈 문자열
        XCTAssertEqual(
            MapConfig.resolveStyleURL(""),
            "mapbox://styles/mapbox/standard"
        )
    }

    func test_resolveStyleURL_whitespaceOnly_fallsBackToStandard() {
        // Given 공백만
        XCTAssertEqual(
            MapConfig.resolveStyleURL("   "),
            "mapbox://styles/mapbox/standard"
        )
    }

    func test_resolveStyleURL_customStyle_passesThrough() {
        // Given 커스텀 style URL
        XCTAssertEqual(
            MapConfig.resolveStyleURL("mapbox://styles/wherewego/custom"),
            "mapbox://styles/wherewego/custom"
        )
    }

    func test_resolveStyleURL_trimsWhitespace() {
        // Given 앞뒤 공백 포함
        XCTAssertEqual(
            MapConfig.resolveStyleURL("  mapbox://styles/mapbox/standard  "),
            "mapbox://styles/mapbox/standard"
        )
    }
}
