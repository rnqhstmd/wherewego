import Foundation

// 장소 검색 → 핀 추가 ViewModel(설계 §3, FR-13/14).
// frontend/src/app/map/_components/AddPinPickerContent.tsx 의 검색→선택→태그→추가 UX 이식.
//
// 흐름:
//  1) 검색어 입력 → placeAPI.search(q) → 결과 목록(placeName/address) 표시.
//  2) 결과 1개 선택 → 태그 선택(REEL/WISH/MEMORY) → pinAPI.create(groupId:CreatePinRequest).
//  3) 생성 핀 → MapViewModel.appendPin + flyTo(lat:lng:zoom15). 시트 닫기는 View 가 처리.
//
// 의존(placeAPI/pinAPI/groupId)은 MapViewModel 에서 가져온다(pins 단일 출처/카메라 명령 위임).
@MainActor
final class SearchPinViewModel: ObservableObject {

    /// 검색 단계(설계 §3). 검색 → 결과 → (선택) 태그 입력.
    enum Phase: Equatable {
        case searching
        /// 결과에서 장소를 골라 태그를 정하는 단계.
        case picking(PlaceItem)
    }

    // MARK: - 게시 상태

    /// 검색창 입력값. 비어 있으면 검색 비활성.
    @Published var query: String = ""
    /// 검색 결과 목록.
    @Published private(set) var results: [PlaceItem] = []
    /// 검색 진행 중(스피너).
    @Published private(set) var isSearching = false
    /// 핀 생성 진행 중(중복 탭 방지).
    @Published private(set) var isCreating = false
    /// 검색했지만 결과가 0건인지(빈 결과 안내). 검색 전에는 false.
    @Published private(set) var didSearch = false
    /// 현재 단계.
    @Published var phase: Phase = .searching
    /// 인라인 에러 메시지.
    @Published var errorMessage: String?
    /// 생성 성공 시 true → View 가 시트를 닫는다.
    @Published private(set) var didCreate = false

    // MARK: - 의존성

    private weak var mapViewModel: MapViewModel?

    init(mapViewModel: MapViewModel) {
        self.mapViewModel = mapViewModel
    }

    // MARK: - 검색(FR-13)

    /// 검색어로 장소 검색. 공백 입력은 무시. 결과/에러를 게시 상태로 반영.
    func search() async {
        let keyword = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyword.isEmpty else { return }
        guard let mapViewModel else { return }
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

    // MARK: - 장소 선택 → 태그 단계(FR-14)

    /// 검색 결과에서 장소 1개 선택 → 태그 선택 단계로 전환.
    func select(_ place: PlaceItem) {
        errorMessage = nil
        phase = .picking(place)
    }

    /// 태그 선택 단계에서 검색 단계로 복귀(뒤로).
    func backToSearch() {
        errorMessage = nil
        phase = .searching
    }

    // MARK: - 핀 추가(FR-14)

    /// 선택 장소 + 태그로 핀 생성 → MapViewModel 에 append + 해당 위치로 flyTo.
    /// 성공 시 didCreate=true → View 가 시트를 닫는다.
    func createPin(tag: PinTag) async {
        guard case let .picking(place) = phase else { return }
        guard let mapViewModel else { return }
        guard let groupId = mapViewModel.groupId else {
            errorMessage = MapError.noActiveGroup.errorDescription
            return
        }
        errorMessage = nil
        // BR-4 클라이언트 검증(장소명 ≤200자·좌표 범위). 위반 시 즉시 안내 후 종료.
        do {
            try MapViewModel.validatePinInput(
                placeName: place.placeName,
                latitude: place.latitude,
                longitude: place.longitude
            )
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
            return
        }
        isCreating = true
        defer { isCreating = false }

        let request = CreatePinRequest(
            placeName: place.placeName,
            address: place.address,
            latitude: place.latitude,
            longitude: place.longitude,
            instagramUrl: nil,
            memo: nil,
            tag: tag
        )
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
}
