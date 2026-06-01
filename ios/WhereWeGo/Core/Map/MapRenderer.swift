import Foundation

// 지도 렌더 추상화(설계 §1, FR-1, AC-1) — MUST-1 격리 규칙의 SDK 비의존 코어.
// frontend/src/app/map/_components/MapboxView.tsx 의 카메라/마커 인터페이스를 SDK 중립 형태로 옮긴다.
//
// 이 파일은 어떤 SDK(MapboxMaps 등)에도 의존하지 않으며 token 없이 항상 컴파일된다.
// Mapbox 타입 참조는 오직 MapboxMapView.swift 1개 파일에만 존재한다(MUST-1).
// ViewModel/View 는 구체 SDK 구현체가 아닌 이 MapRenderer 프로토콜 타입만 다룬다(규칙3).

/// 지도 위 핀 1개의 렌더 표현. id/좌표/태그만 보유(색·심볼 매핑은 렌더러 내부).
struct MapMarker {
    let id: Int
    let latitude: Double
    let longitude: Double
    let tag: PinTag
}

/// 카메라 이동 목표. flyTo 애니메이션 대상(설계 §3 cameraCommand).
struct CameraTarget {
    let latitude: Double
    let longitude: Double
    let zoom: Double
    /// 애니메이션 지속(ms). 기본 700ms.
    var durationMs: Int = 700
}

/// 지도에서 올라오는 사용자 상호작용 이벤트. ViewModel 이 소비.
enum MapEvent {
    /// 단일 마커 탭(pinId).
    case markerTapped(Int)
    /// 클러스터 탭(포함 pinId 목록, FR-5 Should).
    case clusterTapped([Int])
    /// 카메라 이동이 멈춘 시점의 중심 좌표(크로스헤어/방문감지 좌표 추적용).
    case cameraIdle(centerLat: Double, centerLng: Double)
}

/// 지도 렌더러 추상 인터페이스. SDK 구현체(MapboxMapRenderer)와 테스트용 MockMapRenderer 가 채택.
/// View/ViewModel 은 이 프로토콜 타입으로만 지도를 제어한다(MUST-1 규칙3).
protocol MapRenderer: AnyObject {
    /// 현재 표시할 마커 전체를 동기화(diff 는 구현체 책임).
    func setMarkers(_ markers: [MapMarker])
    /// 단일 카메라 이동 애니메이션.
    func flyTo(_ target: CameraTarget)
    /// 다수 마커가 모두 보이도록 카메라 맞춤(FR-26 Should).
    func fitBounds(_ markers: [MapMarker], padding: Double, maxZoom: Double)
    /// 지도 → ViewModel 이벤트 콜백.
    var eventHandler: ((MapEvent) -> Void)? { get set }
}
