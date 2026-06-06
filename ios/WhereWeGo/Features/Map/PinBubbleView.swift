import SwiftUI

// 핀 상세 말풍선 오버레이(설계 §6, D-1/D-5, FR-9). 풀 모달 시트(PinDetailSheet) 대체.
// frontend/src/components/ui/SpeechBubblePopup.tsx(말풍선+꼬리) 시각 동치.
//
// 구조(.position 적용 단위 분리):
//  - 말풍선 본체+꼬리(bubble): 그 자체에만 `.position` 적용 → 마커 위로 띄우고 꼬리 끝이 마커를 향하게 한다.
//  - 전체화면 투명 배경탭은 제거됐다(#4): 말풍선이 지도 제스처(드래그/줌)를 가로막지 않게 한다.
//    닫기는 빈 지도 탭(MapEvent.mapTapped → MapViewModel selectedPinId nil)으로 처리한다.
// 앵커: BubbleOverlay 가 마커 화면점(anchor)을 넘기고, 여기서 말풍선 전체 높이 h 를 측정(PreferenceKey)해
//  중심 y 를 `anchor.y - h/2 - markerGap` 으로 보정한다(웹 translate(-50%, calc(-100% - 16px)) 동치 — 본체가 마커 위, 꼬리 끝이 마커 방향).
// 상태: detailVM(@StateObject) 가 사진 업로드/삭제 진행을 보유 — 배경탭 닫기 가드(BR-3)와 PinDetailContent 가 공유.
// 닫기: closeBubble() = selectedPinId nil + clearSelectedPinScreenPoint()(말풍선 화면좌표 해제).
struct PinBubbleView: View {
    let pin: PinSummary
    /// 마커 화면점(BubbleOverlay 가 selectedPinScreenPoint 를 넘김). 말풍선 꼬리 끝이 향할 좌표.
    let anchor: ScreenPoint
    @ObservedObject var mapViewModel: MapViewModel
    @StateObject private var detailVM: PinDetailViewModel

    /// 공유 카드 모달 표시(웹 PinShareSheet 동치). 모달은 말풍선 ScrollView clip 밖에서 전체화면 중앙에 떠야 하므로
    /// PinDetailContent 가 아닌 이 ZStack 최상위에 둔다(PinDetailContent 는 onRequestShare 콜백만 발사).
    @State private var showShareCard = false

    /// 말풍선 최대 폭(웹 SpeechBubblePopup 동치). 하단 날짜·작성자 행이 한 줄에 들어가도록 웹 일반(296)에 가깝게 280.
    /// (웹: 일반 296 / compact 240 — iOS 는 날짜 "2026.05.20" 전체 표시 확보 위해 280). 초과 콘텐츠는 내부 ScrollView(FR-9).
    private let maxBubbleWidth: CGFloat = 280
    /// 말풍선 본체 최대 높이(FR-9). 초과 시 내부 ScrollView 스크롤. 값(480)은 DoD-B(Mac) 미세조정 여지.
    private let maxBubbleHeight: CGFloat = 480
    /// 꼬리 크기(웹 svg 22x12 동치). 본체 하단 중앙에서 아래로 향한다.
    private let tailWidth: CGFloat = 22
    private let tailHeight: CGFloat = 12
    /// 꼬리 끝 y 오프셋(anchor 기준, tip = anchor.y - markerGap). 너무 작으면(음수) 말풍선이 마커를 덮어 가린다.
    /// 마커가 보이도록 작은 양수 간격을 둬 꼬리 끝이 마커 바로 위에 오게 한다(시각 확인으로 미세조정).
    private let markerGap: CGFloat = 4

    init(pin: PinSummary, anchor: ScreenPoint, mapViewModel: MapViewModel) {
        self.pin = pin
        self.anchor = anchor
        self.mapViewModel = mapViewModel
        _detailVM = StateObject(
            wrappedValue: PinDetailViewModel(pinAPI: mapViewModel.pinAPI, mapViewModel: mapViewModel)
        )
    }

    var body: some View {
        ZStack {
            // 전체화면 투명 배경탭은 제거됐다(#4) — 말풍선 열린 채로 지도 드래그/줌이 가능하게 한다.
            // 닫기는 빈 지도 탭(MapEvent.mapTapped → MapViewModel selectedPinId nil)으로 처리한다.

            // 말풍선 본체+꼬리만 마커 위로 앵커. 꼬리 끝(bottom-center)을 마커 위 markerGap 지점에 고정하고
            // 콘텐츠는 위로 자라게 한다. 커스텀 Layout 이 같은 패스에서 자식 높이를 읽어 배치하므로,
            // 사진 펼침/접힘으로 높이가 애니메이션돼도 꼬리가 마커에서 떨어지지 않는다(측정 지연 제거).
            TailAnchorLayout(tip: CGPoint(x: anchor.x, y: anchor.y - markerGap)) {
                bubble
            }

            // 공유 카드 모달(웹 PinShareSheet 동치) — 전체화면 중앙. ZStack 최상위라 말풍선 ScrollView clip·.position
            // 영향을 받지 않고 화면 중앙에 뜬다(backdrop 바깥 탭으로 닫힘).
            if showShareCard {
                PinShareCardSheet(pin: pin) { showShareCard = false }
            }
        }
    }

    // MARK: - 말풍선 본체 + 꼬리

    /// 본체(흰 카드) 아래에 꼬리(아래 방향 삼각형)를 붙인 말풍선. 꼬리 끝이 마커를 향한다.
    private var bubble: some View {
        VStack(spacing: 0) {
            ScrollView {
                PinDetailContent(
                    pin: pin,
                    mapViewModel: mapViewModel,
                    detailVM: detailVM,
                    onRequestClose: closeBubble,
                    onRequestShare: { showShareCard = true }
                )
            }
            // 본체: 흰 카드 + 라운드 + 그림자(웹 SpeechBubblePopup 컨테이너 동치). 외곽 테두리는 본체+꼬리 합쳐 BubbleShape 가 그린다(N1).
            // FR-9: 콘텐츠가 maxHeight 를 넘으면 내부 ScrollView 로 스크롤. .clipped() 를 background/clipShape
            // 전에 두어 스크롤 콘텐츠가 RoundedRectangle 밖으로 새지 않게 한다(maxHeight 480 은 DoD-B 미세조정 여지).
            .frame(maxWidth: maxBubbleWidth, maxHeight: maxBubbleHeight)
            .clipped()
            .background(WGColor.panel)
            .clipShape(RoundedRectangle(cornerRadius: 18))

            // 꼬리(아래 방향) — 본체 하단 중앙. 웹 svg 22x12 `M 0 0 L 11 11 L 22 0 Z` 동치. 채움만(테두리는 BubbleShape).
            BubbleTail()
                .fill(WGColor.panel)
                .frame(width: tailWidth, height: tailHeight)
        }
        // 본체+꼬리 외곽 테두리를 한 번에 그려 접합부 끊김·이중선 없이 연속(N1, 웹 border 연속 동치).
        .overlay(
            BubbleShape(cornerRadius: 18, tailWidth: tailWidth, tailHeight: tailHeight)
                .stroke(WGColor.hairline, lineWidth: 1)
        )
        .shadow(color: WGColor.shadowMd, radius: 16, y: 6)
        .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: - 닫기

    /// 말풍선 닫기(배경탭/삭제 완료). 단일 출처(selectedPinId)와 말풍선 화면좌표를 함께 해제한다(AC-5/D-4 보존 무관).
    private func closeBubble() {
        mapViewModel.selectedPinId = nil
        mapViewModel.clearSelectedPinScreenPoint()
    }
}

// MARK: - 말풍선 꼬리 앵커 레이아웃

/// 말풍선 꼬리 끝(bottom-center)을 마커 지점(tip)에 고정하고 콘텐츠는 위로 자라게 하는 레이아웃.
/// 기존 `.position(center = anchor.y - 측정높이/2)` 방식은 높이를 GeometryReader+PreferenceKey 로
/// 비동기 측정해 1프레임 지연이 있었고, 사진 펼침/접힘처럼 높이가 크게 바뀔 때 꼬리가 마커에서
/// 잠깐 떨어지거나 겹치는 현상이 있었다. 커스텀 Layout 은 같은 레이아웃 패스에서 자식 크기를 읽어
/// 배치하므로 지연이 없고, 높이가 애니메이션돼도 꼬리가 항상 마커에 붙은 채 부드럽게 위로 자란다.
private struct TailAnchorLayout: Layout {
    /// 꼬리 끝이 향할 화면 좌표(마커 위 markerGap 지점).
    var tip: CGPoint

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        // 오버레이로 부모가 제안한 영역(전체 화면)을 그대로 차지.
        proposal.replacingUnspecifiedDimensions()
    }

    /// 화면 상단 여백(상태바/노치). 사진 펼침처럼 말풍선이 커져 위로 넘칠 때 이 선에서 멈춰 화면 이탈을 막는다.
    private static let topInset: CGFloat = 64

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let bubble = subviews.first else { return }
        let size = bubble.sizeThatFits(.unspecified)
        // 기본: bottom-center(꼬리 끝)를 tip 에 고정 → 콘텐츠가 커지면 위로 자란다.
        var bottomY = bounds.minY + tip.y
        // 위로 자라다 상단을 넘으면(사진 펼침 등) 화면 안에 머물도록 아래로 민다(꼬리는 떨어지나 화면 이탈 방지).
        let minTop = bounds.minY + Self.topInset
        if bottomY - size.height < minTop {
            bottomY = minTop + size.height
        }
        bubble.place(
            at: CGPoint(x: bounds.minX + tip.x, y: bottomY),
            anchor: .bottom,
            proposal: ProposedViewSize(size)
        )
    }
}

// MARK: - 말풍선 꼬리(아래 방향 삼각형)

/// 본체 하단 중앙에서 아래로 향하는 삼각형 꼬리. 웹 SpeechBubblePopup svg(22x12 `M 0 0 L 11 11 L 22 0 Z`) 동치.
/// 상단 두 꼭짓점(좌상/우상)이 본체에 붙고, 가운데 아래 꼭짓점이 마커를 향한다.
/// 채움(fill) 전용 — 외곽 테두리는 BubbleShape 가 본체와 연속으로 한 번에 그린다(N1).
private struct BubbleTail: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.minY))      // 좌상(M 0 0)
        path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))   // 가운데 아래(L 11 11)
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))   // 우상(L 22 0)
        path.closeSubpath()
        return path
    }
}

// MARK: - 말풍선 외곽선(본체 라운드 사각형 + 하단 중앙 꼬리, 테두리 연속)

/// 본체(RoundedRectangle)와 꼬리(아래 삼각형)를 하나로 합친 외곽 윤곽(N1).
/// stroke 로 그리면 본체-꼬리 접합부에서 테두리가 끊기지 않고, 꼬리 윗변(본체와 맞닿는 변)은
/// 윤곽에서 빠져 이중선이 생기지 않는다. 전체 rect 의 하단 tailHeight 만큼을 꼬리 영역으로 본다.
private struct BubbleShape: Shape {
    let cornerRadius: CGFloat
    let tailWidth: CGFloat
    let tailHeight: CGFloat

    func path(in rect: CGRect) -> Path {
        // 본체 영역(꼬리 높이를 제외한 상단). 본체 하단변 중앙에서 꼬리로 빠져나간다.
        let bodyRect = CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: rect.height - tailHeight)
        let r = min(cornerRadius, min(bodyRect.width, bodyRect.height) / 2)
        let tailHalf = tailWidth / 2
        let tailLeftX = bodyRect.midX - tailHalf
        let tailRightX = bodyRect.midX + tailHalf
        let bodyBottom = bodyRect.maxY

        var path = Path()
        // 좌상단 모서리에서 시계방향으로 외곽을 그린다.
        path.move(to: CGPoint(x: bodyRect.minX + r, y: bodyRect.minY))
        // 상단변 → 우상단 모서리
        path.addLine(to: CGPoint(x: bodyRect.maxX - r, y: bodyRect.minY))
        path.addArc(
            center: CGPoint(x: bodyRect.maxX - r, y: bodyRect.minY + r),
            radius: r, startAngle: .degrees(-90), endAngle: .degrees(0), clockwise: false
        )
        // 우측변 → 우하단 모서리
        path.addLine(to: CGPoint(x: bodyRect.maxX, y: bodyBottom - r))
        path.addArc(
            center: CGPoint(x: bodyRect.maxX - r, y: bodyBottom - r),
            radius: r, startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false
        )
        // 하단변(우→꼬리 우측 진입점)
        path.addLine(to: CGPoint(x: tailRightX, y: bodyBottom))
        // 꼬리: 우측 빗변 → 아래 꼭짓점 → 좌측 빗변(윗변 제외)
        path.addLine(to: CGPoint(x: bodyRect.midX, y: rect.maxY))
        path.addLine(to: CGPoint(x: tailLeftX, y: bodyBottom))
        // 하단변(꼬리 좌측 진입점→좌하단 모서리)
        path.addLine(to: CGPoint(x: bodyRect.minX + r, y: bodyBottom))
        path.addArc(
            center: CGPoint(x: bodyRect.minX + r, y: bodyBottom - r),
            radius: r, startAngle: .degrees(90), endAngle: .degrees(180), clockwise: false
        )
        // 좌측변 → 좌상단 모서리
        path.addLine(to: CGPoint(x: bodyRect.minX, y: bodyRect.minY + r))
        path.addArc(
            center: CGPoint(x: bodyRect.minX + r, y: bodyRect.minY + r),
            radius: r, startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false
        )
        path.closeSubpath()
        return path
    }
}
