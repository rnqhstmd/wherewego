import UIKit

// 사진 크롭·리사이즈·압축 순수 로직(설계 §6, FR-16~19, AC-8).
// - crop: 호출부가 계산한 정사각형(1:1) rect 로 이미지 영역 크롭.
// - resizeAndCompress: 장변 maxEdge 로 리사이즈(UIGraphicsImageRenderer) → JPEG 압축,
//   2MB 초과 시 품질 1.0 → 0.8 → 0.6 → 0.4 단계 감소. 최종도 초과면 nil.
// 결과는 image/jpeg(매직바이트 FF D8 FF). 네트워크/SDK 의존 없는 순수 변환.

enum ImageCropper {
    /// 리사이즈 장변 기본값(px).
    static let defaultMaxEdge: CGFloat = 1600
    /// 업로드 허용 최대 바이트(2MB).
    static let defaultMaxBytes: Int = 2 * 1024 * 1024

    /// JPEG 압축 품질 단계(2MB 초과 시 순차 감소).
    private static let qualitySteps: [CGFloat] = [1.0, 0.8, 0.6, 0.4]

    /// rect 영역 크롭(정사각형 1:1 가정, 호출부가 rect 계산).
    /// rect 가 이미지 밖이거나 cgImage 추출 실패 시 nil.
    /// 아이폰 카메라 세로 사진은 보통 .right orientation 인데 cgImage.cropping(to:) 는
    /// orientation 을 무시하고 원시 픽셀 기준으로 크롭한다(엉뚱한 영역). 크롭 전 orientation 을
    /// .up 으로 정규화(픽셀 물리 회전)해 표시 좌표와 픽셀 좌표를 일치시킨 뒤 크롭한다.
    static func crop(_ image: UIImage, to rect: CGRect) -> UIImage? {
        let normalized = normalizeOrientation(image)
        guard let cgImage = normalized.cgImage else { return nil }
        // UIImage point(scale 포함) → CGImage pixel 좌표 보정.
        let scale = normalized.scale
        let pixelRect = CGRect(
            x: rect.origin.x * scale,
            y: rect.origin.y * scale,
            width: rect.size.width * scale,
            height: rect.size.height * scale
        )
        guard let cropped = cgImage.cropping(to: pixelRect) else { return nil }
        return UIImage(cgImage: cropped, scale: scale, orientation: .up)
    }

    /// orientation 을 .up 으로 정규화(UIGraphicsImageRenderer 로 다시 그려 픽셀 물리 회전).
    /// 이미 .up 이면 원본을 그대로 반환(불필요한 재렌더 회피).
    private static func normalizeOrientation(_ image: UIImage) -> UIImage {
        if image.imageOrientation == .up { return image }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = image.scale
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
    }

    /// 장변 maxEdge 로 리사이즈 후 JPEG 압축. 2MB 초과 시 품질 단계 감소.
    /// - Returns: image/jpeg Data(매직바이트 FF D8 FF). 모든 품질에서 maxBytes 초과면 nil.
    static func resizeAndCompress(
        _ image: UIImage,
        maxEdge: CGFloat = defaultMaxEdge,
        maxBytes: Int = defaultMaxBytes
    ) -> Data? {
        let resized = resize(image, maxEdge: maxEdge)
        for quality in qualitySteps {
            guard let data = resized.jpegData(compressionQuality: quality) else { continue }
            if data.count <= maxBytes {
                return data
            }
        }
        return nil
    }

    /// 장변(긴 변)을 maxEdge 로 맞춰 비율 유지 리사이즈. 이미 작으면 원본 크기 유지.
    private static func resize(_ image: UIImage, maxEdge: CGFloat) -> UIImage {
        let size = image.size
        let longest = max(size.width, size.height)
        guard longest > maxEdge, longest > 0 else {
            // 축소 불필요 — 단 orientation 정규화를 위해 동일 크기로 재렌더.
            return render(image, to: size)
        }
        let ratio = maxEdge / longest
        let target = CGSize(width: size.width * ratio, height: size.height * ratio)
        return render(image, to: target)
    }

    /// UIGraphicsImageRenderer 로 지정 크기에 렌더(orientation 정규화 포함).
    private static func render(_ image: UIImage, to size: CGSize) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
