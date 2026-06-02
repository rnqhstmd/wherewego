import SwiftUI

// 봇 방/커플방 공용 채팅 스크롤 컨테이너(설계 §7, FR-2/13/15/16, QE-2/BR-8/AC-8/AC-19).
//  - 상단 ConnectionState 배너: 연결 중/재연결 중/연결 끊김(+수동 재시도) — QE-2/BR-8/AC-8.
//  - 진입 시 최신 메시지로 스크롤(FR-15), 신규 메시지 도착 시 하단 추적.
//  - 상단 도달 시 onLoadMore 콜백(FR-2 과거 메시지 추가 로드).
//  - 키보드 회피(FR-16): ScrollView 기본 회피 + safeArea 입력바는 상위 화면이 배치.
//  - 빈 상태(FR-13/AC-19): emptyText 파라미터로 봇/커플 문구 주입.
//
// 순수 프레젠테이션 뷰. ViewModel 비참조 — 데이터([ChatFrame])·상태(ConnectionState)·콜백만 파라미터로 받는다.
// messages 는 화면 표시 순서(오름차순: 오래된 → 최신)로 전달받는다(정렬은 상위 ViewModel 책임).
struct ChatScrollContainer: View {
    let messages: [ChatFrame]
    let connectionState: ConnectionState
    /// 빈 상태 안내 문구(봇/커플 다른 문구 주입, FR-13/AC-19).
    let emptyText: String
    /// 상단 도달 시 과거 메시지 추가 로드(FR-2). 더 없으면 상위에서 no-op 처리.
    var onLoadMore: () -> Void = {}
    /// 연결 끊김 시 수동 재시도(AC-8/BR-8).
    var onRetry: () -> Void = {}
    /// PLACE_CARDS 저장 콜백(ChatMessageRow → 상위 ViewModel 위임).
    var onSavePlaceCards: (([PlaceCard], Int) -> Void)?

    /// 하단 자동 스크롤 앵커 식별자.
    private let bottomAnchor = "chat-bottom-anchor"

    var body: some View {
        VStack(spacing: 0) {
            connectionBanner

            if messages.isEmpty {
                emptyState
            } else {
                messageList
            }
        }
    }

    // MARK: - 연결 상태 배너(QE-2/BR-8/AC-8)

    @ViewBuilder
    private var connectionBanner: some View {
        switch connectionState {
        case .connected:
            EmptyView()
        case .connecting:
            banner(text: "연결 중…", tint: WGColor.inkSoft, showRetry: false)
        case .reconnecting:
            banner(text: "재연결 중…", tint: WGColor.cta, showRetry: false)
        case .disconnected:
            banner(text: "연결이 끊겼어요", tint: WGColor.pinNew, showRetry: true)
        }
    }

    private func banner(text: String, tint: Color, showRetry: Bool) -> some View {
        HStack(spacing: 8) {
            Circle().fill(tint).frame(width: 7, height: 7)
            Text(text)
                .font(WGFont.sans(12))
                .foregroundStyle(WGColor.ink)
            Spacer(minLength: 0)
            if showRetry {
                Button(action: onRetry) {
                    Text("다시 연결")
                        .font(WGFont.sans(12))
                        .fontWeight(.semibold)
                        .foregroundStyle(WGColor.cta)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(tint.opacity(0.1))
        .overlay(alignment: .bottom) {
            Rectangle().fill(WGColor.hairline).frame(height: 1)
        }
    }

    // MARK: - 빈 상태(FR-13/AC-19)

    private var emptyState: some View {
        VStack(spacing: 10) {
            Spacer()
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 36))
                .foregroundStyle(WGColor.inkFaint)
            Text(emptyText)
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 32)
    }

    // MARK: - 메시지 리스트(FR-15 최신 스크롤, FR-2 상단 loadMore)

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 10) {
                    // 상단 도달 감지 → 과거 메시지 추가 로드(FR-2).
                    Color.clear
                        .frame(height: 1)
                        .onAppear { onLoadMore() }

                    ForEach(messages) { frame in
                        ChatMessageRow(frame: frame, onSavePlaceCards: onSavePlaceCards)
                            .id(frame.id)
                    }

                    // 하단 자동 스크롤 앵커.
                    Color.clear
                        .frame(height: 1)
                        .id(bottomAnchor)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
            }
            .scrollDismissesKeyboard(.interactively)
            // 진입 시 최신으로(FR-15).
            .onAppear {
                scrollToBottom(proxy, animated: false)
            }
            // 신규 메시지 도착 시 하단 추적(FR-15).
            .onChange(of: messages.count) { _, _ in
                scrollToBottom(proxy, animated: true)
            }
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy, animated: Bool) {
        if animated {
            withAnimation(.easeOut(duration: 0.25)) {
                proxy.scrollTo(bottomAnchor, anchor: .bottom)
            }
        } else {
            proxy.scrollTo(bottomAnchor, anchor: .bottom)
        }
    }
}
