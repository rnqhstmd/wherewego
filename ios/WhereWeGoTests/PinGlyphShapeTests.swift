import XCTest
import CoreGraphics
@testable import WhereWeGo

// PinGlyphShape(웹 markers.tsx SVG 경로 이식) 검증. 형상이 주어진 rect 안에 정확히 들어오는지 확인.
final class PinGlyphShapeTests: XCTestCase {

    private let rect = CGRect(x: 10, y: 20, width: 28, height: 28)

    func testAllTagsProduceNonEmptyPathWithinRect() {
        for tag in PinTag.allCases {
            let path = PinGlyphShape.cgPath(for: tag, in: rect)
            XCTAssertFalse(path.isEmpty, "\(tag) path empty")
            let box = path.boundingBox
            XCTAssertGreaterThanOrEqual(box.minX, rect.minX - 0.5, "\(tag) minX")
            XCTAssertGreaterThanOrEqual(box.minY, rect.minY - 0.5, "\(tag) minY")
            XCTAssertLessThanOrEqual(box.maxX, rect.maxX + 0.5, "\(tag) maxX")
            XCTAssertLessThanOrEqual(box.maxY, rect.maxY + 0.5, "\(tag) maxY")
        }
    }

    func testReelIsCenteredCircle() {
        let box = PinGlyphShape.cgPath(for: .REEL, in: rect).boundingBox
        // 지름 80% → 폭/높이 ≈ 22.4, 중심은 rect 중심.
        XCTAssertEqual(box.midX, rect.midX, accuracy: 0.5)
        XCTAssertEqual(box.midY, rect.midY, accuracy: 0.5)
        XCTAssertEqual(box.width, rect.width * 0.8, accuracy: 0.5)
        XCTAssertEqual(box.height, rect.height * 0.8, accuracy: 0.5)
    }

    func testWishAndMemoryFillMostOfRect() {
        // 별/하트는 rect 폭의 상당 부분을 차지해야 한다(형상 누락 가드).
        for tag in [PinTag.WISH, PinTag.MEMORY] {
            let box = PinGlyphShape.cgPath(for: tag, in: rect).boundingBox
            XCTAssertGreaterThan(box.width, rect.width * 0.6, "\(tag) width too small")
            XCTAssertGreaterThan(box.height, rect.height * 0.6, "\(tag) height too small")
        }
    }
}
