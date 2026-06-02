import SwiftUI

// 커플 1:1 채팅 화면(설계 §6/§7, FR-9~16, AC-5/6, BR-3).
//  - 상단: ChatScrollContainer(messages 오름차순, 연결 배너, 최신 스크롤, 상단 loadMore).
//    커플 방은 카드 없음 → onSavePlaceCards: nil. 빈 상태 "파트너에게 첫 메시지를 보내보세요"(AC-19).
//  - 하단: 입력바(텍스트 필드 + 전송 버튼 + 1000자 카운터, BR-3/AC-5).
//  - 진입/이탈에서 ViewModel.appear()/disappear() 연결(구독·로드·구독해제).
//
// 키보드 회피(FR-16): ChatScrollContainer 의 ScrollView 기본 회피 + safeArea 입력바는 이 화면이 배치.
struct CoupleChatView: View {
    @StateObject private var viewModel: CoupleChatViewModel

    init(viewModel: CoupleChatViewModel) {
        _viewModel = StateObject(wrappedValue: viewModel)
    }

    var body: some View {
        VStack(spacing: 0) {
            ChatScrollContainer(
                messages: viewModel.messages,
                connectionState: viewModel.realtimeState,
                emptyText: "파트너에게 첫 메시지를 보내보세요",
                onLoadMore: { Task { await viewModel.loadMore() } },
                onRetry: { Task { await viewModel.retryRealtime() } },
                onSavePlaceCards: nil
            )

            inputBar
        }
        .background(WGColor.bg)
        .task {
            await viewModel.appear()
        }
        .onDisappear {
            Task { await viewModel.disappear() }
        }
        .overlay(alignment: .top) {
            if let message = bannerMessage {
                infoBanner(message)
            }
        }
    }

    // MARK: - 입력바(BR-3/AC-5 1000자 카운터)

    private var inputBar: some View {
        // trimmed 단일 기준으로 통일(AC-5) — ViewModel.send() 의 trimmed 가드와 정합.
        let trimmed = viewModel.draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedCount = trimmed.count
        let isOverLimit = trimmedCount > CoupleChatViewModel.textMaxLength
        let canSend = trimmedCount > 0 && !isOverLimit

        return VStack(spacing: 4) {
            HStack(alignment: .bottom, spacing: 10) {
                TextField("메시지를 입력하세요", text: $viewModel.draft, axis: .vertical)
                    .font(WGFont.sans(15))
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(1...4)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(WGColor.panel)
                    .clipShape(RoundedRectangle(cornerRadius: 18))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18)
                            .stroke(isOverLimit ? WGColor.pinNew : WGColor.hairline, lineWidth: 1)
                    )

                Button {
                    Task { await viewModel.send() }
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(canSend ? WGColor.cta : WGColor.inkFaint)
                }
                .disabled(!canSend)
                .accessibilityLabel("전송")
            }

            // 1000자 카운터(BR-3/AC-5). trimmed 기준 통일. 초과 시 강조.
            HStack {
                Spacer()
                Text("\(trimmedCount)/\(CoupleChatViewModel.textMaxLength)")
                    .font(WGFont.sans(11))
                    .foregroundStyle(isOverLimit ? WGColor.pinNew : WGColor.inkSoft)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(WGColor.bg)
        .overlay(alignment: .top) {
            Rectangle().fill(WGColor.hairline).frame(height: 1)
        }
    }

    // MARK: - 파생 값

    /// 표시할 인라인 안내(전송 실패 우선, 없으면 로드 실패).
    private var bannerMessage: String? {
        viewModel.sendErrorMessage ?? viewModel.loadErrorMessage
    }

    private func infoBanner(_ message: String) -> some View {
        Text(message)
            .font(WGFont.sans(13))
            .foregroundStyle(WGColor.panel)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(WGColor.pinNew)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .padding(.top, 8)
            .padding(.horizontal, 16)
            .transition(.move(edge: .top).combined(with: .opacity))
    }
}
