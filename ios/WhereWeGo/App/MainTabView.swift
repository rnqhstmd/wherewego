import SwiftUI

// 메인 탭 화면(설계 §1·§2·§11 + 내비 셸 재구성 FR-1~9). 온보딩 종착.
//  - 루트 = 지도(MapView) 상시 홈. 채팅·어디갈까는 풀스크린 탭이 아니라 지도 위 .sheet 팝업으로 띄운다(TabView 제거).
//      TopBar 가 콘텐츠 상단을 가려 흰 화면처럼 보이던 문제 해소 — 시트 뒤로 지도가 보인다.
//  - 하단 FloatingTabBar: 그룹 종속 3탭(지도·채팅·어디갈까). selection 으로 시트 표시를 제어한다.
//      · .map → 시트 없음(지도). .chat → 채팅 시트. .roulette → 룰렛 시트. 시트 dismiss(스와이프/닫기) 시 selection=.map.
//  - 상단 TopBar(지도 위 오버레이): 좌측 그룹 전환 칩 / 우측 🔔 알림·👤 내정보.
//      · 그룹 칩 탭 → GroupSwitcherSheet(목록 + 전환). 알림/내정보 탭 → 각각 .sheet.
//  - 활성 그룹 컨텍스트(GroupContext): 본 뷰가 @StateObject 로 소유. 전환 시 지도 재로드 + 채팅 방 전환 + 룰렛 재추첨(FR-5/BR-4).
//  - 지도=전체 핀 보기/관리. 룰렛("어디갈까")은 지도 위 시트(.roulette 진입 시 자동 추첨), 그룹 전환 시 룰렛 시트 떠 있으면 새 풀로 재추첨.
//      VM 수명은 본 뷰가 @StateObject 로 보유(시트 토글에도 결과 유지).
//  - 봇 채팅 수신은 STOMP 제거 후 전송 직후 폴링 + scenePhase 재조회 + APNs 푸시로 대체(채팅 이벤트 전환).
//  - 딥링크 소비(설계 §3): DeepLinkRouter.pending 관찰 → 탭 전환/네비게이션 후 pending=nil. (.chat/.pin/.map 만 — 알림 딥링크 없음.)
//  - 알림 배지(설계 §14): 앱 진입/포그라운드 복귀 시 onForeground(list 만 — 배지 갱신, 읽음 처리 안 함).
//      읽음 처리(readAll)는 알림 시트 진입 시 NotificationInboxView.load() 에서만 발생한다.
// ViewModel 수명은 본 뷰가 @StateObject 로 보유(시트 토글에도 유지 — QE-1 map/bot VM 수명 보존).
struct MainTabView: View {

    private let dependencies: AppDependencies

    @StateObject private var mapViewModel: MapViewModel
    /// 룰렛("어디갈까") VM. mapViewModel 공유(추첨 풀=pins, "지도에서 보기" flyTo). 본 뷰가 소유해 탭 전환에도 결과 유지.
    @StateObject private var rouletteViewModel: RouletteViewModel
    @StateObject private var botViewModel: BotChatViewModel
    /// 알림함 VM(설계 §2). 미읽음 배지(unreadCount)를 TopBar 🔔 가 관찰 — 시트 진입 전에도 노출(BR-3).
    @StateObject private var notificationInboxViewModel: NotificationInboxViewModel
    /// 내정보 VM(설계 §2). 본 뷰가 소유해 body 재계산마다 재생성되지 않도록 한다(MyInfoView 는 @ObservedObject 관찰).
    @StateObject private var myInfoViewModel: MyInfoViewModel
    /// 활성 그룹 컨텍스트(FR-5). TopBar 그룹 칩 + GroupSwitcherSheet 가 관찰, 전환 동기화의 단일 소스.
    @StateObject private var groupContext: GroupContext
    /// 딥링크 라우터(AppDependencies 소유 단일 인스턴스). pending 변화를 관찰해 탭 전환.
    @ObservedObject private var deepLinkRouter: DeepLinkRouter

    @Environment(\.scenePhase) private var scenePhase

    @State private var selection: MainTab = .map

    /// 상단 TopBar 시트 표시 상태(BR-5 — 알림·내정보·그룹 전환 모두 시트 진입).
    @State private var showGroupSwitcher = false
    @State private var showNotifications = false
    @State private var showMyInfo = false

    init(dependencies: AppDependencies) {
        self.dependencies = dependencies
        // mapViewModel 을 먼저 생성해 rouletteViewModel 과 공유한다(추첨 풀=pins, "지도에서 보기" flyTo 대상).
        let map = MapViewModel(
            pinAPI: dependencies.pinAPI,
            placeAPI: dependencies.placeAPI,
            groupAPI: dependencies.groupAPI,
            locationService: dependencies.locationService
        )
        _mapViewModel = StateObject(wrappedValue: map)
        _rouletteViewModel = StateObject(
            wrappedValue: RouletteViewModel(
                mapViewModel: map,
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
        _groupContext = StateObject(
            wrappedValue: GroupContext(groupAPI: dependencies.groupAPI)
        )
        _deepLinkRouter = ObservedObject(wrappedValue: dependencies.deepLinkRouter)
    }

    var body: some View {
        // 내비 위계 구분(Option 1):
        //  - 하단 탭(지도·봇·어디갈까) = "내가 있는 곳"(destination). 봇·어디갈까는 시트가 아니라 지도 위
        //    풀스크린 인플레이스 콘텐츠로 띄운다(불투명 배경·자체 헤더). 손잡이/스와이프 닫기 없음, 탭바는 항상
        //    유지(선택 하이라이트). 전환은 시트 슬라이드업과 구분되는 빠른 크로스페이드.
        //  - 상단 버튼(그룹·알림·내정보) = "잠깐 열고 닫는" 유틸리티 → 지금처럼 .sheet 로 띄운다.
        //  지도는 항상 마운트(Mapbox 인스턴스/카메라 유지) — 풀스크린 탭이 그 위를 불투명으로 덮는다.
        ZStack(alignment: .bottom) {
            // 지도(상시 홈) + 지도 탭에서만 상단 TopBar 오버레이.
            MapView(viewModel: mapViewModel)
                .overlay(alignment: .top) {
                    if selection == .map {
                        TopBar(
                            groupName: groupContext.activeGroupName,
                            hasUnread: notificationInboxViewModel.unreadCount > 0,
                            onTapGroupChip: { showGroupSwitcher = true },
                            onTapNotification: { showNotifications = true },
                            onTapMyInfo: { showMyInfo = true }
                        )
                    }
                }

            // 봇 채팅 / 어디갈까(룰렛) — 풀스크린 인플레이스 탭(지도 위 불투명 오버레이, 자체 헤더).
            if selection == .chat {
                chatScreen
                    .transition(.opacity)
                    .zIndex(1)
            } else if selection == .roulette {
                rouletteScreen
                    .transition(.opacity)
                    .zIndex(1)
            }

            // 플로팅 필 바(3탭) — 항상 표시. 풀스크린 콘텐츠 위에 떠 탭 전환·선택 하이라이트 유지.
            //  키보드 표시 시 바만 고정(Q3/QE-2, ZT-3): ignoresSafeArea(.keyboard)를 바에 한정한다.
            FloatingTabBar(selection: $selection)
                .ignoresSafeArea(.keyboard, edges: .bottom)
                .zIndex(2)
        }
        .tint(WGColor.cta)
        // 탭 전환은 시트 슬라이드업이 아닌 빠른 크로스페이드(인플레이스 탭 느낌, 상단 시트와 구분).
        .animation(.easeInOut(duration: 0.22), value: selection)
        // 그룹 전환 시트(FR-4): 진입 시 listMyGroups(BR-6). 선택 → switchActiveGroup(전환 동기화).
        .sheet(isPresented: $showGroupSwitcher) {
            GroupSwitcherSheet(
                context: groupContext,
                onSelect: { group in
                    showGroupSwitcher = false
                    switchActiveGroup(to: group)
                },
                onCreateGroup: {
                    // 그룹 생성/초대 진입(비범위 — 진입 경로만). 내정보 시트로 안내(그룹 관리 화면).
                    //  별도 생성 플로우 UI 는 범위 밖이라, 우선 시트를 닫고 내정보로 유도해 회귀(컴파일/기능 유지).
                    showGroupSwitcher = false
                    showMyInfo = true
                }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(24)
            .presentationBackground(.regularMaterial)
        }
        // 알림 시트(FR-8, BR-5): 진입 시 NotificationInboxView.load() 가 list+readAll(읽음 처리, 설계 §14).
        .sheet(isPresented: $showNotifications) {
            NavigationStack {
                NotificationInboxView(viewModel: notificationInboxViewModel)
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(24)
        }
        // 내정보 시트(FR-9, BR-5): 그룹 전환과 무관(전역).
        .sheet(isPresented: $showMyInfo) {
            NavigationStack {
                MyInfoView(
                    authAPI: dependencies.authAPI,
                    viewModel: myInfoViewModel
                )
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(24)
        }
        // 진입 시: 딥링크 소비 + 알림 배지 갱신 + 활성 그룹 시드(myActiveGroup, 앱 재시작 기본값).
        //  활성 그룹 해석은 GroupContext.bootstrap() 단일 소스로 모은다(이중 resolve 제거). 그 결과(groupId)를
        //  지도 초기 로드에 주입한다 — 활성 그룹 있으면 load(groupId:), 없으면 빈 지도(loadEmpty, BR-2).
        //  MapView.task 의 자체 load()(myActiveGroup 자체 resolve)는 loadState .idle 가드를 갖고 있으므로,
        //  여기서 먼저 로드를 트리거하면(.loading/.loaded 로 전환) MapView 가 중복 호출하지 않는다.
        .task {
            consumePending()
            await notificationInboxViewModel.onForeground()
            // 지도 초기 로드가 아직 안 일어났을 때만 주도(에러 재시도 등으로 이미 로드됐으면 중복 회피).
            guard case .idle = mapViewModel.loadState else {
                await groupContext.bootstrap()
                return
            }
            if let groupId = await groupContext.bootstrap() {
                await mapViewModel.load(groupId: groupId)
            } else {
                await mapViewModel.loadEmpty()
            }
        }
        .onChange(of: deepLinkRouter.pending) { _, _ in
            consumePending()
        }
        // selection 변경(채팅/룰렛 시트 토글 포함) 시 인라인 추가 모드 종료(BR-1/AC-12). 지도에서 ＋ FAB 로 추가 모드에
        //  들어간 뒤 채팅/룰렛 시트로 이동하면 작성 중 상태를 정리한다.
        //  어디갈까(룰렛) 시트 진입(selection==.roulette) 시마다 자동 추첨(시트 표시 = spin()).
        .onChange(of: selection) { _, newSelection in
            mapViewModel.exitAddPin()
            mapViewModel.isAddMenuExpanded = false
            if newSelection == .roulette {
                Task { await rouletteViewModel.spin() }
            }
        }
        // 포그라운드 복귀 시 알림 배지 갱신(설계 §14, list 만 — 읽음 처리는 알림 시트 진입에서만).
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await notificationInboxViewModel.onForeground() }
            }
        }
    }

    // MARK: - 풀스크린 탭 콘텐츠(봇·어디갈까) — 시트가 아닌 지도 위 인플레이스 오버레이(Option 1)

    /// 봇 채팅 풀스크린 화면. 불투명 배경으로 지도를 덮고, 하단 플로팅 탭바 footprint 만큼 여백을 둬
    ///  입력바가 탭바에 가리지 않게 한다. 자체 NavigationStack 헤더("어디가지 봇").
    private var chatScreen: some View {
        NavigationStack {
            BotChatView(viewModel: botViewModel)
        }
        .background(.ultraThinMaterial)   // iOS 글라스(과감) — 지도가 또렷이 비치는 얇은 글라스 패널
        .safeAreaInset(edge: .bottom) {
            Color.clear.frame(height: FloatingTabBar.Metrics.contentFootprint)
        }
    }

    /// 어디갈까(룰렛) 풀스크린 화면. "지도에서 보기" → 지도 탭 전환(selection=.map). 자동 추첨은 onChange(selection).
    private var rouletteScreen: some View {
        NavigationStack {
            RouletteView(
                viewModel: rouletteViewModel,
                onShowOnMap: { selection = .map }
            )
            .navigationTitle("어디갈까")
            .navigationBarTitleDisplayMode(.inline)
        }
        .background(.ultraThinMaterial)   // iOS 글라스(과감) — 지도가 또렷이 비치는 얇은 글라스 패널
        .safeAreaInset(edge: .bottom) {
            Color.clear.frame(height: FloatingTabBar.Metrics.contentFootprint)
        }
    }

    // MARK: - 활성 그룹 전환 동기화(FR-5, BR-4)

    /// 그룹 전환: 활성 그룹 갱신 + 지도 재로드 + 채팅 방 전환 + (어디갈까 시트 떠 있으면) 새 풀로 룰렛 재추첨.
    /// 동일 그룹 재선택은 재로드 없이 즉시 반환(불필요한 폐기/재조회 방지).
    ///
    /// 지도 재로드 실패(서버 403/네트워크) 시 GroupContext(칩)를 이전 그룹으로 롤백한다(FR-5 — 칩/지도 정합).
    /// 채팅 전환 실패는 봇 방이 전역이라 치명적이지 않으므로 롤백 기준에서 제외(지도 실패만 롤백 판정).
    /// 지도(switchTo)와 채팅(switchGroup)은 독립이므로 async let 으로 병렬 await 한다.
    private func switchActiveGroup(to group: GroupSummary) {
        guard groupContext.activeGroupId != group.groupId else { return }
        // 전환 전 활성 그룹 보관(재로드 실패 시 칩 롤백용).
        let previousGroupId = groupContext.activeGroupId
        let previousGroupName = groupContext.activeGroupName
        groupContext.setActiveGroup(group)
        Task {
            // 지도/채팅 재로드는 독립 — 병렬 실행(전환 전 데이터 폐기, FR-5/7).
            async let mapOK = mapViewModel.switchTo(groupId: group.groupId)
            async let chatDone: Void = botViewModel.switchGroup()
            let (succeeded, _) = await (mapOK, chatDone)
            // 지도 실패 시 칩을 이전 그룹으로 롤백(칩/지도 정합 유지).
            if !succeeded {
                groupContext.rollbackActiveGroup(toId: previousGroupId, name: previousGroupName)
            }
            // BR-4 — 어디갈까 시트가 떠 있으면(selection==.roulette) 전환된 새 풀 기준으로 다시 추첨한다.
            if selection == .roulette {
                await rouletteViewModel.spin()
            }
        }
    }

    // MARK: - 딥링크 소비(설계 §3)

    /// pending destination 을 읽어 탭 전환/네비게이션 후 nil 로 리셋(1회 소비). .chat/.pin/.map 만 — 알림 딥링크 없음.
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
        }
        deepLinkRouter.pending = nil
    }
}
