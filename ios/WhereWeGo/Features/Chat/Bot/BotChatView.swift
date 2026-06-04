import SwiftUI

// 봇 채팅 화면(설계 §5·§7, FR-2/3/5/6/7/13/16, BR-3, AC-2/3/4/19/20).
//  - ChatScrollContainer(공용): messages 오름차순, 빈 상태 "릴스 링크를 입력해보세요"(AC-19),
//    상단 loadMore(FR-2), 연결 배너 + 수동 재시도(QE-2/BR-8), PLACE_CARDS 저장 위임(onSavePlaceCards → savePlaceCards).
//  - 하단 입력바: TextField(draft 바인딩) + 전송 버튼 + 2000자 카운터(BR-3/AC-4). safeArea 하단 배치(키보드 회피 FR-16).
//  - saveInfoMessage: 카드 저장/409 흡수 안내 배너(AC-3, 에러 아님). 일정 시간 후/탭 시 해제.
//  - 라이프사이클: .task → appear(로드+구독), onDisappear → disappear(구독 해제).
//
// 사용자는 릴스 URL 텍스트만 전송한다(FR-6/BR-6/FR-27/AC-20). 미디어는 단말에 저장하지 않는다.
struct BotChatView: View {
    @ObservedObject var viewModel: BotChatViewModel

    /// 입력바 포커스(전송 후 유지/해제 제어).
    @FocusState private var inputFocused: Bool

    /// 포그라운드 복귀 감지(FR-4 — 백그라운드 수신/폴링 상한 초과 결과 보완 재조회).
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(spacing: 0) {
            ChatScrollContainer(
                messages: viewModel.messages,
                emptyText: "릴스 링크를 입력해보세요",
                onLoadMore: { Task { await viewModel.loadMore() } },
                onSavePlaceCards: { selected, messageId in
                    Task { await viewModel.savePlaceCards(selected, from: messageId) }
                }
            )

            saveInfoBanner

            inputBar
        }
        .background(WGColor.bg)
        .navigationTitle("어디가지 봇")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await viewModel.appear()
        }
        .onDisappear {
            Task { await viewModel.disappear() }
        }
        // 포그라운드 복귀 시 결과 재조회(FR-4 — 폴링 상한 초과/백그라운드 수신 보완).
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await viewModel.reconcileLatest() }
            }
        }
    }

    // MARK: - 카드 저장 안내 배너(AC-3)

    @ViewBuilder
    private var saveInfoBanner: some View {
        if let message = viewModel.saveInfoMessage {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 14))
                    .foregroundStyle(WGColor.cta)
                Text(message)
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.ink)
                Spacer(minLength: 0)
                Button {
                    viewModel.saveInfoMessage = nil
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(WGColor.inkSoft)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(WGColor.cta.opacity(0.08))
            .overlay(alignment: .top) {
                Rectangle().fill(WGColor.hairline).frame(height: 1)
            }
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    // MARK: - 입력바(FR-3/BR-3/AC-4)

    private var inputBar: some View {
        // trimmed 단일 기준으로 통일(AC-4) — ViewModel.send() 의 trimmed 가드와 정합.
        let trimmed = viewModel.draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedCount = trimmed.count
        let isOverLimit = trimmedCount > BotChatViewModel.messageMaxLength
        let canSend = trimmedCount > 0 && !isOverLimit

        return VStack(spacing: 4) {
            HStack(alignment: .bottom, spacing: 10) {
                TextField("릴스 링크를 붙여넣어 보세요", text: $viewModel.draft, axis: .vertical)
                    .font(WGFont.sans(15))
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(1...4)
                    .focused($inputFocused)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
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
            }

            // 2000자 카운터(BR-3/AC-4). trimmed 기준으로 통일. 초과 임박 시 표시·초과 시 강조.
            if trimmedCount > BotChatViewModel.messageMaxLength - 200 {
                HStack {
                    Spacer()
                    Text("\(trimmedCount)/\(BotChatViewModel.messageMaxLength)")
                        .font(WGFont.mono(11))
                        .foregroundStyle(isOverLimit ? WGColor.pinNew : WGColor.inkSoft)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background(WGColor.bg)
        .overlay(alignment: .top) {
            Rectangle().fill(WGColor.hairline).frame(height: 1)
        }
    }
}
