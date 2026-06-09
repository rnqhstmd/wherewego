import SwiftUI
import CoreGraphics

// 핀 태그 글리프 형상. 웹 frontend/src/lib/pin/markers.tsx 의 SVG path 를 좌표 그대로 이식.
//  - REEL  : viewBox 0 0 10 10, <circle cx5 cy5 r4>  → 지름 80% 원
//  - WISH  : viewBox 0 0 10 10, 5각 별 path
//  - MEMORY: viewBox 0 0 24 24, 하트 path(cubic bezier)
// SF Symbol 근사 대신 웹과 동일한 형상을 보장한다(카드 장소명 태그 글리프 + 배경 지도 글리프 공용).
enum PinGlyphShape {

    /// 주어진 정사각 rect 에 맞춘 글리프 CGPath(viewBox 좌표 → rect 스케일).
    static func cgPath(for tag: PinTag, in rect: CGRect) -> CGPath {
        switch tag {
        case .REEL:   return reelPath(in: rect)
        case .WISH:   return wishPath(in: rect)
        case .MEMORY: return memoryPath(in: rect)
        }
    }

    // MARK: - 개별 글리프

    /// REEL — viewBox 0 0 10 10, circle cx5 cy5 r4(지름 80%).
    private static func reelPath(in rect: CGRect) -> CGPath {
        let d = min(rect.width, rect.height)
        let r = d * 0.4
        let c = CGPoint(x: rect.midX, y: rect.midY)
        let path = CGMutablePath()
        path.addEllipse(in: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2))
        return path
    }

    /// WISH — viewBox 0 0 10 10, 5각 별(10개 꼭짓점 polygon).
    private static func wishPath(in rect: CGRect) -> CGPath {
        let pts: [(CGFloat, CGFloat)] = [
            (5, 0.1), (6.18, 3.38), (9.66, 3.49), (6.90, 5.62), (7.88, 8.96),
            (5, 7.0), (2.12, 8.96), (3.10, 5.62), (0.34, 3.49), (3.82, 3.38)
        ]
        let path = CGMutablePath()
        for (i, p) in pts.enumerated() {
            let q = scaled(p.0, p.1, viewBox: 10, rect: rect)
            if i == 0 { path.move(to: q) } else { path.addLine(to: q) }
        }
        path.closeSubpath()
        return path
    }

    /// MEMORY — viewBox 0 0 24 24, 하트(cubic bezier). 웹 path 를 절대 좌표로 환산해 이식.
    private static func memoryPath(in rect: CGRect) -> CGPath {
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { scaled(x, y, viewBox: 24, rect: rect) }
        let path = CGMutablePath()
        path.move(to: p(12, 21.35))
        path.addLine(to: p(10.55, 20.03))
        path.addCurve(to: p(2, 8.5), control1: p(5.4, 15.36), control2: p(2, 12.28))
        path.addCurve(to: p(7.5, 3), control1: p(2, 5.42), control2: p(4.42, 3))
        path.addCurve(to: p(12, 5.09), control1: p(9.24, 3), control2: p(10.91, 3.81))
        path.addCurve(to: p(16.5, 3), control1: p(13.09, 3.81), control2: p(14.76, 3))
        path.addCurve(to: p(22, 8.5), control1: p(19.58, 3), control2: p(22, 5.42))
        path.addCurve(to: p(13.45, 20.04), control1: p(22, 12.28), control2: p(18.6, 15.36))
        path.addLine(to: p(12, 21.35))
        path.closeSubpath()
        return path
    }

    /// viewBox 좌표(0..viewBox)를 rect 안의 점으로 스케일.
    private static func scaled(_ x: CGFloat, _ y: CGFloat, viewBox: CGFloat, rect: CGRect) -> CGPoint {
        CGPoint(
            x: rect.minX + x / viewBox * rect.width,
            y: rect.minY + y / viewBox * rect.height
        )
    }
}

/// SwiftUI Shape 래퍼(카드 장소명 좌측 태그 글리프용). 색은 호출처가 `.foregroundStyle`/`.fill` 로 지정.
struct PinGlyph: Shape {
    let tag: PinTag
    func path(in rect: CGRect) -> Path {
        Path(PinGlyphShape.cgPath(for: tag, in: rect))
    }
}
