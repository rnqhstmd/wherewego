import XCTest
@testable import WhereWeGo

// CONSIDER 증명(설계 §3): AppConfig 의 순수 함수 resolveBaseURL/isConfigured 검증.
// xcconfig 의 /$()/ 이스케이프는 빌드 시 Info.plist 에서 "http://localhost:8080" 으로 복원되므로,
// 여기서는 "복원 후 값"을 입력으로 가정하고 URL 파싱·폴백·키 판단을 검증한다.
final class AppConfigTests: XCTestCase {

    // MARK: - resolveBaseURL: 정상 파싱

    func test_resolveBaseURL_validHTTP_parsesCorrectly() {
        // Given Info.plist 치환 후 정상 URL 문자열
        let url = AppConfig.resolveBaseURL(from: "http://localhost:8080")
        // Then 그대로 파싱
        XCTAssertEqual(url.absoluteString, "http://localhost:8080")
        XCTAssertEqual(url.scheme, "http")
    }

    func test_resolveBaseURL_validHTTPS_parsesCorrectly() {
        // Given 운영 URL
        let url = AppConfig.resolveBaseURL(from: "https://api.wherewego.app")
        // Then
        XCTAssertEqual(url.absoluteString, "https://api.wherewego.app")
        XCTAssertEqual(url.scheme, "https")
    }

    func test_resolveBaseURL_trimsWhitespace() {
        // Given 앞뒤 공백 포함
        let url = AppConfig.resolveBaseURL(from: "  http://localhost:8080  ")
        // Then trim 후 파싱
        XCTAssertEqual(url.absoluteString, "http://localhost:8080")
    }

    // MARK: - resolveBaseURL: 폴백

    func test_resolveBaseURL_nil_fallsBackToLocalhost() {
        // Given nil
        let url = AppConfig.resolveBaseURL(from: nil)
        // Then localhost 폴백
        XCTAssertEqual(url.absoluteString, "http://localhost:8080")
    }

    func test_resolveBaseURL_empty_fallsBackToLocalhost() {
        // Given 빈 문자열
        XCTAssertEqual(
            AppConfig.resolveBaseURL(from: "").absoluteString,
            "http://localhost:8080"
        )
    }

    func test_resolveBaseURL_whitespaceOnly_fallsBackToLocalhost() {
        // Given 공백만
        XCTAssertEqual(
            AppConfig.resolveBaseURL(from: "   ").absoluteString,
            "http://localhost:8080"
        )
    }

    func test_resolveBaseURL_noScheme_fallsBackToLocalhost() {
        // Given scheme 없는 문자열(URL(string:) 이 scheme 을 파싱하지 못하는 형태).
        // 주의: "localhost:8080" 은 URL 이 "localhost" 를 scheme 으로 해석하므로 폴백되지 않는다.
        //       슬래시로 시작하는 경로형 입력은 scheme == nil 이라 폴백한다.
        XCTAssertEqual(
            AppConfig.resolveBaseURL(from: "//localhost:8080").absoluteString,
            "http://localhost:8080"
        )
    }

    // MARK: - isConfigured: 카카오 키 판단

    func test_isConfigured_placeholder_returnsFalse() {
        // Given placeholder
        XCTAssertFalse(AppConfig.isConfigured(kakaoKey: "KAKAO_APP_KEY_NOT_SET"))
    }

    func test_isConfigured_empty_returnsFalse() {
        XCTAssertFalse(AppConfig.isConfigured(kakaoKey: ""))
    }

    func test_isConfigured_whitespaceOnly_returnsFalse() {
        XCTAssertFalse(AppConfig.isConfigured(kakaoKey: "   "))
    }

    func test_isConfigured_nil_returnsFalse() {
        XCTAssertFalse(AppConfig.isConfigured(kakaoKey: nil))
    }

    func test_isConfigured_realKey_returnsTrue() {
        // Given 실제 키 형태
        XCTAssertTrue(AppConfig.isConfigured(kakaoKey: "abc123realkey"))
    }
}
