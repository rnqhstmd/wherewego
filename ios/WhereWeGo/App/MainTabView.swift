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
        // 루트 = 지도(상시 홈). 채팅·어디갈까는 지도 위 .sheet 팝업으로 띄운다(TabView 제거).
        //  MapView 가 자체 하단 패딩(FloatingTabBar.Metrics.contentFootprint)으로 바 footprint 를 회피하므로
        //  별도 reserveFloatingTabBarSpace 는 불필요하다(룰렛/채팅은 시트라 바 footprint 와 무관).
        MapView(viewModel: mapViewModel)
        .tint(WGColor.cta)
        // 채팅 시트(.chat 선택 시) — 지도 위 팝업. NavigationStack 으로 타이틀("어디가지 봇") 표시.
        //  dismiss(스와이프/닫기) 시 바인딩 set 이 selection=.map 으로 되돌린다.
        .sheet(isPresented: chatSheetBinding) {
            NavigationStack {
                BotChatView(viewModel: botViewModel)
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationBackground(.regularMaterial)
        }
        // 룰렛("어디갈까") 시트(.roulette 선택 시) — 지도 위 팝업. 시트엔 자체 타이틀이 없으므로
        //  NavigationStack title "어디갈까"(.inline)로 표기. "지도에서 보기"는 시트 닫고 지도로(selection=.map).
        //  자동 추첨은 .onChange(of: selection) 에서 .roulette 진입 시 spin() 한다(아래 onChange).
        .sheet(isPresented: rouletteSheetBinding) {
            NavigationStack {
                RouletteView(
                    viewModel: rouletteViewModel,
                    onShowOnMap: { selection = .map }
                )
                .navigationTitle("어디갈까")
                .navigationBarTitleDisplayMode(.inline)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        // 상단 TopBar 오버레이(FR-2): 좌 그룹 칩 / 우 🔔·👤. 상단 safe area 안에 배치(지도 chrome 과 충돌 회피).
        .overlay(alignment: .top) {
            TopBar(
                groupName: groupContext.activeGroupName,
                hasUnread: notificationInboxViewModel.unreadCount > 0,
                onTapGroupChip: { showGroupSwitcher = true },
                onTapNotification: { showNotifications = true },
                onTapMyInfo: { showMyInfo = true }
            )
        }
        // 둥근 플로팅 필 바(3탭 순수 네비게이션). 지도 루트 위 .overlay 로 얹는다.
        //  지도 콘텐츠(우하단 컨트롤 등)는 MapView 가 자체 하단 패딩으로 바 footprint 를 회피한다.
        //  채팅·룰렛은 시트라 바 위로 떠 footprint 와 무관(시트 dismiss 시 selection=.map 으로 바 선택 복귀).
        .overlay(alignment: .bottom) {
            FloatingTabBar(selection: $selection)
                // 키보드 표시 시 바만 고정(Q3/QE-2, ZT-3): ignoresSafeArea(.keyboard)를 바에 한정한다.
                .ignoresSafeArea(.keyboard, edges: .bottom)
        }
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

    // MARK: - 시트 표시 바인딩(selection → 채팅·룰렛 시트)

    /// 채팅 시트 표시 바인딩(selection==.chat). dismiss(스와이프/닫기) 시 set(false)가 selection=.map 으로 되돌린다.
    private var chatSheetBinding: Binding<Bool> {
        Binding(
            get: { selection == .chat },
            set: { if !$0 { selection = .map } }
        )
    }

    /// 룰렛("어디갈까") 시트 표시 바인딩(selection==.roulette). dismiss 시 selection=.map.
    private var rouletteSheetBinding: Binding<Bool> {
        Binding(
            get: { selection == .roulette },
            set: { if !$0 { selection = .map } }
        )
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
