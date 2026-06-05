import CoreGraphics
import Foundation

// 지도 메인 ViewModel(설계 §3, FR-2/6/7, AC-4/6/7). 웹 MapClient.tsx 의 상태/낙관적 업데이트 이식.
// frontend/src/app/map/MapClient.tsx 의 pins/filter/optimistic(patch/remove/append) 흐름을 SwiftUI ObservableObject 로 옮긴다.
//
// 책임:
//  - 활성 그룹 확보(myActiveGroup) → 핀 목록 로드(BR-7 낙관적 업데이트 기반 상태).
//  - 태그 필터(activeFilters) → visiblePins → markers(MapContainerView 선언적 바인딩, MUST-1 규칙3).
//  - 카메라 명령(cameraCommand): MapContainerView 가 소비 후 nil 로 리셋(B2 계약).
//  - 낙관적 PATCH/DELETE 와 실패 시 스냅샷 복원(AC-6/7) — B4(PinDetail/Search/Roulette)가 호출.
//  - 동시 1패널(activeSheet) 상태 — 실제 시트 표시는 B4 가 연결.
// 비책임(B4): VisitDetectionEngine 오케스트레이션(여기서는 locationService 보유·startUpdating 훅 자리만),
//            PinBubbleView(핀 상세 말풍선)/RouletteView(어디갈까 탭)의 실제 UI·로직.
// P8 영역1: 인라인 핀 추가 모드 상태(isAddingPin/addPlaceVM/mapZoom)와 AddPlaceViewModel 수명을 본 VM 이 소유한다.
//
// 지도 제어는 MapRenderer 프로토콜이 아니라 선언적 바인딩(markers + cameraCommand)으로 한다(B2 계약).
// VM 은 renderer 인스턴스를 직접 보유하지 않는다(MockMapRenderer 는 markers/cameraCommand 산출 검증에만 사용).
@MainActor
final class MapViewModel: ObservableObject {

    /// 핀 로드 상태(QE-2 재시도 가능). idle → loading → loaded/error.
    enum LoadState: Equatable {
        case idle
        case loading
        case loaded
        case error(String)
    }

    /// 동시에 하나만 표시되는 패널(설계 §3·§11).
    /// P8 영역1: ＋ 장소 추가는 시트가 아닌 인라인 오버레이(isAddingPin)로 전환되어 .addPlace case 제거(AC-11).
    /// 룰렛은 "어디갈까" 탭(RouletteView)으로 분리되어 .roulette case 제거 — 시트는 .visitMemo 만 남는다.
    enum ActiveSheet: Equatable {
        case none
        /// 방문 "다녀왔어요" 후 메모 입력 시트(B4 가 트리거).
        case visitMemo(pinId: Int)
    }

    /// 미허용/거부 시 초기 카메라(서울시청, 웹 동일). zoom3 전국 뷰.
    static let seoulCityHall = CameraTarget(latitude: 37.5, longitude: 127.0, zoom: 3)
    /// 위치 허용 시 현재 위치 줌 레벨.
    static let currentLocationZoom: Double = 15
    /// 핀 1개 선택 flyTo 시 줌 레벨.
    static let pinFocusZoom: Double = 15

    // MARK: - 인라인 핀 추가 진입 줌(FR-11, AC-16). 웹 MapClient.tsx:1043-1066 동치.

    /// 인라인 추가 진입 시 이 값 미만이면 줌인 분기(웹 getZoom() < 13).
    static let addPinMinZoom: Double = 13
    /// 위치 권한 허용 + 좌표 확보 시 그 위치로 flyTo 할 줌(웹 zoom 15).
    static let addPinLocatedZoom: Double = 15
    /// 위치 확보 실패/권한 거부 시 현재 중심 유지하며 올릴 줌(웹 zoom 14).
    static let addPinFallbackZoom: Double = 14
    /// mapZoom 이 끝내 nil 일 때 가정하는 줌(MUST-4, AC-20). 13 미만이라 줌인 시도를 보장한다.
    static let addPinAssumeZoomWhenUnknown: Double = 3
    /// 인라인 진입 줌인 시 one-shot 위치 자체 타임아웃(초, FR-11). 공유 CoreLocationService(10초)와 별개.
    static let addPinOneShotTimeoutSeconds: Double = 5

    /// 핀 목록 캐시 TTL(초, FR-24). 웹 PINS_CACHE_TTL_MS = 5*60*1000 동치.
    static let pinsCacheTTL: TimeInterval = 5 * 60
    /// 백엔드 좌표 검증(scale ≤ 7) 대비 반올림 자리수(FR-15). 웹 MapClient.tsx:781~782 동치.
    /// nonisolated roundCoordinate에서 참조하므로 nonisolated(상수 Sendable).
    nonisolated static let coordinateScale = 7

    /// 장소명 최대 길이(BR-4 클라이언트 검증). 백엔드 검증(≤200자)과 동치.
    nonisolated static let placeNameMaxLength = 200

    /// 메모 최대 길이(API 계층 방어 검증). 백엔드 검증(≤500자)과 동치.
    /// 호출부 UI(PinDetailContent/VisitMemoSheet)가 이미 절단하지만 PATCH 직전 한 번 더 절단해 방어한다.
    nonisolated static let memoMaxLength = 500

    /// 위도 허용 범위(BR-4, -90~90). 좌표 범위 클라이언트 검증.
    nonisolated static let latitudeRange: ClosedRange<Double> = -90...90
    /// 경도 허용 범위(BR-4, -180~180). 좌표 범위 클라이언트 검증.
    nonisolated static let longitudeRange: ClosedRange<Double> = -180...180

    /// 핀 생성 입력 검증(BR-4, 순수). 장소명 ≤200자·좌표 범위 위반 시 MapError throw.
    /// AddPlaceViewModel/addPinAtCenter 공유 — 백엔드 400 전에 클라이언트에서 차단.
    nonisolated static func validatePinInput(placeName: String, latitude: Double, longitude: Double) throws {
        guard placeName.count <= placeNameMaxLength else {
            throw MapError.placeNameTooLong
        }
        guard latitudeRange.contains(latitude), longitudeRange.contains(longitude) else {
            throw MapError.invalidCoordinate
        }
    }

    /// APIError → 핀 생성/수정 경로 View 친화 한국어 메시지(BR-2). GROUP_NOT_MEMBER → 권한 안내.
    /// PinDetailViewModel 의 사진 경로 매핑과 별개(문구가 작업별로 다름) — 생성/공통 경로 공유.
    nonisolated static func message(for error: APIError) -> String {
        switch error.code {
        case "GROUP_NOT_MEMBER":
            return "권한이 없어요. 그룹의 활성 멤버만 핀을 추가할 수 있어요."
        default:
            return error.message
        }
    }

    /// 임의 좌표(크로스헤어/Mapbox center)를 백엔드 검증(scale ≤ 7)에 맞게 7자리로 반올림(FR-15, 순수).
    /// 웹 `Number(lat.toFixed(7))` 동치 — `(v*1e7).rounded()/1e7`. 토큰 무관 테스트 대상.
    /// 순수 함수라 actor 격리 불필요 → nonisolated(테스트 등 nonisolated 동기 컨텍스트에서 직접 호출).
    nonisolated static func roundCoordinate(_ value: Double) -> Double {
        let factor = pow(10.0, Double(coordinateScale))
        return (value * factor).rounded() / factor
    }

    // MARK: - 게시 상태

    @Published private(set) var pins: [PinSummary] = []
    /// 표시 태그 필터(FR-6). 기본 전체 ON.
    @Published var activeFilters: Set<PinTag> = [.REEL, .WISH, .MEMORY]
    /// 선택된 핀(마커 탭). 정보창(말풍선 오버레이) 연결은 MapContainerView.
    @Published var selectedPinId: Int?
    /// 선택핀의 현재 화면좌표(말풍선 .position 앵커). markerTapped 가 즉시 세팅(지연 0, MUST-ADDRESS②),
    /// cameraMoved 가 추적 갱신(distinct + 화면밖 nil, MUST-ADDRESS③④). 화면밖/미투영이면 nil → 말풍선 숨김.
    @Published private(set) var selectedPinScreenPoint: ScreenPoint?
    /// 지도 뷰 크기(MapContainerView GeometryReader geo.size → updateMapSize 보관). 화면밖 판정 입력(AC-14).
    private(set) var lastMapSize: CGSize = .zero
    /// 카메라 이동 명령. MapContainerView 가 flyTo 후 nil 로 리셋(B2 계약).
    @Published var cameraCommand: CameraTarget?
    /// 다수 마커 일괄 표시 카메라 명령(FR-26). 클러스터 탭 확장 등에서 사용.
    /// MapContainerView 가 fitBounds 후 nil 로 리셋(cameraCommand 와 동일 1회 소비 계약).
    @Published var fitBoundsCommand: [MapMarker]?
    @Published private(set) var loadState: LoadState = .idle
    /// 동시 1패널. 실제 시트 표시는 B4.
    @Published var activeSheet: ActiveSheet = .none
    /// 방문 메모 시트 닫힘 후 열어야 할 핀 상세(정보창) id.
    /// 단일 사이클 이중 시트 전환 경쟁 회피용 — VisitMemoSheet.finish() 가 세팅하고,
    /// visitMemoSheet 의 onDismiss 에서 selectedPinId 로 소비한다(시트 dismiss 이후로 시퀀싱).
    @Published var pendingDetailPinId: Int?

    // MARK: - 방문감지 게시 상태(FR-27~31, AC-15)

    /// 방문 감지 토스트 대상 핀 id(VisitToastView 트리거). nil 이면 토스트 미표시.
    @Published private(set) var visitToastPinId: Int?
    /// confetti(하트 fan-out) 1회 발사 트리거. MapView 가 변화에 반응해 애니메이션 후 자연 소멸.
    /// 값은 발사 식별용으로 매번 새 UUID — 동일 핀 재전환도 구분.
    @Published private(set) var confettiTrigger: UUID?
    /// 방문 PATCH 실패/안내(이미 추억) 인라인 토스트 메시지. nil 이면 미표시.
    @Published var visitInfoMessage: String?

    // MARK: - 지도 중심 좌표(FR-15 크로스헤어)

    /// 지도 카메라가 멈춘 시점의 최신 중심 좌표(cameraIdle 추적). 크로스헤어 임의 좌표 추가의 입력.
    /// 실값은 Mapbox(token 후)에서만 cameraIdle 이벤트로 들어온다. 플레이스홀더에선 nil 유지.
    @Published private(set) var mapCenter: Coordinate?

    // MARK: - 인라인 핀 추가 모드(P8 영역1, FR-1~11, AC-11)

    /// 인라인 핀 추가 모드 활성(FR-1/2, AC-11). true 시 MapView 가 십자선+하단 확정 카드를 오버레이한다.
    @Published private(set) var isAddingPin = false
    /// 인라인 추가 VM(검색/콕찍기/생성 로직, AddPlaceSheet 에서 이관). 진입 시 생성, 종료 시 nil(작성 중 폐기, BR-1).
    @Published private(set) var addPlaceVM: AddPlaceViewModel?
    /// ＋ FAB speed-dial 펼침 상태(P8 영역4 후속). ＋ 탭 시 "지도에서 찍기(콕찍기)/검색해서 찾기" 2선택지를 펼친다.
    /// 모드 진입(enterAddPin) 시 false 로 닫는다. View 가 토글하므로 private(set) 아님.
    @Published var isAddMenuExpanded = false
    /// cameraIdle 최신 줌(FR-11 진입 판단, MUST-4). idle 전에도 flyTo/applyInitialCamera 명령 줌으로 시드.
    @Published private(set) var mapZoom: Double?

    /// 프로그래매틱 카메라 이동(검색 flyTo/줌인/seed)으로 발생할 cameraIdle 대기 카운터(MUST-1).
    /// flyTo 류에서 +1, handle(.cameraIdle)에서 >0 이면 1 감소 후 onMapMoved 스킵(검색 결과 보존, AC-17).
    private var pendingProgrammaticIdle = 0
    /// 진입 줌인 flyTo 의 idle 도착 시 1회 seed 를 수행할지(MUST-2, AC-18). 줌인 후 그 idle 에서 초기 역지오 1회.
    private var seedOnNextProgrammaticIdle = false
    /// 진입 후 사용자가 드래그(진짜 사용자 idle)했는지(MUST-2, AC-18). true 면 늦게 도착한 줌인 flyTo 를 스킵한다.
    private var userDraggedSinceEntry = false
    /// 진입 줌인 one-shot 위치 요청 Task. 모드 종료 시 취소해 위치 서비스 낭비를 막는다(review Warning).
    private var entryZoomTask: Task<Void, Never>?

    // MARK: - 파생 값

    /// activeFilters 로 필터링한 표시 핀(FR-6/AC-4).
    var visiblePins: [PinSummary] {
        pins.filter { activeFilters.contains($0.tag) }
    }

    /// visiblePins → 지도 마커(선언적 바인딩, MapContainerView 입력).
    var markers: [MapMarker] {
        visiblePins.map {
            MapMarker(id: $0.id, latitude: $0.latitude, longitude: $0.longitude, tag: $0.tag)
        }
    }

    /// 현재 선택된 핀(selectedPinId 기준). 말풍선 오버레이(PinBubbleView)가 소비.
    var selectedPin: PinSummary? {
        guard let id = selectedPinId else { return nil }
        return pins.first { $0.id == id }
    }

    /// 방문 토스트 대상 핀(visitToastPinId 기준). VisitToastView 가 소비. 핀이 사라지면 nil.
    var visitToastPin: PinSummary? {
        guard let id = visitToastPinId else { return nil }
        return pins.first { $0.id == id }
    }

    /// visitMemo 시트 대상 핀(activeSheet 기준). VisitMemoSheet 가 소비.
    var visitMemoPin: PinSummary? {
        guard case let .visitMemo(pinId) = activeSheet else { return nil }
        return pins.first { $0.id == pinId }
    }

    // MARK: - 의존성

    /// 핀 API. PinDetailViewModel(사진 업로드/삭제)이 직접 호출 후 replacePin 으로 반영하기 위해 읽기 전용 공개.
    let pinAPI: PinAPIProtocol
    /// 장소 검색 API. AddPlaceViewModel 이 검색에 사용(읽기 전용 공개).
    let placeAPI: PlaceAPIProtocol
    private let groupAPI: GroupAPIProtocol
    /// 위치 서비스. RouletteViewModel(one-shot)·방문감지 구독에 사용(읽기 전용 공개).
    let locationService: LocationServiceProtocol

    /// 방문 감지 평가 엔진(설계 §4). firstEnterAt 상태 보유, evaluate 는 onSample 에서 호출.
    private let visitEngine = VisitDetectionEngine()
    /// 세션 내 이미 토스트 표시한 pinId(중복 차단, AC-14). 백그라운드 전환에도 유지.
    private var shownVisitPinIds: Set<Int> = []
    /// 방문감지 구독 시작 여부(중복 startUpdating 방지).
    private var isDetecting = false

    /// load() 에서 확보한 활성 그룹 id(핀 CRUD 의 groupId 출처). 확보 전 nil.
    private(set) var groupId: Int?

    // MARK: - 캐시·폴링(FR-24/BR-7)

    /// 마지막으로 핀 목록을 서버에서 새로 받은 시각(FR-24 캐시). nil 이면 미로드.
    /// 낙관 PATCH/DELETE/append 성공 후에도 갱신해 "방금 만든 핀까지 포함하는 fresh" 상태를 유지한다(웹 동치).
    private(set) var lastFetchedAt: Date?
    /// 시각 주입(테스트 결정성). 기본 Date().
    private let now: () -> Date
    /// append-only 폴링 주기(초, BR-7). 기본 30초.
    private let pollInterval: TimeInterval
    /// 폴링 Task 핸들(중복/누수 방지 — onDisappear·scenePhase 에서 정리).
    private var pollTask: Task<Void, Never>?

    init(
        pinAPI: PinAPIProtocol,
        placeAPI: PlaceAPIProtocol,
        groupAPI: GroupAPIProtocol,
        locationService: LocationServiceProtocol,
        pollInterval: TimeInterval = 30,
        now: @escaping () -> Date = { Date() }
    ) {
        self.pinAPI = pinAPI
        self.placeAPI = placeAPI
        self.groupAPI = groupAPI
        self.locationService = locationService
        self.pollInterval = pollInterval
        self.now = now
    }

    // MARK: - 로드(FR-2)

    /// 활성 그룹 → 핀 목록 로드. 실패 시 loadState=.error(재시도 가능, QE-2).
    /// 초기 카메라는 위치 권한 상태에 따라 결정(granted=현재위치 zoom15, 미허용=서울시청 zoom3, FR-2).
    func load() async {
        loadState = .loading
        do {
            guard let group = try await groupAPI.myActiveGroup() else {
                // 활성 그룹 없음 — 핀 없는 상태로 로드 완료(EmptyMapCard 분기는 핀 0개로 처리).
                groupId = nil
                pins = []
                loadState = .loaded
                await applyInitialCamera()
                return
            }
            groupId = group.groupId
            pins = try await pinAPI.list(groupId: group.groupId)
            lastFetchedAt = now()
            loadState = .loaded
            await applyInitialCamera()
        } catch {
            loadState = .error("핀을 불러오지 못했어요. 다시 시도해 주세요.")
        }
    }

    /// 위치 권한에 따른 초기 카메라(FR-2). granted=현재위치 zoom15, 미허용=서울시청 zoom3.
    private func applyInitialCamera() async {
        let status = locationService.authorizationStatus
        let granted = status == .authorizedWhenInUse || status == .authorizedAlways
        if granted, let sample = await locationService.requestOneShot() {
            cameraCommand = CameraTarget(
                latitude: sample.latitude,
                longitude: sample.longitude,
                zoom: Self.currentLocationZoom
            )
            mapZoom = Self.currentLocationZoom   // MUST-4 — idle 도착 전 명령 줌으로 시드(FR-11 평가 가능).
            if mapCenter == nil {
                // MEDIUM-3 — idle 도착 전 중심도 시드(seedInitialPinpoint/bumpZoomOnly 의 mapCenter nil no-op 방지).
                mapCenter = Coordinate(latitude: sample.latitude, longitude: sample.longitude)
            }
        } else {
            cameraCommand = Self.seoulCityHall
            mapZoom = Self.seoulCityHall.zoom    // MUST-4 — 서울시청 zoom3 시드(<13 → 줌인 평가 보장).
            if mapCenter == nil {
                // MEDIUM-3 — Placeholder(토큰 미설정)/첫 진입 idle 미도착 race 에서도 seed/bump 동작 보장.
                mapCenter = Coordinate(latitude: Self.seoulCityHall.latitude, longitude: Self.seoulCityHall.longitude)
            }
        }
    }

    // MARK: - 낙관적 업데이트(B4 가 호출하는 공개 메서드, BR-7/AC-6/7)

    /// 태그 변경 낙관 PATCH(AC-6). 로컬 즉시 반영 → PATCH → 실패 시 스냅샷 복원 + 에러 throw.
    func applyTagOptimistic(pinId: Int, tag: PinTag) async throws {
        guard let groupId else { throw MapError.noActiveGroup }
        let snapshot = pins
        patchLocal(pinId: pinId) { $0.with(tag: tag) }
        do {
            var request = UpdatePinRequest()
            request.tag = .set(tag)
            let response = try await pinAPI.update(groupId: groupId, pinId: pinId, request: request)
            replacePin(response.summary)
        } catch {
            pins = snapshot
            throw error
        }
    }

    /// 메모 변경 낙관 PATCH. 로컬 즉시 반영 → PATCH → 실패 시 복원.
    /// PATCH 전 ≤500자 방어 검증(API 계층). 초과 시 앞 500자로 절단 후 진행(호출부 UI 절단과 정합, 무해 처리).
    func updateMemoOptimistic(pinId: Int, memo: String) async throws {
        guard let groupId else { throw MapError.noActiveGroup }
        let memo = memo.count > Self.memoMaxLength ? String(memo.prefix(Self.memoMaxLength)) : memo
        let snapshot = pins
        patchLocal(pinId: pinId) { $0.with(memo: memo) }
        do {
            var request = UpdatePinRequest()
            request.memo = .set(memo)
            let response = try await pinAPI.update(groupId: groupId, pinId: pinId, request: request)
            replacePin(response.summary)
        } catch {
            pins = snapshot
            throw error
        }
    }

    /// 장소명 변경 낙관 PATCH(Should). 로컬 즉시 반영 → PATCH → 실패 시 복원.
    func updatePlaceNameOptimistic(pinId: Int, placeName: String) async throws {
        guard let groupId else { throw MapError.noActiveGroup }
        let snapshot = pins
        patchLocal(pinId: pinId) { $0.with(placeName: placeName) }
        do {
            var request = UpdatePinRequest()
            request.placeName = .set(placeName)
            let response = try await pinAPI.update(groupId: groupId, pinId: pinId, request: request)
            replacePin(response.summary)
        } catch {
            pins = snapshot
            throw error
        }
    }

    /// 핀 삭제 낙관 DELETE(AC-7). 로컬 즉시 제거 → DELETE → 실패 시 복원.
    func deletePinOptimistic(pinId: Int) async throws {
        guard let groupId else { throw MapError.noActiveGroup }
        let snapshot = pins
        pins.removeAll { $0.id == pinId }
        if selectedPinId == pinId {
            selectedPinId = nil
            // 선택핀 삭제 시 말풍선 화면좌표도 함께 해제(AC-7 — 말풍선 잔상 방지).
            selectedPinScreenPoint = nil
        }
        do {
            try await pinAPI.delete(groupId: groupId, pinId: pinId)
        } catch {
            pins = snapshot
            throw error
        }
    }

    /// 신규 핀 추가(생성/폴링, BR-7 append-only). 중복 id 는 무시.
    func appendPin(_ pin: PinSummary) {
        guard !pins.contains(where: { $0.id == pin.id }) else { return }
        pins.append(pin)
    }

    /// 핀 교체(사진 업로드 후 등). id 일치 핀을 갱신, 없으면 무시.
    func replacePin(_ updated: PinSummary) {
        guard let index = pins.firstIndex(where: { $0.id == updated.id }) else { return }
        pins[index] = updated
    }

    // MARK: - 카메라(B2 계약: cameraCommand 설정 → MapContainerView 가 소비 후 nil)

    /// 특정 핀으로 카메라 이동(zoom15).
    func flyTo(pinId: Int) {
        guard let pin = pins.first(where: { $0.id == pinId }) else { return }
        flyTo(lat: pin.latitude, lng: pin.longitude, zoom: Self.pinFocusZoom)
    }

    /// 임의 좌표로 카메라 이동.
    /// 인라인 추가 모드 중에는 이 flyTo 로 발생할 cameraIdle 을 프로그래매틱으로 표시(MUST-1)하고
    /// mapZoom 을 명령 줌으로 즉시 시드(MUST-4)한다 → 검색 결과 보존 + FR-11 평가 정합.
    func flyTo(lat: Double, lng: Double, zoom: Double = MapViewModel.pinFocusZoom) {
        if isAddingPin {
            pendingProgrammaticIdle += 1
            mapZoom = zoom
        }
        cameraCommand = CameraTarget(latitude: lat, longitude: lng, zoom: zoom)
    }

    /// 다수 마커가 모두 보이도록 카메라 맞춤(FR-26). 2개 이상일 때만 fitBounds, 1개면 단일 flyTo.
    func fitBounds(markers: [MapMarker]) {
        guard !markers.isEmpty else { return }
        if markers.count == 1, let single = markers.first {
            cameraCommand = CameraTarget(latitude: single.latitude, longitude: single.longitude, zoom: Self.pinFocusZoom)
        } else {
            fitBoundsCommand = markers
        }
    }

    // MARK: - 캐시·폴링(FR-24/BR-7)

    /// 캐시가 stale(마지막 조회 후 TTL 경과)이거나 미로드면 true(FR-24). 웹 PINS_CACHE_TTL_MS 동치.
    var isPinsCacheStale: Bool {
        guard let lastFetchedAt else { return true }
        return now().timeIntervalSince(lastFetchedAt) >= Self.pinsCacheTTL
    }

    /// stale(>5분)일 때만 핀 목록을 재조회(FR-24). 룰렛 진입 등에서 호출.
    /// fresh 면 네트워크 없이 즉시 반환. 재조회 결과는 append-only 병합(로컬 낙관 보존, BR-7).
    func refreshPinsIfStale() async {
        guard isPinsCacheStale else { return }
        await reloadPinsAppendOnly()
    }

    /// append-only 폴링 시작(BR-7, FR-32). 중복 시작 방지(pollTask 보유 시 무시).
    /// pollInterval 주기로 list 재조회 → id 기준 신규 핀만 추가. 기존 핀의 수정/삭제는 로컬 낙관 우선.
    func startPolling() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                // interval 만 weak 로 읽어 sleep 동안 strong self 를 잡지 않는다(retain cycle 방지).
                guard let interval = self?.pollInterval else { return }
                try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
                // sleep 후 strong self 재획득 — 그 사이 VM 이 해제됐으면 종료.
                guard let self else { return }
                await self.reloadPinsAppendOnly()
            }
        }
    }

    /// 폴링 중지(누수/중복 방지 — onDisappear·scenePhase background 에서 호출).
    func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    /// 핀 목록 재조회 후 append-only 병합(BR-7). 신규 id 만 추가, 기존 핀은 로컬 상태 유지.
    /// 실패는 조용히 무시(폴링/캐시 갱신은 best-effort — loadState 를 흔들지 않는다).
    private func reloadPinsAppendOnly() async {
        guard let groupId else { return }
        guard let fetched = try? await pinAPI.list(groupId: groupId) else { return }
        mergeAppendOnly(fetched)
        lastFetchedAt = now()
    }

    /// 서버 핀 목록을 append-only 로 병합(BR-7). 로컬에 없는 id 만 추가한다.
    /// 기존 핀의 수정/삭제는 로컬 낙관 업데이트를 우선해 덮어쓰지 않는다(웹 동치).
    func mergeAppendOnly(_ fetched: [PinSummary]) {
        let existingIds = Set(pins.map(\.id))
        let newPins = fetched.filter { !existingIds.contains($0.id) }
        guard !newPins.isEmpty else { return }
        pins.append(contentsOf: newPins)
    }

    // MARK: - 크로스헤어 임의 좌표 추가(FR-15)

    /// 현재 지도 중심(mapCenter)으로 임의 좌표 핀 생성(FR-15). 7자리 반올림(scale ≤ 7) 후 create.
    /// 성공 시 appendPin + flyTo + 캐시 갱신. 중심 좌표 미확보(플레이스홀더)면 throw 로 안내.
    func addPinAtCenter(placeName: String, tag: PinTag) async throws {
        guard let groupId else { throw MapError.noActiveGroup }
        guard let center = mapCenter else { throw MapError.noMapCenter }
        let lat = Self.roundCoordinate(center.latitude)
        let lng = Self.roundCoordinate(center.longitude)
        // BR-4 클라이언트 검증(장소명 ≤200자·좌표 범위). 반올림 이후 좌표로 검증.
        try Self.validatePinInput(placeName: placeName, latitude: lat, longitude: lng)
        let request = CreatePinRequest(
            placeName: placeName,
            address: nil,
            latitude: lat,
            longitude: lng,
            instagramUrl: nil,
            memo: nil,
            tag: tag
        )
        do {
            let created = try await pinAPI.create(groupId: groupId, request: request)
            appendPin(created)
            lastFetchedAt = now()
            flyTo(lat: created.latitude, lng: created.longitude, zoom: Self.pinFocusZoom)
        } catch let error as APIError where error.code == "GROUP_NOT_MEMBER" {
            // BR-2 403 — 권한 안내로 변환(호출부 catch 의 LocalizedError 경로가 한국어 문구 노출).
            throw MapError.permissionDenied
        }
    }

    // MARK: - 인라인 핀 추가 모드 진입/종료(P8 영역1, FR-1~11)

    /// 인라인 추가 모드 진입(FR-1/2, AC-1). 지도 FAB speed-dial 의 2선택지·EmptyMapCard 가 호출한다.
    /// AddPlaceViewModel 생성 → isAddingPin=true. 진입 모드(P8 영역4 후속):
    ///  - `.pinpoint`(콕찍기, 기본): 진입 줌인 + 현재 중심 초기 seed(applyAddPinEntry) → 십자선으로 위치 지정.
    ///  - `.search`(검색): 줌인/seed 생략. inputMode 기본값 .search 유지 → 검색바로 위치 지정(선택 시 flyTo).
    /// 이미 활성이면 무시(중복 진입 방어). 룰렛/메모 등 다른 시트와의 배타는 호출부에서 처리(BR-6).
    func enterAddPin(mode: AddPlaceViewModel.InputMode = .pinpoint) {
        guard !isAddingPin else { return }
        isAddMenuExpanded = false   // speed-dial 닫고 선택한 모드로 진입.
        // 진입 추적 플래그 초기화(이전 세션 잔여 제거).
        pendingProgrammaticIdle = 0
        seedOnNextProgrammaticIdle = false
        userDraggedSinceEntry = false
        addPlaceVM = AddPlaceViewModel(mapViewModel: self, entryMode: mode)   // B-2 — 진입 모드 보존(검색 진입 시 드래그 가드).
        isAddingPin = true
        // 콕찍기만 진입 줌인/초기 seed. 검색은 사용자가 검색→선택할 때까지 콕찍기 중심을 만들지 않는다(십자선 미표시).
        if mode == .pinpoint {
            applyAddPinEntry()
        }
    }

    /// 인라인 추가 모드 종료(FR-8, AC-12). 탭 전환/취소/생성 성공/룰렛 진입 등 모든 종료 경로 공통.
    /// 진행 중 작업(디바운스 역지오·생성 Task)을 취소(MUST-3/AC-19)하고 작성 중 위치 정보를 폐기(BR-1)한다.
    func exitAddPin() {
        guard isAddingPin else { return }
        entryZoomTask?.cancel()   // review Warning — 진입 줌인 위치 요청 중단(모드 종료 후 최대 5초 낭비 방지).
        entryZoomTask = nil
        addPlaceVM?.cancelPendingWork()
        isAddingPin = false
        addPlaceVM = nil
        pendingProgrammaticIdle = 0
        seedOnNextProgrammaticIdle = false
        userDraggedSinceEntry = false
    }

    /// 진입 직후 줌인 분기 + 초기 콕찍기 seed 를 배타적으로 수행(MUST-2/AC-18).
    /// 줌 ≥ addPinMinZoom(가정값 포함): 줌인 불필요 → seed 1회 직접. 줌 < addPinMinZoom: 줌인 flyTo 의 idle 에서 seed.
    private func applyAddPinEntry() {
        let zoom = mapZoom ?? Self.addPinAssumeZoomWhenUnknown   // MUST-4/AC-20 — nil 이면 가정값(<13).
        if zoom < Self.addPinMinZoom {
            // 줌인 flyTo 가 일어나므로 그 프로그래매틱 idle 에서 seed(중복 역지오 방지, MUST-2).
            seedOnNextProgrammaticIdle = true
            applyAddPinEntryZoom()
        } else {
            // 이미 충분히 확대 → 줌인 없이 현재 중심으로 초기 역지오 1회(FR-9).
            seedInitialPinpoint()
        }
    }

    /// 진입 줌인(FR-11, AC-16). 웹 MapClient.tsx:1043-1066 동치.
    /// 우선순위: 권한 허용+좌표 → 그 위치 zoom15 / 미결정 → 권한요청 후 현재중심 zoom14(Q7) / 거부 → 현재중심 zoom14.
    private func applyAddPinEntryZoom() {
        switch locationService.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            entryZoomTask?.cancel()
            entryZoomTask = Task { @MainActor [weak self] in
                guard let self else { return }
                let sample = await self.requestOneShotWithTimeout(seconds: Self.addPinOneShotTimeoutSeconds)
                // 위치 대기 사이 사용자가 드래그했거나 모드를 떠났으면 강제 점프하지 않는다(MUST-2/AC-18).
                guard self.isAddingPin, !self.userDraggedSinceEntry else { return }
                if let sample {
                    self.flyTo(lat: sample.latitude, lng: sample.longitude, zoom: Self.addPinLocatedZoom)
                } else {
                    self.bumpZoomOnly(to: Self.addPinFallbackZoom)
                }
            }
        case .notDetermined:
            // Q7 — 권한만 요청하고 다이얼로그 응답을 기다리지 않는다(카메라 지연/점프 회피). 즉시 zoom14 bump.
            // 권한 허용은 다음 진입/내위치 버튼에서 반영된다.
            locationService.requestWhenInUsePermission()
            bumpZoomOnly(to: Self.addPinFallbackZoom)
        default:
            // 거부/제한 → 현재 중심 유지하며 zoom14 로만 올림.
            bumpZoomOnly(to: Self.addPinFallbackZoom)
        }
    }

    /// 현재 지도 중심 유지하며 줌만 올림(웹 map.flyTo({ zoom }) 동치). mapCenter 미확보면 no-op.
    /// 이 flyTo 의 프로그래매틱 idle 에서 (seedOnNextProgrammaticIdle 이면) 초기 seed 가 수행된다.
    private func bumpZoomOnly(to zoom: Double) {
        guard let center = mapCenter else { return }
        flyTo(lat: center.latitude, lng: center.longitude, zoom: zoom)
    }

    /// 진입 즉시 현재 중심으로 초기 콕찍기 역지오 1회 트리거(FR-9). mapCenter 미확보면 스킵.
    /// 줌인이 없던 경로(줌 ≥ 13)에서만 직접 호출 — 줌인 경로는 idle 에서 수행(중복 방지, MUST-2).
    private func seedInitialPinpoint() {
        guard let center = mapCenter else { return }
        addPlaceVM?.onMapMoved(center: center)
    }

    /// one-shot 위치를 seconds 초 자체 타임아웃과 race(FR-11). 공유 CoreLocationService(10초)는 불변.
    /// 타임아웃이 먼저면 nil 을 반환해 호출부가 fallback(zoom14)로 진행한다(설계 확정 결정 3).
    /// withTaskGroup 으로 위치 요청 child task(@MainActor — LocationServiceProtocol 격리 정합)와 타임아웃
    /// child task 를 동시에 띄워 먼저 끝난 결과(group.next())를 취한 뒤 나머지를 취소한다.
    /// LocationSample 은 저장 프로퍼티가 모두 Double/Double? 이라 암시적 Sendable — 그룹 반환에 안전.
    private func requestOneShotWithTimeout(seconds: Double) async -> LocationSample? {
        // 위치 요청(@MainActor 격리 locationService 접근)은 별도 @MainActor Task 로 분리한다.
        // withTaskGroup 의 child 는 'sending' 클로저라 actor 격리 클로저를 직접 담을 수 없으므로
        // (Swift 6 strict concurrency — main actor-isolated 클로저를 동시 컨텍스트로 보내면 데이터 레이스),
        // Sendable 한 Task 핸들의 .value 만 await 하여 race 한다. 타임아웃이 먼저면 locationTask 를 취소한다.
        let locationTask = Task { @MainActor [weak self] in
            await self?.locationService.requestOneShot() ?? nil
        }
        return await withTaskGroup(of: LocationSample?.self) { group in
            group.addTask {
                await locationTask.value
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                return nil
            }
            let result = await group.next() ?? nil
            group.cancelAll()
            locationTask.cancel()   // 타임아웃 승리 시 진행 중 위치 요청 중단(불필요한 대기 제거).
            return result
        }
    }

    // MARK: - 지도 이벤트(MapContainerView onEvent 바인딩)

    /// 지도에서 올라오는 이벤트 처리. markerTapped → selectedPinId + 말풍선 화면좌표 즉시 세팅.
    func handle(_ event: MapEvent) {
        switch event {
        case let .markerTapped(pinId, screenPoint):
            // BR-2/AC-13 — 인라인 추가 모드 중에는 마커 탭(핀 상세 진입)을 차단(십자선 모드 선택 충돌 방지, #97).
            guard !isAddingPin else { return }
            // 동일 핀 재탭은 무시(D-2/AC-13)하되, 프로그래밍 선택 등으로 selectedPinScreenPoint 가 nil 인 상태면
            // 재탭으로 좌표를 복구한다(G1/G2 투영 실패 시 마커 재탭이 말풍선 복구 안전망, #96).
            guard selectedPinId != pinId || selectedPinScreenPoint == nil else { return }
            selectedPinId = pinId
            // 탭 즉시 화면좌표 세팅(지연 0, MUST-ADDRESS②). 화면밖이면 숨김(AC-14) — 이후 추적이 재표시.
            setSelectedPinScreenPoint(visibleOrNil(screenPoint))
        case let .cameraMoved(screenPoint):
            // 선택핀 추적 갱신(MUST-ADDRESS③). distinct + 화면밖 nil(GeoMath.isPointVisible) → 그 자식 뷰만 무효화.
            // 선택 해제 상태(selectedPinId nil)의 잔여 방출은 무시(게이팅 b 방어).
            guard selectedPinId != nil else { return }
            setSelectedPinScreenPoint(visibleOrNil(screenPoint))
        case .clusterTapped(let pinIds):
            // BR-2 — 인라인 추가 모드 중 클러스터 탭(지도 강제이동→cameraIdle→콕찍기 좌표 오염)을 차단(markerTapped 와 동일).
            guard !isAddingPin else { return }
            // 클러스터 탭(FR-5) → 포함 핀들이 모두 보이도록 fitBounds(FR-26).
            handleClusterTapped(pinIds)
        case .cameraIdle(let lat, let lng, let zoom):
            // 항상 최신 중심/줌 보유(기존 방문감지 입력 + MUST-4).
            mapCenter = Coordinate(latitude: lat, longitude: lng)
            mapZoom = zoom
            guard isAddingPin else { return }
            if pendingProgrammaticIdle > 0 {
                // 내가 명령한 flyTo(검색/줌인/seed)로 인한 idle — onMapMoved 스킵(검색 결과 보존, MUST-1/AC-17).
                pendingProgrammaticIdle -= 1
                if seedOnNextProgrammaticIdle {
                    // 줌인 flyTo 의 idle → 초기 콕찍기 중심 1회 확정(MUST-2/AC-18).
                    seedOnNextProgrammaticIdle = false
                    addPlaceVM?.onMapMoved(center: Coordinate(latitude: lat, longitude: lng))
                }
                return
            }
            // 여기부터는 진짜 사용자 드래그 idle.
            userDraggedSinceEntry = true   // MUST-2 — 이후 늦게 도착한 줌인 flyTo 를 스킵하는 가드.
            // MUST-1 2차 안전망: 검색 flyTo 좌표 잔여 idle(선택 좌표와 동일)만 콕찍기로 덮지 않고 보존한다.
            //  다른 좌표로의 실제 수동 드래그는 콕찍기로 전환한다(AC-17 엣지 — 검색 후 드래그 시 전환).
            //  (selectedPlace 유무만으로 막으면 진짜 드래그도 영구 보존되어 콕찍기 전환이 불가능해진다.)
            if addPlaceVM?.inputMode == .search, let selected = addPlaceVM?.selectedPlace,
               abs(lat - selected.latitude) < 1e-6, abs(lng - selected.longitude) < 1e-6 {
                return
            }
            addPlaceVM?.onMapMoved(center: Coordinate(latitude: lat, longitude: lng))   // 사용자 드래그(AC-5)
        }
    }

    /// 지도 뷰 크기 갱신(MapContainerView GeometryReader). 화면밖 판정의 기준(AC-14).
    func updateMapSize(_ size: CGSize) {
        lastMapSize = size
    }

    /// 말풍선 화면좌표 해제(선택 해제/삭제 시). 닫기 흐름에서 selectedPinId nil 과 함께 호출.
    func clearSelectedPinScreenPoint() {
        selectedPinScreenPoint = nil
    }

    /// raw 투영점을 화면 영역으로 필터(D-3 clamp 없음 — 밖이면 nil 로 숨김, AC-14).
    /// lastMapSize 미확보(.zero) 시 isPointVisible 이 false → nil(투영 기준 없음 방어).
    private func visibleOrNil(_ screenPoint: ScreenPoint?) -> ScreenPoint? {
        guard let screenPoint, GeoMath.isPointVisible(screenPoint, in: lastMapSize) else { return nil }
        return screenPoint
    }

    /// distinctUntilChanged(QE-1 게이팅 b): 1pt 반올림 비교로 같으면 set 생략(불필요 무효화 차단).
    private func setSelectedPinScreenPoint(_ next: ScreenPoint?) {
        guard !screenPointsEqual(selectedPinScreenPoint, next) else { return }
        selectedPinScreenPoint = next
    }

    /// 화면좌표 동등 비교(1pt 반올림). nil↔값 전환은 변화로 본다.
    private func screenPointsEqual(_ lhs: ScreenPoint?, _ rhs: ScreenPoint?) -> Bool {
        switch (lhs, rhs) {
        case (nil, nil): return true
        case let (l?, r?): return l.x.rounded() == r.x.rounded() && l.y.rounded() == r.y.rounded()
        default: return false
        }
    }

    /// 클러스터 탭 처리(FR-5/FR-26). 포함 pinId 들의 마커를 모아 fitBounds 명령을 낸다.
    /// 1개뿐이면 단일 flyTo, 0개면 무시(방어).
    private func handleClusterTapped(_ pinIds: [Int]) {
        let targets = visiblePins
            .filter { pinIds.contains($0.id) }
            .map { MapMarker(id: $0.id, latitude: $0.latitude, longitude: $0.longitude, tag: $0.tag) }
        guard !targets.isEmpty else { return }
        if targets.count == 1, let single = targets.first {
            cameraCommand = CameraTarget(latitude: single.latitude, longitude: single.longitude, zoom: Self.pinFocusZoom)
        } else {
            fitBoundsCommand = targets
        }
    }

    // MARK: - 방문감지 오케스트레이션(FR-27~32, AC-14/15)

    /// 방문감지 위치 구독 시작(FR-32). 권한 granted 일 때만 onSample 연결 + startUpdating.
    /// onSample → VisitDetectionEngine.evaluate → detectedPinId & 미노출이면 토스트 트리거(AC-14).
    func startVisitDetection() {
        guard !isDetecting else { return }
        let status = locationService.authorizationStatus
        let granted = status == .authorizedWhenInUse || status == .authorizedAlways
        guard granted else { return }
        isDetecting = true
        locationService.onSample = { [weak self] sample in
            // LocationServiceProtocol 은 @MainActor — 콜백도 MainActor 컨텍스트에서 호출된다.
            self?.handleVisitSample(sample)
        }
        locationService.startUpdating()
    }

    /// 위치 표본 1건 처리. evaluate 결과가 미노출 핀이면 토스트 트리거 + 세션 Set 추가.
    private func handleVisitSample(_ sample: LocationSample) {
        let wishReelPins = pins
            .filter { $0.tag == .REEL || $0.tag == .WISH }
            .map { VisitCandidatePin(pinId: $0.id, latitude: $0.latitude, longitude: $0.longitude) }
        let detected = visitEngine.evaluate(
            sample: sample,
            wishReelPins: wishReelPins,
            shownPinIds: shownVisitPinIds,
            now: Date().timeIntervalSince1970
        )
        // evaluate 가 이미 shownPinIds 를 필터하므로 !contains 는 이중 체크다.
        // evaluate 계약 변경(필터 누락 등) 대비 방어 코드로 유지한다.
        guard let pinId = detected, !shownVisitPinIds.contains(pinId) else { return }
        // 이미 다른 토스트가 떠 있으면 덮어쓰지 않는다(동시 1토스트).
        guard visitToastPinId == nil else { return }
        shownVisitPinIds.insert(pinId)
        visitToastPinId = pinId
    }

    /// 방문감지 위치 구독 중지. 구독만 멈추고 firstEnterAt/세션 Set 은 유지.
    func stopVisitDetection() {
        locationService.stopUpdating()
        locationService.onSample = nil
        isDetecting = false
    }

    /// scenePhase background 진입 시 호출(설계 §4). firstEnterAt 전체 clear(누적 무효화).
    func onEnterBackground() {
        visitEngine.clearAll()
    }

    /// 방문 토스트 "나중에요"/닫기. 토스트만 닫고 firstEnterAt 비움(같은 방문 즉시 재트리거 방지).
    /// 세션 Set 에는 이미 추가돼 있으므로 같은 핀은 이번 세션 동안 재노출되지 않는다.
    func dismissVisitToast() {
        if let pinId = visitToastPinId {
            visitEngine.clearFirstEnterAt(pinId: pinId)
        }
        visitToastPinId = nil
    }

    /// "네, 다녀왔어요" → 태그 MEMORY 전환 PATCH(AC-15).
    /// - transitionedToMemoryNow == true: 로컬 replacePin + confetti + visitMemo 시트.
    /// - false: 로컬 replacePin + 안내 토스트만(confetti/시트 없음).
    /// - PATCH 실패: 인라인 에러 토스트, 태그 미변경. 세션 Set/firstEnterAt 은 유지해
    ///   재진입 즉시 재토스트되는 무한 루프를 차단한다(수동 재시도는 PinDetail 에서 MEMORY 전환).
    func confirmVisit(pinId: Int) async {
        guard let groupId else {
            visitInfoMessage = MapError.noActiveGroup.errorDescription
            return
        }
        // 토스트는 즉시 닫는다(중복 탭 방지). detected 상태는 confirmVisit 진행으로 대체.
        visitToastPinId = nil

        var request = UpdatePinRequest()
        request.tag = .set(.MEMORY)
        do {
            let response = try await pinAPI.update(groupId: groupId, pinId: pinId, request: request)
            replacePin(response.summary)
            visitEngine.clearFirstEnterAt(pinId: pinId)
            if response.transitionedToMemoryNow {
                // 본 디바이스에서 전환 발생 — confetti + 메모 시트(AC-15).
                confettiTrigger = UUID()
                activeSheet = .visitMemo(pinId: pinId)
            } else {
                // 짝꿍이 먼저 전환한 핀 — confetti/시트 스킵 + 안내 토스트(AC-15).
                visitInfoMessage = "이미 추억으로 기록된 곳이에요."
            }
        } catch {
            // 시스템 에러(FR-VD-21) — 태그 미변경. 세션 Set/firstEnterAt 은 유지한다.
            // (제거하면 재진입 즉시 재토스트되어 무한 루프 — 수동 재시도는 PinDetail 에서 MEMORY 전환.)
            visitInfoMessage = "장소를 추억으로 옮기지 못했어요. 다시 시도해 주세요."
        }
    }

    // MARK: - Private 헬퍼

    /// id 일치 핀에 변환을 적용(낙관 로컬 갱신).
    private func patchLocal(pinId: Int, _ transform: (PinSummary) -> PinSummary) {
        guard let index = pins.firstIndex(where: { $0.id == pinId }) else { return }
        pins[index] = transform(pins[index])
    }
}

// MARK: - 지도 도메인 에러

/// 지도 작업 에러(View 친화 메시지).
enum MapError: LocalizedError {
    case noActiveGroup
    case noMapCenter
    /// 좌표 범위 위반(BR-4, 위도 -90~90·경도 -180~180).
    case invalidCoordinate
    /// 장소명 길이 초과(BR-4, ≤200자).
    case placeNameTooLong
    /// 비-멤버 권한 거부(BR-2, 403 GROUP_NOT_MEMBER).
    case permissionDenied

    var errorDescription: String? {
        switch self {
        case .noActiveGroup:
            return "활성 그룹을 찾지 못했어요. 잠시 후 다시 시도해 주세요."
        case .noMapCenter:
            return "지도 위치를 확인할 수 없어요. 지도를 움직인 뒤 다시 시도해 주세요."
        case .invalidCoordinate:
            return "좌표 범위를 벗어났어요. 지도를 움직인 뒤 다시 시도해 주세요."
        case .placeNameTooLong:
            return "장소 이름은 200자 이내로 입력해 주세요."
        case .permissionDenied:
            return "권한이 없어요. 그룹의 활성 멤버만 핀을 추가할 수 있어요."
        }
    }
}

// MARK: - PinSummary 부분 갱신(낙관 로컬 반영용)

private extension PinSummary {
    /// 태그만 바꾼 복제본.
    func with(tag: PinTag) -> PinSummary {
        copy(tag: tag)
    }

    /// 메모만 바꾼 복제본.
    func with(memo: String) -> PinSummary {
        copy(memo: memo)
    }

    /// 장소명만 바꾼 복제본.
    func with(placeName: String) -> PinSummary {
        copy(placeName: placeName)
    }

    /// PinSummary 는 let 프로퍼티 struct 라 부분 갱신을 위해 전체 필드 복제 생성자를 제공한다.
    func copy(
        tag: PinTag? = nil,
        memo: String? = nil,
        placeName: String? = nil
    ) -> PinSummary {
        PinSummary(
            id: id,
            groupId: groupId,
            createdBy: createdBy,
            createdByNickname: createdByNickname,
            placeName: placeName ?? self.placeName,
            address: address,
            latitude: latitude,
            longitude: longitude,
            instagramUrl: instagramUrl,
            memo: memo ?? self.memo,
            memoSource: memoSource,
            tag: tag ?? self.tag,
            createdAt: createdAt,
            visitedAt: visitedAt,
            memoUpdatedBy: memoUpdatedBy,
            memoUpdatedByNickname: memoUpdatedByNickname,
            photoUrl: photoUrl,
            photoThumbnailUrl: photoThumbnailUrl
        )
    }
}
