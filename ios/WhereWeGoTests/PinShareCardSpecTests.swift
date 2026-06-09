import XCTest
@testable import WhereWeGo

// 공유 카드 스펙/입력 순수 로직 검증(작성자 라벨 BR-1, 좌표 변환 비율).
final class PinShareCardSpecTests: XCTestCase {

    func testAuthorLabel_nilOrBlankIsAnonymous() {
        XCTAssertEqual(PinShareCardInput.authorLabel(nickname: nil), "익명")
        XCTAssertEqual(PinShareCardInput.authorLabel(nickname: ""), "익명")
        XCTAssertEqual(PinShareCardInput.authorLabel(nickname: "   "), "익명")
    }

    func testAuthorLabel_usesNickname() {
        XCTAssertEqual(PinShareCardInput.authorLabel(nickname: "철수"), "철수")
    }

    func testContentMaxWidth() {
        XCTAssertEqual(PinShareCardSpec.contentMaxWidth, 952, accuracy: 0.0001)
    }

    func testApiToCardScale() {
        XCTAssertEqual(PinShareCardSpec.scaleX, 1080.0 / 1024.0, accuracy: 0.0001)
        XCTAssertEqual(PinShareCardSpec.scaleY, 1350.0 / 1280.0, accuracy: 0.0001)
    }
}
