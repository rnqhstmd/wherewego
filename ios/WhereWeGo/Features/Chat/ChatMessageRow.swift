import SwiftUI

// 채팅 메시지 1건(ChatFrame) 렌더(설계 §7, FR-8/14, AC-19).
// kind/senderType 분기:
//  - TEXT      : USER 우측 정렬(cta 배경·panel 텍스트), BOT/상대 좌측 정렬(panel 배경·ink 텍스트) — FR-14
//  - PROCESSING: 좌측 로딩 점 애니메이션(결과 도착 시 숨김은 상위 ViewModel 책임; 이 뷰는 표시만)
//  - PLACE_CARDS: PlaceCardsBubble 위임(선택/저장 콜백은 상위 주입)
//  - MEMO_PROMPT: 좌측 안내 버블(FR-7)
//  - SYSTEM    : 중앙 정렬 캡션(FR-8)
//
// 순수 프레젠테이션 뷰. ViewModel 비참조 — 데이터(ChatFrame)+콜백(onSavePlaceCards)만 파라미터로 받는다.
struct ChatMessageRow: View {
    let frame: ChatFrame
    /// PLACE_CARDS 저장 콜백. (선택된 카드, 출처 messageId) → 상위(ViewModel)가 핀 저장 처리.
    /// PLACE_CARDS 가 아닌 메시지에는 사용되지 않는다.
    var onSavePlaceCards: (([PlaceCard], Int) -> Void)?

    var body: some View {
        switch frame.kind {
        case .TEXT:
            textBubble
        case .PROCESSING:
            ProcessingDots()
                .frame(maxWidth: .infinity, alignment: .leading)
        case .PLACE_CARDS:
            PlaceCardsBubble(
                cards: frame.placeCards ?? [],
                onSave: { selected in onSavePlaceCards?(selected, frame.messageId) }
            )
            .frame(maxWidth: .infinity, alignment: .leading)
        case .MEMO_PROMPT:
            memoPromptBubble
        case .SYSTEM:
            systemCaption
        }
    }

    // MARK: - TEXT(FR-14)

    /// USER 는 우측·cta 배경, BOT/그 외는 좌측·panel 배경.
    private var isOutgoing: Bool { frame.senderType == .USER }

    private var textBubble: some View {
        HStack {
            if isOutgoing { Spacer(minLength: 48) }
            Text(frame.text ?? "")
                .font(WGFont.sans(15))
                .foregroundStyle(isOutgoing ? WGColor.panel : WGColor.ink)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(isOutgoing ? WGColor.cta : WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(isOutgoing ? Color.clear : WGColor.hairline, lineWidth: 1)
                )
            if !isOutgoing { Spacer(minLength: 48) }
        }
    }

    // MARK: - MEMO_PROMPT(FR-7 안내 버블)

    private var memoPromptBubble: some View {
        HStack {
            Text(frame.text ?? "")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.cta)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(WGColor.cta.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 16))
            Spacer(minLength: 48)
        }
    }

    // MARK: - SYSTEM(FR-8 중앙 정렬 캡션)

    private var systemCaption: some View {
        Text(frame.text ?? "")
            .font(WGFont.sans(12))
            .foregroundStyle(WGColor.inkSoft)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .center)
    }
}

// MARK: - PROCESSING 로딩 점 애니메이션

/// BOT 처리 중 표시용 3-점 펄스 버블(좌측 정렬). 결과 도착 시 상위에서 이 프레임을 제거한다.
private struct ProcessingDots: View {
    @State private var phase = 0

    private let timer = Timer.publish(every: 0.35, on: .main, in: .common).autoconnect()

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(WGColor.inkSoft)
                    .frame(width: 7, height: 7)
                    .opacity(phase == index ? 1.0 : 0.3)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(WGColor.hairline, lineWidth: 1))
        .onReceive(timer) { _ in
            withAnimation(.easeInOut(duration: 0.3)) {
                phase = (phase + 1) % 3
            }
        }
        .accessibilityLabel("응답을 기다리는 중")
    }
}
