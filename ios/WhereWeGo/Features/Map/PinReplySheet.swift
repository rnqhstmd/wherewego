import SwiftUI

// 핀 답장 시트(PIN_REPLY → 그룹 채팅방). 핀 상세 말풍선의 "답장" 버튼이 띄우는 경량 시트.
// 구성(위→아래): 핀 미니 카드(글리프 + 장소명) / 한마디 입력(1~3줄) / 전송 버튼(cta 원형 ↑).
// 전송은 MapViewModel.sendPinReply(pinId:text:) 에 위임 — 성공 시 onClose(말풍선은 유지), 실패 시 인라인 에러.
//  · 빈 입력·2000자 초과·전송 중에는 전송 비활성(GroupChatViewModel.messageMaxLength 와 동치).
struct PinReplySheet: View {
    let pin: PinSummary
    @ObservedObject var mapViewModel: MapViewModel
    /// 시트 닫기(전송 성공/사용자 취소). 호출처가 showReplySheet=false 로 연결.
    var onClose: () -> Void

    /// 답장 본문 초안. 전송 성공 시 시트가 닫히며 폐기.
    @State private var draft: String = ""
    /// 전송 중 표시(중복 전송·닫기 차단). 전송 완료 시 해제.
    @State private var isSending = false
    /// 전송 실패 인라인 에러 문구. nil 이면 미표시.
    @State private var inlineError: String?
    @FocusState private var inputFocused: Bool

    /// 답장 최대 길이(TEXT/PIN_REPLY 백엔드 검증과 동치).
    private let maxLength = GroupChatViewModel.messageMaxLength

    private var trimmed: String {
        draft.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    private var canSend: Bool {
        !trimmed.isEmpty && trimmed.count <= maxLength && !isSending
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            pinMiniCard
            inputRow
            if let inlineError {
                Text(inlineError)
                    .font(WGFont.sans(12))
                    .foregroundStyle(WGColor.pinNew)
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .presentationDetents([.height(220)])
        .presentationDragIndicator(.visible)
        .onAppear { inputFocused = true }
    }

    // MARK: - 핀 미니 카드(글리프 + 장소명)

    /// 답장 대상 핀 요약 — 마커 미니 글리프(PinDetailContent.placeRow 선례) + 장소명 semibold.
    private var pinMiniCard: some View {
        HStack(alignment: .center, spacing: 7) {
            Image(uiImage: PinMarkerGlyphs.image(for: pin.tag))
                .resizable()
                .scaledToFit()
                .frame(
                    width: pin.tag == .MEMORY ? 16 : 12,
                    height: pin.tag == .MEMORY ? 16 : 12
                )
            Text(pin.placeName)
                .font(WGFont.sansSemiBold(15))
                .foregroundStyle(WGColor.ink)
                .lineLimit(1)
            Spacer(minLength: 0)
        }
    }

    // MARK: - 입력 행(한마디 + 전송)

    /// 한마디 입력(1~3줄) + cta 원형 전송 버튼. 빈 입력·초과·전송 중 비활성.
    private var inputRow: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField("이 장소에 대해 한마디…", text: $draft, axis: .vertical)
                .font(WGFont.sans(15))
                .foregroundStyle(WGColor.ink)
                .lineLimit(1...3)
                .focused($inputFocused)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(
                    RoundedRectangle(cornerRadius: 16)
                        .fill(WGColor.panel)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(trimmed.count > maxLength ? WGColor.pinNew : WGColor.hairline, lineWidth: 1)
                )

            Button {
                Task { await sendReply() }
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 31))
                    .foregroundStyle(canSend ? WGColor.cta : WGColor.inkFaint)
            }
            .disabled(!canSend)
            .accessibilityLabel("답장 보내기")
        }
    }

    // MARK: - 전송

    /// 답장 전송 → MapViewModel.sendPinReply 위임. 성공 시 시트 닫기(말풍선 유지), 실패 시 인라인 에러.
    private func sendReply() async {
        guard canSend else { return }
        inlineError = nil
        isSending = true
        defer { isSending = false }
        let sent = await mapViewModel.sendPinReply(pinId: pin.id, text: trimmed)
        if sent {
            onClose()
        } else {
            inlineError = "답장을 보내지 못했어요. 다시 시도해 주세요."
        }
    }
}
