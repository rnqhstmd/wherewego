import SwiftUI

// 메인 탭 화면(설계 §1·§2·§11, FR-1~4/10/19). 온보딩 종착 — 5탭 재구성(어디갈까·채팅·＋·알림·내정보).
//  - 시스템 탭바 숨김 + .safeAreaInset(edge:.bottom) 으로 FloatingTabBar 부착(둥근 플로팅 필 바, 콘텐츠 자동 회피). ＋ 는 탭이 아닌 액션(selection 불변, BR-1/AC-2).
//  - 어디갈까: MapView(외부 주입 MapViewModel 공유 — 딥링크 .pin/.map flyTo 를 위해 VM 을 본 뷰가 소유).
//  - 채팅: BotChatView(BotChatViewModel). 알림: NotificationInboxView. 내정보: MyInfoView.
//  - ＋: showAddPlace → AddPlaceSheet(MapView EmptyMapCard 진입점과 동일 컴포넌트, ＋ 2진입점·1컴포넌트).
//  - 딥링크 소비(설계 §3): DeepLinkRouter.pending 관찰 → 탭 전환/네비게이션 후 pending=nil.
//      · .chat → 채팅 탭, .pin(id)/.map → 지도 탭 (+ .pin 은 핀 로드 후 flyTo), .invite(slug) → 초대 시트
//  - 알림 배지(설계 §14): 앱 진입/포그라운드 복귀 시 onForeground(list 만 — 배지 갱신, 읽음 처리 안 함).
//      읽음 처리(readAll)는 알림 탭 진입 시 NotificationInboxView.load() 에서만 발생한다.
//
// 봇 채팅 수신은 STOMP 제거 후 전송 직후 폴링 + scenePhase 재조회 + APNs 푸시로 대체(채팅 이벤트 전환).
// ViewModel 수명은 본 뷰가 @StateObject 로 보유(탭 전환에도 유지 — QE-1 map/bot VM 수명 보존).
struct MainTabView: View {

    private let dependencies: AppDependencies

    @StateObject private var mapViewModel: MapViewModel
    @StateObject private var botViewModel: BotChatViewModel
    /// 알림함 VM(설계 §2). 미읽음 배지(unreadCount)를 FloatingTabBar 가 항상 관찰 — 탭 진입 전에도 노출.
    @StateObject private var notificationInboxViewModel: NotificationInboxViewModel
    /// 내정보 VM(설계 §2). 본 뷰가 소유해 body 재계산마다 재생성되지 않도록 한다(MyInfoView 는 @ObservedObject 관찰).
    @StateObject private var myInfoViewModel: MyInfoViewModel
    /// 딥링크 라우터(AppDependencies 소유 단일 인스턴스). pending 변화를 관찰해 탭 전환.
    @ObservedObject private var deepLinkRouter: DeepLinkRouter

    @Environment(\.scenePhase) private var scenePhase

    @State private var selection: MainTab = .map
    /// ＋ 통합 장소 추가 시트 트리거(설계 §2). EmptyMapCard 진입(MapViewModel.activeSheet)과 동일 컴포넌트.
    @State private var showAddPlace = false
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
                currentUser: dependencies.currentUser
            )
        )
        _notificationInboxViewModel = StateObject(
            wrappedValue: NotificationInboxViewModel(
                api: dependencies.notificationAPI,
                deepLinkRouter: dependencies.deepLinkRouter
            )
        )
        _myInfoViewModel = StateObject(
            wrappedValue: MyInfoViewModel(
                authAPI: dependencies.authAPI,
                groupAPI: dependencies.groupAPI,
                sessionStore: dependencies.session,
                currentUser: dependencies.currentUser,
                logoutHandler: dependencies.logout
            )
        )
        _deepLinkRouter = ObservedObject(wrappedValue: dependencies.deepLinkRouter)
    }

    var body: some View {
        TabView(selection: $selection) {
            // 어디갈까(지도) 탭 — 외부 주입 VM 공유(딥링크 flyTo 대상).
            MapView(viewModel: mapViewModel)
                .tag(MainTab.map)

            // 채팅(봇 방) 탭. navigationTitle 표시를 위해 NavigationStack 으로 감싼다.
            NavigationStack {
                BotChatView(viewModel: botViewModel)
            }
            .reserveFloatingTabBarSpace()   // 탭 콘텐츠가 바 footprint 회피(TabView는 safe area 전파 안 함 — PR리뷰)
            .tag(MainTab.chat)

            // 알림 탭. 진입 시 NotificationInboxView.load() 가 list+readAll(읽음 처리, 설계 §14).
            NavigationStack {
                NotificationInboxView(viewModel: notificationInboxViewModel)
            }
            .reserveFloatingTabBarSpace()
            .tag(MainTab.notification)

            // 내정보 탭. VM 은 본 뷰가 소유(@StateObject), authAPI 는 닉네임 수정 시트용으로 전달.
            NavigationStack {
                MyInfoView(
                    authAPI: dependencies.authAPI,
                    viewModel: myInfoViewModel
                )
            }
            .reserveFloatingTabBarSpace()
            .tag(MainTab.myInfo)
        }
        // 시스템 탭바 숨김 — 커스텀 FloatingTabBar 로 대체(설계 §1).
        .toolbar(.hidden, for: .tabBar)
        .tint(WGColor.cta)
        // 바 부착(설계 §2 개정 / PR리뷰): TabView 에 직접 .safeAreaInset 을 걸면 그 safe area 가 개별 탭
        //  자식 뷰로 전파되지 않는 SwiftUI 한계가 있다(콘텐츠가 바 뒤로 가림). 따라서 각 탭이
        //  .reserveFloatingTabBarSpace() 로 자체 footprint 를 확보하고, 바는 .overlay 로 얹는다.
        //  overlay 콘텐츠는 container safe area 를 존중하므로 바가 홈 인디케이터 위에 배치된다(AC-4).
        .overlay(alignment: .bottom) {
            FloatingTabBar(
                selection: $selection,
                hasUnread: notificationInboxViewModel.unreadCount > 0,
                onPlusTap: { showAddPlace = true }
            )
            // 키보드 표시 시 바만 고정(Q3/QE-2, ZT-3): ignoresSafeArea(.keyboard)를 바에 한정한다.
            //  TabView 전체에 걸면 채팅 입력바의 SwiftUI 키보드 회피까지 억제되어 입력바가 키보드에 가려진다.
            //  바에만 적용 → 바는 고정(키보드 뒤), 채팅 입력바는 키보드 위로 정상 회피.
            .ignoresSafeArea(.keyboard, edges: .bottom)
        }
        // 딥링크 소비(설계 §3) + 알림 배지 최초 갱신(설계 §14). 진입 시 보류분 1회 + 배지 1회.
        .task {
            consumePending()
            await notificationInboxViewModel.onForeground()
        }
        .onChange(of: deepLinkRouter.pending) { _, _ in
            consumePending()
        }
        // 포그라운드 복귀 시 알림 배지 갱신(설계 §14, list 만 — 읽음 처리는 알림 탭 진입에서만).
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await notificationInboxViewModel.onForeground() }
            }
        }
        // ＋ 통합 장소 추가 시트(설계 §2, ＋ 진입점). EmptyMapCard 와 동일 AddPlaceSheet.
        .sheet(isPresented: $showAddPlace) {
            AddPlaceSheet(mapViewModel: mapViewModel)
        }
        // 초대 코드 합류 시트(.invite 딥링크). 합류/취소 시 닫힘.
        .sheet(item: inviteSlugBinding) { slug in
            NavigationStack {
                InviteCodeView(
                    groupAPI: dependencies.groupAPI,
                    prefill: slug.value,
                    onJoined: { inviteSlug = nil },
                    onCancel: { inviteSlug = nil }
                )
            }
        }
    }

    // MARK: - 딥링크 소비(설계 §3)

    /// pending destination 을 읽어 탭 전환/네비게이션 후 nil 로 리셋(1회 소비).
    private func consumePending() {
        guard let destination = deepLinkRouter.pending else { return }
        switch destination {
        case .chat:
            selection = .chat
        case .map:
            selection = .map
        case .pin(let pinId):
            // 지도 탭으로 전환 후 핀으로 flyTo(핀 목록 로드 전이면 flyTo 가 no-op — 로드 후 재소비는 없으나
            // 진입 시점엔 이미 load 가 진행되므로 best-effort). 설계 §3.
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

private extension View {
    /// 하단 플로팅 탭바 footprint 만큼 콘텐츠 하단 safe area 를 확보한다(설계 §2 개정 / PR리뷰).
    ///  TabView 는 safe area 를 자식 탭으로 전파하지 않으므로, 각 탭이 직접 적용해야 콘텐츠가 바를 회피한다.
    func reserveFloatingTabBarSpace() -> some View {
        safeAreaInset(edge: .bottom) {
            Color.clear.frame(height: FloatingTabBar.Metrics.contentFootprint)
        }
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
