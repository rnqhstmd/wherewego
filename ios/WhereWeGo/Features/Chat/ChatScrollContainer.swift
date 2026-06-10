import SwiftUI

// 봇 방 채팅 스크롤 컨테이너(설계 §7 → 이벤트 전환: 연결 상태 배너 제거).
//  - 진입 시 최신 메시지로 스크롤(FR-15), 신규 메시지 도착 시 하단 추적.
//  - 상단 도달 시 onLoadMore 콜백(FR-2 과거 메시지 추가 로드).
//  - 키보드 회피(FR-16): ScrollView 기본 회피 + safeArea 입력바는 상위 화면이 배치.
//  - 빈 상태(FR-13/AC-19): emptyText 파라미터로 문구 주입.
//
// 순수 프레젠테이션 뷰. ViewModel 비참조 — 데이터([ChatFrame])·콜백만 파라미터로 받는다.
// messages 는 화면 표시 순서(오름차순: 오래된 → 최신)로 전달받는다(정렬은 상위 ViewModel 책임).
struct ChatScrollContainer: View {
    let messages: [ChatFrame]
    /// 빈 상태 안내 문구(FR-13/AC-19).
    let emptyText: String
    /// 상단 도달 시 과거 메시지 추가 로드(FR-2). 더 없으면 상위에서 no-op 처리.
    var onLoadMore: () -> Void = {}
    /// PLACE_CARDS 저장 콜백(ChatMessageRow → 상위 ViewModel 위임). (전체 카드, 체크된 카드 id=WISH, 공통 메모, 출처 릴스 URL).
    var onSavePlaceCards: ((_ cards: [PlaceCard], _ wishIDs: Set<String>, _ memo: String?, _ sourceInstagramUrl: String?) -> Void)?

    /// 하단 자동 스크롤 앵커 식별자.
    private let bottomAnchor = "chat-bottom-anchor"

    var body: some View {
        VStack(spacing: 0) {
            if messages.isEmpty {
                emptyState
            } else {
                messageList
            }
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
            // 신규 메시지 도착(마지막 id 변경) 시에만 하단 추적(FR-15, 버그 ① 동일 패턴).
            //  count 추적은 상단 loadMore 의 prepend 도 증가시켜 과거 로드 중 하단으로 튀는 회귀를 유발한다 →
            //  messages.last?.id(Int? Equatable)로 교체해 끝(append)에 새 프레임이 들어올 때만 반응.
            .onChange(of: messages.last?.id) { _, _ in
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
