import SwiftUI

// 위치 기반 룰렛 화면(설계 §5, FR-20~24, AC-10/11).
// frontend/src/app/map/_components/RouletteResultContent.tsx + RouletteSpinContent.tsx 이식.
//
// 룰렛("어디갈까")은 지도 위 시트가 아니라 하단 3번째 탭으로 편입됐다(룰렛 탭화):
//  - MainTabView 의 .roulette 탭 → NavigationStack { RouletteView } (navigationTitle "어디갈까" .inline).
//  - 탭 진입(selection==.roulette)할 때마다 자동 추첨(MainTabView 가 spin() 트리거).
//  - "지도에서 보기" → showOnMap()(flyTo+정보창) 후 onShowOnMap 콜백으로 지도 탭 전환(selection=.map).
//  - VM 수명은 MainTabView 가 @StateObject 로 보유(탭 전환에도 결과 유지, mapViewModel 공유).
//
// 본 파일 구성:
//  - RouletteView: 토글/상태별 본문(자체 타이틀/네비게이션 미보유 — 탭의 NavigationStack 이 타이틀 부여).
//
// 상태별 표시:
//  - .idle/.spinning: 안내 + 진행 스피너.
//  - .result: 거리 강조 헤더 + 장소 카드(태그/장소명/주소/메모) + "지도에서 보기"/"다시".
//  - .exhausted: "추첨할 핀이 없어요"(AC-10).
//  - .locationError: 위치 권한 안내.
//  추첨 풀 = REEL/WISH ∩ 화면 필터(웹 정합 — MEMORY 제외, 토글 UI 없음).

struct RouletteView: View {
    @ObservedObject var viewModel: RouletteViewModel
    /// "지도에서 보기" → 지도 탭으로 전환(MainTabView selection=.map). showOnMap() 직후 호출.
    let onShowOnMap: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            content
            Spacer(minLength: 0)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
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
                    // 결과 핀으로 카메라 이동 + 정보창(selectedPinId) 후 지도 탭으로 전환(AC-11, 룰렛 탭화 — selection=.map).
                    viewModel.showOnMap()
                    onShowOnMap()
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
            subtitle: "가까운 곳에 발견/위시 핀이 없어요. 핀을 추가해 보세요.",
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
