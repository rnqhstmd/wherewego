import Foundation
import UIKit

// ＋ 통합 장소 추가 ViewModel(설계 §4, FR-12~16, AC-8/AC-9).
// SearchPinViewModel(검색→선택→태그→create)과 콕찍기(중앙좌표→create)를 하나로 흡수한다.
// 검색바·확정 카드가 인라인 하단 카드에 공존하며, 마지막 상호작용으로 inputMode 가 결정된다(P8 영역1 인라인화).
//
// 흐름:
//  - 검색(FR-13): query → placeAPI.search → results → selectResult → selectedPlace(이름·주소·좌표) + 메인 지도 flyTo.
//  - 콕찍기(FR-14, AC-8): 메인 지도 드래그(cameraIdle) → onMapMoved(center:) → query="" + .pinpoint + 디바운스 역지오 → resolvedAddress.
//    실패 시 ReverseGeocoder.coordinateFallback(AC-9).
//  - 확정(FR-15): createPin(tag:) → validatePinInput 재사용 → pinAPI.create
//    (검색=selectedPlace 좌표, 콕찍기=roundCoordinate 7자리 center) → mapViewModel.appendPin + flyTo → didCreate.
//  - 릴스 링크 미포함(FR-16).
//
// P8 영역1(인라인화): 별도 시트/독립맵 제거. 본 VM 의 수명을 MapViewModel 이 소유(enterAddPin 생성, exitAddPin nil).
// 콕찍기 중심은 메인 지도 cameraIdle → MapViewModel.handle → onMapMoved(center:) 로 들어온다(독립맵 미사용).
// 핀 생성 결과 반영(appendPin/flyTo) + 검색 결과 flyTo 도 메인 mapViewModel 의 cameraCommand 를 공유한다.
//
// 의존(placeAPI/pinAPI/groupId)은 MapViewModel 에서 가져온다(pins 단일 출처/카메라 명령 위임).
@MainActor
final class AddPlaceViewModel: ObservableObject {

    /// 입력 방식(설계 §4). 검색 결과 선택 ↔ 지도 콕찍기. 마지막 상호작용이 결정한다.
    enum InputMode {
        case search
        case pinpoint
    }

    /// 인라인 추가 진행 단계(웹 MapClient 패널 흐름 정합).
    ///  - locate: 위치 확정 단계(검색바+결과 또는 콕찍기 십자선+주소). 위치를 정한 뒤 form 으로 전이.
    ///  - form: 메모·태그 폼(웹 MemoTagPanelContent) — 태그/장소명/메모/사진/릴스 입력 후 저장.
    enum Step {
        case locate
        case form
    }

    // MARK: - 게시 상태

    /// 현재 단계(위치 확정 ↔ 메모·태그 폼). 검색 결과 선택/콕찍기 완료 시 .form 으로 전이.
    @Published private(set) var step: Step = .locate

    // MARK: - 메모·태그 폼 입력(웹 MemoTagPanelContent 정합)

    /// 폼에서 선택한 태그(필수). 미선택 시 저장 비활성. 비-MEMORY 전환 시 보관 사진 폐기.
    @Published var selectedTag: PinTag? {
        didSet {
            // 비-MEMORY 태그로 변경 시 보관 사진 폐기 — 비-MEMORY 핀 업로드(PIN_PHOTO_NOT_MEMORY) 방지(웹 정합).
            if selectedTag != .MEMORY { pendingPhoto = nil }
        }
    }
    /// 장소 이름(검색 진입도 편집 가능, 웹 정합). 폼 진입 시 selectedPlace/좌표로 시드.
    @Published var formPlaceName: String = ""
    /// 메모(선택, 최대 500자).
    @Published var memo: String = ""
    /// 릴스(인스타그램) 링크(선택). https:// 미시작 시 urlError.
    @Published var instagramUrl: String = "" {
        didSet { urlError = Self.validateUrl(instagramUrl) }
    }
    /// 릴스 링크 형식 오류 메시지(https:// 미시작). nil 이면 정상.
    @Published private(set) var urlError: String?
    /// MEMORY 핀 신규 등록 2-step 보관 사진(크롭·압축 완료 후 발급된 pinId 로 업로드).
    @Published var pendingPhoto: UIImage?

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
    /// 콕찍기 역지오 진행 중(BR-4, AC-B3). true 시 하단 카드에 "주소를 찾는 중..." 표시.
    /// onMapMoved 에서 true, resolveAddress 완료/폐기(다른 좌표로 이동) 시 false.
    @Published private(set) var isResolvingAddress = false
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
    /// 핀은 저장됐으나 사진 업로드만 실패한 경우 안내 메시지(BR-6). MapView 가 토스트로 노출.
    @Published var photoWarning: String?

    /// 콕찍기 모드에서 추적 중인 메인 지도 중심 좌표(확정 시 7자리 반올림 후 create). 초기 nil.
    private(set) var pinpointCenter: Coordinate?

    /// 진입 모드(콕찍기 vs 검색, 작업 B-2). enterAddPin(mode:)에서 전달.
    /// 검색으로 진입했으면 지도 드래그(onMapMoved)가 콕찍기로 전환되지 않게 가드한다(검색 결과/선택 유지).
    /// 웹은 검색/콕찍기가 완전 별개 패널이라 이 전환 자체가 없다.
    let entryMode: InputMode

    // MARK: - 의존성

    private weak var mapViewModel: MapViewModel?
    private let reverseGeocoder: ReverseGeocoder
    private let debouncer: Debouncer
    /// 핀 생성 Task 핸들(Q6a, AC-19). 모드 종료(exitAddPin) 시 취소해 "취소했는데 생성"을 차단한다.
    private var createTask: Task<Void, Never>?

    init(
        mapViewModel: MapViewModel,
        entryMode: InputMode = .pinpoint,
        reverseGeocoder: ReverseGeocoder = ReverseGeocoder(),
        debouncer: Debouncer = Debouncer()
    ) {
        self.mapViewModel = mapViewModel
        self.entryMode = entryMode
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

    /// 검색 결과 1개 선택(FR-13) → selectedPlace 채움 + 메인 지도 flyTo(View 가 cameraCommand 소비).
    /// 콕찍기 상태(pinpointCenter/resolvedAddress)는 초기화한다(검색 좌표가 입력의 단일 출처).
    /// 웹 정합: 결과 탭 → 곧바로 메모·태그 폼 단계로 전이(MemoTagPanelContent).
    func selectResult(_ place: PlaceItem) {
        errorMessage = nil
        inputMode = .search
        selectedPlace = place
        pinpointCenter = nil
        resolvedAddress = nil
        isResolvingAddress = false   // 검색 선택은 주소 즉시 확보 — 역지오 진행 표시 해제.
        proceedToForm()
    }

    // MARK: - 단계 전이(위치 확정 ↔ 메모·태그 폼)

    /// 위치 확정(검색 선택 / 콕찍기 완료) → 메모·태그 폼 단계로 전이(웹 MemoTagPanelContent).
    /// 장소 이름은 검색=장소명, 콕찍기=주소/좌표로 시드하고 사용자가 편집할 수 있다.
    func proceedToForm() {
        formPlaceName = seedPlaceName
        step = .form
    }

    /// 메모·태그 폼에서 "취소" → 위치 확정 단계로 복귀(웹은 패널 닫힘이나, iOS 인라인은 단계 뒤로).
    /// 폼 입력값을 초기화해 재진입 시 잔여를 제거한다.
    func backToLocate() {
        step = .locate
        selectedTag = nil
        memo = ""
        instagramUrl = ""
        urlError = nil
        pendingPhoto = nil
        errorMessage = nil
    }

    /// 폼 진입 시 장소 이름 시드값. 검색=장소명, 콕찍기=역지오 주소(없으면 좌표 문자열).
    private var seedPlaceName: String {
        switch inputMode {
        case .search:
            return selectedPlace?.placeName ?? ""
        case .pinpoint:
            if let address = resolvedAddress, !address.isEmpty { return address }
            guard let center = pinpointCenter else { return "" }
            let lat = MapViewModel.roundCoordinate(center.latitude)
            let lng = MapViewModel.roundCoordinate(center.longitude)
            return String(format: "%.7f, %.7f", lat, lng)
        }
    }

    /// 릴스 링크 형식 검증(웹 정합). 빈 값은 정상(nil), https:// 미시작 시 오류 메시지.
    private static func validateUrl(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return nil }
        if !trimmed.hasPrefix("https://") { return "올바른 URL 형식이 아닙니다" }
        return nil
    }

    // MARK: - 콕찍기(FR-14, AC-8)

    /// 메인 지도 드래그(cameraIdle) → 콕찍기 전환. query 비움 + .pinpoint + 중심 추적 + 디바운스 역지오 트리거.
    /// 역지오는 ReverseGeocoder 결과, 실패 시 coordinateFallback(AC-9)로 resolvedAddress 를 채운다.
    ///
    /// 작업 B-2 가드: 검색으로 진입(entryMode==.search)했으면 지도 드래그를 무시한다.
    ///  검색 모드 진입 후 지도만 움직여도 검색이 콕찍기로 날아가던 버그 차단(검색 결과/선택 유지).
    ///  콕찍기로 진입(entryMode==.pinpoint)한 경우만 드래그가 좌표를 갱신한다.
    ///  (콕찍기 진입의 초기 seed/줌인 idle 도 .pinpoint 경로라 정상 통과한다.)
    func onMapMoved(center: Coordinate) {
        guard entryMode == .pinpoint else { return }   // B-2 — 검색 진입 시 드래그로 콕찍기 전환 안 함.
        guard !isCreating else { return }   // 등록 진행 중 지도 드래그로 인한 상태 불일치 방지(Gemini MEDIUM).
        query = ""                  // AC-8 — 콕찍기 시작 시 검색어 초기화.
        inputMode = .pinpoint
        selectedPlace = nil
        results = []
        didSearch = false
        errorMessage = nil
        pinpointCenter = center
        isResolvingAddress = true   // BR-4 — 새 중심 역지오 시작(하단 카드 "주소를 찾는 중...").
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
            isResolvingAddress = false   // BR-4 — 이 좌표 역지오 완료.
        } else {
            guard inputMode == .pinpoint, pinpointCenter == center else { return }
            resolvedAddress = ReverseGeocoder.coordinateFallback(
                lat: center.latitude,
                lng: center.longitude
            )
            isResolvingAddress = false   // BR-4 — 좌표 폴백으로 확정(더 이상 "찾는 중" 아님).
        }
    }

    // MARK: - 확정(FR-15)

    /// 메모·태그 폼 "저장"(웹 MemoTagPanelContent.handleSave). 폼 입력(태그/장소명/메모/사진/릴스)으로 핀 생성.
    /// 호출부(InlineAddPlaceCard 폼)는 비-async 로 호출하고, 내부에서 createTask 로 실행한다(Q6a, AC-19).
    /// MEMORY + 보관 사진이 있으면 생성 후 발급된 pinId 로 2-step 업로드(웹 정합, BR-6 — 업로드 실패는 핀 생성 무효화 안 함).
    func submit() {
        guard let tag = selectedTag else { return }
        createTask?.cancel()
        createTask = Task { [weak self] in
            await self?.performCreate(tag: tag)
        }
    }

    /// 핀 생성 실제 본체(submit 의 Task 내부에서 실행). validatePinInput 재사용(BR-4 장소명 ≤200자·좌표 범위).
    /// appendPin/flyTo/didCreate 직전에 Task.isCancelled 가드 — 취소(모드 종료)된 생성이 지도/상태를 건드리지 않게 한다.
    private func performCreate(tag: PinTag) async {
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
            var created = try await mapViewModel.pinAPI.create(groupId: groupId, request: request)
            // 생성 응답 도착 전에 모드가 종료(취소)됐으면 지도/상태를 건드리지 않는다(Q6a, AC-19).
            guard !Task.isCancelled else { return }
            // 2-step(웹 정합) — MEMORY 핀 + 보관 사진이 있으면 발급된 pinId 로 업로드.
            //  업로드 실패는 핀 생성을 무효화하지 않는다(BR-6) — 핀은 그대로 반영하고 안내만 노출한다.
            if tag == .MEMORY, let photo = pendingPhoto,
               let jpegData = ImageCropper.resizeAndCompress(photo) {
                if let uploaded = try? await mapViewModel.pinAPI.uploadPhoto(
                    groupId: created.groupId, pinId: created.id, imageData: jpegData
                ) {
                    created = uploaded
                } else {
                    photoWarning = "핀은 저장됐지만 사진 업로드에 실패했어요. 수정에서 다시 시도할 수 있어요."
                }
                guard !Task.isCancelled else { return }
            }
            mapViewModel.appendPin(created)
            // #3 — 생성 직후 상세 말풍선 자동 표시. selectedPinId 세팅 후 flyTo 의 카메라 이동이
            //  cameraMoved 로 말풍선 화면좌표를 운반해 말풍선이 자동으로 뜬다(G1 — 프로그래밍 선택은 카메라 이동이 운반자).
            mapViewModel.selectedPinId = created.id
            mapViewModel.flyTo(lat: created.latitude, lng: created.longitude, zoom: MapViewModel.pinFocusZoom)
            didCreate = true
            // 견고화(review LOW) — 생성 성공 즉시 인라인 모드 종료. didCreate 설정과 같은 @MainActor 런루프에서 직접 종료해
            // MapView onChange(addPlaceVM?.didCreate) Optional 체인 관찰 누락 창을 제거한다. flyTo 의 프로그래매틱 idle 은
            // pendingProgrammaticIdle 가 흡수하므로 종료 전 콕찍기 onMapMoved 가 끼어들지 않는다(MUST-1).
            // cross-review HIGH — exitAddPin 직전 취소 가드(AC-19 대칭, appendPin/catch 와 동일): 외부에서 이미 종료(취소)됐으면 중복 회피.
            guard !Task.isCancelled else { return }
            // performCreate Task body 가 self(AddPlaceViewModel)를 strong 유지하므로 exitAddPin(addPlaceVM=nil) 후에도
            // 아래 return/defer 까지 self 는 유효하다(@MainActor 동기 연속 실행, 중간 await 없음).
            mapViewModel.exitAddPin()
            return
        } catch let error as APIError {
            guard !Task.isCancelled else { return }
            // BR-2 403 GROUP_NOT_MEMBER 포함 코드별 한국어 매핑(MapViewModel 공유 헬퍼).
            errorMessage = MapViewModel.message(for: error)
        } catch {
            guard !Task.isCancelled else { return }
            errorMessage = "핀을 추가하지 못했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    // MARK: - 모드 종료 시 진행 작업 취소(MUST-3, AC-19)

    /// 인라인 추가 모드 종료(MapViewModel.exitAddPin)에서 호출. 디바운스 역지오 + 핀 생성 Task 를 모두 취소한다.
    /// "취소했는데 뒤늦게 역지오/생성"이 발생하지 않도록 한다(MUST-3).
    /// UI 상태(query/results/didSearch)는 VM 폐기(addPlaceVM=nil)로 복원되므로 여기선 비동기 작업 취소만 수행한다(MEDIUM-5).
    func cancelPendingWork() {
        debouncer.cancel()
        createTask?.cancel()
        createTask = nil
        // HIGH-2 — 조기 종료 경로(performCreate 의 defer 미실행)에서도 취소 버튼 .disabled(isCreating) 가 굳지 않도록 명시 리셋.
        isCreating = false
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

    /// 콕찍기 "완료"(위치 확정) 활성 여부. 중심 좌표를 확보했는지(웹 AddPinPickerContent — center 있을 때만).
    var canProceed: Bool {
        pinpointCenter != nil
    }

    /// 메모·태그 폼 "저장" 활성 여부(웹 canSubmit 정합). 태그 선택 + 장소명 비어있지 않음 + URL 유효 + 생성 중 아님.
    var canSubmit: Bool {
        guard !isCreating, urlError == nil, selectedTag != nil else { return false }
        let trimmedName = formPlaceName.trimmingCharacters(in: .whitespacesAndNewlines)
        return !(trimmedName.isEmpty && seedPlaceName.isEmpty)
    }

    // MARK: - 초기 카메라 seed(＋진입 FR-9)

    /// ＋ 진입 시 초기 콕찍기 좌표 참조(FR-9). 메인 지도의 마지막 중심(mapViewModel.mapCenter)을 우선 쓰고,
    /// 아직 cameraIdle 이 없었으면(mapCenter == nil) 서울시청 좌표로 폴백한다.
    /// 줌은 콕찍기 시가지 레벨(pinFocusZoom) — seoulCityHall(zoom3 전국뷰)은 콕찍기엔 부적합해 좌표만 차용한다.
    /// MapViewModel.seedInitialPinpoint 가 이 좌표로 진입 즉시 콕찍기 중심을 확정한다(제품 결정: 자동 콕찍기 허용).
    var initialCameraTarget: CameraTarget {
        let center = mapViewModel?.mapCenter ?? Coordinate(latitude: 37.5, longitude: 127.0)
        return CameraTarget(
            latitude: center.latitude,
            longitude: center.longitude,
            zoom: MapViewModel.pinFocusZoom
        )
    }

    // MARK: - Private 헬퍼

    /// 폼 입력(장소명/메모/릴스) + 위치(검색 좌표 또는 콕찍기 7자리 중심)로 생성 요청을 만든다.
    /// 장소명은 폼 편집값 우선(빈 값이면 시드값으로 폴백). 콕찍기 좌표는 7자리 반올림. 입력 미정 시 nil.
    private func buildRequest(tag: PinTag) -> CreatePinRequest? {
        let trimmedName = formPlaceName.trimmingCharacters(in: .whitespacesAndNewlines)
        let placeName = trimmedName.isEmpty ? seedPlaceName : trimmedName
        let trimmedMemo = memo.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedUrl = instagramUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        switch inputMode {
        case .search:
            guard let place = selectedPlace else { return nil }
            return CreatePinRequest(
                placeName: placeName,
                address: place.address,
                latitude: place.latitude,
                longitude: place.longitude,
                instagramUrl: trimmedUrl.isEmpty ? nil : trimmedUrl,
                memo: trimmedMemo.isEmpty ? nil : trimmedMemo,
                tag: tag
            )
        case .pinpoint:
            guard let center = pinpointCenter else { return nil }
            let lat = MapViewModel.roundCoordinate(center.latitude)
            let lng = MapViewModel.roundCoordinate(center.longitude)
            return CreatePinRequest(
                placeName: placeName,
                address: resolvedAddress,
                latitude: lat,
                longitude: lng,
                instagramUrl: trimmedUrl.isEmpty ? nil : trimmedUrl,
                memo: trimmedMemo.isEmpty ? nil : trimmedMemo,
                tag: tag
            )
        }
    }
}
