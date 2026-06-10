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
    /// 첫 진입 앵커 스크롤 1회 가드(미읽음 위치부터 진입).
    @State private var didInitialScroll = false
    /// 하단 근접 여부(하단 센티널 가시성) — 신규 도착 시 자동 스크롤 vs 배너 분기.
    @State private var isNearBottom = true
    /// 위로 스크롤 중 신규 메시지 도착 배너("새 메시지가 있어요").
    @State private var showNewMessagePill = false

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
                    LazyVStack(spacing: 0) {
                        // 상단 도달 → 과거 메시지 추가 로드(FR-GC2-2).
                        Color.clear
                            .frame(height: 1)
                            .onAppear { Task { await viewModel.loadMore() } }

                        // 카톡식 그루핑: 같은 발신자+같은 분 묶음은 간격 2pt 로 붙이고,
                        // 닉네임/아바타는 묶음 첫 메시지·시간은 묶음 마지막 메시지에만.
                        ForEach(Array(viewModel.messages.enumerated()), id: \.element.id) { index, frame in
                            let chainedWithPrev = viewModel.isChainedWithPrevious(at: index)
                            GroupMessageRow(
                                frame: frame,
                                currentUserId: viewModel.currentUserId,
                                showsSender: !chainedWithPrev,
                                showsTime: !viewModel.isChainedWithNext(at: index),
                                onRegister: { messageId, url in viewModel.register(messageId: messageId, url: url) },
                                onOpenReel: { url in viewModel.openReel(url: url) }
                            )
                            .id(frame.id)
                            .padding(.top, index == 0 ? 0 : (chainedWithPrev ? 2 : 12))
                        }

                        // 하단 센티널: 가시성으로 "하단 근접" 추적(신규 도착 시 자동 스크롤 vs 배너 분기).
                        Color.clear
                            .frame(height: 1)
                            .id(bottomAnchor)
                            .onAppear {
                                isNearBottom = true
                                showNewMessagePill = false
                            }
                            .onDisappear { isNearBottom = false }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                }
                .scrollDismissesKeyboard(.interactively)
                // 첫 진입: 미읽음이 있으면 첫 미읽음 메시지부터(앵커 상단), 없으면 맨 아래로.
                .onAppear {
                    if !didInitialScroll, let anchor = viewModel.initialUnreadAnchorId {
                        proxy.scrollTo(anchor, anchor: .top)
                    } else {
                        scrollToBottom(proxy, animated: false)
                    }
                    didInitialScroll = true
                }
                // 마지막 메시지 id 변경(신규 append)일 때만 반응(버그 ①, FR-GC2-1/BR-2 — count 추적은 prepend 에도 발화).
                //  하단 근접/내 전송이면 자동 스크롤, 위로 스크롤해 읽는 중이면 "새 메시지" 배너만(강제 이동 금지).
                .onChange(of: viewModel.messages.last?.messageId) { _, _ in
                    let isMine = viewModel.currentUserId != nil
                        && viewModel.messages.last?.senderUserId == viewModel.currentUserId
                    if isNearBottom || isMine {
                        scrollToBottom(proxy, animated: true)
                        showNewMessagePill = false
                    } else {
                        showNewMessagePill = true
                    }
                }
                // "새 메시지가 있어요" 배너 — 탭하면 맨 아래로.
                .overlay(alignment: .bottom) {
                    if showNewMessagePill {
                        Button {
                            scrollToBottom(proxy, animated: true)
                            showNewMessagePill = false
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "arrow.down")
                                    .font(.system(size: 11, weight: .semibold))
                                Text("새 메시지가 있어요")
                                    .font(WGFont.sansSemiBold(12))
                            }
                            .foregroundStyle(WGColor.panel)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Capsule().fill(WGColor.cta))
                            .shadow(color: WGColor.shadowMd, radius: 8, y: 3)
                        }
                        .padding(.bottom, 10)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .animation(.easeOut(duration: 0.18), value: showNewMessagePill)
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

        // 플로팅 캡슐 입력바(인스타 DM·FloatingTabBar 디자인 언어 정합) — 풀폭 바+상단 구분선 대신 떠 있는 필.
        //  전송 버튼을 필 내부 우측에 배치, 배경은 panel+그림자(콘텐츠 위에 부유).
        return VStack(spacing: 4) {
            HStack(alignment: .bottom, spacing: 6) {
                TextField("메시지를 입력하거나 릴스 링크를 붙여넣어 보세요", text: $viewModel.draft, axis: .vertical)
                    .font(WGFont.sans(15))
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(1...4)
                    .focused($inputFocused)
                    .padding(.leading, 18)
                    .padding(.vertical, 12)

                Button {
                    Task { await viewModel.send() }
                } label: {
                    Image(systemName: isReel ? "paperplane.circle.fill" : "arrow.up.circle.fill")
                        .font(.system(size: 31))
                        .foregroundStyle(canSend ? WGColor.cta : WGColor.inkFaint)
                }
                .disabled(!canSend)
                .padding(.trailing, 6)
                .padding(.bottom, 6)
            }
            .background(
                RoundedRectangle(cornerRadius: 22)
                    .fill(WGColor.panel)
                    .shadow(color: WGColor.shadowMd, radius: 12, y: 4)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 22)
                    .stroke(isOverLimit ? WGColor.pinNew : WGColor.hairline, lineWidth: 1)
            )

            // TEXT 2000자 카운터(REEL_LINK 는 URL 이라 미표시).
            if !isReel, trimmed.count > GroupChatViewModel.messageMaxLength - 200 {
                HStack {
                    Spacer()
                    Text("\(trimmed.count)/\(GroupChatViewModel.messageMaxLength)")
                        .font(WGFont.mono(11))
                        .foregroundStyle(isOverLimit ? WGColor.pinNew : WGColor.inkSoft)
                }
                .padding(.horizontal, 8)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .padding(.bottom, 10)
    }

    private var registerSheetBinding: Binding<Bool> {
        Binding(
            get: { viewModel.registerState.isActive },
            set: { presented in if !presented { viewModel.dismissRegister() } }
        )
    }
}
