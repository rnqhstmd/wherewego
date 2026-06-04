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

// `MapView` 이름이 WhereWeGo 의 SwiftUI MapView(struct, Features/Map)와 충돌하므로
// Mapbox 네이티브 MapView(UIView)를 별칭으로 명시한다(같은 모듈 struct 가 우선 바인딩되는 것 회피).
typealias MBMapView = MapboxMaps.MapView

// MARK: - 실구현(token 발급 후 컴파일·검증, DoD-B)

/// Mapbox MapView 를 SwiftUI 로 래핑(UIViewRepresentable).
/// 입력: 마커/카메라 명령/이벤트 콜백/스타일·토큰. frontend MapboxView.tsx 의 props 대응.
struct MapboxMapView: UIViewRepresentable {
    let markers: [MapMarker]
    @Binding var cameraCommand: CameraTarget?
    /// 다수 마커 일괄 표시 명령(FR-26). 소비 후 nil 리셋(cameraCommand 와 동일 1회 소비 계약).
    @Binding var fitBoundsCommand: [MapMarker]?
    let onEvent: (MapEvent) -> Void
    /// 추적 대상 선택핀 좌표(말풍선 앵커, MUST-ADDRESS③ 게이팅). nil 이면 onCameraChanged 에서 투영·방출 skip.
    let selectedPin: (latitude: Double, longitude: Double)?
    let styleURL: String
    let accessToken: String

    func makeCoordinator() -> Coordinator {
        Coordinator(onEvent: onEvent)
    }

    func makeUIView(context: Context) -> MBMapView {
        // 토큰 주입 + 스타일 적용으로 MapView 초기화(서울시청 기본 카메라는 ViewModel flyTo 로 보정).
        MapboxOptions.accessToken = accessToken
        let initOptions = MapInitOptions(
            styleURI: StyleURI(rawValue: styleURL) ?? .standard
        )
        let mapView = MBMapView(frame: .zero, mapInitOptions: initOptions)
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
        // + 선택핀 추적(MUST-ADDRESS③): trackedPinCoordinate 있을 때만 투영→cameraMoved 방출(게이팅 a).
        // 화면밖 판정·distinct 는 VM 책임 — 여기선 raw 투영값만 운반(throttle 없음, 점1개 투영 비용).
        mapView.mapboxMap.onCameraChanged.observe { [weak coordinator = context.coordinator] _ in
            guard let coordinator, let mv = coordinator.mapView else { return }
            coordinator.lastCenter = mv.mapboxMap.cameraState.center
            guard let tracked = coordinator.trackedPinCoordinate else { return }
            let screenPoint = coordinator.screenPoint(for: tracked)
            coordinator.onEvent(.cameraMoved(screenPoint: screenPoint))
        }.store(in: &context.coordinator.cancellables)
        mapView.mapboxMap.onMapIdle.observe { [weak coordinator = context.coordinator] _ in
            guard let coordinator, let mv = coordinator.mapView, let center = coordinator.lastCenter else { return }
            // idle 시점의 줌을 함께 전달(FR-11 인라인 줌인 판단). center 와 동일 cameraState 출처.
            coordinator.onEvent(.cameraIdle(
                centerLat: center.latitude,
                centerLng: center.longitude,
                zoom: mv.mapboxMap.cameraState.zoom
            ))
        }.store(in: &context.coordinator.cancellables)

        return mapView
    }

    func updateUIView(_ uiView: MBMapView, context: Context) {
        context.coordinator.onEvent = onEvent
        context.coordinator.syncMarkers(markers)

        // 선택핀 추적 좌표 갱신(MUST-ADDRESS③ 게이팅 입력). 선택 해제 시 nil → onCameraChanged 가 방출 skip.
        // 탭 경로(markerTapped)는 좌표를 즉시 운반하지만, 프로그래밍 선택(visitMemo onDismiss 승격·룰렛/검색 결과 등
        // markerTapped 없이 selectedPinId 만 set)은 운반자가 없어 onCameraChanged 전까지 말풍선이 안 뜬다(G1).
        // → selectedPin 좌표가 직전과 다르게 바뀐 "첫 갱신"에만 투영해 cameraMoved 를 방출한다.
        //   QE-1: 매 updateUIView(부모 body 재평가) 마다 방출하지 않도록 lastTrackedCoordinate 와 비교해 변경 시에만 수행.
        if let selectedPin {
            let coordinate = CLLocationCoordinate2D(
                latitude: selectedPin.latitude, longitude: selectedPin.longitude
            )
            context.coordinator.trackedPinCoordinate = coordinate
            // 게이팅 판정·lastTrackedCoordinate 갱신은 동기 유지(매 updateUIView 중복 방출 차단, QE-1).
            if !coordinatesEqual(context.coordinator.lastTrackedCoordinate, coordinate) {
                context.coordinator.lastTrackedCoordinate = coordinate
                // 투영+방출만 다음 런루프로 지연(M1): visitMemo 닫힘 직후 등 카메라/레이아웃 안정화 전에 투영하면
                // 화면밖 좌표가 나와 말풍선이 안 뜨므로, async 블록 안에서 안정화 후 기준으로 재투영해 자동 회복성을 확보한다.
                // coordinate(값타입)·coordinator(클래스) 명시 캡처 — async 시점에 최신 카메라로 투영.
                DispatchQueue.main.async { [coordinator = context.coordinator, coordinate] in
                    if let screenPoint = coordinator.screenPoint(for: coordinate) {
                        coordinator.onEvent(.cameraMoved(screenPoint: screenPoint))
                    }
                }
            }
        } else {
            context.coordinator.trackedPinCoordinate = nil
            context.coordinator.lastTrackedCoordinate = nil
        }

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

    /// 추적 좌표 변경 비교(G1, CLLocationCoordinate2D 는 Equatable 아님). 같은 핀이면 동일 Double 값이 들어와 정확 일치한다.
    private func coordinatesEqual(_ lhs: CLLocationCoordinate2D?, _ rhs: CLLocationCoordinate2D?) -> Bool {
        switch (lhs, rhs) {
        case (nil, nil): return true
        case let (l?, r?): return l.latitude == r.latitude && l.longitude == r.longitude
        default: return false
        }
    }

    // MARK: Coordinator

    /// 클러스터링 GeoJSON 소스/레이어를 보유하고 탭→이벤트, 카메라 명령을 SDK 호출로 위임.
    /// frontend MapboxView.tsx + _lib/clusterer.ts(supercluster radius 60 / maxZoom 16 / minPoints 2) 동치.
    final class Coordinator: NSObject, GestureManagerDelegate {
        var onEvent: (MapEvent) -> Void
        weak var mapView: MBMapView?
        var lastCenter: CLLocationCoordinate2D?
        /// 추적 중인 선택핀 좌표(MUST-ADDRESS③ 게이팅). nil 이면 onCameraChanged 가 투영·방출 skip.
        var trackedPinCoordinate: CLLocationCoordinate2D?
        /// updateUIView 에서 좌표 변경을 감지하기 위한 직전 추적 좌표(G1). 변경된 첫 갱신에만 투영·방출(QE-1).
        /// selectedPin 이 nil 로 바뀌면 함께 nil 로 리셋한다.
        var lastTrackedCoordinate: CLLocationCoordinate2D?
        // AnyCancelable = MapboxMaps SDK 타입(Combine 의 AnyCancellable 아님). observe API 반환.
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
            let map: MapboxMap = mapView.mapboxMap
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
                let feature = first.queriedFeature.feature
                let props = feature.properties
                if let pointCountValue = props?["point_count"], case .number = pointCountValue {
                    // 클러스터 탭 → 포함 핀 id 수집 → clusterTapped(FR-5).
                    self.handleClusterTap(feature)
                } else if let pinIdValue = props?["pinId"], case let .number(pinId) = pinIdValue {
                    // 마커 중심 좌표를 화면점으로 투영해 운반(MUST-ADDRESS②) — 탭과 동시 말풍선 앵커(지연 0).
                    // feature geometry(마커 중심) 우선, 실패 시 탭 지점(point) 폴백.
                    let screenPoint = self.markerScreenPoint(feature: feature) ?? ScreenPoint(x: Double(point.x), y: Double(point.y))
                    self.onEvent(.markerTapped(pinId: Int(pinId), screenPoint: screenPoint))
                }
            }
        }

        /// feature 의 Point geometry(마커 중심 좌표)를 화면점으로 투영. geometry/투영 실패 시 nil(탭 지점 폴백).
        private func markerScreenPoint(feature: Feature) -> ScreenPoint? {
            guard case let .point(point) = feature.geometry else { return nil }
            return screenPoint(for: point.coordinates)
        }

        /// 위경도 좌표를 mapView.bounds 로컬 화면점(논리 pt, 원점 좌상단)으로 투영(MUST-ADDRESS②/④).
        /// 결과가 뷰 밖이어도 raw 값을 그대로 반환 — 안/밖 판정은 VM(GeoMath.isPointVisible) 책임.
        func screenPoint(for coordinate: CLLocationCoordinate2D) -> ScreenPoint? {
            guard let mapView else { return nil }
            let cgPoint = mapView.mapboxMap.point(for: coordinate)
            // 투영 불가(지구 반대편 등) 시 SDK 가 (-1,-1) 류 sentinel 을 줄 수 있어 NaN/무한대만 차단한다.
            guard cgPoint.x.isFinite, cgPoint.y.isFinite else { return nil }
            return ScreenPoint(x: Double(cgPoint.x), y: Double(cgPoint.y))
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
    private weak var mapView: MBMapView?
    private var pointManager: PointAnnotationManager?

    init(mapView: MBMapView) {
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

    func point(for latitude: Double, longitude: Double) -> ScreenPoint? {
        guard let mapView else { return nil }
        let cgPoint = mapView.mapboxMap.point(for: CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
        guard cgPoint.x.isFinite, cgPoint.y.isFinite else { return nil }
        return ScreenPoint(x: Double(cgPoint.x), y: Double(cgPoint.y))
    }
}

#else

// MARK: - Stub(token 미설정 시 컴파일 — 동일 이름·시그니처)
// MapContainerView 가 분기 무관하게 컴파일되도록 #if 실구현과 동일한 공개 시그니처를 유지한다.

/// MapboxMapView stub: Mapbox 모듈 부재 시 PlaceholderMapView 를 렌더한다.
/// 생성자 시그니처는 #if 실구현과 정확히 동일(markers/cameraCommand/fitBoundsCommand/onEvent/selectedPin/styleURL/accessToken).
/// stub 은 좌표 투영이 없으므로 selectedPin 을 받기만 하고 사용하지 않는다(AC-10 selectedPin 영원 nil 경로).
struct MapboxMapView: View {
    let markers: [MapMarker]
    @Binding var cameraCommand: CameraTarget?
    @Binding var fitBoundsCommand: [MapMarker]?
    let onEvent: (MapEvent) -> Void
    let selectedPin: (latitude: Double, longitude: Double)?
    let styleURL: String
    let accessToken: String

    var body: some View {
        PlaceholderMapView()
    }
}

/// MapboxMapRenderer stub: no-op MapRenderer(SDK 부재). 시그니처는 #if 실구현과 동일.
/// point(for:) 는 항상 nil — stub 에선 투영 불가하므로 말풍선이 표시되지 않는다(AC-10).
final class MapboxMapRenderer: MapRenderer {
    var eventHandler: ((MapEvent) -> Void)?

    func setMarkers(_ markers: [MapMarker]) {}
    func flyTo(_ target: CameraTarget) {}
    func fitBounds(_ markers: [MapMarker], padding: Double, maxZoom: Double) {}
    func point(for latitude: Double, longitude: Double) -> ScreenPoint? { nil }
}

#endif
