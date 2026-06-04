import CoreGraphics
import Foundation

// 좌표 거리·BBox 계산 순수 로직(설계 §4).
// frontend/src/app/map/_lib/roulette.ts(haversineKm) +
// frontend/src/app/map/_hooks/useVisitDetection.ts(BBox prefilter) 와 동치 이식.
// SwiftUI/CoreLocation 의존 없이 결정적으로 테스트 가능한 순수 함수만 보유.
// (CGSize 는 CoreGraphics — 화면밖 판정용. MapboxMaps 격리와 무관.)

/// 위경도 좌표. 백엔드 BigDecimal → Double 수용(7자리 반올림 안전, 설계 CONSIDER).
struct Coordinate: Equatable {
    let latitude: Double
    let longitude: Double
}

enum GeoMath {
    /// 지구 반경(km). 웹 roulette.ts 와 동일.
    private static let earthRadiusKm = 6371.0

    /// BBox 사전 필터 기준: 위도 1도 ≈ 111,320m(useVisitDetection.ts LAT_DEG_PER_METER).
    private static let latDegPerMeter = 1.0 / 111_320.0

    /// 두 좌표 사이 거리(km, Haversine 공식, 지구 반경 6371km).
    /// roulette.ts `haversineKm` 1:1 이식.
    static func haversineKm(_ a: Coordinate, _ b: Coordinate) -> Double {
        let dLat = toRadians(b.latitude - a.latitude)
        let dLng = toRadians(b.longitude - a.longitude)
        let sinDLat = sin(dLat / 2)
        let sinDLng = sin(dLng / 2)
        let h =
            sinDLat * sinDLat
            + cos(toRadians(a.latitude)) * cos(toRadians(b.latitude)) * sinDLng * sinDLng
        return 2 * earthRadiusKm * asin(min(1, sqrt(h)))
    }

    /// BBox 사전 필터. center 기준 radiusMeters 반경의 경위도 박스 안에 point 가 들면 true.
    /// useVisitDetection.ts BBox prefilter(latDelta/lngDelta, cos 하한 0.01) 와 동치.
    /// - 위도: ±(radiusMeters × LAT_DEG_PER_METER)
    /// - 경도: ±(radiusMeters × LAT_DEG_PER_METER / max(|cos(lat)|, 0.01))
    /// 정밀 원 필터(haversine) 전 비용을 줄이기 위한 1차 컷.
    static func bboxContains(center: Coordinate, point: Coordinate, radiusMeters: Double) -> Bool {
        let latDelta = radiusMeters * latDegPerMeter
        let cosLat = cos(toRadians(center.latitude))
        // 적도 근접/극단값 안전: cos 가 0 이 되지 않도록 하한 0.01.
        let lngDelta = radiusMeters * latDegPerMeter / max(abs(cosLat), 0.01)
        if abs(point.latitude - center.latitude) > latDelta { return false }
        // antimeridian(180도 자오선) 보정: 경도 차이가 180도를 넘으면 반대편을 돌아 360 - diff 로 정규화.
        // (예: 179.9 ↔ -179.9 의 실제 차이는 359.8 이 아니라 0.2 도.)
        var lngDiff = abs(point.longitude - center.longitude)
        if lngDiff > 180 { lngDiff = 360 - lngDiff }
        if lngDiff > lngDelta { return false }
        return true
    }

    /// 화면점이 지도 뷰 영역 안에 있는지 판정(AC-14, 순수). bboxContains 패턴 동형.
    /// 밖이면 말풍선 숨김(D-3 clamp 없음 — 화면밖은 nil 로 숨기고 복귀 시 재표시).
    /// - margin: 가장자리 여유(pt). 양수면 영역을 넓혀 경계 직전까지 보이게, 0 이면 정확히 [0, size] 닫힌 구간.
    /// 경계값(0/size)은 포함(closed). size 가 0 이하면 항상 false(투영 불가 상태 방어).
    static func isPointVisible(_ p: ScreenPoint, in size: CGSize, margin: Double = 0) -> Bool {
        guard size.width > 0, size.height > 0 else { return false }
        if p.x < -margin || p.x > Double(size.width) + margin { return false }
        if p.y < -margin || p.y > Double(size.height) + margin { return false }
        return true
    }

    private static func toRadians(_ degrees: Double) -> Double {
        degrees * Double.pi / 180
    }
}
