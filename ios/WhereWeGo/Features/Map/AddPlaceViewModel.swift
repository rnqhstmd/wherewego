import Foundation

// ＋ 통합 장소 추가 ViewModel(설계 §4, FR-12~16, AC-8/AC-9).
// SearchPinViewModel(검색→선택→태그→create)과 CrosshairAddView(중앙좌표→addPinAtCenter)를 하나로 흡수한다.
// 토글/탭 없이 검색바·독립맵(콕찍기)·확정 카드가 한 화면에 공존하며, 마지막 상호작용으로 inputMode 가 결정된다.
//
// 흐름:
//  - 검색(FR-13): query → placeAPI.search → results → selectResult → selectedPlace(이름·주소·좌표) + 독립맵 flyTo.
//  - 콕찍기(FR-14, AC-8): 독립맵 드래그(cameraIdle) → onMapMoved(center:) → query="" + .pinpoint + 디바운스 역지오 → resolvedAddress.
//    실패 시 ReverseGeocoder.coordinateFallback(AC-9).
//  - 확정(FR-15): createPin(tag:) → validatePinInput 재사용 → pinAPI.create
//    (검색=selectedPlace 좌표, 콕찍기=roundCoordinate 7자리 center) → mapViewModel.appendPin + flyTo → didCreate.
//  - 릴스 링크 미포함(FR-16).
//
// 독립맵 cameraIdle 은 AddPlaceSheet 의 독립 MapContainerView 가 onMapMoved(center:) 로 직접 넘긴다
// (메인 mapViewModel 의 카메라/mapCenter 와 분리, MUST-ADDRESS #2). 핀 생성 결과 반영(appendPin/flyTo)에만 mapViewModel 공유.
//
// 의존(placeAPI/pinAPI/groupId)은 MapViewModel 에서 가져온다(pins 단일 출처/카메라 명령 위임).
@MainActor
final class AddPlaceViewModel: ObservableObject {

    /// 입력 방식(설계 §4). 검색 결과 선택 ↔ 지도 콕찍기. 마지막 상호작용이 결정한다.
    enum InputMode {
        case search
        case pinpoint
    }

    // MARK: - 게시 상태

    /// 검색창 입력값. 콕찍기 전환 시 ""(AC-8).
    @Published var query: String = ""
    /// 검색 결과 목록.
    @Published private(set) var results: [PlaceItem] = []
    /// 현재 입력 방식(검색/콕찍기).
    @Published private(set) var inputMode: InputMode = .search
    /// 검색에서 선택한 장소(이름·주소·좌표). 콕찍기 전환 시 nil.
    @Published private(set) var selectedPlace: PlaceItem?
    /// 콕찍기 중심 좌표의 역지오 결과(또는 좌표 폴백, AC-9). 하단 카드 주소 표시.
    @Published private(set) var resolvedAddress: String?
    /// 검색했지만 결과가 0건인지(빈 결과 안내). 검색 전에는 false.
    @Published private(set) var didSearch = false
    /// 검색 진행 중(스피너).
    @Published private(set) var isSearching = false
    /// 핀 생성 진행 중(중복 탭 방지).
    @Published private(set) var isCreating = false
    /// 인라인 에러 메시지.
    @Published var errorMessage: String?
    /// 생성 성공 시 true → View 가 시트를 닫는다.
    @Published private(set) var didCreate = false

    /// 콕찍기 모드에서 추적 중인 독립맵 중심 좌표(확정 시 7자리 반올림 후 create). 초기 nil.
    private(set) var pinpointCenter: Coordinate?

    // MARK: - 의존성

    private weak var mapViewModel: MapViewModel?
    private let reverseGeocoder: ReverseGeocoder
    private let debouncer: Debouncer

    init(
        mapViewModel: MapViewModel,
        reverseGeocoder: ReverseGeocoder = ReverseGeocoder(),
        debouncer: Debouncer = Debouncer()
    ) {
        self.mapViewModel = mapViewModel
        self.reverseGeocoder = reverseGeocoder
        self.debouncer = debouncer
    }

    // MARK: - 검색(FR-13)

    /// 검색어로 장소 검색. 공백 입력은 무시. 결과/에러를 게시 상태로 반영.
    func search() async {
        let keyword = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyword.isEmpty else { return }
        guard let mapViewModel else { return }
        inputMode = .search
        errorMessage = nil
        isSearching = true
        didSearch = false
        defer { isSearching = false }
        do {
            results = try await mapViewModel.placeAPI.search(keyword)
            didSearch = true
        } catch let error as APIError {
            errorMessage = error.message
        } catch {
            errorMessage = "장소를 검색하지 못했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    /// 검색 결과 1개 선택(FR-13) → selectedPlace 채움 + 독립맵 flyTo(View 가 cameraCommand 소비).
    /// 콕찍기 상태(pinpointCenter/resolvedAddress)는 초기화한다(검색 좌표가 입력의 단일 출처).
    func selectResult(_ place: PlaceItem) {
        errorMessage = nil
        inputMode = .search
        selectedPlace = place
        pinpointCenter = nil
        resolvedAddress = nil
    }

    // MARK: - 콕찍기(FR-14, AC-8)

    /// 독립맵 드래그(cameraIdle) → 콕찍기 전환. query 비움 + .pinpoint + 중심 추적 + 디바운스 역지오 트리거.
    /// 역지오는 ReverseGeocoder 결과, 실패 시 coordinateFallback(AC-9)로 resolvedAddress 를 채운다.
    func onMapMoved(center: Coordinate) {
        guard !isCreating else { return }   // 등록 진행 중 지도 드래그로 인한 상태 불일치 방지(Gemini MEDIUM).
        query = ""                  // AC-8 — 콕찍기 시작 시 검색어 초기화.
        inputMode = .pinpoint
        selectedPlace = nil
        results = []
        didSearch = false
        errorMessage = nil
        pinpointCenter = center
        // 디바운스 300ms — 드래그 중 연발하는 cameraIdle 중 마지막 1회만 역지오(설계 §5).
        debouncer.call { [weak self] in
            Task { await self?.resolveAddress(for: center) }
        }
    }

    /// 콕찍기 중심 좌표 역지오 → resolvedAddress. 실패/무결과 시 좌표 폴백(AC-9).
    private func resolveAddress(for center: Coordinate) async {
        // 디바운스 실행 시점에 이미 다른 좌표로 이동했으면 무시(마지막 center 우선).
        guard inputMode == .pinpoint, pinpointCenter == center else { return }
        if let address = await reverseGeocoder.reverseGeocode(center) {
            guard inputMode == .pinpoint, pinpointCenter == center else { return }
            resolvedAddress = address
        } else {
            guard inputMode == .pinpoint, pinpointCenter == center else { return }
            resolvedAddress = ReverseGeocoder.coordinateFallback(
                lat: center.latitude,
                lng: center.longitude
            )
        }
    }

    // MARK: - 확정(FR-15)

    /// 선택 장소(검색) 또는 콕찍기 중심으로 핀 생성 → MapViewModel.appendPin + flyTo → didCreate.
    /// 검색: selectedPlace 의 좌표/주소 사용. 콕찍기: roundCoordinate 7자리 center + resolvedAddress.
    /// validatePinInput 재사용(BR-4 장소명 ≤200자·좌표 범위). 성공 시 View 가 시트를 닫는다.
    func createPin(tag: PinTag) async {
        // weak mapViewModel 해제 시 무음 종료 대신 사용자 피드백(cross-review #3) — 확정 동선이므로 안내 노출.
        guard let mapViewModel else {
            errorMessage = "일시적인 오류가 발생했어요. 다시 시도해주세요."
            return
        }
        guard let groupId = mapViewModel.groupId else {
            errorMessage = MapError.noActiveGroup.errorDescription
            return
        }
        guard let request = buildRequest(tag: tag) else {
            errorMessage = "추가할 위치를 먼저 정해 주세요."
            return
        }
        errorMessage = nil
        // BR-4 클라이언트 검증(장소명 ≤200자·좌표 범위). 위반 시 즉시 안내 후 종료.
        do {
            try MapViewModel.validatePinInput(
                placeName: request.placeName,
                latitude: request.latitude,
                longitude: request.longitude
            )
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
            return
        }
        isCreating = true
        defer { isCreating = false }
        do {
            let created = try await mapViewModel.pinAPI.create(groupId: groupId, request: request)
            mapViewModel.appendPin(created)
            mapViewModel.flyTo(lat: created.latitude, lng: created.longitude, zoom: MapViewModel.pinFocusZoom)
            didCreate = true
        } catch let error as APIError {
            // BR-2 403 GROUP_NOT_MEMBER 포함 코드별 한국어 매핑(MapViewModel 공유 헬퍼).
            errorMessage = MapViewModel.message(for: error)
        } catch {
            errorMessage = "핀을 추가하지 못했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    // MARK: - 파생 값(확정 카드)

    /// 하단 확정 카드의 장소명. 검색=선택 장소명, 콕찍기=중심 좌표(7자리). 미정 시 nil.
    var confirmTitle: String? {
        switch inputMode {
        case .search:
            return selectedPlace?.placeName
        case .pinpoint:
            guard let center = pinpointCenter else { return nil }
            let lat = MapViewModel.roundCoordinate(center.latitude)
            let lng = MapViewModel.roundCoordinate(center.longitude)
            return String(format: "%.7f, %.7f", lat, lng)
        }
    }

    /// 하단 확정 카드의 주소. 검색=장소 주소, 콕찍기=resolvedAddress(역지오/좌표 폴백). 없으면 nil.
    var confirmAddress: String? {
        switch inputMode {
        case .search:
            return selectedPlace?.address
        case .pinpoint:
            return resolvedAddress
        }
    }

    /// "여기 등록" 활성 여부. 검색=선택 장소 존재, 콕찍기=중심 좌표 확보 + 생성 중 아님.
    var canConfirm: Bool {
        guard !isCreating else { return false }
        switch inputMode {
        case .search:
            return selectedPlace != nil
        case .pinpoint:
            return pinpointCenter != nil
        }
    }

    // MARK: - 초기 카메라 seed(＋시트 진입)

    /// ＋ 시트 진입 시 독립맵 초기 카메라(설계 §4 보강 — 시트 독립맵이 SDK 기본 카메라(대서양)로 떠
    /// 콕찍기 좌표가 엉뚱해지던 결함 수정). 메인 지도의 마지막 중심(mapViewModel.mapCenter)을 우선 쓰고,
    /// 아직 cameraIdle 이 없었으면(mapCenter == nil) 서울시청 좌표로 폴백한다.
    /// 줌은 콕찍기 시가지 레벨(pinFocusZoom) — seoulCityHall(zoom3 전국뷰)은 콕찍기엔 부적합해 좌표만 차용한다.
    /// 이 seed 가 onMapIdle→onMapMoved 로 이어져 진입 즉시 콕찍기 중심을 확정한다(제품 결정: 자동 콕찍기 허용).
    var initialCameraTarget: CameraTarget {
        let center = mapViewModel?.mapCenter ?? Coordinate(latitude: 37.5, longitude: 127.0)
        return CameraTarget(
            latitude: center.latitude,
            longitude: center.longitude,
            zoom: MapViewModel.pinFocusZoom
        )
    }

    // MARK: - Private 헬퍼

    /// 현재 입력 방식에 맞는 생성 요청(콕찍기 좌표는 7자리 반올림). 입력 미정 시 nil.
    private func buildRequest(tag: PinTag) -> CreatePinRequest? {
        switch inputMode {
        case .search:
            guard let place = selectedPlace else { return nil }
            return CreatePinRequest(
                placeName: place.placeName,
                address: place.address,
                latitude: place.latitude,
                longitude: place.longitude,
                instagramUrl: nil,
                memo: nil,
                tag: tag
            )
        case .pinpoint:
            guard let center = pinpointCenter else { return nil }
            let lat = MapViewModel.roundCoordinate(center.latitude)
            let lng = MapViewModel.roundCoordinate(center.longitude)
            // 콕찍기 장소명: 역지오 주소 우선, 미해소 시 좌표 문자열로 폴백(별도 장소명 입력 없음, 설계 §4 카드 구성).
            let placeName = resolvedAddress ?? String(format: "%.7f, %.7f", lat, lng)
            return CreatePinRequest(
                placeName: placeName,
                address: resolvedAddress,
                latitude: lat,
                longitude: lng,
                instagramUrl: nil,
                memo: nil,
                tag: tag
            )
        }
    }
}
