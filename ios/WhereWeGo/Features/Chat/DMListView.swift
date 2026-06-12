import SwiftUI

// 채팅 목록 화면(GC-2 FR-GC2-1 / IG-1 인스타 리디자인). 인스타그램 DM 스타일 — 내 활성 그룹별 그룹 채팅방 목록.
//  - 상단: InstaNavBar("채팅")(편집 버튼 없음). 카드 → 플랫 행 56pt(IG-1).
//  - 행(DMRoomRow): 그룹 아바타 56 + 그룹명 + 미리보기(시각 인라인) + 미읽음 강조(굵기 + 우측 cta 점).
//  - 행 탭 → openedRoom → navigationDestination 으로 그 그룹 채팅방 push.
//  - 읽음 갱신: 방 pop(openedRoom==nil) 시 refresh — 백엔드가 방 GET 시 읽음처리 → 목록 최신화.
//
// NavigationStack 안의 콘텐츠(navigationTitle 만 지정, 스택은 MainTabView 가 제공).
struct DMListView: View {
    // MainTabView 가 @StateObject 로 소유한 VM 을 그대로 주입받는다(소유 금지 — 배지 정합).
    @ObservedObject var viewModel: DMListViewModel
    /// 그룹 컨텍스트(GP-1 FR-5). 방 썸네일을 room.groupId 로 groups 조인해 대표 이미지/콜라주를 그린다.
    ///  MainTabView 가 @StateObject 로 소유한 단일 인스턴스를 주입(목록 부트스트랩 시 이미지·멤버 동봉).
    @ObservedObject var groupContext: GroupContext
    /// 포그라운드 수신 신호(willPresent 현재 방). 방 화면(GroupChatView)에 전달.
    let pushSignal: ChatPushSignal
    /// room → 방 VM 팩토리(MainTabView 가 dependencies 캡처). 방 진입 시 @StateObject 로 1회 생성.
    let makeRoomViewModel: (GroupRoomSummary) -> GroupChatViewModel
    /// 진입한 방(navigationDestination 트리거). nil 이면 목록 표시.
    @State private var openedRoom: GroupRoomSummary?

    var body: some View {
        VStack(spacing: 0) {
            // 경량 상단바(IG-1). 편집(✏️) 버튼 없음(trailing 미지정).
            InstaNavBar(title: "채팅")
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
        // 플랫 행 목록(IG-1) — 카드/구분선 제거. 여백(.vertical 8)이 행을 구분.
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(rooms) { room in
                    Button {
                        openedRoom = room
                    } label: {
                        // room.groupId 로 그룹 목록 조인(GP-1 FR-5) — 대표 이미지/콜라주 입력. 미로딩 시 nil → 이니셜 폴백.
                        DMRoomRow(
                            room: room,
                            group: groupContext.groups.first { $0.groupId == room.groupId }
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
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

/// 채팅 목록 1건 행(IG-1 플랫화). 그룹 아바타 56 + 그룹명 + 미리보기(시각 인라인) + 미읽음(굵기 + 우측 cta 점).
/// 인스타식 미읽음: 카운트 캡슐·시간 컬럼 제거 → 미읽음일 때만 우측 cta 점 8pt. 미리보기 한 줄에 시각을 인라인 병기.
private struct DMRoomRow: View {
    let room: GroupRoomSummary
    /// room.groupId 조인 그룹(GP-1 FR-5). 대표 이미지/멤버 콜라주 입력. nil = 미로딩 → 그룹명 이니셜 폴백.
    let group: GroupSummary?

    private var isUnread: Bool { room.hasUnread }

    var body: some View {
        // 세로 중앙 정렬: 우측 점과 미리보기가 행 가운데로.
        HStack(alignment: .center, spacing: 12) {
            roomAvatar

            VStack(alignment: .leading, spacing: 3) {
                // 그룹명: 미읽음 Bold / 읽음 SemiBold(Pretendard 고정 웨이트라 실제 페이스 사용).
                Text(room.groupName)
                    .font(isUnread ? WGFont.sansBold(15) : WGFont.sansSemiBold(15))
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(1)

                // 미리보기 한 줄에 시각 인라인(IG-1). 미읽음=ink Bold, 읽음=inkSoft/inkFaint.
                Text(previewText)
                    .font(isUnread ? WGFont.sansBold(13) : WGFont.sans(13))
                    .foregroundStyle(previewColor)
                    .lineLimit(1)
            }

            Spacer(minLength: 0)

            // 인스타식 미읽음 점(빨간 카운트 캡슐 대신 cta 점 1개). 읽음이면 우측 비움.
            if isUnread {
                Circle()
                    .fill(WGColor.cta)
                    .frame(width: 8, height: 8)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    /// 방 썸네일(GP-1 FR-5) — 그룹 조인 성공 시 GroupAvatarView(대표 이미지/멤버 콜라주). 56pt(IG-1).
    /// 미로딩(목록 부트스트랩 전)엔 그룹명 이니셜 원형 — 아바타 외형을 유지해 로딩 전후 모양이 튀지 않는다.
    @ViewBuilder
    private var roomAvatar: some View {
        if let group {
            GroupAvatarView(
                imageUrl: group.imageThumbUrl ?? group.imageUrl,
                members: group.members,
                size: 56
            )
        } else {
            AvatarView(imageUrl: nil, name: room.groupName, size: 56)
        }
    }

    /// 미리보기 시각(인라인 병기) — lastAt 있으면 " · 상대시각", 없으면 빈 문자열(시각 생략).
    private var timeSuffix: String {
        guard let lastAt = room.lastAt else { return "" }
        return " · \(DMListViewModel.formatTime(lastAt))"
    }

    /// 미리보기 텍스트(IG-1 규칙):
    ///  - 미읽음 + count>0 → "새 메시지 N개 · 시각", count nil/0 인데 hasUnread → "새 메시지 · 시각".
    ///  - 읽음 → "(마지막 메시지 or '아직 대화가 없어요') · 시각". lastAt nil 이면 " · 시각" 생략.
    private var previewText: String {
        if isUnread {
            if let count = room.unreadCount, count > 0 {
                return "새 메시지 \(count)개\(timeSuffix)"
            }
            return "새 메시지\(timeSuffix)"
        }
        let body = room.lastPreview ?? "아직 대화가 없어요"
        return "\(body)\(timeSuffix)"
    }

    /// 미리보기 색: 미읽음=ink, 읽음=메시지 있으면 inkSoft / 없으면 inkFaint(IG-1).
    private var previewColor: Color {
        if isUnread { return WGColor.ink }
        return room.lastPreview == nil ? WGColor.inkFaint : WGColor.inkSoft
    }
}
