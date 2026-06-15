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
    /// 등록 → MapView 에서 등록 폼(PinRegisterForm) 표시. 종류·메모·인스타·장소명은 폼에서 한 번에 입력, 폼 제출 시 생성.
    let onRegister: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            // 검색 중(미선택): 검색바+결과만 노출. 결과를 선택(selectedPlace)하거나 콕찍기면 취소/등록 카드만 노출.
            //  (웹 검색 패널 → 선택 시 MemoTag 단계 전이 정합 — 검색 결과와 확정 카드가 동시에 뜨지 않는다.)
            if viewModel.inputMode == .search && viewModel.selectedPlace == nil {
                searchBar
            } else {
                confirmCard
            }
        }
    }

    // MARK: - 검색바 + 결과(FR-10, AC-8)

    private var searchBar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
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
                // 필드 박스가 남는 너비만 채우고 취소 자리를 확보(취소가 화면 밖으로 밀려나는 것 방지).
                .frame(maxWidth: .infinity)

                // 검색 중 취소(추가 모드 종료) — 인라인 오버레이엔 시트 헤더가 없어 검색바에 취소를 둔다.
                Button("취소") { onCancel() }
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.inkSoft)
                    .fixedSize()
            }
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
            placeSummary

            if let error = viewModel.errorMessage {
                errorBanner(error)
            }

            actionButtons
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .shadow(color: WGColor.shadow, radius: 12, y: -2)
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

    // MARK: - 취소 + 등록(FR-7/FR-8, AC-7)

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
            Button {
                onRegister()   // 등록 폼 표시(종류·메모·인스타·장소명 입력 → 폼 제출 시 생성).
            } label: {
                Text("등록")
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

    private func hideKeyboard() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil, from: nil, for: nil
        )
    }
}
