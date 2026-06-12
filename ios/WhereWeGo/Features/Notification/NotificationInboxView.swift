import SwiftUI

// 알림함 화면(IG-2 FR-4 인스타 피드화). 상세 화면 폐기 — 행 탭 = 지도 탭 직행(.pinFocus 딥링크).
//  - 상태별: 로딩 ProgressView / 빈 "아직 알림이 없어요" / 에러 메시지+재시도(BR-6) / 피드.
//  - 피드: 섹션(오늘/이번 주/이전) + 플랫 행(아바타 + 인라인 굵은 문구 + 그룹명·시각 + 썸네일 + 미읽음 점).
//  - 행 탭 → selectItem(detail 로 유효 핀 추출 → .pinFocus 딥링크). 접근 불가/삭제된 장소/실패는 토스트 안내.
//
// NavigationStack 안의 콘텐츠(navigationTitle 만 지정, 스택은 MainTabView 가 제공).
struct NotificationInboxView: View {
    // MainTabView 가 @StateObject 로 소유한 VM 을 그대로 주입받는다(소유 금지).
    // 자체 @StateObject 로 새 인스턴스를 만들면 배지(unreadCount)와 load()/readAll() 이
    // 서로 다른 인스턴스에서 일어나 읽음 처리 후에도 배지가 사라지지 않는다.
    @ObservedObject var viewModel: NotificationInboxViewModel

    init(viewModel: NotificationInboxViewModel) {
        self.viewModel = viewModel
    }

    var body: some View {
        VStack(spacing: 0) {
            // 경량 상단바(IG-1). 상세 화면 폐기로 항상 표시.
            InstaNavBar(title: "알림")
            content
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(WGColor.bg)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        // 행 탭 안내 토스트(IG-2 FR-4): 하단 캡슐. 2.5초 자동 사라짐 + 탭 시 닫기.
        .overlay(alignment: .bottom) { infoToast }
        .animation(.easeOut(duration: 0.2), value: viewModel.infoMessage)
            // 탭 진입 시 load(list + readAll 1회, 설계 §14). MainTabView 의 onForeground 가 먼저 loadState 를
            // .loaded 로 바꿔도 읽음 처리(readAll)가 누락되지 않도록 .idle 가드 없이 호출한다.
            // readAll 1회 보장은 VM 내부 didReadAll 플래그가 담당한다.
            .task {
                await viewModel.load()
            }
    }

    // MARK: - 상태 분기

    @ViewBuilder
    private var content: some View {
        switch viewModel.loadState {
        case .idle, .loading:
            loadingView
        case let .loaded(items):
            if items.isEmpty {
                emptyView
            } else {
                feedView(items)
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

    // MARK: - 빈 상태(FR-20 카피)

    private var emptyView: some View {
        VStack(spacing: 8) {
            Spacer()
            Image(systemName: "bell")
                .font(.system(size: 32))
                .foregroundStyle(WGColor.inkFaint)
            Text("아직 알림이 없어요")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - 에러 + 재시도(BR-6)

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

    // MARK: - 피드(섹션 + 플랫 행)

    /// 섹션(오늘/이번 주/이전)별 그룹핑 후 헤더 + 플랫 행. 빈 섹션은 헤더 생략.
    private func feedView(_ items: [NotificationItem]) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(Self.sections, id: \.self) { section in
                    let sectionItems = items.filter {
                        NotificationInboxViewModel.sectionKey($0.createdAt) == section
                    }
                    if !sectionItems.isEmpty {
                        Text(section.title)
                            .font(WGFont.sansBold(15))
                            .foregroundStyle(WGColor.ink)
                            .padding(.horizontal, 20)
                            .padding(.top, 18)
                            .padding(.bottom, 6)

                        ForEach(sectionItems) { item in
                            Button {
                                Task { await viewModel.selectItem(item) }
                            } label: {
                                NotificationRow(item: item)
                            }
                            .buttonStyle(.plain)
                            // 라우팅 중(detail 조회) 중복 탭 방지.
                            .disabled(viewModel.isRouting)
                        }
                    }
                }
            }
            .padding(.bottom, 16)
        }
    }

    /// 섹션 표시 순서(오늘 → 이번 주 → 이전).
    private static let sections: [NotificationInboxViewModel.Section] = [.today, .thisWeek, .earlier]

    // MARK: - 행 탭 안내 토스트(IG-2 FR-4)

    @ViewBuilder
    private var infoToast: some View {
        if let message = viewModel.infoMessage {
            HStack(spacing: 8) {
                Image(systemName: "info.circle.fill")
                    .font(.system(size: 14))
                    .foregroundStyle(WGColor.cta)
                Text(message)
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.ink)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(WGColor.panel)
            .clipShape(Capsule())
            .overlay(Capsule().stroke(WGColor.hairline, lineWidth: 1))
            .shadow(color: WGColor.shadowMd, radius: 10, y: 3)
            .padding(.bottom, 24)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            // 탭 시 즉시 닫기.
            .onTapGesture { viewModel.infoMessage = nil }
            // 2.5초 자동 사라짐(문구 변경마다 재시작 — id 부여로 Task 재생성).
            .task(id: message) {
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                if viewModel.infoMessage == message {
                    viewModel.infoMessage = nil
                }
            }
        }
    }
}

// MARK: - 알림 행(IG-2 FR-4 피드 행)

/// 알림 1건 플랫 행. 아바타 + 인라인 굵은 문구 + 그룹명·시각 + 썸네일 + 미읽음 점.
private struct NotificationRow: View {
    let item: NotificationItem

    private var isUnread: Bool { item.readAt == nil }

    var body: some View {
        HStack(spacing: 12) {
            // 행위자 아바타(GP-1 AvatarView — 유효 프사 없으면 이니셜 폴백).
            AvatarView(
                imageUrl: item.registeredByProfileImageUrl,
                name: item.registeredByNickname ?? "?",
                size: 40
            )

            VStack(alignment: .leading, spacing: 3) {
                // 인라인 굵은 문구: 닉네임(SemiBold) + 행위 문구(Regular).
                (
                    Text(item.registeredByNickname ?? "누군가")
                        .font(WGFont.sansSemiBold(14))
                        + Text(Self.actionText(for: item))
                        .font(WGFont.sans(14))
                )
                .foregroundStyle(WGColor.ink)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)

                // 둘째 줄: 그룹명(있으면) · 상대시각. 한 줄.
                Text(Self.subtitle(for: item))
                    .font(WGFont.sans(12))
                    .foregroundStyle(WGColor.inkSoft)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            // 썸네일(첫 핀 사진) — 없으면 회색 타일 폴백.
            thumbnail
        }
        // 미읽음 점은 행 우측 상단(썸네일과 겹치지 않게 overlay 로 배치).
        .overlay(alignment: .topTrailing) {
            if isUnread {
                Circle()
                    .fill(WGColor.pinNew)
                    .frame(width: 7, height: 7)
                    .padding(.top, 8)
                    .padding(.trailing, 2)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        // 미읽음만 옅은 cta 풀폭 배경(카드 제거).
        .background(isUnread ? WGColor.cta.opacity(0.06) : Color.clear)
        .contentShape(Rectangle())
    }

    // MARK: - 썸네일

    /// 첫 핀 사진 36pt. 사진이 없으면(thumbnailUrl nil) 썸네일 자체를 생략한다(Q3 확정 — 인스타 문법).
    /// URL 은 있으나 로딩/실패 중에는 회색 타일(레이아웃 점프 방지).
    @ViewBuilder
    private var thumbnail: some View {
        if let urlString = item.thumbnailUrl, let url = URL(string: urlString) {
            AsyncImage(url: url) { phase in
                switch phase {
                case .success(let image):
                    image.resizable().scaledToFill()
                case .empty, .failure:
                    thumbnailPlaceholder
                @unknown default:
                    thumbnailPlaceholder
                }
            }
            .frame(width: 36, height: 36)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8).stroke(WGColor.hairline, lineWidth: 1)
            )
        }
    }

    /// 썸네일 폴백 타일(hairline 배경 + photo 글리프).
    private var thumbnailPlaceholder: some View {
        WGColor.hairline.opacity(0.5)
            .overlay(
                Image(systemName: "photo")
                    .font(.system(size: 14))
                    .foregroundStyle(WGColor.inkFaint)
            )
    }

    // MARK: - 문구(IG-2 FR-4 — 닉네임 주어 분리)

    /// 닉네임 뒤에 붙는 행위 문구(FR-4). 닉네임은 NotificationRow 가 별도 Text 로 굵게 렌더.
    static func actionText(for item: NotificationItem) -> String {
        switch item.type {
        case .MANUAL_PIN:
            return "님이 새 장소를 등록했어요 · \(item.firstPlaceName)"
        case .CHATBOT_PINS:
            return "님이 릴스에서 장소 \(item.totalPinCount)개를 저장했어요"
        }
    }

    /// 둘째 줄: 그룹명(있으면) · 상대시각. 그룹명 nil 이면 시각만.
    static func subtitle(for item: NotificationItem) -> String {
        let time = NotificationInboxViewModel.formatTime(item.createdAt)
        if let groupName = item.groupName, !groupName.isEmpty {
            return "\(groupName) · \(time)"
        }
        return time
    }
}
