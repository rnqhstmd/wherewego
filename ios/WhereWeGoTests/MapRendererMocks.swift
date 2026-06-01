import Foundation
@testable import WhereWeGo

// MapRenderer 테스트 더블(설계 CONSIDER, MapViewModelTests AC-6/7 용).
// SDK 구현체(MapboxMapRenderer)와 짝을 이루는 두 번째 채택자로 프로토콜 추상화를 정당화한다.
// 호출을 배열로 기록하여 cameraCommand 소비(flyTo/fitBounds)·마커 동기화를 단언한다.

/// MapRenderer 목. 모든 호출을 순서대로 기록한다.
final class MockMapRenderer: MapRenderer {
    var eventHandler: ((MapEvent) -> Void)?

    /// setMarkers 로 전달된 마커 목록(호출 시마다 누적).
    private(set) var setMarkersCalls: [[MapMarker]] = []
    /// flyTo 로 전달된 카메라 타깃(호출 순서대로).
    private(set) var flyToCalls: [CameraTarget] = []
    /// fitBounds 호출 인자(markers/padding/maxZoom).
    private(set) var fitBoundsCalls: [(markers: [MapMarker], padding: Double, maxZoom: Double)] = []

    func setMarkers(_ markers: [MapMarker]) {
        setMarkersCalls.append(markers)
    }

    func flyTo(_ target: CameraTarget) {
        flyToCalls.append(target)
    }

    func fitBounds(_ markers: [MapMarker], padding: Double, maxZoom: Double) {
        fitBoundsCalls.append((markers: markers, padding: padding, maxZoom: maxZoom))
    }

    /// 테스트에서 지도 이벤트를 강제로 발생시켜 ViewModel 반응을 검증.
    func emit(_ event: MapEvent) {
        eventHandler?(event)
    }
}
