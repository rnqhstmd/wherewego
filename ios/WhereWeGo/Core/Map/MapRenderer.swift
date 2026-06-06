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

/// 좌표→화면 투영 결과(논리 pt, 원점 좌상단). 말풍선 앵커(.position)·화면밖 판정의 입력.
/// SDK(CGPoint)·UIKit 비노출 — MUST-1 격리(Double 만 운반). MapboxMapView 가 CGPoint↔ScreenPoint 변환.
/// frontend/src/app/map/_components/PinPopup.tsx 의 map.project([lng,lat]) 화면좌표 동치.
struct ScreenPoint: Equatable {
    let x: Double
    let y: Double
}

/// 카메라 이동 목표. flyTo 애니메이션 대상(설계 §3 cameraCommand).
struct CameraTarget {
    let latitude: Double
    let longitude: Double
    let zoom: Double
    /// 애니메이션 지속(ms). 기본 700ms.
    var durationMs: Int = 700
    /// 화면에서 center 좌표가 보일 세로 위치(0=상단, 0.5=중앙, 1=하단). 기본 중앙.
    /// 사진 펼침 시 핀을 아래(>0.5)로 내려 말풍선이 그 위로 자랄 공간을 만든다(핀 가림 방지).
    var focusYFraction: Double = 0.5
}

/// 지도에서 올라오는 사용자 상호작용 이벤트. ViewModel 이 소비.
enum MapEvent {
    /// 단일 마커 탭(pinId + 마커 중심 화면좌표). screenPoint 로 말풍선을 탭 즉시 앵커(지연 0, MUST-ADDRESS②).
    /// 투영 실패 시 screenPoint nil(VM 이 화면밖 처리와 동일하게 흡수).
    case markerTapped(pinId: Int, screenPoint: ScreenPoint?)
    /// 클러스터 탭(포함 pinId 목록, FR-5 Should).
    case clusterTapped([Int])
    /// 빈 지도(마커/클러스터에 안 맞은) 탭. 선택핀 말풍선이 열려 있으면 선택 해제로 닫는다(#4).
    /// PinBubbleView 전체화면 배경탭 제거 대체 — 말풍선 열린 채로 지도 드래그/줌을 허용한다.
    case mapTapped
    /// 카메라 이동이 멈춘 시점의 중심 좌표 + 줌(크로스헤어/방문감지 좌표 추적 + FR-11 인라인 줌인 판단).
    case cameraIdle(centerLat: Double, centerLng: Double, zoom: Double)
    /// 선택핀 추적 중 카메라 변화로 갱신된 선택핀 화면좌표(QE-1 게이팅: 추적 좌표 있을 때만 방출).
    /// 화면밖이어도 raw 투영값을 그대로 운반 — 안/밖 판정·distinct 는 VM 책임(MUST-ADDRESS③④).
    case cameraMoved(screenPoint: ScreenPoint?)
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
    /// 위경도 좌표를 현재 카메라 기준 화면점(논리 pt, 원점 좌상단)으로 투영(MUST-ADDRESS②).
    /// 좌표가 지도 뷰 밖이거나 투영 불가면 nil. SDK stub(MapboxMapRenderer #else)·Mock 은 nil 반환.
    func point(for latitude: Double, longitude: Double) -> ScreenPoint?
    /// 지도 → ViewModel 이벤트 콜백.
    var eventHandler: ((MapEvent) -> Void)? { get set }
}
