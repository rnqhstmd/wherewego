import SwiftUI

// 인라인 핀 추가 하단 확정 카드(설계 §컴포넌트②, FR-4/FR-10/AC-3). 웹 AddPinPickerContent + 검색 통합 동치.
// AddPlaceSheet 의 searchBar/resultsList/confirmCard/태그토글/제출/에러배너를 하단 카드로 이식한다
// (시트 전용 NavigationStack/toolbar/dismiss/독립맵은 제거 — 메인 지도 cameraIdle 재사용).
//
// VM(AddPlaceViewModel) 은 MapViewModel 이 소유하고 여기선 @ObservedObject 로 관찰만 한다.
// 검색 결과 선택·취소는 카드가 직접 카메라/모드를 만지지 않고 콜백으로 MapView 에 위임한다(B2 계약).
struct InlineAddPlaceCard: View {
    @ObservedObject var viewModel: AddPlaceViewModel
    /// 검색 결과 선택 → MapView 에서 selectResult + 메인 cameraCommand flyTo(AC-9).
    let onSelectResult: (PlaceItem) -> Void
    /// 취소 → MapView 에서 MapViewModel.exitAddPin().
    let onCancel: () -> Void

    /// 확정 카드에서 선택한 태그(설계 §4 — 태그 3종 + "여기 등록"). 기본 위시.
    @State private var selectedTag: PinTag = .WISH

    var body: some View {
        VStack(spacing: 0) {
            // 검색 모드일 때만 검색바 노출(P8 영역4 후속 모드 분리). 콕찍기 모드는 지도 십자선으로 위치를 지정하므로
            //  검색바를 숨겨 화면을 단순화한다. 검색 중 지도를 드래그하면 콕찍기로 전환되며 검색바가 사라진다(inputMode 추종).
            if viewModel.inputMode == .search {
                searchBar
            }
            confirmCard
        }
    }

    // MARK: - 검색바 + 결과(FR-10, AC-8)

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
            .padding(.horizontal, 12)
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
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 12)
        } else if viewModel.didSearch {
            // 무결과 안내(FR-10). 검색했으나 0건.
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

    /// 검색 결과 선택 → 카메라/VM 갱신은 MapView 에 위임(B2 계약). 키보드만 내린다.
    private func select(_ place: PlaceItem) {
        onSelectResult(place)
        hideKeyboard()
    }

    // MARK: - 하단 확정 카드(FR-4, 태그 3종)

    private var confirmCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            // 바텀시트임을 표시하는 공통 드래그 핸들(글래스 통일).
            DragHandle()

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

            actionButtons
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        // 글래스 통일: 불투명 panel fill + 단일 shadow 를 glassCard(Material+hairline+그림자)로 대체.
        .glassCard(cornerRadius: 18)
        .padding(.horizontal, 12)
        .padding(.top, 10)
    }

    @ViewBuilder
    private var placeSummary: some View {
        if let title = viewModel.confirmTitle {
            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .font(WGFont.serif(19))
                    .foregroundStyle(WGColor.ink)
                // 역지오 진행 중이면 "주소를 찾는 중...", 아니면 주소/좌표 폴백(BR-4, AC-B3/B4).
                if viewModel.isResolvingAddress {
                    Text("주소를 찾는 중...")
                        .font(WGFont.mono(12))
                        .foregroundStyle(WGColor.inkSoft)
                } else if let address = viewModel.confirmAddress, !address.isEmpty {
                    Text(address)
                        .font(WGFont.mono(12))
                        .foregroundStyle(WGColor.inkSoft)
                }
            }
        } else if viewModel.isResolvingAddress {
            Text("주소를 찾는 중...")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
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

    // MARK: - 취소 + 여기 등록(FR-7/FR-8, AC-7)

    private var actionButtons: some View {
        HStack(spacing: 10) {
            Button(action: onCancel) {
                Text("취소")
                    .font(WGFont.sans(14))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(WGColor.bg)
                    .foregroundStyle(WGColor.ink)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            // MUST-3/AC-19 — 생성 진행 중에는 취소 비활성(생성 Task 취소 경쟁 방지, BR-3 일관).
            .disabled(viewModel.isCreating)

            Button {
                viewModel.createPin(tag: selectedTag)
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
