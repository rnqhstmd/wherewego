import SwiftUI

// 알림함 화면(설계 §7, FR-17~22, BR-4/BR-6).
// frontend/src/app/map/_components/notifications/NotificationPanel.tsx + NotificationItem.tsx + NotificationPinList.tsx 이식.
//
//  - 상태별: 로딩 ProgressView / 빈 "아직 알림이 없어요" / 에러 메시지+재시도(BR-6) / 목록.
//  - 목록 행: 종류 아이콘(MANUAL_PIN=mappin.circle / CHATBOT_PINS=bubble.left / VISIT_DETECTED=location)
//    + 문구(FR-18) + 시간(웹 formatTime 이식) + 미읽음 강조(readAt==nil → 옅은 cta 배경).
//  - 행 탭 → selectItem(detail 로드) → 핀 목록(activeDetail) 표시. "← 목록" 으로 복귀.
//  - 핀 탭 → flyToPin(딥링크 pending). soft delete 핀은 "삭제된 장소: {이름}" + flyTo 비활성.
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
            // 경량 상단바(IG-1). 상세(핀 목록)에는 자체 "← 목록" 헤더가 있어 제외.
            if viewModel.activeDetail == nil {
                InstaNavBar(title: "알림")
            }
            content
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(WGColor.bg)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
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
        if let detail = viewModel.activeDetail {
            detailView(detail)
        } else {
            switch viewModel.loadState {
            case .idle, .loading:
                loadingView
            case let .loaded(items):
                if items.isEmpty {
                    emptyView
                } else {
                    listView(items)
                }
            case let .error(message):
                errorView(message)
            }
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

    // MARK: - 목록

    private func listView(_ items: [NotificationItem]) -> some View {
        // 카드 목록(그룹 목록/채팅 목록 디자인 언어 정합) — hairline 구분선 평면 리스트의 밋밋함 해소.
        ScrollView {
            LazyVStack(spacing: 10) {
                ForEach(items) { item in
                    Button {
                        Task { await viewModel.selectItem(item) }
                    } label: {
                        NotificationRow(item: item)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 4)
            .padding(.bottom, 16)
        }
    }

    // MARK: - 상세(핀 목록)

    private func detailView(_ detail: NotificationDetail) -> some View {
        VStack(spacing: 0) {
            // 상단 "← 목록" 헤더(웹 NotificationPanel 헤더 이식).
            HStack {
                Button {
                    viewModel.clearDetail()
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 13, weight: .semibold))
                        Text("목록")
                            .font(WGFont.sans(14))
                    }
                    .foregroundStyle(WGColor.inkSoft)
                }
                Spacer()
                // 상세 헤더 그룹명(D단계). nil 이면 생략(방어, Should-10).
                if let groupName = detail.groupName, !groupName.isEmpty {
                    Text(groupName)
                        .font(WGFont.sans(13))
                        .foregroundStyle(WGColor.inkSoft)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .overlay(alignment: .bottom) {
                Rectangle().fill(WGColor.hairline).frame(height: 1)
            }

            if viewModel.isDetailLoading {
                loadingView
            } else if detail.pins.isEmpty {
                VStack {
                    Spacer()
                    Text("연결된 장소가 없어요")
                        .font(WGFont.sans(14))
                        .foregroundStyle(WGColor.inkSoft)
                    Spacer()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(detail.pins, id: \.pinId) { pin in
                            Button {
                                viewModel.flyToPin(pin)
                            } label: {
                                NotificationPinRow(pin: pin)
                            }
                            .buttonStyle(.plain)
                            .disabled(pin.deleted)

                            Rectangle()
                                .fill(WGColor.hairline)
                                .frame(height: 1)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 알림 행

/// 알림 1건 행. 종류 아이콘 + 문구(FR-18) + 시간 + 미읽음 강조(readAt==nil → 옅은 cta 배경).
private struct NotificationRow: View {
    let item: NotificationItem

    private var isUnread: Bool { item.readAt == nil }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            // 종류 아이콘을 틴트 원 안에(채팅 목록 아바타와 동일 패턴) — 평면 글리프의 밋밋함 해소.
            Image(systemName: Self.icon(for: item.type))
                .font(.system(size: 17))
                .foregroundStyle(WGColor.cta)
                .frame(width: 40, height: 40)
                .background(WGColor.cta.opacity(0.10))
                .clipShape(Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(Self.message(for: item))
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.ink)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)

                // 그룹명(D단계) + 시간. groupName nil 이면 그룹 칩 생략(방어, Should-10).
                HStack(spacing: 6) {
                    if let groupName = item.groupName, !groupName.isEmpty {
                        Text(groupName)
                            .font(WGFont.sans(11))
                            .foregroundStyle(WGColor.cta)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(WGColor.cta.opacity(0.1))
                            .clipShape(Capsule())
                    }
                    Text(NotificationInboxViewModel.formatTime(item.createdAt))
                        .font(WGFont.mono(11))
                        .foregroundStyle(WGColor.inkSoft)
                }
            }

            Spacer(minLength: 0)

            if isUnread {
                Circle()
                    .fill(WGColor.pinNew)
                    .frame(width: 7, height: 7)
                    .padding(.top, 5)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        // 카드 스타일(채팅 목록 정합): 미읽음 = 옅은 cta 채움 + cta 테두리.
        .background(isUnread ? WGColor.cta.opacity(0.07) : WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(isUnread ? WGColor.cta.opacity(0.35) : WGColor.hairline, lineWidth: 1)
        )
        .contentShape(RoundedRectangle(cornerRadius: 16))
    }

    /// 종류별 SF Symbol(설계 §7).
    static func icon(for type: NotificationType) -> String {
        switch type {
        case .MANUAL_PIN: return "mappin.circle"
        case .CHATBOT_PINS: return "bubble.left"
        case .VISIT_DETECTED: return "location"
        }
    }

    /// 종류별 문구(FR-18).
    static func message(for item: NotificationItem) -> String {
        switch item.type {
        case .MANUAL_PIN:
            return "파트너가 새 장소를 등록했어요"
        case .CHATBOT_PINS:
            return "릴스에서 \(item.totalPinCount)개 장소가 저장됐어요"
        case .VISIT_DETECTED:
            return "방문 장소가 감지됐어요"
        }
    }
}

// MARK: - 알림 상세 핀 행

/// 상세 핀 1건. soft delete 핀은 "삭제된 장소: {이름}" + 비활성(취소선/흐림).
/// frontend NotificationPinList.tsx 핀 행 이식.
private struct NotificationPinRow: View {
    let pin: NotificationPinItem

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "mappin")
                .font(.system(size: 16))
                .foregroundStyle(pin.deleted ? WGColor.inkFaint : WGColor.cta)
                .frame(width: 20, alignment: .center)
                .padding(.top, 1)

            VStack(alignment: .leading, spacing: 2) {
                if pin.deleted {
                    Text("삭제된 장소: \(pin.placeName)")
                        .font(WGFont.sans(14))
                        .foregroundStyle(WGColor.inkSoft)
                        .strikethrough()
                } else {
                    Text(pin.placeName)
                        .font(WGFont.sans(14))
                        .foregroundStyle(WGColor.ink)
                    if let address = pin.address, !address.isEmpty {
                        Text(address)
                            .font(WGFont.mono(12))
                            .foregroundStyle(WGColor.inkSoft)
                    }
                }
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .opacity(pin.deleted ? 0.55 : 1)
        .contentShape(Rectangle())
    }
}
