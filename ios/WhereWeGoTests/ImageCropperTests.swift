import XCTest
import UIKit
@testable import WhereWeGo

// ImageCropper 순수 변환 단위 테스트(설계 §6, AC-8).
// UIGraphicsImageRenderer 로 결정적 테스트 이미지를 생성해 crop/resize/compress 검증.
final class ImageCropperTests: XCTestCase {

    /// scale=1 단색 테스트 이미지(픽셀 좌표 계산 일관).
    private func makeImage(width: CGFloat, height: CGFloat, color: UIColor = .red) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format)
        return renderer.image { ctx in
            color.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        }
    }

    // MARK: - crop

    func test_crop_returnsRequestedRectSize() {
        // Given 400x600 이미지
        let image = makeImage(width: 400, height: 600)
        // When 중앙 400x400(1:1) 크롭
        let rect = CGRect(x: 0, y: 100, width: 400, height: 400)
        let cropped = ImageCropper.crop(image, to: rect)
        // Then 결과 size = rect size
        XCTAssertNotNil(cropped)
        XCTAssertEqual(cropped?.size.width, 400)
        XCTAssertEqual(cropped?.size.height, 400)
    }

    func test_crop_squareSubregion() {
        // Given 정사각형 이미지
        let image = makeImage(width: 500, height: 500)
        // When 100x100 부분 크롭
        let cropped = ImageCropper.crop(image, to: CGRect(x: 50, y: 50, width: 100, height: 100))
        // Then
        XCTAssertEqual(cropped?.size, CGSize(width: 100, height: 100))
    }

    // MARK: - resizeAndCompress

    func test_resizeAndCompress_longEdgeWithinMaxEdge() {
        // Given 장변 3000 이미지
        let image = makeImage(width: 3000, height: 2000)
        // When 기본 maxEdge=1600
        guard let data = ImageCropper.resizeAndCompress(image) else {
            return XCTFail("expected non-nil data")
        }
        // Then 디코드 후 장변 ≤ 1600
        guard let decoded = UIImage(data: data) else {
            return XCTFail("decode failed")
        }
        let longest = max(decoded.size.width * decoded.scale, decoded.size.height * decoded.scale)
        XCTAssertLessThanOrEqual(longest, 1600 + 1) // 반올림 오차 허용
    }

    func test_resizeAndCompress_dataWithinMaxBytes() {
        // Given 큰 이미지
        let image = makeImage(width: 3000, height: 3000)
        // When
        guard let data = ImageCropper.resizeAndCompress(image) else {
            return XCTFail("expected non-nil data")
        }
        // Then 2MB 이하
        XCTAssertLessThanOrEqual(data.count, 2 * 1024 * 1024)
    }

    func test_resizeAndCompress_jpegMagicBytes() {
        // Given 임의 이미지
        let image = makeImage(width: 800, height: 800, color: .blue)
        // When
        guard let data = ImageCropper.resizeAndCompress(image) else {
            return XCTFail("expected non-nil data")
        }
        // Then JPEG 매직바이트 FF D8 FF
        XCTAssertGreaterThanOrEqual(data.count, 3)
        let bytes = [UInt8](data.prefix(3))
        XCTAssertEqual(bytes, [0xFF, 0xD8, 0xFF])
    }

    func test_resizeAndCompress_smallImageNotUpscaled() {
        // Given maxEdge 미만 이미지(800)
        let image = makeImage(width: 800, height: 600)
        // When
        guard let data = ImageCropper.resizeAndCompress(image), let decoded = UIImage(data: data) else {
            return XCTFail("expected non-nil")
        }
        // Then 원본 크기 유지(확대 X)
        let longest = max(decoded.size.width * decoded.scale, decoded.size.height * decoded.scale)
        XCTAssertLessThanOrEqual(longest, 800 + 1)
    }
}
