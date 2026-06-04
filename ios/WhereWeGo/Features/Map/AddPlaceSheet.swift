import SwiftUI

// ＋ 통합 장소 추가 시트(설계 §4, FR-12~16, AC-8/AC-9). SearchPinSheet + CrosshairAddView 를 하나로 흡수.
// MainTabView(＋ FAB) 와 MapView(EmptyMapCard) 두 진입점이 동일 컴포넌트를 사용한다(B3 배선).
//
// 구성(토글/탭 없음):
//  - 상단 검색바: query → search → 결과 리스트(선택 시 selectedPlace + 독립맵 flyTo).
//  - 중앙 독립 MapContainerView + 중앙 고정 핀 오버레이: 드래그(cameraIdle) → 콕찍기 전환(AC-8).
//  - 하단 확정 카드: 선택 장소명/주소(검색) 또는 좌표/역지오 주소(콕찍기) + 태그 3종 + "여기 등록".
//
// 독립맵(MUST-ADDRESS #2): 메인 mapViewModel 과 카메라/콕찍기 분리를 위해 시트가 자체 cameraCommand/
// fitBoundsCommand @State 를 보유한 MapContainerView 인스턴스를 둔다. 그 인스턴스의 cameraIdle 이벤트만
// AddPlaceViewModel.onMapMoved(center:) 로 전달해 center 를 갱신한다. mapViewModel 공유는 핀 생성 결과
// 반영(appendPin/flyTo)에만 쓴다. token 미설정 시 PlaceholderMapView 폴백(콕찍기 center=실렌더, DoD-B).
struct AddPlaceSheet: View {
    @ObservedObject var mapViewModel: MapViewModel
    @StateObject private var viewModel: AddPlaceViewModel

    @Environment(\.dismiss) private var dismiss

    /// 독립맵 카메라 명령(검색 선택 시 flyTo). MapContainerView 가 소비 후 nil 로 리셋(메인과 분리).
    @State private var mapCameraCommand: CameraTarget?
    /// 독립맵 fitBounds 명령(미사용이지만 MapContainerView 시그니처 충족). 항상 nil.
    @State private var mapFitBoundsCommand: [MapMarker]?
    /// 확정 카드에서 선택한 태그(설계 §4 — 태그 3종 + "여기 등록" 분리). 기본 위시.
    @State private var selectedTag: PinTag = .WISH

    init(mapViewModel: MapViewModel) {
        self.mapViewModel = mapViewModel
        _viewModel = StateObject(wrappedValue: AddPlaceViewModel(mapViewModel: mapViewModel))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchBar
                mapSection
                confirmCard
            }
            .background(WGColor.bg)
            .navigationTitle("장소 추가")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기") { dismiss() }
                        .foregroundStyle(WGColor.cta)
                }
            }
        }
        // 생성 성공 → 시트 닫기.
        .onChange(of: viewModel.didCreate) { _, created in
            if created { dismiss() }
        }
        // 독립맵 초기 카메라 1회 seed — 메인 지도 중심에서 시작(SDK 기본 카메라가 대서양으로 뜨던 결함 수정).
        // 이 seed 의 flyTo 가 onMapIdle→onMapMoved 로 이어져 진입 즉시 콕찍기 중심을 확정한다(자동 콕찍기 허용, 설계 §4 보강).
        // 이미 검색 선택/콕찍기 진행 중이거나 카메라 명령이 대기 중이면 건너뛴다(최초 진입 1회).
        .onAppear {
            guard mapCameraCommand == nil,
                  viewModel.selectedPlace == nil,
                  viewModel.pinpointCenter == nil else { return }
            mapCameraCommand = viewModel.initialCameraTarget
        }
    }

    // MARK: - 상단 검색바 + 결과(FR-13)

    private var searchBar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(WGColor.inkSoft)
                TextField("장소를 검색해 보세요", text: $viewModel.query)
                    .font(WGFont.sans(15))
                    .submitLabel(.search)
                    .onSubmit { Task { await viewModel.search() } }
                if viewModel.isSearching {
                    ProgressView().tint(WGColor.cta)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(WGColor.panel)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
            .padding(.horizontal, 20)
            .padding(.top, 14)
            .padding(.bottom, viewModel.results.isEmpty ? 0 : 8)

            resultsList
        }
    }

    @ViewBuilder
    private var resultsList: some View {
        if !viewModel.results.isEmpty {
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(viewModel.results) { place in
                        Button {
                            select(place)
                        } label: {
                            resultRow(place)
                        }
                        Divider().overlay(WGColor.hairline)
                    }
                }
            }
            .frame(maxHeight: 220)
            .background(WGColor.bg)
        } else if viewModel.didSearch {
            // 무결과 안내(FR-13). 검색했으나 0건.
            Text("검색 결과가 없어요")
                .font(WGFont.sans(13))
                .foregroundStyle(WGColor.inkSoft)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 22)
                .padding(.top, 8)
        }
    }

    private func resultRow(_ place: PlaceItem) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(place.placeName)
                .font(WGFont.sans(15))
                .foregroundStyle(WGColor.ink)
            if let address = place.address, !address.isEmpty {
                Text(address)
                    .font(WGFont.mono(12))
                    .foregroundStyle(WGColor.inkSoft)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .padding(.horizontal, 20)
        .padding(.vertical, 10)
    }

    /// 검색 결과 선택 → VM 상태 갱신 + 독립맵 flyTo(시트 자체 cameraCommand).
    private func select(_ place: PlaceItem) {
        viewModel.selectResult(place)
        mapCameraCommand = CameraTarget(
            latitude: place.latitude,
            longitude: place.longitude,
            zoom: MapViewModel.pinFocusZoom
        )
        hideKeyboard()
    }

    // MARK: - 중앙 독립맵 + 중앙 고정 핀(FR-14, 콕찍기 MUST-ADDRESS #2)

    private var mapSection: some View {
        ZStack {
            // 시트 전용 독립 MapContainerView — cameraIdle 만 onMapMoved 로 전달(메인 mapViewModel 과 분리).
            MapContainerView(
                markers: [],
                cameraCommand: $mapCameraCommand,
                fitBoundsCommand: $mapFitBoundsCommand,
                onEvent: handleMapEvent
            )

            // 중앙 고정 핀 오버레이(콕찍기 조준점). 드래그 시 지도만 움직이고 핀은 정중앙 고정.
            Image(systemName: "mappin")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(WGColor.cta)
                .shadow(color: WGColor.shadow, radius: 4, y: 2)
                .offset(y: -14)   // 핀 촉이 정중앙을 가리키도록 위로 보정.
                .allowsHitTesting(false)
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: 220)
        .clipped()
    }

    /// 독립맵 이벤트 처리. cameraIdle 만 소비해 콕찍기 center 갱신(나머지 이벤트는 무시 — 마커 없음).
    private func handleMapEvent(_ event: MapEvent) {
        guard case let .cameraIdle(lat, lng) = event else { return }
        viewModel.onMapMoved(center: Coordinate(latitude: lat, longitude: lng))
    }

    // MARK: - 하단 확정 카드(FR-15, 태그 3종)

    private var confirmCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            placeSummary

            VStack(alignment: .leading, spacing: 10) {
                Text("어떤 핀으로 저장할까요?")
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkSoft)
                HStack(spacing: 10) {
                    ForEach(PinTag.allCases, id: \.self) { tag in
                        tagToggle(tag, isOn: selectedTag == tag)
                    }
                }
            }

            if let error = viewModel.errorMessage {
                errorBanner(error)
            }

            if viewModel.isCreating {
                HStack(spacing: 8) {
                    ProgressView().tint(WGColor.cta)
                    Text("추가하는 중...")
                        .font(WGFont.sans(13))
                        .foregroundStyle(WGColor.inkSoft)
                }
            }

            submitButton
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .shadow(color: WGColor.shadow, radius: 12, y: -2)
        .padding(.horizontal, 12)
        .padding(.bottom, 12)
    }

    @ViewBuilder
    private var placeSummary: some View {
        if let title = viewModel.confirmTitle {
            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .font(WGFont.serif(19))
                    .foregroundStyle(WGColor.ink)
                if let address = viewModel.confirmAddress, !address.isEmpty {
                    Text(address)
                        .font(WGFont.mono(12))
                        .foregroundStyle(WGColor.inkSoft)
                }
            }
        } else {
            Text("검색하거나 지도를 움직여 위치를 정해 주세요.")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
        }
    }

    private func tagToggle(_ tag: PinTag, isOn: Bool) -> some View {
        Button {
            selectedTag = tag
        } label: {
            HStack(spacing: 6) {
                Circle().fill(tagColor(tag)).frame(width: 8, height: 8)
                Text(tagLabel(tag))
                    .font(WGFont.sans(14))
                    .foregroundStyle(isOn ? WGColor.ink : WGColor.inkSoft)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(isOn ? WGColor.panel : WGColor.bg)
            .overlay(Capsule().stroke(isOn ? tagColor(tag) : WGColor.hairline, lineWidth: 1))
            .clipShape(Capsule())
        }
        .disabled(viewModel.isCreating)
    }

    private var submitButton: some View {
        Button {
            Task { await viewModel.createPin(tag: selectedTag) }
        } label: {
            Text("여기 등록")
                .font(WGFont.sans(14))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(viewModel.canConfirm ? WGColor.cta : WGColor.cta.opacity(0.4))
                .foregroundStyle(WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .disabled(!viewModel.canConfirm)
    }

    // MARK: - 공통 작은 뷰

    private func errorBanner(_ message: String) -> some View {
        Text(message)
            .font(WGFont.sans(13))
            .foregroundStyle(WGColor.pinNew)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(WGColor.pinNew.opacity(0.1))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func tagColor(_ tag: PinTag) -> Color {
        switch tag {
        case .REEL: return WGColor.pinReel
        case .WISH: return WGColor.pinWish
        case .MEMORY: return WGColor.pinMemory
        }
    }

    private func tagLabel(_ tag: PinTag) -> String {
        switch tag {
        case .REEL: return "릴스"
        case .WISH: return "위시"
        case .MEMORY: return "추억"
        }
    }

    private func hideKeyboard() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil, from: nil, for: nil
        )
    }
}
