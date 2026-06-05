import SwiftUI

// 위치 기반 룰렛 화면(설계 §5, FR-20~24, AC-10/11).
// frontend/src/app/map/_components/RouletteResultContent.tsx + RouletteSpinContent.tsx 이식.
//
// 룰렛("어디갈까")은 독립 탭에서 지도 위 시트로 환원(웹 MapClient activeSheet==="roulette" 정합, 작업 D):
//  - "지도" 화면 우상단 🎲 → .sheet(RouletteSheetContent). 표시 즉시 자동 추첨(MapView 가 isRoulettePresented 토글 시 spin() 트리거).
//  - "지도에서 보기" → showOnMap()(flyTo+정보창) 후 onShowOnMap 콜백으로 시트 dismiss(지도를 드러냄).
//  - VM 수명은 MainTabView 가 @StateObject 로 보유(시트 닫혀도 결과 유지, mapViewModel 공유).
//
// 본 파일 구성:
//  - RouletteSheetContent: 시트 래퍼(상단 "어디갈까" 타이틀 + 닫기 버튼 + RouletteView 본문). 글래스 시트 detents 는 MapView 가 지정.
//  - RouletteView: 토글/상태별 본문(시트·전체화면 공용 — 자체 타이틀/네비게이션 미보유).
//
// 상태별 표시:
//  - .idle/.spinning: 안내 + 진행 스피너.
//  - .result: 거리 강조 헤더 + 장소 카드(태그/장소명/주소/메모) + "지도에서 보기"/"다시".
//  - .exhausted: "추첨할 핀이 없어요"(AC-10).
//  - .locationError: 위치 권한 안내.
//  MEMORY 포함 토글(기본 OFF) → 추첨 풀 확장(AC-11).

/// 룰렛 시트 래퍼(작업 D). 상단 "어디갈까" 타이틀 + 닫기 버튼 헤더 + RouletteView 본문.
///  글래스 시트 표현(detents/배경/코너)은 호출부(MapView)가 .sheet 수식어로 지정한다.
struct RouletteSheetContent: View {
    @ObservedObject var viewModel: RouletteViewModel
    /// "지도에서 보기" → 시트 닫기(showOnMap() 직후 호출, MapView 가 isRoulettePresented=false).
    let onShowOnMap: () -> Void
    /// 헤더 닫기(✕) 버튼 → 시트 닫기.
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            header
            RouletteView(viewModel: viewModel, onShowOnMap: onShowOnMap)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    /// 상단 헤더(navigationTitle 대체 — 시트엔 NavigationStack 미사용). 좌측 "어디갈까" 타이틀 + 우측 닫기(✕).
    private var header: some View {
        HStack {
            Text("어디갈까")
                .font(WGFont.serif(18))
                .foregroundStyle(WGColor.ink)
            Spacer()
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(WGColor.inkSoft)
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(WGColor.panel.opacity(0.6)))
            }
            .accessibilityLabel("닫기")
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
        .padding(.bottom, 4)
    }
}

struct RouletteView: View {
    @ObservedObject var viewModel: RouletteViewModel
    /// "지도에서 보기" → 시트 닫기(MapView isRoulettePresented=false). showOnMap() 직후 호출.
    let onShowOnMap: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            memoToggle
            content
            Spacer(minLength: 0)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
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
                    // 결과 핀으로 카메라 이동 + 정보창(selectedPinId) 후 지도 탭으로 전환(AC-11).
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
