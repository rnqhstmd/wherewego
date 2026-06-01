import SwiftUI

// 장소 검색 → 핀 추가 시트(설계 §3, FR-13/14).
// frontend/src/app/map/_components/AddPinPickerContent.tsx 의 검색→결과→태그→추가 UX 이식.
//
// 2단계 구성:
//  - .searching: 검색창 + 결과 목록(로딩/빈결과/에러).
//  - .picking(place): 선택 장소 요약 + 태그 선택(REEL/WISH/MEMORY) + 추가 버튼.
struct SearchPinSheet: View {
    @ObservedObject var mapViewModel: MapViewModel
    @StateObject private var viewModel: SearchPinViewModel

    @Environment(\.dismiss) private var dismiss

    init(mapViewModel: MapViewModel) {
        self.mapViewModel = mapViewModel
        _viewModel = StateObject(wrappedValue: SearchPinViewModel(mapViewModel: mapViewModel))
    }

    var body: some View {
        NavigationStack {
            content
                .background(WGColor.bg)
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        if case .picking = viewModel.phase {
                            Button("뒤로") { viewModel.backToSearch() }
                                .foregroundStyle(WGColor.cta)
                        }
                    }
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
    }

    private var title: String {
        switch viewModel.phase {
        case .searching: return "장소 검색"
        case .picking: return "핀 추가"
        }
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.phase {
        case .searching:
            searchingView
        case .picking(let place):
            pickingView(place)
        }
    }

    // MARK: - 검색 단계

    private var searchingView: some View {
        VStack(spacing: 0) {
            searchField
            if let error = viewModel.errorMessage {
                errorBanner(error)
            }
            resultsList
        }
    }

    private var searchField: some View {
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
        .padding(.top, 16)
        .padding(.bottom, 12)
    }

    @ViewBuilder
    private var resultsList: some View {
        if viewModel.results.isEmpty {
            Spacer(minLength: 0)
            if viewModel.didSearch {
                emptyState("검색 결과가 없어요", "다른 키워드로 다시 검색해 보세요.")
            } else {
                emptyState("가고 싶은 곳을 검색해 보세요", "장소 이름이나 주소로 찾을 수 있어요.")
            }
            Spacer(minLength: 0)
        } else {
            List {
                ForEach(viewModel.results) { place in
                    Button {
                        viewModel.select(place)
                    } label: {
                        resultRow(place)
                    }
                    .listRowBackground(WGColor.bg)
                    .listRowSeparatorTint(WGColor.hairline)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
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
        .padding(.vertical, 4)
    }

    // MARK: - 태그 선택 단계

    private func pickingView(_ place: PlaceItem) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 6) {
                Text(place.placeName)
                    .font(WGFont.serif(20))
                    .foregroundStyle(WGColor.ink)
                if let address = place.address, !address.isEmpty {
                    Text(address)
                        .font(WGFont.mono(12))
                        .foregroundStyle(WGColor.inkSoft)
                }
            }

            VStack(alignment: .leading, spacing: 10) {
                Text("어떤 핀으로 저장할까요?")
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkSoft)
                HStack(spacing: 10) {
                    ForEach(PinTag.allCases, id: \.self) { tag in
                        tagButton(tag)
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

            Spacer(minLength: 0)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func tagButton(_ tag: PinTag) -> some View {
        Button {
            Task { await viewModel.createPin(tag: tag) }
        } label: {
            HStack(spacing: 6) {
                Circle().fill(tagColor(tag)).frame(width: 8, height: 8)
                Text(tagLabel(tag))
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.ink)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(WGColor.panel)
            .overlay(Capsule().stroke(tagColor(tag), lineWidth: 1))
            .clipShape(Capsule())
        }
        .disabled(viewModel.isCreating)
    }

    // MARK: - 공통 작은 뷰

    private func emptyState(_ title: String, _ subtitle: String) -> some View {
        VStack(spacing: 8) {
            Text(title)
                .font(WGFont.emo(18))
                .foregroundStyle(WGColor.ink)
            Text(subtitle)
                .font(WGFont.sans(13))
                .foregroundStyle(WGColor.inkSoft)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 30)
    }

    private func errorBanner(_ message: String) -> some View {
        Text(message)
            .font(WGFont.sans(13))
            .foregroundStyle(WGColor.pinNew)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(WGColor.pinNew.opacity(0.1))
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .padding(.horizontal, 20)
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
}
