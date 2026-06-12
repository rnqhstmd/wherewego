import SwiftUI

// 메인 탭 화면(설계 §1·§2·§11 / IA 재설계 4탭). 온보딩 종착 — 4탭(지도·DM·알림·내정보).
//  - 지도=2레벨(IA 재설계 §3): currentGroupId==nil → GroupListView(그룹 목록 레벨0), !=nil → MapView(그 그룹 지도 레벨1).
//  - DM=DMListView(그룹별 봇방 목록, #105 소비) → 행 탭 시 그 그룹 BotChatView 방 push(2레벨). 알림: NotificationInboxView. 내정보: MyInfoView.
//  - 어디갈까(룰렛)는 탭 제거 → 지도 좌하단 어디가지 FAB(MapView)로 이동. rouletteViewModel 은 그 FAB 시트가 재사용(VM 수명 본 뷰 소유).
//  - 그룹 컨텍스트(IA 재설계 §1): GroupContext 를 @StateObject 로 소유. onGroupChanged → mapViewModel.switchTo 배선(그룹 전환 시 지도 재로드).
//    진입 시 .task 에서 bootstrap(listMyGroups → groups, lastGroupId 유효 시 그 그룹 직행).
//  - 시스템 탭바 숨김 + .safeAreaInset(edge:.bottom) 으로 FloatingTabBar 부착(둥근 플로팅 필 바, 콘텐츠 자동 회피). ＋ 는 탭이 아닌 액션(selection 불변, BR-1/AC-2).
//  - ＋ 장소 추가(P8 영역1·영역4 후속): 탭바에서 분리되어 지도 화면 우하단 speed-dial(MapView.addPinSpeedDial)로 이동했다.
//    speed-dial 2선택지(✋ 콕찍기 / 🔍 검색) → mapViewModel.enterAddPin(mode:) → 메인 지도 인라인 오버레이(시트 제거).
//    탭 전환(selection 변경) 시 exitAddPin 으로 자동 종료(BR-1).
//  - 딥링크 소비(설계 §3): DeepLinkRouter.pending 관찰 → 탭 전환/네비게이션 후 pending=nil.
//      · .chat → DM 탭, .pin(id)/.map → 지도 탭 (+ .pin 은 핀 로드 후 flyTo), .invite(slug) → 초대 시트
//  - 알림 배지(설계 §14): 앱 진입/포그라운드 복귀 시 onForeground(list 만 — 배지 갱신, 읽음 처리 안 함).
//      읽음 처리(readAll)는 알림 탭 진입 시 NotificationInboxView.load() 에서만 발생한다.
//
// 봇 채팅 수신은 STOMP 제거 후 전송 직후 폴링 + scenePhase 재조회 + APNs 푸시로 대체(채팅 이벤트 전환).
// ViewModel 수명은 본 뷰가 @StateObject 로 보유(탭 전환에도 유지 — QE-1 map/bot VM 수명 보존).
struct MainTabView: View {

    private let dependencies: AppDependencies

    @StateObject private var mapViewModel: MapViewModel
    /// 그룹 컨텍스트(IA 재설계 §1). 지도 탭 2레벨 분기·그룹 전환 상태 소유. onGroupChanged → mapViewModel.switchTo 배선.
    @StateObject private var groupContext: GroupContext
    /// 어디갈까(룰렛) VM. mapViewModel 공유(추첨 풀=pins, "지도에서 보기" flyTo). 탭 전환에도 결과 유지.
    @StateObject private var rouletteViewModel: RouletteViewModel
    /// DM 목록 VM(DM 그룹별 전환, #105 소비). 미읽음 배지(hasUnread)를 FloatingTabBar 가 항상 관찰 — 탭 진입 전에도 노출.
    ///  방별 BotChatViewModel 은 본 VM 이 소유하지 않고, DMListView 가 방 진입 시 팩토리로 @StateObject 생성(인스타식 수명).
    @StateObject private var dmListViewModel: DMListViewModel
    /// 알림함 VM(설계 §2). 미읽음 배지(unreadCount)를 FloatingTabBar 가 항상 관찰 — 탭 진입 전에도 노출.
    @StateObject private var notificationInboxViewModel: NotificationInboxViewModel
    /// 내정보 VM(설계 §2). 본 뷰가 소유해 body 재계산마다 재생성되지 않도록 한다(MyInfoView 는 @ObservedObject 관찰).
    @StateObject private var myInfoViewModel: MyInfoViewModel
    /// 딥링크 라우터(AppDependencies 소유 단일 인스턴스). pending 변화를 관찰해 탭 전환.
    @ObservedObject private var deepLinkRouter: DeepLinkRouter

    @Environment(\.scenePhase) private var scenePhase

    @State private var selection: MainTab = .map
    /// .invite 딥링크 시 표시할 초대 슬러그(시트 트리거). nil 이면 미표시.
    @State private var inviteSlug: String?
    /// 그룹 추가 진입(GroupListView "새 그룹/합류") 시 표시할 시트 트리거(IA 재설계 §3 빈 상태 연계).
    @State private var groupEntrySheet: GroupEntrySheet?

    init(dependencies: AppDependencies) {
        self.dependencies = dependencies
        // mapViewModel 을 먼저 생성해 rouletteViewModel·groupContext 와 공유한다(추첨 풀=pins, 그룹 전환 시 switchTo 트리거).
        let map = MapViewModel(
            pinAPI: dependencies.pinAPI,
            placeAPI: dependencies.placeAPI,
            groupAPI: dependencies.groupAPI,
            locationService: dependencies.locationService
        )
        _mapViewModel = StateObject(wrappedValue: map)
        // 그룹 컨텍스트(IA 재설계 §1). 그룹 진입/전환 시 mapViewModel.switchTo 로 지도 재로드를 약결합 배선.
        //  map 로컬 인스턴스를 클로저가 캡처(StateObject wrappedValue 와 동일 인스턴스) — 그룹 변경 시 그 지도 VM 이 재로드된다.
        _groupContext = StateObject(
            wrappedValue: GroupContext(
                groupAPI: dependencies.groupAPI,
                onGroupChanged: { groupId in
                    Task { await map.switchTo(groupId: groupId) }
                }
            )
        )
        _rouletteViewModel = StateObject(
            wrappedValue: RouletteViewModel(
                mapViewModel: map,
                locationService: dependencies.locationService
            )
        )
        _dmListViewModel = StateObject(
            wrappedValue: DMListViewModel(chatAPI: dependencies.chatAPI, currentUser: dependencies.currentUser)
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
                sessionStore: dependencies.session,
                currentUser: dependencies.currentUser,
                logoutHandler: dependencies.logout
            )
        )
        _deepLinkRouter = ObservedObject(wrappedValue: dependencies.deepLinkRouter)
    }

    var body: some View {
        TabView(selection: $selection) {
            // 지도 탭 2레벨(IA 재설계 §3): currentGroupId==nil → GroupListView(레벨0), !=nil → MapView(레벨1).
            //  레벨1 MapView 는 외부 주입 VM 공유(딥링크 flyTo·그룹 전환 switchTo 대상). 어디가지(룰렛) FAB 로 진입.
            mapTabContent
                // iOS 26: TabView 레벨의 .toolbar(.hidden, for:.tabBar) 가 무시되어 네이티브 Liquid Glass
                // 탭바(라벨 없는 빈 캡슐)가 커스텀 바 뒤에 유령처럼 렌더됐다 → 탭 콘텐츠별로 걸어야 적용된다.
                .toolbar(.hidden, for: .tabBar)
                .tag(MainTab.map)

            // DM 탭(그룹별 봇방 목록, #105 소비). DMListView(레벨0 목록) → 행 탭 시 그 그룹 BotChatView 방 push(레벨1).
            //  방별 BotChatViewModel 은 DMListView 가 진입 시 팩토리로 @StateObject 생성(deps 캡처) — 인스타식 수명(pop 시 해제).
            NavigationStack {
                DMListView(
                    viewModel: dmListViewModel,
                    groupContext: groupContext,   // GP-1 FR-5: 방 썸네일 그룹 조인(대표 이미지/콜라주).
                    pushSignal: dependencies.chatPushSignal,
                    makeRoomViewModel: { room in
                        GroupChatViewModel(
                            groupId: room.groupId,
                            roomId: room.roomId,
                            initialUnreadCount: room.unreadCount ?? 0,   // 미읽음 위치부터 진입 앵커.
                            chatAPI: dependencies.chatAPI,
                            pinAPI: dependencies.pinAPI,
                            currentUser: dependencies.currentUser,
                            deepLinkRouter: dependencies.deepLinkRouter,
                            chatPushSignal: dependencies.chatPushSignal
                        )
                    }
                )
            }
            .reserveFloatingTabBarSpace()   // 탭 콘텐츠가 바 footprint 회피(TabView는 safe area 전파 안 함 — PR리뷰)
            .toolbar(.hidden, for: .tabBar)   // iOS 26: 탭 콘텐츠별 부착 필요(유령 네이티브 바 방지)
            .tag(MainTab.chat)

            // 알림 탭. 진입 시 NotificationInboxView.load() 가 list+readAll(읽음 처리, 설계 §14).
            NavigationStack {
                NotificationInboxView(viewModel: notificationInboxViewModel)
            }
            .reserveFloatingTabBarSpace()
            .toolbar(.hidden, for: .tabBar)   // iOS 26: 탭 콘텐츠별 부착 필요(유령 네이티브 바 방지)
            .tag(MainTab.notification)

            // 내정보 탭. VM 은 본 뷰가 소유(@StateObject), authAPI 는 닉네임 수정 시트용으로 전달.
            NavigationStack {
                MyInfoView(
                    authAPI: dependencies.authAPI,
                    viewModel: myInfoViewModel
                )
            }
            .reserveFloatingTabBarSpace()
            .toolbar(.hidden, for: .tabBar)   // iOS 26: 탭 콘텐츠별 부착 필요(유령 네이티브 바 방지)
            .tag(MainTab.myInfo)
        }
        // 시스템 탭바 숨김 — 커스텀 FloatingTabBar 로 대체(설계 §1, #95 5탭 플로팅).
        .toolbar(.hidden, for: .tabBar)
        .tint(WGColor.cta)
        // 바 부착(설계 §2 개정 / PR리뷰): TabView 에 직접 .safeAreaInset 을 걸면 그 safe area 가 개별 탭
        //  자식 뷰로 전파되지 않는 SwiftUI 한계가 있다(콘텐츠가 바 뒤로 가림). 따라서 각 탭이
        //  .reserveFloatingTabBarSpace() 로 자체 footprint 를 확보하고, 바는 .overlay 로 얹는다.
        //  overlay 콘텐츠는 container safe area 를 존중하므로 바가 홈 인디케이터 위에 배치된다(AC-4).
        // 둥근 플로팅 필 바(4탭 순수 네비게이션). 알림 미읽음 배지(unreadCount>0)만 표시.
        // 장소 추가(＋)는 지도 화면 우하단 speed-dial 로 분리됨(MapView.addPinSpeedDial) — 탭바에는 없다.
        .overlay(alignment: .bottom) {
            FloatingTabBar(
                selection: $selection,
                hasUnread: notificationInboxViewModel.unreadCount > 0,
                // DM 탭 미읽음 배지(FR-10/AC-9): 안 읽은 봇 방 1개 이상이면 빨간 점.
                hasChatUnread: dmListViewModel.hasUnread,
                // 내정보 탭 아이콘 = 내 프사 원형(IG-1) — 프사/닉네임 변경 관찰.
                currentUser: dependencies.currentUser,
                // 지도 탭 재탭(이미 .map 선택 중) → 그룹 목록(레벨0)(IA 재설계 FR-3/AC-4).
                onReselectMap: { groupContext.backToList() }
            )
            // 키보드 표시 시 바만 고정(Q3/QE-2, ZT-3): ignoresSafeArea(.keyboard)를 바에 한정한다.
            //  TabView 전체에 걸면 채팅 입력바의 SwiftUI 키보드 회피까지 억제되어 입력바가 키보드에 가려진다.
            //  바에만 적용 → 바는 고정(키보드 뒤), 채팅 입력바는 키보드 위로 정상 회피.
            .ignoresSafeArea(.keyboard, edges: .bottom)
        }
        // 릴스 포커스 배너(FR-I13): 지도 탭 + 그룹 진입(레벨1) + focusedInstagramUrl 활성 시 상단 오버레이. ✕(전체 보기) → clearReelFocus(BR-4).
        //  레벨0(그룹 목록)에선 지도가 없으므로 배너를 띄우지 않는다(IA 재설계 §3).
        .overlay(alignment: .top) {
            if selection == .map, groupContext.currentGroupId != nil, mapViewModel.focusedInstagramUrl != nil {
                reelFocusBanner
            }
        }
        .animation(.easeOut(duration: 0.2), value: mapViewModel.focusedInstagramUrl)
        // 그룹 컨텍스트 부트스트랩(IA 재설계 §1) + 딥링크 소비(설계 §3) + 알림 배지 최초 갱신(설계 §14).
        //  bootstrap: listMyGroups → groups, lastGroupId 유효 시 그 그룹 직행(currentGroupId 세팅). 진입 시 1회.
        //  bootstrap 으로 currentGroupId 가 채워지면 지도 탭이 레벨1(MapView)로 들어가며 MapView.task 가 핀을 로드한다.
        .task {
            await groupContext.bootstrap()
            consumePending()
            await notificationInboxViewModel.onForeground()
            // DM 탭 미읽음 배지 최초 갱신(FR-6/FR-10) — 탭 진입 전에도 배지 노출(무음 refresh).
            await dmListViewModel.refresh()
        }
        .onChange(of: deepLinkRouter.pending) { _, _ in
            consumePending()
        }
        // 탭 전환(selection 변경) 시 인라인 추가 모드 종료(BR-1/AC-12). 지도 탭에서 ＋ FAB 로 추가 모드에
        //  들어간 뒤 다른 탭으로 이동하면 작성 중 상태를 정리한다.
        // 작성 중 위치 정보는 exitAddPin 이 폐기(cancelPendingWork → 디바운스/생성 Task 취소).
        // ＋ speed-dial 을 펼친 채(모드 미진입) 탭 이동한 경우도 메뉴를 닫아 잔여 펼침 상태를 정리한다.
        .onChange(of: selection) { _, _ in
            mapViewModel.exitAddPin()
            mapViewModel.isAddMenuExpanded = false
        }
        // 포그라운드 복귀 시 알림 배지 갱신(설계 §14, list 만 — 읽음 처리는 알림 탭 진입에서만) + DM 배지 갱신(FR-6/FR-10).
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task {
                    await notificationInboxViewModel.onForeground()
                    await dmListViewModel.refresh()
                }
            }
        }
        // 초대 코드 합류 시트(.invite 딥링크). 합류/취소 시 닫힘.
        .sheet(item: inviteSlugBinding) { slug in
            NavigationStack {
                InviteCodeView(
                    groupAPI: dependencies.groupAPI,
                    prefill: slug.value,
                    onJoined: { groupId in
                        // 합류 성공: 가입 그룹으로 진입(지도 레벨1) + 목록 갱신 + 지도 탭으로 + 시트 닫기.
                        groupContext.enterGroup(groupId)
                        selection = .map
                        Task { await groupContext.refresh() }
                        inviteSlug = nil
                    },
                    onCancel: { inviteSlug = nil }
                )
            }
        }
        // 그룹 목록(레벨0) 빈 상태/추가 진입 시트(IA 재설계 §3 연계). 새 그룹 생성 / 초대 코드 합류.
        //  합류·생성 후 그룹 목록을 재조회(refresh)해 최신 목록을 반영한다(닫힘 시).
        .sheet(item: $groupEntrySheet, onDismiss: {
            Task { await groupContext.refresh() }
        }) { entry in
            NavigationStack {
                switch entry {
                case .create:
                    GroupCreateView(
                        groupAPI: dependencies.groupAPI,
                        onCreated: { groupId in
                            // 생성 성공: 그 그룹으로 진입(레벨1) + 목록 갱신(이미지·멤버 동봉) + 시트 닫기(.invite 패턴 동치).
                            groupContext.enterGroup(groupId)
                            Task { await groupContext.refresh() }
                            groupEntrySheet = nil
                        }
                    )
                case .invite:
                    InviteCodeView(
                        groupAPI: dependencies.groupAPI,
                        onJoined: { groupId in
                            // 합류 성공: 가입 그룹으로 진입(레벨0 목록 → 그 그룹 지도) + 목록 갱신 + 시트 닫기.
                            groupContext.enterGroup(groupId)
                            Task { await groupContext.refresh() }
                            groupEntrySheet = nil
                        },
                        onCancel: { groupEntrySheet = nil }
                    )
                }
            }
        }
    }

    // MARK: - 지도 탭 2레벨 분기(IA 재설계 §3)

    /// 지도 탭 콘텐츠: 그룹 미진입(currentGroupId==nil) → 그룹 목록(레벨0), 진입 → 그 그룹 지도(레벨1).
    ///  레벨0 GroupListView 의 그룹 선택 → groupContext.enterGroup(currentGroupId 세팅 + switchTo 재로드).
    ///  레벨1 MapView 는 상단 그룹 오버레이(전환/뒤로/⋯) + 좌하단 어디가지 FAB(룰렛)를 위해 groupContext/rouletteViewModel 주입.
    @ViewBuilder
    private var mapTabContent: some View {
        if groupContext.currentGroupId == nil {
            GroupListView(
                groupContext: groupContext,
                onCreateGroup: { groupEntrySheet = .create },
                onJoin: { groupEntrySheet = .invite }
            )
            .reserveFloatingTabBarSpace()
        } else {
            MapView(
                viewModel: mapViewModel,
                groupContext: groupContext,
                rouletteViewModel: rouletteViewModel,
                currentUser: dependencies.currentUser,
                groupAPI: dependencies.groupAPI
            )
        }
    }

    // MARK: - 릴스 포커스 배너(FR-I13)

    /// "🎬 이 릴스에서 저장한 N곳 · 전체 보기 ✕" 배너. N=visiblePins.count(릴스 포커스 적용된 표시 핀 수).
    /// 탭 시 clearReelFocus(필터 해제 + 배너 닫힘, BR-4). TopBar 영역과 겹치지 않도록 상단 safe area 아래 배치.
    private var reelFocusBanner: some View {
        HStack(spacing: 8) {
            Text("🎬 이 릴스에서 저장한 \(mapViewModel.visiblePins.count)곳")
                .font(WGFont.sans(13))
                .fontWeight(.semibold)
                .foregroundStyle(WGColor.ink)
            Spacer(minLength: 0)
            Button {
                mapViewModel.clearReelFocus()
            } label: {
                HStack(spacing: 4) {
                    Text("전체 보기")
                        .font(WGFont.sans(13))
                        .foregroundStyle(WGColor.cta)
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(WGColor.inkSoft)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(WGColor.hairline, lineWidth: 1))
        .shadow(color: WGColor.shadowMd, radius: 10, y: 3)
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    // MARK: - 딥링크 소비(설계 §3)

    /// pending destination 을 읽어 탭 전환/네비게이션 후 nil 로 리셋(1회 소비).
    private func consumePending() {
        guard let destination = deepLinkRouter.pending else { return }
        switch destination {
        case .chat:
            // .chat → DM 탭(MainTab.chat 유지, 라벨만 "DM"). 케이스 리네이밍 없이 라우팅 정합(IA 재설계 §7).
            selection = .chat
        case .map:
            // .map → 지도 탭. 그룹 미진입(레벨0)이면 그룹 목록, 진입(레벨1)이면 그 그룹 지도가 표시된다(2레벨).
            selection = .map
        case .pin(let pinId):
            // 지도 탭으로 전환 후 핀으로 flyTo(핀 목록 로드 전이면 flyTo 가 no-op — 로드 후 재소비는 없으나
            // 진입 시점엔 이미 load 가 진행되므로 best-effort). 설계 §3.
            selection = .map
            mapViewModel.flyTo(pinId: pinId)
        case .reelFocus(let groupId, let url):
            // 「구경하실래요?」(GC-2 FR-GC2-5): 지도 탭 + 해당 그룹 전환(레벨1) + 릴스 핀 필터/fitBounds.
            //  enterGroup 으로 UI 레벨1·배너 정합을 맞추고, 그룹 핀 로드+포커스는 focusReel(groupId:) 가 일임한다.
            selection = .map
            groupContext.enterGroup(groupId)
            Task { await mapViewModel.focusReel(groupId: groupId, instagramUrl: url) }
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

/// 그룹 목록(레벨0) 추가 진입 시트 식별자(IA 재설계 §3). 새 그룹 생성 / 초대 코드 합류.
private enum GroupEntrySheet: Identifiable {
    case create
    case invite

    var id: Int {
        switch self {
        case .create: return 0
        case .invite: return 1
        }
    }
}
