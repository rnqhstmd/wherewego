import SwiftUI

// 핀 상세 말풍선 오버레이(설계 §6, D-1/D-5, FR-9). 풀 모달 시트(PinDetailSheet) 대체.
// frontend/src/components/ui/SpeechBubblePopup.tsx(말풍선+꼬리) 시각 동치.
//
// 구조(.position 적용 단위 분리):
//  - 전체화면 투명 배경탭(BR-3 isPhotoBusy 가드): ZStack 전체에 깔린다. `.position` 영향 안 받음(바깥 탭 = 닫기).
//  - 말풍선 본체+꼬리(bubble): 그 자체에만 `.position` 적용 → 마커 위로 띄우고 꼬리 끝이 마커를 향하게 한다.
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

    /// 측정한 말풍선 전체 높이(본체+꼬리). 측정 전에는 추정치로 위치를 잡고 onPreferenceChange 로 보정(1프레임 깜빡임 최소화).
    /// 초기값 220 = PreferenceKey 측정 전 1프레임 추정치(헤더+태그+메모 2줄 기준 평균 높이). 측정 후 보정되며 점프 폭은 DoD-B 시각 확인.
    @State private var bubbleHeight: CGFloat = 220

    /// 말풍선 최대 폭(웹 SpeechBubblePopup 동치, compact). 초과 콘텐츠는 내부 ScrollView(FR-9).
    private let maxBubbleWidth: CGFloat = 280
    /// 말풍선 본체 최대 높이(FR-9). 초과 시 내부 ScrollView 스크롤. 값(480)은 DoD-B(Mac) 미세조정 여지.
    private let maxBubbleHeight: CGFloat = 480
    /// 꼬리 크기(웹 svg 22x12 동치). 본체 하단 중앙에서 아래로 향한다.
    private let tailWidth: CGFloat = 22
    private let tailHeight: CGFloat = 12
    /// 꼬리 끝과 마커 사이 간격(웹 calc(-100% - 16px) 의 16px 동치). 픽셀 정밀 일치는 DoD-B(Mac) 미세조정.
    private let markerGap: CGFloat = 16

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
            // 전체화면 투명 배경탭(BR-3): 바깥 탭 시 닫기. 단, 진행 중이면 닫기 무시(silent 실패 방지, N2).
            //  - isPhotoBusy: 사진 업로드/삭제 중.
            //  - isMutating: 태그/메모/장소명 저장·삭제(DELETE 응답 대기) 중 — 닫히면 결과/실패 inlineError 를 놓친다.
            // ZStack 전체에 깔려 `.position` 영향을 받지 않는다 — 말풍선 본체와 적용 단위 분리(AC-2).
            Color.clear
                .contentShape(Rectangle())
                .ignoresSafeArea()
                .onTapGesture {
                    guard !detailVM.isPhotoBusy, !detailVM.isMutating else { return }
                    closeBubble()
                }

            // 말풍선 본체+꼬리만 마커 위로 앵커. `.position` 은 뷰 중심을 좌표에 놓으므로
            // 꼬리 끝(VStack 맨 아래)이 마커(anchor.y) 근처에 오도록 중심 y 를 위로 보정한다.
            bubble
                .position(
                    x: anchor.x,
                    y: anchor.y - bubbleHeight / 2 - markerGap
                )
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
                    onRequestClose: closeBubble
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
        // 말풍선 전체 높이 측정 → 중심 y 보정(꼬리 끝이 마커를 향하도록). 측정 전 1프레임은 추정치(220) 사용.
        .background(
            GeometryReader { geo in
                Color.clear.preference(key: BubbleHeightKey.self, value: geo.size.height)
            }
        )
        .onPreferenceChange(BubbleHeightKey.self) { height in
            if height > 0 { bubbleHeight = height }
        }
    }

    // MARK: - 닫기

    /// 말풍선 닫기(배경탭/삭제 완료). 단일 출처(selectedPinId)와 말풍선 화면좌표를 함께 해제한다(AC-5/D-4 보존 무관).
    private func closeBubble() {
        mapViewModel.selectedPinId = nil
        mapViewModel.clearSelectedPinScreenPoint()
    }
}

// MARK: - 말풍선 높이 측정 PreferenceKey

/// 말풍선(본체+꼬리) 전체 높이를 상위로 전달해 `.position` 중심 y 보정에 사용(꼬리 끝 = 마커 방향).
private struct BubbleHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
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
