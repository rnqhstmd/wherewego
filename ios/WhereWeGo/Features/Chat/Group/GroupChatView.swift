import SwiftUI

// 그룹 채팅방 화면(GC-2 FR-GC2-2/3/4/6/8). 멤버 단체 채팅 + REEL_LINK 3상태 버블 + 추출 팝업.
//  - 메시지 스크롤(GroupMessageRow): 오름차순, 진입 시 최신 스크롤, 상단 도달 loadMore, 신규 도착 하단 추적.
//  - 입력바: 인스타 URL 단독이면 REEL_LINK, 그 외 TEXT(FR-GC2-8). TEXT 2000자 카운터.
//  - 추출 팝업: registerState 기반 .sheet(ReelRegisterSheet).
//  - 수신(FR-GC2-6): scenePhase .active 재조회 + ChatPushSignal.tick(willPresent 현재 방) 재조회. 폴링은 ViewModel.
//  - saveInfoMessage: 저장/중복 안내 배너(시트 밖, 봇 패턴 동치).
struct GroupChatView: View {
    @ObservedObject var viewModel: GroupChatViewModel
    /// 포그라운드 수신 신호(willPresent 현재 방 tick). 같은 인스턴스를 ViewModel 도 보유.
    @ObservedObject var pushSignal: ChatPushSignal
    /// 방의 그룹명(네비게이션 타이틀).
    let groupName: String

    @FocusState private var inputFocused: Bool
    @Environment(\.scenePhase) private var scenePhase

    private let bottomAnchor = "group-chat-bottom"

    var body: some View {
        VStack(spacing: 0) {
            messageScroll
            saveInfoBanner
            inputBar
        }
        .background(WGColor.bg)
        .animation(.easeOut(duration: 0.2), value: viewModel.saveInfoMessage)
        .navigationTitle(groupName)
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.appear() }
        .onDisappear { Task { await viewModel.disappear() } }
        // scenePhase 복귀 재조회(FR-GC2-6).
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await viewModel.reconcileLatest() } }
        }
        // 포그라운드 willPresent 가 현재 방 메시지를 통지(tick) → 재조회(FR-GC2-6).
        .onChange(of: pushSignal.tick) { _, _ in
            Task { await viewModel.reconcileLatest() }
        }
        // 추출 등록 팝업(FR-GC2-4).
        .sheet(isPresented: registerSheetBinding) {
            ReelRegisterSheet(
                state: viewModel.registerState,
                onSave: { url, cards, wishIDs, memo in
                    Task { await viewModel.saveFromWizard(url: url, cards: cards, wishIDs: wishIDs, memo: memo) }
                },
                onClose: { viewModel.dismissRegister() }
            )
        }
    }

    // MARK: - 메시지 스크롤

    @ViewBuilder
    private var messageScroll: some View {
        if viewModel.messages.isEmpty {
            emptyState
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 10) {
                        // 상단 도달 → 과거 메시지 추가 로드(FR-GC2-2).
                        Color.clear
                            .frame(height: 1)
                            .onAppear { Task { await viewModel.loadMore() } }

                        ForEach(viewModel.messages) { frame in
                            GroupMessageRow(
                                frame: frame,
                                currentUserId: viewModel.currentUserId,
                                onRegister: { messageId, url in viewModel.register(messageId: messageId, url: url) },
                                onOpenReel: { url in viewModel.openReel(url: url) }
                            )
                            .id(frame.id)
                        }

                        Color.clear
                            .frame(height: 1)
                            .id(bottomAnchor)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                }
                .scrollDismissesKeyboard(.interactively)
                .onAppear { scrollToBottom(proxy, animated: false) }
                // 마지막 메시지 id 변경(신규 append)일 때만 하단 추적(버그 ①, FR-GC2-1/BR-2).
                //  count 추적은 loadMore 의 prepend 도 증가시켜 과거 로드 중 하단으로 튀는 회귀를 유발한다 →
                //  messages.last?.messageId(Int? Equatable)로 교체해 신규 도착에만 반응. 빈 방 첫 append(nil→id)도 포함.
                .onChange(of: viewModel.messages.last?.messageId) { _, _ in
                    scrollToBottom(proxy, animated: true)
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Spacer()
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 36))
                .foregroundStyle(WGColor.inkFaint)
            Text("아직 대화가 없어요\n첫 메시지나 릴스 링크를 보내보세요 ✨")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 32)
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy, animated: Bool) {
        if animated {
            withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(bottomAnchor, anchor: .bottom) }
        } else {
            proxy.scrollTo(bottomAnchor, anchor: .bottom)
        }
    }

    // MARK: - 저장 안내 배너(시트 밖)

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
                Button { viewModel.saveInfoMessage = nil } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(WGColor.inkSoft)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(WGColor.cta.opacity(0.08))
            .overlay(alignment: .top) { Rectangle().fill(WGColor.hairline).frame(height: 1) }
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    // MARK: - 입력바(FR-GC2-8)

    private var inputBar: some View {
        let trimmed = viewModel.draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let isReel = InstagramURL.isReelURL(trimmed)
        let isOverLimit = !isReel && trimmed.count > GroupChatViewModel.messageMaxLength
        let canSend = !trimmed.isEmpty && !isOverLimit

        return VStack(spacing: 4) {
            HStack(alignment: .bottom, spacing: 10) {
                TextField("메시지를 입력하거나 릴스 링크를 붙여넣어 보세요", text: $viewModel.draft, axis: .vertical)
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
                    Image(systemName: isReel ? "paperplane.circle.fill" : "arrow.up.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(canSend ? WGColor.cta : WGColor.inkFaint)
                }
                .disabled(!canSend)
            }

            // TEXT 2000자 카운터(REEL_LINK 는 URL 이라 미표시).
            if !isReel, trimmed.count > GroupChatViewModel.messageMaxLength - 200 {
                HStack {
                    Spacer()
                    Text("\(trimmed.count)/\(GroupChatViewModel.messageMaxLength)")
                        .font(WGFont.mono(11))
                        .foregroundStyle(isOverLimit ? WGColor.pinNew : WGColor.inkSoft)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background(WGColor.bg)
        .overlay(alignment: .top) { Rectangle().fill(WGColor.hairline).frame(height: 1) }
    }

    private var registerSheetBinding: Binding<Bool> {
        Binding(
            get: { viewModel.registerState.isActive },
            set: { presented in if !presented { viewModel.dismissRegister() } }
        )
    }
}
