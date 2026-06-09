import Foundation

// Web Mercator(EPSG:3857) 투영. 웹 frontend/src/lib/share/geoToPixel.ts 이식.
// 핀 위경도를 Mapbox Static API 응답 이미지(apiW×apiH) 기준 픽셀 좌표로 변환한다.
// 카드 캔버스 좌표로의 변환(scaleX/scaleY 곱)은 호출처(PinShareCardRenderer)가 담당한다.
enum ShareGeoToPixel {

    /// 위도를 Mercator 발산 방지를 위해 ±84.9°로 clamp.
    static func clampLat(_ lat: Double) -> Double {
        max(-84.9, min(84.9, lat))
    }

    private static func mercatorX(_ lng: Double) -> Double {
        (lng + 180) / 360
    }

    private static func mercatorY(_ lat: Double) -> Double {
        let phi = clampLat(lat) * (Double.pi / 180)
        return 0.5 - log((1 + sin(phi)) / (1 - sin(phi))) / (4 * Double.pi)
    }

    /// API 원본 이미지(apiW×apiH) 좌표계 기준 픽셀 좌표.
    /// antimeridian(±180° 경계) 통과 감지 책임은 호출처(abs(pinLng-centerLng) > 180 skip).
    static func apiPixel(
        pinLat: Double,
        pinLng: Double,
        centerLat: Double,
        centerLng: Double,
        zoom: Int,
        apiW: Double,
        apiH: Double
    ) -> (x: Double, y: Double) {
        let scale = 256.0 * pow(2.0, Double(zoom))
        let dx = (mercatorX(pinLng) - mercatorX(centerLng)) * scale
        let dy = (mercatorY(pinLat) - mercatorY(centerLat)) * scale
        return (x: apiW / 2 + dx, y: apiH / 2 + dy)
    }
}
