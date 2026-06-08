import SwiftUI

// DM 목록 화면(설계 §5, FR-1/2/3/6/7/8). 인스타그램 DM 스타일 — 내 활성 그룹별 봇 방 목록.
//  - 상태별: 로딩 ProgressView / 빈 "참여 중인 그룹이 없어요" / 에러+재시도(FR-7) / 목록.
//  - 목록 행(DMRoomRow): 그룹 아바타 + 그룹명 + 마지막 미리보기(FR-8) + 시각 + 미읽음 강조(FR-2).
//  - 행 탭 → openedRoom 세팅 → navigationDestination 으로 그 그룹 봇 채팅 방 push(FR-3).
//  - 읽음 갱신(FR-6): 방 pop(openedRoom==nil) 시 refresh — 백엔드가 방 GET 시 읽음처리 → 목록 최신화(AC-4).
//
// NotificationInboxView 의 로딩/빈/에러 분기 패턴과 WGColor/WGFont 사용을 그대로 따른다.
// NavigationStack 안의 콘텐츠(navigationTitle 만 지정, 스택은 MainTabView 가 제공).
struct DMListView: View {
    // MainTabView 가 @StateObject 로 소유한 VM 을 그대로 주입받는다(소유 금지 — 배지 정합).
    @ObservedObject var viewModel: DMListViewModel
    /// groupId → 방 VM 팩토리(MainTabView 가 dependencies 캡처). 방 진입 시 @StateObject 로 1회 생성.
    let makeRoomViewModel: (Int) -> BotChatViewModel
    /// 진입한 방(navigationDestination 트리거). nil 이면 목록 표시.
    @State private var openedRoom: BotRoomSummary?

    var body: some View {
        content
            .background(WGColor.bg)
            .navigationTitle("DM")
            .navigationBarTitleDisplayMode(.inline)
            // 최초 진입 로드(list, FR-1). 이미 .loaded 면 깜빡임 없이 갱신.
            .task {
                await viewModel.load()
            }
            // 행 탭 → 그 그룹 봇 채팅 방 push(FR-3). 방 VM 은 BotChatRoomView 가 @StateObject 로 소유(인스타식 수명).
            .navigationDestination(item: $openedRoom) { room in
                BotChatRoomView(
                    groupId: room.groupId,
                    groupName: room.groupName,
                    makeViewModel: makeRoomViewModel
                )
                // groupId 변경 시 뷰 정체성을 새로 부여해 StateObject(방 VM)를 안전하게 재구성한다.
                //  navigationDestination(item:)이 nil 경유 없이 다른 room 으로 갱신될 때(딥링크/빠른 갱신)
                //  기존 StateObject 가 잔존해 잘못된 방 데이터를 표시하는 것을 방지(PR #108 Gemini 리뷰 반영).
                .id(room.groupId)
            }
            // 방 pop(openedRoom==nil) → 읽음 갱신(FR-6/AC-4). 백엔드가 방 GET 시 읽음처리 → 목록 재조회로 반영.
            .onChange(of: openedRoom) { _, new in
                if new == nil {
                    Task { await viewModel.refresh() }
                }
            }
    }

    // MARK: - 상태 분기(NotificationInboxView 패턴)

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

    // MARK: - 로딩

    private var loadingView: some View {
        VStack {
            Spacer()
            ProgressView()
                .tint(WGColor.cta)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - 빈 상태(FR-7 — 그룹 0개)

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

    // MARK: - 에러 + 재시도(FR-7)

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

    // MARK: - 목록

    private func listView(_ rooms: [BotRoomSummary]) -> some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(rooms) { room in
                    Button {
                        openedRoom = room
                    } label: {
                        DMRoomRow(room: room)
                    }
                    .buttonStyle(.plain)

                    Rectangle()
                        .fill(WGColor.hairline)
                        .frame(height: 1)
                }
            }
        }
    }
}

// MARK: - 방 화면 래퍼

/// 방 화면 래퍼 — 방별 BotChatViewModel 을 @StateObject 로 소유(push 수명, pop 시 해제, 재진입 시 재생성).
/// 인스타식 수명: 백엔드 읽음 시맨틱(방 GET 시 읽음처리)과 정합 — 재진입마다 재로드된다.
struct BotChatRoomView: View {
    private let groupName: String
    @StateObject private var viewModel: BotChatViewModel

    init(groupId: Int, groupName: String, makeViewModel: @escaping (Int) -> BotChatViewModel) {
        self.groupName = groupName
        // StateObject wrappedValue: 최초 1회만 평가(이후 재계산에서도 동일 인스턴스 유지).
        //  wrappedValue 는 @autoclosure @escaping 이라 makeViewModel 도 @escaping 이어야 캡처 가능.
        _viewModel = StateObject(wrappedValue: makeViewModel(groupId))
    }

    var body: some View {
        BotChatView(viewModel: viewModel, groupName: groupName)
    }
}

// MARK: - DM 방 행

/// DM 목록 1건 행(설계 §5). 그룹 아바타 + 그룹명 + 미리보기(FR-8) + 시각/미읽음(FR-2).
/// unread → 그룹명·미리보기 굵게 + 우측 강조점 + 옅은 cta 배경(NotificationRow 미읽음 패턴 동치).
private struct DMRoomRow: View {
    let room: BotRoomSummary

    private var isUnread: Bool { room.unread }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            // 그룹 아바타(SF 말풍선 + 원형 배경) — 인스타 DM 프로필 자리.
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
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(isUnread ? WGColor.cta.opacity(0.06) : Color.clear)
        .contentShape(Rectangle())
    }

    /// 미리보기 텍스트(FR-8): 메시지 없음 → "아직 대화가 없어요". USER → "나: …". 그 외 → 미리보기 그대로.
    private var previewText: String {
        guard let preview = room.lastPreview else {
            return "아직 대화가 없어요"
        }
        if room.lastSenderType == .USER {
            return "나: \(preview)"
        }
        return preview
    }
}
