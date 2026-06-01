import SwiftUI

// MUST-1 격리 단일 파일: `import MapboxMaps` 및 Mapbox 타입 참조는 오직 이 파일에만 존재한다.
// 검증 게이트: `grep -rl "import MapboxMaps" ios/WhereWeGo` == 이 파일 1개(설계 §1, MUST-1).
//
// 파일 전체를 `#if canImport(MapboxMaps)` 로 감싸:
//  - #if  : Mapbox SDK 실구현(MapboxMapView UIViewRepresentable + MapboxMapRenderer).
//  - #else: 동일 이름·동일 생성자 시그니처 stub(PlaceholderMapView 렌더 + no-op MapRenderer).
// → MapContainerView 는 `MapboxMapView` 타입만 참조하므로 SPM 추가 여부와 무관하게 컴파일된다.
//
// token 미설정(SPM 주석) 상태에서는 MapboxMaps 모듈이 부재 → canImport false → #else 만 컴파일된다.
// #if 실구현은 "작성 완료, 컴파일 검증은 token 발급 후"(DoD-B). frontend MapboxView.tsx 1:1 이식 목표.

#if canImport(MapboxMaps)
import MapboxMaps
import CoreLocation

// MARK: - 실구현(token 발급 후 컴파일·검증, DoD-B)

/// Mapbox MapView 를 SwiftUI 로 래핑(UIViewRepresentable).
/// 입력: 마커/카메라 명령/이벤트 콜백/스타일·토큰. frontend MapboxView.tsx 의 props 대응.
struct MapboxMapView: UIViewRepresentable {
    let markers: [MapMarker]
    @Binding var cameraCommand: CameraTarget?
    /// 다수 마커 일괄 표시 명령(FR-26). 소비 후 nil 리셋(cameraCommand 와 동일 1회 소비 계약).
    @Binding var fitBoundsCommand: [MapMarker]?
    let onEvent: (MapEvent) -> Void
    let styleURL: String
    let accessToken: String

    func makeCoordinator() -> Coordinator {
        Coordinator(onEvent: onEvent)
    }

    func makeUIView(context: Context) -> MapView {
        // 토큰 주입 + 스타일 적용으로 MapView 초기화(서울시청 기본 카메라는 ViewModel flyTo 로 보정).
        MapboxOptions.accessToken = accessToken
        let initOptions = MapInitOptions(
            styleURI: StyleURI(rawValue: styleURL) ?? .standard
        )
        let mapView = MapView(frame: .zero, mapInitOptions: initOptions)
        mapView.ornaments.options.scaleBar.visibility = .hidden
        context.coordinator.mapView = mapView

        // 스타일 로드 후 클러스터 소스/레이어 구성(FR-5). supercluster 동치(radius 60 / maxZoom 16 / minPoints 2).
        mapView.mapboxMap.onStyleLoaded.observe { [weak coordinator = context.coordinator] _ in
            coordinator?.installClusterLayers()
            coordinator?.syncMarkers(coordinator?.pendingMarkers ?? [])
        }.store(in: &context.coordinator.cancellables)

        // 클러스터/개별 마커 탭 → clusterTapped/markerTapped 이벤트(FR-5).
        mapView.gestures.delegate = context.coordinator
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleMapTap(_:)))
        mapView.addGestureRecognizer(tap)
        context.coordinator.tapRecognizer = tap

        // 카메라 멈춤(cameraIdle) 추적 — 크로스헤어/방문감지 중심 좌표(FR-15).
        mapView.mapboxMap.onCameraChanged.observe { [weak coordinator = context.coordinator] _ in
            guard let coordinator, let mv = coordinator.mapView else { return }
            coordinator.lastCenter = mv.mapboxMap.cameraState.center
        }.store(in: &context.coordinator.cancellables)
        mapView.mapboxMap.onMapIdle.observe { [weak coordinator = context.coordinator] _ in
            guard let coordinator, let center = coordinator.lastCenter else { return }
            coordinator.onEvent(.cameraIdle(centerLat: center.latitude, centerLng: center.longitude))
        }.store(in: &context.coordinator.cancellables)

        return mapView
    }

    func updateUIView(_ uiView: MapView, context: Context) {
        context.coordinator.onEvent = onEvent
        context.coordinator.syncMarkers(markers)

        // cameraCommand 소비: 비어있지 않으면 flyTo 후 바인딩 nil 로 리셋(설계 §3 1회 소비).
        if let command = cameraCommand {
            context.coordinator.fly(to: command)
            DispatchQueue.main.async {
                self.cameraCommand = nil
            }
        }

        // fitBoundsCommand 소비(FR-26): 다수 마커가 모두 보이도록 카메라 맞춤 후 nil 리셋.
        if let bounds = fitBoundsCommand, !bounds.isEmpty {
            context.coordinator.fitBounds(bounds)
            DispatchQueue.main.async {
                self.fitBoundsCommand = nil
            }
        }
    }

    // MARK: Coordinator

    /// 클러스터링 GeoJSON 소스/레이어를 보유하고 탭→이벤트, 카메라 명령을 SDK 호출로 위임.
    /// frontend MapboxView.tsx + _lib/clusterer.ts(supercluster radius 60 / maxZoom 16 / minPoints 2) 동치.
    final class Coordinator: NSObject, GestureManagerDelegate {
        var onEvent: (MapEvent) -> Void
        weak var mapView: MapView?
        var lastCenter: CLLocationCoordinate2D?
        var cancellables = Set<AnyCancelable>()
        var tapRecognizer: UITapGestureRecognizer?

        /// 스타일 로드 전 들어온 마커(로드 후 1회 반영).
        var pendingMarkers: [MapMarker] = []
        /// 클러스터 소스/레이어 설치 여부.
        private var clusterInstalled = false
        /// feature id 문자열 → pinId(Int) 역매핑(개별 마커 탭 변환).
        private var featureToPin: [String: Int] = [:]

        // 클러스터 GeoJSON 소스/레이어 식별자.
        private let sourceId = "wwg-pins"
        private let clusterCircleLayerId = "wwg-cluster-circle"
        private let clusterCountLayerId = "wwg-cluster-count"
        private let pinCircleLayerId = "wwg-pin-circle"

        init(onEvent: @escaping (MapEvent) -> Void) {
            self.onEvent = onEvent
        }

        /// 클러스터 소스/레이어 설치(FR-5). 스타일 로드 직후 1회. supercluster 옵션 동치.
        func installClusterLayers() {
            guard let mapView, !clusterInstalled else { return }
            let map = mapView.mapboxMap
            do {
                var source = GeoJSONSource(id: sourceId)
                source.data = .featureCollection(FeatureCollection(features: []))
                source.cluster = true
                source.clusterRadius = 60      // clusterer.ts radius
                source.clusterMaxZoom = 16     // clusterer.ts maxZoom
                source.clusterMinPoints = 2    // clusterer.ts minPoints
                try map.addSource(source)

                // 클러스터 원(rust 톤) — cluster == true 인 feature.
                var clusterCircle = CircleLayer(id: clusterCircleLayerId, source: sourceId)
                clusterCircle.filter = Exp(.has) { "point_count" }
                clusterCircle.circleColor = .constant(StyleColor(UIColor(WGColor.cta)))
                clusterCircle.circleRadius = .constant(32)   // PRD FR-5 rust 32px
                clusterCircle.circleStrokeColor = .constant(StyleColor(.white))
                clusterCircle.circleStrokeWidth = .constant(2)
                try map.addLayer(clusterCircle)

                // 클러스터 숫자(point_count).
                var clusterCount = SymbolLayer(id: clusterCountLayerId, source: sourceId)
                clusterCount.filter = Exp(.has) { "point_count" }
                clusterCount.textField = .expression(Exp(.get) { "point_count_abbreviated" })
                clusterCount.textSize = .constant(13)
                clusterCount.textColor = .constant(StyleColor(.white))
                try map.addLayer(clusterCount)

                // 개별 핀 원(태그별 색) — point_count 없는 feature.
                var pinCircle = CircleLayer(id: pinCircleLayerId, source: sourceId)
                pinCircle.filter = Exp(.not) { Exp(.has) { "point_count" } }
                pinCircle.circleColor = .expression(tagColorExpression())
                pinCircle.circleRadius = .constant(9)
                pinCircle.circleStrokeColor = .constant(StyleColor(.white))
                pinCircle.circleStrokeWidth = .constant(2)
                try map.addLayer(pinCircle)

                clusterInstalled = true
            } catch {
                // 스타일 미준비/중복 추가 등은 다음 onStyleLoaded 에서 재시도.
            }
        }

        /// 마커 전량 동기화(FR-5): GeoJSON FeatureCollection 으로 소스 갱신 → supercluster 가 클러스터링.
        func syncMarkers(_ markers: [MapMarker]) {
            pendingMarkers = markers
            guard let mapView, clusterInstalled else { return }
            featureToPin.removeAll()
            var features: [Feature] = []
            for marker in markers {
                let point = Point(CLLocationCoordinate2D(latitude: marker.latitude, longitude: marker.longitude))
                var feature = Feature(geometry: .point(point))
                let fid = String(marker.id)
                feature.identifier = .string(fid)
                feature.properties = ["pinId": .number(Double(marker.id)), "tag": .string(marker.tag.rawValue)]
                featureToPin[fid] = marker.id
                features.append(feature)
            }
            mapView.mapboxMap.updateGeoJSONSource(withId: sourceId, geoJSON: .featureCollection(FeatureCollection(features: features)))
        }

        /// 카메라 flyTo 애니메이션(durationMs 반영).
        func fly(to target: CameraTarget) {
            guard let mapView else { return }
            let cameraOptions = CameraOptions(
                center: CLLocationCoordinate2D(latitude: target.latitude, longitude: target.longitude),
                zoom: target.zoom
            )
            mapView.camera.fly(to: cameraOptions, duration: Double(target.durationMs) / 1000.0)
        }

        /// 다수 마커 일괄 표시(FR-26): camera(for:) 로 bounds 계산 후 flyTo.
        func fitBounds(_ markers: [MapMarker]) {
            guard let mapView, !markers.isEmpty else { return }
            let coordinates = markers.map {
                CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
            }
            if let cameraOptions = try? mapView.mapboxMap.camera(
                for: coordinates,
                camera: CameraOptions(),
                coordinatesPadding: UIEdgeInsets(top: 60, left: 60, bottom: 60, right: 60),
                maxZoom: 16,
                offset: nil
            ) {
                mapView.camera.fly(to: cameraOptions, duration: 0.7)
            }
        }

        // MARK: 탭 처리(클러스터 → clusterTapped, 개별 → markerTapped)

        @objc func handleMapTap(_ recognizer: UITapGestureRecognizer) {
            guard let mapView else { return }
            let point = recognizer.location(in: mapView)
            let options = RenderedQueryOptions(
                layerIds: [clusterCircleLayerId, clusterCountLayerId, pinCircleLayerId],
                filter: nil
            )
            mapView.mapboxMap.queryRenderedFeatures(with: point, options: options) { [weak self] result in
                guard let self, case let .success(features) = result, let first = features.first else { return }
                let props = first.queriedFeature.feature.properties
                if let pointCountValue = props?["point_count"], case .number = pointCountValue {
                    // 클러스터 탭 → 포함 핀 id 수집 → clusterTapped(FR-5).
                    self.handleClusterTap(first.queriedFeature.feature)
                } else if let pinIdValue = props?["pinId"], case let .number(pinId) = pinIdValue {
                    self.onEvent(.markerTapped(Int(pinId)))
                }
            }
        }

        /// 클러스터 leaves 조회 → 포함 pinId 목록으로 clusterTapped 이벤트.
        private func handleClusterTap(_ feature: Feature) {
            guard let mapView else { return }
            mapView.mapboxMap.getGeoJsonClusterLeaves(forSourceId: sourceId, feature: feature, limit: 100, offset: 0) { [weak self] result in
                guard let self, case let .success(featureExtension) = result else { return }
                let pinIds: [Int] = featureExtension.features?.compactMap { f in
                    if let v = f.properties?["pinId"], case let .number(n) = v { return Int(n) }
                    return nil
                } ?? []
                guard !pinIds.isEmpty else { return }
                self.onEvent(.clusterTapped(pinIds))
            }
        }

        // GestureManagerDelegate(필수 메서드 no-op — 제스처 시작/종료 콜백).
        func gestureManager(_ gestureManager: GestureManager, didBegin gestureType: GestureType) {}
        func gestureManager(_ gestureManager: GestureManager, didEnd gestureType: GestureType, willAnimate: Bool) {}
        func gestureManager(_ gestureManager: GestureManager, didEndAnimatingFor gestureType: GestureType) {}

        /// 태그별 개별 핀 색 표현식(REEL/WISH/MEMORY). frontend MapboxView.tsx 마커 색 대응.
        private func tagColorExpression() -> Exp {
            Exp(.match) {
                Exp(.get) { "tag" }
                PinTag.REEL.rawValue
                StyleColor(UIColor(WGColor.pinReel)).rawValue
                PinTag.WISH.rawValue
                StyleColor(UIColor(WGColor.pinWish)).rawValue
                PinTag.MEMORY.rawValue
                StyleColor(UIColor(WGColor.pinMemory)).rawValue
                StyleColor(UIColor(WGColor.cta)).rawValue
            }
        }
    }
}

/// MapRenderer SDK 구현체(설계 §1 규칙3). MapView 를 보유하고 프로토콜 메서드를 SDK 호출로 위임.
final class MapboxMapRenderer: MapRenderer {
    var eventHandler: ((MapEvent) -> Void)?
    private weak var mapView: MapView?
    private var pointManager: PointAnnotationManager?

    init(mapView: MapView) {
        self.mapView = mapView
        self.pointManager = mapView.annotations.makePointAnnotationManager()
    }

    func setMarkers(_ markers: [MapMarker]) {
        guard let pointManager else { return }
        pointManager.annotations = markers.map { marker in
            PointAnnotation(
                coordinate: CLLocationCoordinate2D(latitude: marker.latitude, longitude: marker.longitude)
            )
        }
    }

    func flyTo(_ target: CameraTarget) {
        guard let mapView else { return }
        mapView.camera.fly(
            to: CameraOptions(
                center: CLLocationCoordinate2D(latitude: target.latitude, longitude: target.longitude),
                zoom: target.zoom
            ),
            duration: Double(target.durationMs) / 1000.0
        )
    }

    func fitBounds(_ markers: [MapMarker], padding: Double, maxZoom: Double) {
        guard let mapView, !markers.isEmpty else { return }
        let coordinates = markers.map {
            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
        }
        if let cameraOptions = try? mapView.mapboxMap.camera(
            for: coordinates,
            camera: CameraOptions(),
            coordinatesPadding: UIEdgeInsets(top: padding, left: padding, bottom: padding, right: padding),
            maxZoom: maxZoom,
            offset: nil
        ) {
            mapView.camera.fly(to: cameraOptions, duration: 0.7)
        }
    }
}

#else

// MARK: - Stub(token 미설정 시 컴파일 — 동일 이름·시그니처)
// MapContainerView 가 분기 무관하게 컴파일되도록 #if 실구현과 동일한 공개 시그니처를 유지한다.

/// MapboxMapView stub: Mapbox 모듈 부재 시 PlaceholderMapView 를 렌더한다.
/// 생성자 시그니처는 #if 실구현과 정확히 동일(markers/cameraCommand/fitBoundsCommand/onEvent/styleURL/accessToken).
struct MapboxMapView: View {
    let markers: [MapMarker]
    @Binding var cameraCommand: CameraTarget?
    @Binding var fitBoundsCommand: [MapMarker]?
    let onEvent: (MapEvent) -> Void
    let styleURL: String
    let accessToken: String

    var body: some View {
        PlaceholderMapView()
    }
}

/// MapboxMapRenderer stub: no-op MapRenderer(SDK 부재). 시그니처는 #if 실구현과 동일.
final class MapboxMapRenderer: MapRenderer {
    var eventHandler: ((MapEvent) -> Void)?

    func setMarkers(_ markers: [MapMarker]) {}
    func flyTo(_ target: CameraTarget) {}
    func fitBounds(_ markers: [MapMarker], padding: Double, maxZoom: Double) {}
}

#endif
