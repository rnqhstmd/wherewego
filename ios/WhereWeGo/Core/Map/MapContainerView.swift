import SwiftUI

// 지도 분기 컨테이너(설계 §1, MUST-1 규칙2). `#if` 없이 항상 컴파일된다.
// MapConfig.isMapboxConfigured 여부로 MapboxMapView ↔ PlaceholderMapView 를 선택한다.
//
// 이 파일은 Mapbox 타입을 이름으로도 참조하지 않는다(MUST-1):
//  - `MapboxMapView` 는 MapboxMapView.swift 가 #if/#else 양쪽에서 동일 시그니처로 제공하는 타입이므로
//    SPM 추가 여부와 무관하게 이름 참조가 컴파일된다(SDK import 는 그 파일 내부에만 존재).
//  - 스타일/토큰은 MapConfig 단일 진입점에서 읽는다.
struct MapContainerView: View {
    let markers: [MapMarker]
    @Binding var cameraCommand: CameraTarget?
    /// 다수 마커 일괄 표시 명령(FR-26). MapboxMapView 가 fitBounds 후 nil 로 리셋(1회 소비).
    @Binding var fitBoundsCommand: [MapMarker]?
    let onEvent: (MapEvent) -> Void

    var body: some View {
        if MapConfig.isMapboxConfigured {
            MapboxMapView(
                markers: markers,
                cameraCommand: $cameraCommand,
                fitBoundsCommand: $fitBoundsCommand,
                onEvent: onEvent,
                styleURL: MapConfig.styleURL,
                accessToken: MapConfig.accessToken
            )
        } else {
            PlaceholderMapView()
        }
    }
}
