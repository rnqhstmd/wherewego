import SwiftUI

// 채팅 목록 화면(GC-2 FR-GC2-1). 인스타그램 DM 스타일 — 내 활성 그룹별 그룹 채팅방 목록.
//  - 상태별: 로딩 ProgressView / 빈 "참여 중인 그룹이 없어요" / 에러+재시도 / 목록.
//  - 목록 행(DMRoomRow): 그룹 아바타 + 그룹명 + 마지막 미리보기 + 시각 + 미읽음 강조(hasUnread).
//  - 행 탭 → openedRoom → navigationDestination 으로 그 그룹 채팅방 push.
//  - 읽음 갱신: 방 pop(openedRoom==nil) 시 refresh — 백엔드가 방 GET 시 읽음처리 → 목록 최신화.
//
// NavigationStack 안의 콘텐츠(navigationTitle 만 지정, 스택은 MainTabView 가 제공).
struct DMListView: View {
    // MainTabView 가 @StateObject 로 소유한 VM 을 그대로 주입받는다(소유 금지 — 배지 정합).
    @ObservedObject var viewModel: DMListViewModel
    /// 포그라운드 수신 신호(willPresent 현재 방). 방 화면(GroupChatView)에 전달.
    let pushSignal: ChatPushSignal
    /// room → 방 VM 팩토리(MainTabView 가 dependencies 캡처). 방 진입 시 @StateObject 로 1회 생성.
    let makeRoomViewModel: (GroupRoomSummary) -> GroupChatViewModel
    /// 진입한 방(navigationDestination 트리거). nil 이면 목록 표시.
    @State private var openedRoom: GroupRoomSummary?

    var body: some View {
        VStack(spacing: 0) {
            // 큰 제목 헤더(그룹목록/마이페이지 디자인 언어 정합).
            ScreenHeader(title: "채팅", subtitle: "그룹별 대화방")
            content
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(WGColor.bg)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await viewModel.load()
        }
        // 행 탭 → 그 그룹 채팅방 push. 방 VM 은 GroupChatRoomView 가 @StateObject 로 소유(인스타식 수명).
        .navigationDestination(item: $openedRoom) { room in
            GroupChatRoomView(
                room: room,
                pushSignal: pushSignal,
                makeViewModel: makeRoomViewModel
            )
            // groupId 변경 시 뷰 정체성을 새로 부여해 StateObject(방 VM)를 안전하게 재구성한다.
            .id(room.groupId)
        }
        // 방 pop(openedRoom==nil) → 읽음 갱신. 백엔드가 방 GET 시 읽음처리 → 목록 재조회로 반영.
        .onChange(of: openedRoom) { _, new in
            if new == nil {
                Task { await viewModel.refresh() }
            }
        }
    }

    // MARK: - 상태 분기

    @ViewBuilder
    private var content: some View {
        switch viewModel.loadState {
        case .idle, .loading:
            loadingView
        case let .loaded(rooms):
            if rooms.isEmpty {
                emptyView
            } else {
                listView(rooms)
            }
        case let .error(message):
            errorView(message)
        }
    }

    private var loadingView: some View {
        VStack {
            Spacer()
            ProgressView().tint(WGColor.cta)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // 빈 상태(그룹 0개)
    private var emptyView: some View {
        VStack(spacing: 8) {
            Spacer()
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 32))
                .foregroundStyle(WGColor.inkFaint)
            Text("참여 중인 그룹이 없어요")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func errorView(_ message: String) -> some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 28))
                .foregroundStyle(WGColor.inkFaint)
            Text(message)
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Button {
                Task { await viewModel.load() }
            } label: {
                Text("다시 시도")
                    .font(WGFont.sans(14))
                    .padding(.horizontal, 22)
                    .padding(.vertical, 12)
                    .background(WGColor.cta)
                    .foregroundStyle(WGColor.panel)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func listView(_ rooms: [GroupRoomSummary]) -> some View {
        // 카드 목록(그룹 목록 optionCard 디자인 언어 정합) — hairline 구분선 평면 리스트의 밋밋함 해소.
        ScrollView {
            LazyVStack(spacing: 10) {
                ForEach(rooms) { room in
                    Button {
                        openedRoom = room
                    } label: {
                        DMRoomRow(room: room, currentUserId: viewModel.currentUserId)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 4)
            .padding(.bottom, 16)
        }
    }
}

// MARK: - 방 화면 래퍼

/// 방 화면 래퍼 — 방별 GroupChatViewModel 을 @StateObject 로 소유(push 수명, pop 시 해제, 재진입 시 재생성).
struct GroupChatRoomView: View {
    private let groupName: String
    private let pushSignal: ChatPushSignal
    @StateObject private var viewModel: GroupChatViewModel

    init(room: GroupRoomSummary, pushSignal: ChatPushSignal, makeViewModel: @escaping (GroupRoomSummary) -> GroupChatViewModel) {
        self.groupName = room.groupName
        self.pushSignal = pushSignal
        _viewModel = StateObject(wrappedValue: makeViewModel(room))
    }

    var body: some View {
        GroupChatView(viewModel: viewModel, pushSignal: pushSignal, groupName: groupName)
    }
}

// MARK: - 채팅 방 행

/// 채팅 목록 1건 행. 그룹 아바타 + 그룹명 + 미리보기 + 시각/미읽음(hasUnread).
/// unread → 그룹명·미리보기 굵게 + 우측 강조점 + 옅은 cta 배경.
private struct DMRoomRow: View {
    let room: GroupRoomSummary
    /// 미리보기 "나:" 판정용 내 id.
    let currentUserId: Int?

    private var isUnread: Bool { room.hasUnread }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "bubble.left.and.bubble.right.fill")
                .font(.system(size: 18))
                .foregroundStyle(WGColor.cta)
                .frame(width: 44, height: 44)
                .background(WGColor.cta.opacity(0.10))
                .clipShape(Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text(room.groupName)
                    .font(WGFont.sans(15))
                    .fontWeight(isUnread ? .semibold : .regular)
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(1)

                Text(previewText)
                    .font(WGFont.sans(13))
                    .fontWeight(isUnread ? .semibold : .regular)
                    .foregroundStyle(room.lastPreview == nil ? WGColor.inkFaint : WGColor.inkSoft)
                    .lineLimit(1)
            }

            Spacer(minLength: 0)

            VStack(alignment: .trailing, spacing: 6) {
                if let lastAt = room.lastAt {
                    Text(DMListViewModel.formatTime(lastAt))
                        .font(WGFont.mono(11))
                        .foregroundStyle(WGColor.inkSoft)
                }
                if isUnread {
                    Circle()
                        .fill(WGColor.pinNew)
                        .frame(width: 7, height: 7)
                }
            }
            .padding(.top, 1)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        // 카드 스타일(그룹 목록 정합): 미읽음 = 옅은 cta 채움 + cta 테두리로 강조.
        .background(isUnread ? WGColor.cta.opacity(0.07) : WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(isUnread ? WGColor.cta.opacity(0.35) : WGColor.hairline, lineWidth: 1)
        )
        .contentShape(RoundedRectangle(cornerRadius: 16))
    }

    /// 미리보기 텍스트: 메시지 없음 → "아직 대화가 없어요". 내 메시지(lastSenderUserId == 내 id) → "나: …". 그 외 → 그대로.
    private var previewText: String {
        guard let preview = room.lastPreview else {
            return "아직 대화가 없어요"
        }
        if let me = currentUserId, room.lastSenderUserId == me {
            return "나: \(preview)"
        }
        return preview
    }
}
