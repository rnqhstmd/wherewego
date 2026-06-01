import SwiftUI

// 위치 기반 룰렛 시트(설계 §5, FR-20~24, AC-10/11).
// frontend/src/app/map/_components/RouletteResultContent.tsx + RouletteSpinContent.tsx 이식.
//
// 상태별 표시:
//  - .idle/.spinning: 안내 + 진행 스피너.
//  - .result: 거리 강조 헤더 + 장소 카드(태그/장소명/주소/메모) + "지도에서 보기"/"다시".
//  - .exhausted: "추첨할 핀이 없어요"(AC-10).
//  - .locationError: 위치 권한 안내.
//  MEMORY 포함 토글(기본 OFF) → 추첨 풀 확장(AC-11).
struct RouletteSheet: View {
    @ObservedObject var mapViewModel: MapViewModel
    @StateObject private var viewModel: RouletteViewModel

    @Environment(\.dismiss) private var dismiss

    init(mapViewModel: MapViewModel, locationService: LocationServiceProtocol) {
        self.mapViewModel = mapViewModel
        _viewModel = StateObject(
            wrappedValue: RouletteViewModel(mapViewModel: mapViewModel, locationService: locationService)
        )
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                memoToggle
                content
                Spacer(minLength: 0)
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(WGColor.bg)
            .navigationTitle("가볼까 룰렛")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기") { dismiss() }
                        .foregroundStyle(WGColor.cta)
                }
            }
        }
        .task {
            if case .idle = viewModel.state {
                await viewModel.spin()
            }
        }
    }

    // MARK: - MEMORY 포함 토글(FR-23, AC-11)

    private var memoToggle: some View {
        Toggle(isOn: $viewModel.includeMemory) {
            HStack(spacing: 6) {
                Circle().fill(WGColor.pinMemory).frame(width: 8, height: 8)
                Text("추억 핀도 포함")
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.ink)
            }
        }
        .tint(WGColor.cta)
        .onChange(of: viewModel.includeMemory) { _, _ in
            Task { await viewModel.spin() }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .idle, .spinning:
            spinningView
        case let .result(_, placeName, address, memo, _, _, _):
            resultView(placeName: placeName, address: address, memo: memo)
        case .exhausted:
            exhaustedView
        case .locationError(let message):
            messageView(title: "위치 확인이 필요해요", subtitle: message, retryTitle: "다시 시도")
        }
    }

    // MARK: - 진행 중

    private var spinningView: some View {
        VStack(spacing: 14) {
            ProgressView().tint(WGColor.cta)
            Text("가까운 곳을 고르고 있어요...")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }

    // MARK: - 결과(AC-11)

    private func resultView(placeName: String, address: String?, memo: String?) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            if let distanceLabel = viewModel.distanceLabel {
                HStack(spacing: 6) {
                    Image(systemName: "location.fill")
                        .font(.system(size: 13))
                        .foregroundStyle(WGColor.cta)
                    Text(distanceLabel)
                        .font(WGFont.sans(13))
                        .foregroundStyle(WGColor.cta)
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                Text(placeName)
                    .font(WGFont.serif(18))
                    .foregroundStyle(WGColor.ink)
                if let address, !address.isEmpty {
                    Text(address)
                        .font(WGFont.mono(12))
                        .foregroundStyle(WGColor.inkSoft)
                }
                if let memo, !memo.isEmpty {
                    Text("\"\(memo)\"")
                        .font(WGFont.sans(14))
                        .foregroundStyle(WGColor.ink)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(WGColor.panel)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))

            HStack(spacing: 8) {
                Button {
                    viewModel.showOnMap()
                    dismiss()
                } label: {
                    Text("지도에서 보기")
                        .font(WGFont.sans(14))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(WGColor.cta)
                        .foregroundStyle(WGColor.panel)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }

                Button {
                    Task { await viewModel.reRoll() }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "shuffle")
                        Text("다시")
                    }
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.ctaSub)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
                }
            }
        }
    }

    // MARK: - 후보 0건(AC-10)

    private var exhaustedView: some View {
        messageView(
            title: "추첨할 핀이 없어요",
            subtitle: "가까운 곳에 릴스/위시 핀이 없어요. 핀을 추가하거나 추억 핀을 포함해 보세요.",
            retryTitle: "다시 추첨"
        )
    }

    private func messageView(title: String, subtitle: String, retryTitle: String) -> some View {
        VStack(spacing: 12) {
            Text(title)
                .font(WGFont.emo(18))
                .foregroundStyle(WGColor.ink)
            Text(subtitle)
                .font(WGFont.sans(13))
                .foregroundStyle(WGColor.inkSoft)
                .multilineTextAlignment(.center)
            Button {
                Task { await viewModel.spin() }
            } label: {
                Text(retryTitle)
                    .font(WGFont.sans(14))
                    .padding(.horizontal, 22)
                    .padding(.vertical, 11)
                    .background(WGColor.cta)
                    .foregroundStyle(WGColor.panel)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            .padding(.top, 2)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
    }
}
