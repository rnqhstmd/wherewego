import UIKit
import SwiftUI

// 핀 마커 글리프 이미지 3종(REEL/WISH/MEMORY) — Mapbox SymbolLayer 의 iconImage 로 등록한다.
// frontend/src/lib/pin/markers.tsx 의 SVG 글리프를 UIKit(UIGraphicsImageRenderer)로 1:1 이식.
//
// 웹 원본(markers.tsx):
//  - REEL  : viewBox 0 0 10 10, <circle cx=5 cy=5 r=4>, 색 #7BB3E8 → 지름 18pt 채운 원.
//  - WISH  : viewBox 0 0 10 10, 5각 별 path, 색 #F4C842 → 20pt 별(×2 좌표 변환).
//  - MEMORY: viewBox 0 0 24 24, 하트 path, 색 #FFB3C6 → 20pt 하트(×20/24 좌표 변환).
//  - 그림자: 웹 drop-shadow(0 1px 3px {색}80) 동치 → context.setShadow(offset (0,1), blur 3, 색 50% alpha).
//
// 캔버스는 모양 외곽보다 사방 +shadowPad(5pt) 크게 잡아 그림자가 잘리지 않게 한다(opaque=false).
// 색은 WGColor.pin* 토큰의 UIColor 변환(`UIColor(WGColor.pin*)`)으로 웹 hex 와 일치.
enum PinMarkerGlyphs {

    /// 그림자가 캔버스 밖으로 잘리지 않도록 모양 외곽에 더하는 여백(웹 drop-shadow blur 3 + offset 1 여유).
    private static let shadowPad: CGFloat = 5

    // MARK: - 공개 이미지 3종

    /// REEL 글리프 — 채운 원, 지름 18pt, 색 #7BB3E8(WGColor.pinReel).
    static var reel: UIImage {
        let diameter: CGFloat = 18
        let color = UIColor(WGColor.pinReel)
        return render(shapeSize: CGSize(width: diameter, height: diameter), color: color) { ctx, origin in
            let rect = CGRect(x: origin.x, y: origin.y, width: diameter, height: diameter)
            ctx.cgContext.fillEllipse(in: rect)
        }
    }

    /// WISH 글리프 — 5각 별 20pt, 색 #F4C842(WGColor.pinWish).
    /// 웹 path(viewBox 0 0 10 10)를 ×2 로 스케일해 20pt 좌표로 옮긴 UIBezierPath.
    static var wish: UIImage {
        let size: CGFloat = 20
        let color = UIColor(WGColor.pinWish)
        return render(shapeSize: CGSize(width: size, height: size), color: color) { ctx, origin in
            ctx.cgContext.addPath(wishPath(origin: origin).cgPath)
            ctx.cgContext.fillPath()
        }
    }

    /// MEMORY 글리프 — 하트 20pt, 색 #FFB3C6(WGColor.pinMemory).
    /// 웹 path(viewBox 0 0 24 24)를 ×20/24 로 스케일해 옮긴 UIBezierPath(베지어 커브).
    static var memory: UIImage {
        let size: CGFloat = 20
        let color = UIColor(WGColor.pinMemory)
        return render(shapeSize: CGSize(width: size, height: size), color: color) { ctx, origin in
            ctx.cgContext.addPath(memoryPath(origin: origin).cgPath)
            ctx.cgContext.fillPath()
        }
    }

    /// 태그별 글리프 이미지(편의 진입점).
    static func image(for tag: PinTag) -> UIImage {
        switch tag {
        case .REEL: return reel
        case .WISH: return wish
        case .MEMORY: return memory
        }
    }

    // MARK: - 공통 렌더(그림자 베이크)

    /// 모양 영역(shapeSize) 둘레에 shadowPad 여백을 둔 캔버스에 그림자 + 채움을 그린다.
    /// draw 클로저는 (renderer context, 모양 좌상단 origin)을 받아 색 채움 모양을 그린다.
    private static func render(
        shapeSize: CGSize,
        color: UIColor,
        draw: (UIGraphicsImageRendererContext, CGPoint) -> Void
    ) -> UIImage {
        let canvasSize = CGSize(
            width: shapeSize.width + shadowPad * 2,
            height: shapeSize.height + shadowPad * 2
        )
        let format = UIGraphicsImageRendererFormat.preferred()
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: canvasSize, format: format)
        return renderer.image { ctx in
            // 웹 drop-shadow(0 1px 3px {색}80) 동치: offset (0,1), blur 3, 태그색 50% alpha.
            ctx.cgContext.setShadow(
                offset: CGSize(width: 0, height: 1),
                blur: 3,
                color: color.withAlphaComponent(0.5).cgColor
            )
            color.setFill()
            // 모양 좌상단은 그림자 여백만큼 안쪽으로.
            draw(ctx, CGPoint(x: shadowPad, y: shadowPad))
        }
    }

    // MARK: - SVG path → UIBezierPath 변환

    /// WISH 5각 별. 웹 markers.tsx path(viewBox 0 0 10 10)의 각 좌표를 ×2 로 스케일(20pt).
    /// 원본: M 5 0.1 L 6.18 3.38 L 9.66 3.49 L 6.90 5.62 L 7.88 8.96 L 5 7.0
    ///       L 2.12 8.96 L 3.10 5.62 L 0.34 3.49 L 3.82 3.38 Z
    private static func wishPath(origin: CGPoint) -> UIBezierPath {
        let s: CGFloat = 2 // viewBox 10 → 20pt
        // (x, y)를 ×2 스케일 + origin 평행이동한 점으로 변환.
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: origin.x + x * s, y: origin.y + y * s)
        }
        let path = UIBezierPath()
        path.move(to: p(5, 0.1))
        path.addLine(to: p(6.18, 3.38))
        path.addLine(to: p(9.66, 3.49))
        path.addLine(to: p(6.90, 5.62))
        path.addLine(to: p(7.88, 8.96))
        path.addLine(to: p(5, 7.0))
        path.addLine(to: p(2.12, 8.96))
        path.addLine(to: p(3.10, 5.62))
        path.addLine(to: p(0.34, 3.49))
        path.addLine(to: p(3.82, 3.38))
        path.close()
        return path
    }

    /// MEMORY 하트. 웹 markers.tsx path(viewBox 0 0 24 24)를 ×20/24 로 스케일(20pt).
    /// SVG 원본(절대 M/C/L · 상대 l/c 혼용):
    ///   M12 21.35
    ///   l-1.45-1.32
    ///   C5.4 15.36 2 12.28 2 8.5
    ///   2 5.42 4.42 3 7.5 3        (앞 C 의 연속 — 절대 cubic)
    ///   c1.74 0 3.41 0.81 4.5 2.09
    ///   C13.09 3.81 14.76 3 16.5 3
    ///   C19.58 3 22 5.42 22 8.5
    ///   c0 3.78-3.4 6.86-8.55 11.54
    ///   L12 21.35 z
    /// 상대 명령(l/c)은 직전 현재점 기준으로 절대 좌표로 환산해 옮긴다(아래 주석에 환산값 명기).
    private static func memoryPath(origin: CGPoint) -> UIBezierPath {
        let s: CGFloat = 20.0 / 24.0 // viewBox 24 → 20pt
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: origin.x + x * s, y: origin.y + y * s)
        }
        let path = UIBezierPath()
        // M12 21.35 — 시작(하트 아래 꼭짓점 부근).
        path.move(to: p(12, 21.35))
        // l-1.45 -1.32 (상대) → 절대 (12-1.45, 21.35-1.32) = (10.55, 20.03).
        path.addLine(to: p(10.55, 20.03))
        // C5.4 15.36 2 12.28 2 8.5 (절대 cubic) → 끝 (2, 8.5).
        path.addCurve(to: p(2, 8.5), controlPoint1: p(5.4, 15.36), controlPoint2: p(2, 12.28))
        // 2 5.42 4.42 3 7.5 3 (앞 C 연속 — 절대 cubic) → 끝 (7.5, 3).
        path.addCurve(to: p(7.5, 3), controlPoint1: p(2, 5.42), controlPoint2: p(4.42, 3))
        // c1.74 0 3.41 0.81 4.5 2.09 (상대, 현재점 7.5,3) →
        //   cp1 (7.5+1.74, 3+0)=(9.24, 3), cp2 (7.5+3.41, 3+0.81)=(10.91, 3.81), 끝 (7.5+4.5, 3+2.09)=(12, 5.09).
        path.addCurve(to: p(12, 5.09), controlPoint1: p(9.24, 3), controlPoint2: p(10.91, 3.81))
        // C13.09 3.81 14.76 3 16.5 3 (절대 cubic) → 끝 (16.5, 3).
        path.addCurve(to: p(16.5, 3), controlPoint1: p(13.09, 3.81), controlPoint2: p(14.76, 3))
        // C19.58 3 22 5.42 22 8.5 (절대 cubic) → 끝 (22, 8.5).
        path.addCurve(to: p(22, 8.5), controlPoint1: p(19.58, 3), controlPoint2: p(22, 5.42))
        // c0 3.78 -3.4 6.86 -8.55 11.54 (상대, 현재점 22,8.5) →
        //   cp1 (22+0, 8.5+3.78)=(22, 12.28), cp2 (22-3.4, 8.5+6.86)=(18.6, 15.36), 끝 (22-8.55, 8.5+11.54)=(13.45, 20.04).
        path.addCurve(to: p(13.45, 20.04), controlPoint1: p(22, 12.28), controlPoint2: p(18.6, 15.36))
        // L12 21.35 (절대 line) → 시작점 복귀.
        path.addLine(to: p(12, 21.35))
        path.close()
        return path
    }
}
