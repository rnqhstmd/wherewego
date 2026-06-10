import XCTest
@testable import WhereWeGo

// InstagramURL.isReelURL 판정(GC-2 FR-GC2-8). 백엔드 ReelPlaceExtractor.INSTAGRAM_URL + https 강제 동치.
final class InstagramURLTests: XCTestCase {

    func test_URL_단독이면_REEL_LINK() {
        XCTAssertTrue(InstagramURL.isReelURL("https://www.instagram.com/reel/ABC123_-/"))
        XCTAssertTrue(InstagramURL.isReelURL("https://instagram.com/reel/ABC123"))
        XCTAssertTrue(InstagramURL.isReelURL("https://www.instagram.com/p/Xyz789/"))
        XCTAssertTrue(InstagramURL.isReelURL("https://www.instagram.com/reels/Abc/?igsh=xxx"))
        XCTAssertTrue(InstagramURL.isReelURL("https://instagr.am/reel/ABC123"))
    }

    func test_앞뒤_공백은_트림후_판정() {
        XCTAssertTrue(InstagramURL.isReelURL("  https://instagram.com/reel/ABC123  "))
        XCTAssertTrue(InstagramURL.isReelURL("\nhttps://instagram.com/reel/ABC123\n"))
    }

    func test_텍스트_혼합이면_TEXT() {
        XCTAssertFalse(InstagramURL.isReelURL("여기 가보자 https://www.instagram.com/reel/ABC123"))
        XCTAssertFalse(InstagramURL.isReelURL("https://www.instagram.com/reel/ABC123 좋아"))
    }

    func test_http_는_거부() {
        XCTAssertFalse(InstagramURL.isReelURL("http://www.instagram.com/reel/ABC123"))
    }

    func test_비인스타_또는_프로필은_false() {
        XCTAssertFalse(InstagramURL.isReelURL("https://youtube.com/watch?v=x"))
        XCTAssertFalse(InstagramURL.isReelURL("https://instagram.com/someuser"))   // p/reel/reels 아님
        XCTAssertFalse(InstagramURL.isReelURL("https://instagram.com/reel/"))      // code 없음
        XCTAssertFalse(InstagramURL.isReelURL("그냥 텍스트"))
        XCTAssertFalse(InstagramURL.isReelURL(""))
    }

    func test_2000자_초과_false() {
        let long = "https://www.instagram.com/reel/" + String(repeating: "a", count: 2000)
        XCTAssertFalse(InstagramURL.isReelURL(long))
    }
}
