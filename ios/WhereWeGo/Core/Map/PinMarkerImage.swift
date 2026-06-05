import SwiftUI
import UIKit

// 지도 개별 핀 글리프 이미지 생성 헬퍼 — 웹 frontend/src/lib/pin/markers.tsx 의 SVG path 1:1 이식.
// 태그별 다른 "모양"으로 렌더하기 위해 Mapbox SymbolLayer 의 iconImage 로 쓸 UIImage 를 만든다.
//
// - REEL  : 하늘색 원      (markers.tsx getReelSvgString — circle cx=5 cy=5 r=4, viewBox 0 0 10 10)
// - WISH  : 노란 5각 별     (markers.tsx getWishSvgString — path, viewBox 0 0 10 10) · 1.2배
// - MEMORY: 핑크 하트       (markers.tsx getMemorySvgString — path, viewBox 0 0 24 24)
//
// 색 hex 는 Theme.swift(WGColor.pin*) 및 웹 PIN_COLORS 와 동일. 흰 테두리(stroke 2pt)는
// 기존 CircleLayer 핀(circleStrokeColor=.white/width=2)의 외곽선을 보존한다.

/// 지도 핀 글리프 식별자(Mapbox style.addImage 의 id, SymbolLayer iconImage match 의 결과 문자열).
enum PinMarkerImage {
    static let reelImageId = "wwg-pin-glyph-reel"
    static let wishImageId = "wwg-pin-glyph-wish"
    static let memoryImageId = "wwg-pin-glyph-memory"

    // 글리프 기본 지름(pt). 기존 핀 원(circleRadius 9 → 지름 18)과 시각 무게를 맞춘다.
    // WISH 는 1.2배(웹 getMarkerVariant size) 적용을 위해 캔버스를 키워 별이 잘리지 않게 한다.
    private static let baseDiameter: CGFloat = 24
    private static let strokeWidth: CGFloat = 2

    /// REEL — 하늘색 원. 흰 테두리 포함.
    static func reel() -> UIImage {
        circle(color: UIColor(WGColor.pinReel), diameter: baseDiameter)
    }

    /// WISH — 노란 5각 별. 웹 대비 1.2배.
    static func wish() -> UIImage {
        star(color: UIColor(WGColor.pinWish), diameter: baseDiameter * 1.2)
    }

    /// MEMORY — 핑크 하트.
    static func memory() -> UIImage {
        heart(color: UIColor(WGColor.pinMemory), diameter: baseDiameter)
    }

    // MARK: - 도형 렌더링

    /// 채워진 원 + 흰 테두리.
    private static func circle(color: UIColor, diameter: CGFloat) -> UIImage {
        render(diameter: diameter) { rect in
            let inset = strokeWidth / 2
            let path = UIBezierPath(ovalIn: rect.insetBy(dx: inset, dy: inset))
            fill(path, color: color)
        }
    }

    /// 5각 별 — 웹 markers.tsx WishGlyph 와 동일 비율(외곽 반지름 ~4.9 / 내부 반지름 ~1.9, viewBox 0 0 10 10).
    private static func star(color: UIColor, diameter: CGFloat) -> UIImage {
        render(diameter: diameter) { rect in
            let center = CGPoint(x: rect.midX, y: rect.midY)
            // 외곽 반지름은 테두리만큼 안쪽으로. 내부/외곽 비율 0.39(=1.9/4.9)는 웹 별 path 비율.
            let outer = (diameter / 2) - strokeWidth
            let inner = outer * 0.39
            let path = UIBezierPath()
            // 꼭짓점 5개(위쪽 시작), 외곽/내부 반지름을 번갈아 찍어 10개 점.
            for i in 0..<10 {
                let radius = (i % 2 == 0) ? outer : inner
                // 위쪽(-90°)에서 시작, 36°씩 시계방향.
                let angle = -CGFloat.pi / 2 + CGFloat(i) * (CGFloat.pi / 5)
                let point = CGPoint(
                    x: center.x + radius * cos(angle),
                    y: center.y + radius * sin(angle)
                )
                if i == 0 { path.move(to: point) } else { path.addLine(to: point) }
            }
            path.close()
            fill(path, color: color)
        }
    }

    /// 하트 — 웹 markers.tsx MemoryGlyph path(viewBox 0 0 24 24)를 정규화해 이식.
    private static func heart(color: UIColor, diameter: CGFloat) -> UIImage {
        render(diameter: diameter) { rect in
            // 웹 path 는 24x24 좌표계. 동일 캔버스로 스케일해 그린다.
            let inset = strokeWidth / 2
            let drawRect = rect.insetBy(dx: inset, dy: inset)
            let scale = drawRect.width / 24.0
            let tx = drawRect.minX
            let ty = drawRect.minY
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
                CGPoint(x: tx + x * scale, y: ty + y * scale)
            }
            // markers.tsx: M12 21.35 ... 좌우 대칭 하트(두 반원 + 하단 V). 베지어로 근사 이식.
            let path = UIBezierPath()
            path.move(to: p(12, 21.35))
            // 왼쪽 로브: 하단 꼭짓점 → 왼쪽 위로 올라가는 곡선.
            path.addCurve(to: p(2, 8.5), controlPoint1: p(7, 17), controlPoint2: p(2, 12.28))
            path.addCurve(to: p(7.5, 3), controlPoint1: p(2, 5.42), controlPoint2: p(4.42, 3))
            path.addCurve(to: p(12, 5.09), controlPoint1: p(9.24, 3), controlPoint2: p(10.91, 3.81))
            // 오른쪽 로브.
            path.addCurve(to: p(16.5, 3), controlPoint1: p(13.09, 3.81), controlPoint2: p(14.76, 3))
            path.addCurve(to: p(22, 8.5), controlPoint1: p(19.58, 3), controlPoint2: p(22, 5.42))
            path.addCurve(to: p(12, 21.35), controlPoint1: p(22, 12.28), controlPoint2: p(17, 17))
            path.close()
            fill(path, color: color)
        }
    }

    // MARK: - 공통

    /// 흰 테두리 후 색 채움(stroke 가 fill 아래로 깔려 외곽선처럼 보이게 먼저 두껍게 그린 뒤 fill).
    private static func fill(_ path: UIBezierPath, color: UIColor) {
        UIColor.white.setStroke()
        path.lineWidth = strokeWidth
        path.lineJoinStyle = .round
        path.stroke()
        color.setFill()
        path.fill()
    }

    /// 정사각 캔버스 비트맵 렌더. scale 0 → 디바이스 해상도. 투명 배경.
    private static func render(diameter: CGFloat, draw: (CGRect) -> Void) -> UIImage {
        let size = CGSize(width: diameter, height: diameter)
        let format = UIGraphicsImageRendererFormat.default()
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { _ in
            draw(CGRect(origin: .zero, size: size))
        }
    }
}
