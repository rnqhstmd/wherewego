import SwiftUI

// 메인 탭 화면(설계 §9·§10, FR-10/FR-19). 온보딩 종착 — 하단 TabView(지도/봇방/커플방).
//  - 지도: MapView(외부 주입 MapViewModel 공유 — 딥링크 .pin/.map flyTo 를 위해 VM 을 본 뷰가 소유).
//  - 봇방: BotChatView(BotChatViewModel). 커플방: CoupleChatView(CoupleChatViewModel).
//  - 딥링크 소비(설계 §9): DeepLinkRouter.pending 관찰 → 탭 전환/네비게이션 후 pending=nil.
//      · .botChat → 봇 탭, .coupleChat → 커플 탭
//      · .pin(id)/.map → 지도 탭 (+ .pin 은 핀 로드 후 flyTo)
//      · .invite(slug) → 초대 코드 합류 시트
//
// 채팅 실시간(ChatRealtimeService)은 단일 인스턴스(앱 수명)를 두 ViewModel 이 공유한다(설계 §4).
// ViewModel 수명은 본 뷰가 @StateObject 로 보유(탭 전환에도 유지 — 빠른 방 전환 시 구독/연결 보존).
struct MainTabView: View {

    /// 탭 식별자(딥링크 탭 전환의 selection 바인딩).
    private enum Tab: Hashable {
        case map
        case bot
        case couple
    }

    private let dependencies: AppDependencies

    @StateObject private var mapViewModel: MapViewModel
    @StateObject private var botViewModel: BotChatViewModel
    @StateObject private var coupleViewModel: CoupleChatViewModel
    /// 딥링크 라우터(AppDependencies 소유 단일 인스턴스). pending 변화를 관찰해 탭 전환.
    @ObservedObject private var deepLinkRouter: DeepLinkRouter

    @State private var selection: Tab = .map
    /// .invite 딥링크 시 표시할 초대 슬러그(시트 트리거). nil 이면 미표시.
    @State private var inviteSlug: String?

    init(dependencies: AppDependencies) {
        self.dependencies = dependencies
        _mapViewModel = StateObject(
            wrappedValue: MapViewModel(
                pinAPI: dependencies.pinAPI,
                placeAPI: dependencies.placeAPI,
                groupAPI: dependencies.groupAPI,
                locationService: dependencies.locationService
            )
        )
        _botViewModel = StateObject(
            wrappedValue: BotChatViewModel(
                chatAPI: dependencies.chatAPI,
                pinAPI: dependencies.pinAPI,
                groupAPI: dependencies.groupAPI,
                realtime: dependencies.chatRealtime,
                currentUser: dependencies.currentUser
            )
        )
        _coupleViewModel = StateObject(
            wrappedValue: CoupleChatViewModel(
                chatAPI: dependencies.chatAPI,
                groupAPI: dependencies.groupAPI,
                realtime: dependencies.chatRealtime,
                currentUser: dependencies.currentUser
            )
        )
        _deepLinkRouter = ObservedObject(wrappedValue: dependencies.deepLinkRouter)
    }

    var body: some View {
        TabView(selection: $selection) {
            // 지도 탭(외부 주입 VM 공유 — 딥링크 flyTo 대상).
            MapView(viewModel: mapViewModel)
                .tabItem {
                    Label("지도", systemImage: "map")
                }
                .tag(Tab.map)

            // 봇 방 탭. navigationTitle 표시를 위해 NavigationStack 으로 감싼다.
            NavigationStack {
                BotChatView(viewModel: botViewModel)
            }
            .tabItem {
                Label("어디가지 봇", systemImage: "bubble.left.and.bubble.right")
            }
            .tag(Tab.bot)

            // 커플 방 탭.
            NavigationStack {
                CoupleChatView(viewModel: coupleViewModel)
                    .navigationTitle("우리 채팅")
                    .navigationBarTitleDisplayMode(.inline)
            }
            .tabItem {
                Label("우리 채팅", systemImage: "heart.text.square")
            }
            .tag(Tab.couple)
        }
        .tint(WGColor.cta)
        // 딥링크 소비(설계 §9). 진입 시 보류분 1회 + 이후 변화 반영.
        .task {
            // AC-11: 커플방 활성 그룹/방 부재 시 지도 탭으로 폴백(설계 §9 "대상 조회 실패 시 .map 폴백").
            coupleViewModel.onUnavailable = { [weak deepLinkRouter] in
                deepLinkRouter?.pending = .map
            }
            consumePending()
        }
        .onChange(of: deepLinkRouter.pending) { _, _ in
            consumePending()
        }
        // 초대 코드 합류 시트(.invite 딥링크). 합류/취소 시 닫힘.
        .sheet(item: inviteSlugBinding) { slug in
            NavigationStack {
                InviteCodeView(
                    groupAPI: dependencies.groupAPI,
                    prefill: slug,
                    onJoined: { inviteSlug = nil },
                    onCancel: { inviteSlug = nil }
                )
            }
        }
    }

    // MARK: - 딥링크 소비(설계 §9)

    /// pending destination 을 읽어 탭 전환/네비게이션 후 nil 로 리셋(1회 소비).
    private func consumePending() {
        guard let destination = deepLinkRouter.pending else { return }
        switch destination {
        case .botChat:
            selection = .bot
        case .coupleChat:
            selection = .couple
        case .map:
            selection = .map
        case .pin(let pinId):
            // 지도 탭으로 전환 후 핀으로 flyTo(핀 목록 로드 전이면 flyTo 가 no-op — 로드 후 재소비는 없으나
            // 진입 시점엔 이미 load 가 진행되므로 best-effort). 설계 §9.
            selection = .map
            mapViewModel.flyTo(pinId: pinId)
        case .invite(let slug):
            inviteSlug = slug
        }
        deepLinkRouter.pending = nil
    }

    /// inviteSlug(String?) → 시트 item 바인딩(Identifiable 래핑).
    private var inviteSlugBinding: Binding<InviteSlug?> {
        Binding(
            get: { inviteSlug.map(InviteSlug.init) },
            set: { newValue in inviteSlug = newValue?.value }
        )
    }
}

/// .sheet(item:) 용 Identifiable 래퍼(String 자체는 Identifiable 아님).
private struct InviteSlug: Identifiable {
    let value: String
    var id: String { value }

    init(_ value: String) {
        self.value = value
    }
}
