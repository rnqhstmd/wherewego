import SwiftUI

// 위치 기반 룰렛(어디가지) 컴팩트 팝업(설계 §5, FR-20~24, AC-10/11).
// 지도 좌하단 FAB → 하단에 작게 떠오른다. 시스템 시트가 아닌 MapView 의 하단 오버레이로 표시되어
// 하단 탭바를 가리지 않는다(탭바 footprint 위에 배치).
//
// 결과 표현: 추첨 성공 시 MapView 가 지도를 결과 핀으로 이동시키고 핀 말풍선(상세)을 자동으로 연다.
// 따라서 이 팝업은 스핀/재추첨/안내만 담당해 작게 유지한다(주소·메모 등 상세는 말풍선이 보여줌).
//  - .idle/.spinning: 진행 스피너.
//  - .result: 거리 + 장소명 + "다시 뽑기"(컴팩트 바). 상세는 말풍선.
//  - .exhausted/.locationError: 짧은 안내 + 재시도.
struct RouletteView: View {
    @ObservedObject var viewModel: RouletteViewModel
    /// 팝업 닫기(showRoulette=false).
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(WGColor.hairline, lineWidth: 1))
        .shadow(color: WGColor.shadowMd, radius: 16, y: 6)
    }

    private var header: some View {
        HStack {
            HStack(spacing: 6) {
                Image(systemName: "dice.fill")
                    .font(.system(size: 14))
                    .foregroundStyle(WGColor.cta)
                Text("어디 가지?")
                    .font(WGFont.sans(15))
                    .fontWeight(.semibold)
                    .foregroundStyle(WGColor.ink)
            }
            Spacer()
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(WGColor.inkSoft)
            }
            .accessibilityLabel("닫기")
        }
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .idle, .spinning:
            spinningView
        case let .result(_, placeName, _, _, _, _, _):
            resultBar(placeName: placeName)
        case .exhausted:
            messageView(
                title: "추첨할 핀이 없어요",
                subtitle: "가까운 곳에 갈 만한 핀이 없어요. 핀을 추가해 보세요.",
                retryTitle: "다시 추첨"
            )
        case .locationError(let message):
            messageView(title: "위치 확인이 필요해요", subtitle: message, retryTitle: "다시 시도")
        }
    }

    // MARK: - 진행 중

    private var spinningView: some View {
        HStack(spacing: 10) {
            ProgressView().tint(WGColor.cta)
            Text("가까운 곳을 고르고 있어요…")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 4)
    }

    // MARK: - 결과(컴팩트 바). 상세(주소/메모)는 지도 말풍선이 보여준다.

    private func resultBar(placeName: String) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                if let distanceLabel = viewModel.distanceLabel {
                    Text(distanceLabel)
                        .font(WGFont.sans(12))
                        .foregroundStyle(WGColor.cta)
                }
                Text(placeName)
                    .font(WGFont.serif(16))
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Button {
                Task { await viewModel.reRoll() }
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "shuffle")
                    Text("다시")
                }
                .font(WGFont.sans(13))
                .foregroundStyle(WGColor.cta)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .overlay(Capsule().stroke(WGColor.cta.opacity(0.4), lineWidth: 1))
            }
        }
    }

    // MARK: - 안내(후보 0건 / 위치 오류)

    private func messageView(title: String, subtitle: String, retryTitle: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(WGFont.sans(15))
                .fontWeight(.semibold)
                .foregroundStyle(WGColor.ink)
            Text(subtitle)
                .font(WGFont.sans(13))
                .foregroundStyle(WGColor.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                Task { await viewModel.spin() }
            } label: {
                Text(retryTitle)
                    .font(WGFont.sans(14))
                    .padding(.horizontal, 18)
                    .padding(.vertical, 9)
                    .background(WGColor.cta)
                    .foregroundStyle(WGColor.panel)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            .padding(.top, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
