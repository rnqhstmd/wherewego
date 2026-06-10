import SwiftUI

// 「장소 등록하기」 팝업(GC-2 FR-GC2-4). registerState 분기 렌더:
//  extracting("장소 추출 중…" 애니메이션) → wizard(ReelSaveWizard 재사용) → empty/failed 안내.
//  저장 완료는 시트 밖(채팅 하단 saveInfoMessage 배너) — ReelSaveWizard 가 제출과 동시에 닫히기 때문(봇 공용 무변경).
//  취소(추출 중 닫기) = 전체 취소(핀 0·상태 불변, ViewModel.dismissRegister).
struct ReelRegisterSheet: View {
    let state: GroupChatViewModel.RegisterState
    /// 위저드 제출(url, 전체 카드, 체크된 카드 id=WISH, 메모).
    let onSave: (_ url: String, _ cards: [PlaceCard], _ wishIDs: Set<String>, _ memo: String?) -> Void
    /// 닫기(취소/안내 닫기 공통).
    let onClose: () -> Void

    var body: some View {
        switch state {
        case .idle:
            EmptyView()
        case .extracting:
            extractingView
        case let .wizard(_, url, cards):
            ReelSaveWizard(
                cards: cards,
                onSubmit: { wishIDs, memo in onSave(url, cards, wishIDs, memo) },
                onClose: onClose
            )
        case let .empty(message):
            noticeView(icon: "mappin.slash", message: message)
        case let .failed(message):
            noticeView(icon: "exclamationmark.triangle", message: message)
        }
    }

    // MARK: - 추출 중(애니메이션)

    private var extractingView: some View {
        VStack(spacing: 16) {
            Spacer()
            ProgressView()
                .tint(WGColor.cta)
                .scaleEffect(1.3)
            Text("장소 추출 중…")
                .font(WGFont.serif(18))
                .foregroundStyle(WGColor.ink)
            Text("릴스에서 갈만한 곳을 찾고 있어요")
                .font(WGFont.sans(13))
                .foregroundStyle(WGColor.inkSoft)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(WGColor.bg)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }

    // MARK: - 안내(0곳/좌표 없음/실패) + 닫기

    private func noticeView(icon: String, message: String) -> some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: icon)
                .font(.system(size: 32))
                .foregroundStyle(WGColor.inkFaint)
            Text(message)
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Button { onClose() } label: {
                Text("닫기")
                    .font(WGFont.sans(14))
                    .fontWeight(.semibold)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(WGColor.panel)
                    .foregroundStyle(WGColor.ink)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
            }
            .padding(.horizontal, 20)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(WGColor.bg)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}
