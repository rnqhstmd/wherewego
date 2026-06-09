import SwiftUI
import UIKit
import CoreImage
import CoreImage.CIFilterBuiltins

// 핀 공유 카드 렌더러. 웹 frontend/src/lib/share/renderPinCard.ts 파이프라인 이식.
//  1) Mapbox Static 지도 다운로드(streets-v12 → light-v11 폴백) — 토큰 미설정/실패 시 단색 폴백(웹 BR-6)
//  2) 흑백(채도0) → 핀 글리프 합성(흰 원 + 태그 심볼) → blur(2px)  [CoreImage/CoreGraphics]
//  3) PinShareCardView(베이지 오버레이 + 텍스트)를 ImageRenderer 로 1080×1350 PNG 추출
//
// ImageRenderer 는 MainActor 전용이라 enum 전체를 @MainActor 로 둔다. 네트워크는 await 로 양보하고,
// CoreImage 처리는 짧아(1080×1350 단발) 사용자 트리거 카드 생성에서 허용 범위다.
@MainActor
enum PinShareCardRenderer {

    /// 카드 PNG(UIImage) 생성. 지도 다운로드가 실패해도 단색 폴백 카드를 반환한다(nil 은 ImageRenderer 실패뿐).
    static func render(_ input: PinShareCardInput) async -> UIImage? {
        let background = await buildBackground(input)
        let view = PinShareCardView(input: input, background: background)
        let renderer = ImageRenderer(content: view)
        renderer.scale = 1
        renderer.proposedSize = ProposedViewSize(
            width: PinShareCardSpec.cardWidth,
            height: PinShareCardSpec.cardHeight
        )
        return renderer.uiImage
    }

    // MARK: - 배경 지도 처리

    /// 흑백 + 글리프 + blur 처리된 배경 지도. 토큰 미설정/다운로드 실패 시 nil(뷰가 단색 폴백).
    private static func buildBackground(_ input: PinShareCardInput) async -> UIImage? {
        guard MapConfig.isMapboxConfigured else { return nil }
        let token = MapConfig.accessToken
        let lat = input.pin.latitude
        let lng = input.pin.longitude

        // 1) 다운로드: streets-v12 → light-v11 (웹 동일 순서).
        var original = await fetchStaticMap(lat: lat, lng: lng, styleId: "mapbox/streets-v12", token: token)
        if original == nil {
            original = await fetchStaticMap(lat: lat, lng: lng, styleId: "mapbox/light-v11", token: token)
        }
        guard let map = original else { return nil }

        // 2) 흑백(채도0).
        let grayscale = desaturate(map) ?? map
        // 3) 글리프 합성(blur 전) — 카드 사이즈로 스케일하며 그린다.
        let composited = drawGlyphs(on: grayscale, input: input)
        // 4) blur(2px).
        return gaussianBlur(composited, radius: 2) ?? composited
    }

    /// Mapbox Static 이미지 다운로드(8초 timeout). 실패 시 nil(웹 loadImageWithTimeout 동치).
    private static func fetchStaticMap(
        lat: Double, lng: Double, styleId: String, token: String
    ) async -> UIImage? {
        let urlString = MapboxStaticURL.build(
            latitude: lat, longitude: lng,
            zoom: PinShareCardSpec.mapboxZoom,
            width: PinShareCardSpec.mapboxAPIWidth,
            height: PinShareCardSpec.mapboxAPIHeight,
            styleId: styleId, token: token
        )
        guard let url = URL(string: urlString) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = PinShareCardSpec.mapboxTimeout
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode) else { return nil }
            return UIImage(data: data)
        } catch {
            return nil
        }
    }

    /// 채도 0 흑백 변환(웹 luminance 흑백 동치). 원본 사이즈 유지(스케일은 drawGlyphs 단계).
    private static func desaturate(_ image: UIImage) -> UIImage? {
        guard let ciInput = CIImage(image: image) else { return nil }
        let filter = CIFilter.colorControls()
        filter.inputImage = ciInput
        filter.saturation = 0
        guard let output = filter.outputImage else { return nil }
        let context = CIContext()
        guard let cg = context.createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// 흑백 지도를 카드 사이즈로 그리고, 그 위에 핀 글리프(흰 원 + 태그 심볼)를 합성한다(웹 Step 4.5).
    private static func drawGlyphs(on grayMap: UIImage, input: PinShareCardInput) -> UIImage {
        let size = CGSize(width: PinShareCardSpec.cardWidth, height: PinShareCardSpec.cardHeight)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { ctx in
            let cg = ctx.cgContext
            // 배경 지도(흑백)를 카드 전체로 스케일.
            grayMap.draw(in: CGRect(origin: .zero, size: size))

            let centerLat = input.pin.latitude
            let centerLng = input.pin.longitude

            // 그룹 핀(자기 핀 제외, 최대 16개).
            let groupPins = input.groupPins
                .filter { $0.id != input.pin.id }
                .prefix(PinShareCardSpec.maxGroupPins)
            for gp in groupPins {
                if abs(gp.longitude - centerLng) > 180 { continue } // antimeridian skip
                let p = ShareGeoToPixel.apiPixel(
                    pinLat: gp.latitude, pinLng: gp.longitude,
                    centerLat: centerLat, centerLng: centerLng,
                    zoom: PinShareCardSpec.mapboxZoom,
                    apiW: Double(PinShareCardSpec.mapboxAPIWidth),
                    apiH: Double(PinShareCardSpec.mapboxAPIHeight)
                )
                let cx = CGFloat(p.x) * PinShareCardSpec.scaleX
                let cy = CGFloat(p.y) * PinShareCardSpec.scaleY
                if cx < -60 || cx > size.width + 60 || cy < -60 || cy > size.height + 60 { continue }
                drawMarker(
                    cx: cx, cy: cy,
                    bgRadius: PinShareCardSpec.bgRadiusOther,
                    glyphSize: PinShareCardSpec.glyphSizeOther,
                    tag: gp.tag, bgAlpha: 0.85
                )
            }

            // 자기 핀(항상 중앙) — 최상위.
            drawMarker(
                cx: size.width / 2, cy: size.height / 2,
                bgRadius: PinShareCardSpec.bgRadiusSelf,
                glyphSize: PinShareCardSpec.glyphSizeSelf,
                tag: input.pin.tag, bgAlpha: 0.95
            )
        }
    }

    /// 흰 원 배경 + 태그 글리프(SF Symbol) 1개 마커를 현재 컨텍스트에 그린다.
    private static func drawMarker(
        cx: CGFloat, cy: CGFloat, bgRadius: CGFloat, glyphSize: CGFloat, tag: PinTag, bgAlpha: CGFloat
    ) {
        let circleRect = CGRect(x: cx - bgRadius, y: cy - bgRadius, width: bgRadius * 2, height: bgRadius * 2)
        UIColor.white.withAlphaComponent(bgAlpha).setFill()
        UIBezierPath(ovalIn: circleRect).fill()

        let config = UIImage.SymbolConfiguration(pointSize: glyphSize, weight: .semibold)
        guard let symbol = UIImage(systemName: PinShareGlyph.symbolName(tag), withConfiguration: config) else { return }
        let tinted = symbol.withTintColor(UIColor(PinShareGlyph.color(tag)), renderingMode: .alwaysOriginal)
        let rect = CGRect(x: cx - glyphSize / 2, y: cy - glyphSize / 2, width: glyphSize, height: glyphSize)
        tinted.draw(in: rect)
    }

    /// Gaussian blur(radius≈2px, 웹 blur(2px) 근사). 가장자리 어두워짐 방지 위해 clamp 후 카드 영역으로 crop.
    private static func gaussianBlur(_ image: UIImage, radius: Double) -> UIImage? {
        guard let ciInput = CIImage(image: image) else { return nil }
        let clamped = ciInput.clampedToExtent()
        let filter = CIFilter.gaussianBlur()
        filter.inputImage = clamped
        filter.radius = Float(radius)
        guard let output = filter.outputImage else { return nil }
        let context = CIContext()
        let rect = CGRect(x: 0, y: 0, width: PinShareCardSpec.cardWidth, height: PinShareCardSpec.cardHeight)
        guard let cg = context.createCGImage(output, from: rect) else { return nil }
        return UIImage(cgImage: cg)
    }
}
