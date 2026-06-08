import SwiftUI

// 지도 메인 화면(설계 §3·§7·§11, FR-2/6/7/8). 온보딩 종착 → 지도 진입(GroupsView 대체).
// frontend/src/app/map/MapClient.tsx 레이아웃 이식: 지도 배경 + 좌하단 [!]범례·[▽]필터 + 플로팅 버튼(내위치/＋추가 우하단).
//  룰렛은 "어디갈까" 탭(RouletteView)으로 분리됨 — 우상단 🎲 시트 제거(지도=보기, 어디갈까=추천 멘탈모델 분리).
//
// 지도 배경은 MapContainerView(선언적 바인딩 markers + cameraCommand + onEvent)로 그린다.
// 핀 상세는 말풍선 오버레이(MapContainerView, 설계 §6 D-1)로 표시한다.
// P7: 하단 액션바(검색/여기에추가/룰렛 3버튼) 제거 → 룰렛/내위치 플로팅 버튼.
// P8 영역1: ＋ 장소 추가는 시트가 아닌 인라인 오버레이(isAddingPin → CrosshairOverlay + InlineAddPlaceCard).
// P8 영역4 후속: ＋ 진입점을 탭바 센터에서 지도 우하단 speed-dial(addPinSpeedDial)로 이전. 탭바는 4탭 순수 네비게이션.
//   ＋ 탭 → 2선택지(✋ 지도에서 찍기=콕찍기 / 🔍 검색해서 찾기=검색) → enterAddPin(mode:). 모드별 UI 분리:
//   십자선은 콕찍기 모드(inputMode==.pinpoint)만, 검색바는 검색 모드(.search)만 노출.
struct MapView: View {
    /// 지도 VM. MainTabView 가 @StateObject 로 소유·주입하므로 여기선 @ObservedObject(주입 VM 정석).
    ///  2레벨 조건부 렌더(목록↔지도)로 MapView 가 마운트/언마운트돼도 주입된 동일 인스턴스를 관찰해야
    ///  딥링크 flyTo·그룹 전환 switchTo 가 같은 VM 에 일관 적용된다(@StateObject 면 주입값 무시·상태 손실).
    @ObservedObject private var viewModel: MapViewModel
    /// 그룹 컨텍스트(IA 재설계 §1·§5). 상단 그룹 오버레이(그룹명·전환·뒤로) 표시·전환에 사용.
    @ObservedObject private var groupContext: GroupContext
    /// 어디가지(룰렛) VM(IA 재설계 §5). 좌하단 어디가지 FAB → 룰렛 시트가 재사용(MainTabView 소유, 탭 전환에도 결과 유지).
    @ObservedObject private var rouletteViewModel: RouletteViewModel
    @Environment(\.scenePhase) private var scenePhase

    /// 인라인 확정 카드 하단 여백(QE-2/AC-14). FloatingTabBar(높이 64 + bottom 12 = 76) 위로 겹치지 않게 확보.
    private static let inlineCardBottomPadding: CGFloat = 88

    /// 좌하단 태그 컨트롤(범례/필터) 팝업 상호배타 상태(웹 정합). 동시 표시 금지 + 바깥 탭 닫힘 공유.
    @State private var activeCornerPopup: CornerPopup = .none
    /// 어디가지(룰렛) 시트 표시(IA 재설계 §5). 좌하단 FAB → 룰렛 시트.
    @State private var showRoulette = false
    /// 그룹 전환 시트 표시(IA 재설계 §5). 상단 그룹 전환 버튼 → 내 그룹 목록 시트.
    @State private var showGroupSwitcher = false
    /// ⋯ 그룹관리 시트 표시(IA 재설계 §5, 진입점만 — 내용은 D단계).
    @State private var showGroupManage = false

    private enum CornerPopup { case none, legend, filter }

    /// 범례 팝업 토글 바인딩(열면 다른 팝업은 닫힘).
    private var legendPopupBinding: Binding<Bool> {
        Binding(
            get: { activeCornerPopup == .legend },
            set: { activeCornerPopup = $0 ? .legend : .none }
        )
    }

    /// 필터 팝업 토글 바인딩(열면 다른 팝업은 닫힘).
    private var filterPopupBinding: Binding<Bool> {
        Binding(
            get: { activeCornerPopup == .filter },
            set: { activeCornerPopup = $0 ? .filter : .none }
        )
    }

    /// 외부에서 생성·소유한 VM/컨텍스트를 주입한다(설계 §9 / IA 재설계 §1·§5 — MainTabView 가 수명 보유·공유).
    ///  - viewModel: 딥링크 .pin/.map flyTo·그룹 전환 switchTo 대상(MainTabView @StateObject).
    ///  - groupContext: 상단 그룹 오버레이(그룹명·전환·뒤로)에 사용(MainTabView @StateObject).
    ///  - rouletteViewModel: 좌하단 어디가지 FAB 룰렛 시트 재사용(MainTabView @StateObject, 탭 전환에도 결과 유지).
    init(
        viewModel: MapViewModel,
        groupContext: GroupContext,
        rouletteViewModel: RouletteViewModel
    ) {
        _viewModel = ObservedObject(wrappedValue: viewModel)
        _groupContext = ObservedObject(wrappedValue: groupContext)
        _rouletteViewModel = ObservedObject(wrappedValue: rouletteViewModel)
    }

    var body: some View {
        ZStack {
            // 지도 배경(B2 선언적 바인딩) + 핀 상세 말풍선 오버레이(설계 §6, D-1).
            // 말풍선 좌표 정렬을 위해 ignoresSafeArea 는 MapContainerView 내부 ZStack 이 일괄 적용한다(MUST-ADDRESS①).
            // token 없으면 PlaceholderMapView 로 폴백.
            MapContainerView(viewModel: viewModel)

            content

            // 상단 그룹 오버레이(IA 재설계 §5, FR-4/AC-5): 그룹명 + 그룹 전환 + 뒤로(→목록) + ⋯(그룹관리 진입점).
            //  레벨1(그룹 진입 상태)에서만 표시. 인라인 추가 모드 중엔 검색바/카드와 겹치지 않게 숨긴다.
            if !viewModel.isAddingPin {
                VStack {
                    groupTopOverlay
                    Spacer()
                }
            }

            // 인라인 핀 추가 모드(P8 영역1, FR-3/4, AC-2/3). 십자선은 토스트보다 아래 레이어
            // (방문 토스트가 십자선 위에 표시 — PRD 엣지). 십자선은 지도 중심(=화면 중앙) 위 고정.
            // 십자선은 콕찍기 모드(inputMode==.pinpoint)일 때만 노출(P8 영역4 후속 모드 분리). 검색 모드는 검색바로 위치 지정.
            // inputMode 는 addPlaceVM(중첩 ObservableObject) 소유라 MapView body 가 직접 추종하지 못하므로,
            // addVM 을 @ObservedObject 로 관찰하는 AddPinCrosshairLayer 로 분리해 모드 전환 시 십자선이 토글되게 한다.
            if viewModel.isAddingPin, let addVM = viewModel.addPlaceVM {
                AddPinCrosshairLayer(viewModel: addVM)

                VStack {
                    Spacer()
                    InlineAddPlaceCard(
                        viewModel: addVM,
                        onSelectResult: { place in
                            // 검색 결과 선택 → VM 갱신 + 메인 지도 flyTo(B2 계약, AC-9).
                            addVM.selectResult(place)   // 이미 unwrap 된 로컬 인스턴스 재사용(스타일 통일).
                            viewModel.flyTo(
                                lat: place.latitude,
                                lng: place.longitude,
                                zoom: MapViewModel.pinFocusZoom
                            )
                        },
                        onCancel: { viewModel.exitAddPin() }   // FR-8, AC-12
                    )
                    // QE-2/AC-14 — FloatingTabBar 와 겹치지 않도록 탭바 높이 이상 bottom padding.
                    .padding(.bottom, Self.inlineCardBottomPadding)
                }
                .transition(.move(edge: .bottom))   // QE-1
            }

            // 방문 토스트(설계 §4, FR-27). 화면 정중앙 오버레이. PinDetail 시트와 별개.
            if let pin = viewModel.visitToastPin {
                Color.black.opacity(0.12)
                    .ignoresSafeArea()
                    .transition(.opacity)
                VisitToastView(
                    pin: pin,
                    onConfirm: { Task { await viewModel.confirmVisit(pinId: pin.id) } },
                    onSkip: { viewModel.dismissVisitToast() }
                )
                .transition(.scale(scale: 0.92).combined(with: .opacity))
            }

            // confetti(하트 fan-out, 600ms). transitionedToMemoryNow==true 시 1회 발사(AC-15).
            if let trigger = viewModel.confettiTrigger {
                ConfettiHeartsView()
                    .id(trigger)
                    .allowsHitTesting(false)
            }

            // 방문 안내/에러 토스트(이미 추억/PATCH 실패, AC-15). 하단 자동 안내.
            if let message = viewModel.visitInfoMessage {
                VStack {
                    Spacer()
                    infoToast(message)
                        .padding(.bottom, 90)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: viewModel.visitToastPinId)
        .animation(.easeOut(duration: 0.2), value: viewModel.visitInfoMessage)
        // QE-1 — 십자선/하단 카드 등장·퇴장 전환(opacity + slide).
        .animation(.easeOut(duration: 0.2), value: viewModel.isAddingPin)
        .navigationBarBackButtonHidden(true)
        .task {
            // 진입 시 현재 그룹(currentGroupId)의 핀을 로드한다(IA 재설계 §3). load()(myActiveGroup 단수 resolve)는
            //  다중그룹에서 currentGroupId 와 어긋날 수 있으므로 그룹 id 를 명시해 로드한다. MapView 는 레벨1
            //  (currentGroupId != nil)에서만 렌더되지만 방어적으로 옵셔널 바인딩한다.
            if case .idle = viewModel.loadState, let groupId = groupContext.currentGroupId {
                await viewModel.load(groupId: groupId)
            }
            viewModel.startVisitDetection()
            viewModel.startPolling()
        }
        // 화면 이탈 시 폴링 정리(누수/중복 방지, BR-7) + 좌하단 팝업 닫기(탭 이동 후 잔여 표시 방지).
        .onDisappear {
            viewModel.stopPolling()
            activeCornerPopup = .none
        }
        // scenePhase 전환 처리(설계 §4 + BR-7 폴링 정리/재개).
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background:
                viewModel.onEnterBackground()
                viewModel.stopVisitDetection()
                viewModel.stopPolling()
            case .active:
                viewModel.startVisitDetection()
                viewModel.startPolling()
            default:
                break
            }
        }
        // 안내 토스트 자동 닫힘(1.5초, 웹 동치).
        .onChange(of: viewModel.visitInfoMessage) { _, message in
            guard message != nil else { return }
            Task {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                if viewModel.visitInfoMessage == message {
                    viewModel.visitInfoMessage = nil
                }
            }
        }
        // 핀 생성 성공 시 인라인 모드 종료는 AddPlaceViewModel.performCreate 가 didCreate 직후 직접 수행한다(견고화, #97).
        // (기존 onChange(addPlaceVM?.didCreate) Optional 체인 관찰 제거 — addPlaceVM nil 전환 시 관찰 누락 창 방지.)
        // 핀 상세(정보창): 마커 탭 → selectedPinId → 말풍선 오버레이(MapContainerView, 설계 §6 D-1, #96).
        // 시트(.sheet) 대신 메인 지도 ZStack 오버레이로 표시 — 표시조건 D-4(selectedPin != nil && activeSheet == .none)는 MapContainerView 에서 파생.
        // 장소 추가는 시트가 아닌 인라인 오버레이(isAddingPin)로 전환됨(#97) — AddPlaceSheet/.sheet(item:selectedPinBinding) 제거.
        // 방문 메모 시트(activeSheet=.visitMemo, FR-29/30, AC-15).
        // onDismiss: 시트가 완전히 닫힌 뒤 보류된 pendingDetailPinId 를 selectedPinId 로 소비한다.
        // (finish() 단일 사이클의 시트→말풍선 전환 경쟁 회피 — selectedPinId 세팅이 말풍선 오버레이를 연다.)
        .sheet(item: visitMemoSheetBinding, onDismiss: {
            if let pinId = viewModel.pendingDetailPinId {
                viewModel.pendingDetailPinId = nil
                // 시트 열린 사이 해당 핀이 삭제(낙관삭제/폴링)됐으면 승격하지 않는다 — 좀비 selectedPinId(selectedPin nil) 방지.
                // 좀비 상태면 재탭 가드(D-2)가 오동작할 수 있어 pins 존재를 먼저 검증한다.
                if viewModel.pins.contains(where: { $0.id == pinId }) {
                    viewModel.selectedPinId = pinId
                }
            }
        }) { pin in
            VisitMemoSheet(pin: pin, mapViewModel: viewModel)
        }
        // 어디가지(룰렛) 시트(IA 재설계 §5). 진입 시 자동 추첨(룰렛 탭 onChange 동치 이식). RouletteView 재사용.
        //  "지도에서 보기" → showOnMap()(flyTo+정보창) 후 시트 닫기(onShowOnMap).
        .sheet(isPresented: $showRoulette) {
            NavigationStack {
                RouletteView(
                    viewModel: rouletteViewModel,
                    onShowOnMap: { showRoulette = false }
                )
            }
        }
        // 그룹 전환 시트(IA 재설계 §5). 내 그룹 목록에서 선택 → switchGroup(지도 재로드). 동일 그룹 선택은 no-op.
        .sheet(isPresented: $showGroupSwitcher) {
            NavigationStack {
                groupSwitcherSheet
            }
        }
        // ⋯ 그룹관리 시트(IA 재설계 §5, 진입점만 — 내용은 D단계).
        .sheet(isPresented: $showGroupManage) {
            NavigationStack {
                groupManagePlaceholder
            }
        }
    }

    // MARK: - activeSheet → 시트 표시 바인딩

    /// .visitMemo(pinId) → 핀 투영 바인딩. 닫힘 시 activeSheet=.none.
    private var visitMemoSheetBinding: Binding<PinSummary?> {
        Binding(
            get: { viewModel.visitMemoPin },
            set: { newValue in
                if newValue == nil, case .visitMemo = viewModel.activeSheet { viewModel.activeSheet = .none }
            }
        )
    }

    // MARK: - 안내 토스트

    private func infoToast(_ message: String) -> some View {
        Text(message)
            .font(WGFont.sans(13))
            .foregroundStyle(WGColor.panel)
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .background(WGColor.ink.opacity(0.92))
            .clipShape(Capsule())
            .shadow(color: WGColor.shadowMd, radius: 12, y: 4)
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.loadState {
        case .idle, .loading:
            loadingOverlay
        case .error(let message):
            errorOverlay(message)
        case .loaded:
            loadedOverlay
        }
    }

    // MARK: - 로드 상태 오버레이

    private var loadingOverlay: some View {
        ProgressView()
            .tint(WGColor.cta)
            .padding(20)
            .background(WGColor.panel.opacity(0.9))
            .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private func errorOverlay(_ message: String) -> some View {
        VStack(spacing: 14) {
            Text(message)
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.ink)
                .multilineTextAlignment(.center)
            Button {
                Task {
                    // 재시도도 현재 그룹 기준으로 로드(load() 의 myActiveGroup 폴백이 잘못된 그룹을 부르지 않게).
                    if let groupId = groupContext.currentGroupId {
                        await viewModel.load(groupId: groupId)
                    }
                }
            } label: {
                Text("다시 시도")
                    .font(WGFont.sans(14))
                    .padding(.horizontal, 22)
                    .padding(.vertical, 12)
                    .background(WGColor.cta)
                    .foregroundStyle(WGColor.panel)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
        .padding(EdgeInsets(top: 26, leading: 26, bottom: 26, trailing: 26))
        .frame(maxWidth: 320)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .shadow(color: WGColor.shadowMd, radius: 16, y: 6)
    }

    private var loadedOverlay: some View {
        ZStack {
            // speed-dial 펼침 시 딤 배경(P8 영역4 후속). 패딩 밖 전체를 덮어 바깥 탭을 흡수한다(빈 곳 탭 → 메뉴 닫힘).
            //  컨트롤(태그필터/룰렛/내위치/speed-dial)보다 아래 레이어 → 버튼은 정상 탭되고 빈 곳 탭만 닫힌다.
            if viewModel.isAddMenuExpanded {
                Color.black.opacity(0.18)
                    .ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture { viewModel.isAddMenuExpanded = false }
                    .transition(.opacity)
            }

            // 좌하단 태그 컨트롤(범례/필터) 팝업 열림 중 바깥 탭 흡수 → 닫힘. 딤 없이 투명 레이어.
            //  컨트롤(룰렛/내위치/speed-dial/클러스터)보다 아래 레이어 → 버튼은 정상 탭되고 빈 곳 탭만 닫힌다.
            if activeCornerPopup != .none {
                Color.clear
                    .ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture { activeCornerPopup = .none }
            }

            // 핀 0개(loaded) → 빈 상태 카드(FR-8, 화면 중앙). 첫 장소는 검색 모드로 진입.
            //  상단 태그 필터 칩(구 TagFilterBar)은 좌하단 [!]범례·[▽]필터 버튼으로 이전됨(웹 정합) — 상단 칩 제거.
            if viewModel.pins.isEmpty {
                VStack(spacing: 0) {
                    Spacer(minLength: 0)
                    EmptyMapCard(onAddPin: { viewModel.enterAddPin(mode: .search) })
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
            }

            // 우하단 플로팅 컨트롤 세로 스택: 내 위치(위, 보조) + 장소 추가 ＋ speed-dial(아래, 주 액션).
            //  룰렛(우상단)과 분리(ZStack alignment). ＋ FAB 는 탭바에서 분리되어 이리로 이동(탭=이동 / FAB=지도 행동).
            //  주 액션(＋, 56 주황)을 thumb-reach 코너 최하단에 두고, 보조(내위치, 48 흰)를 그 위에 둔다.
            //  ＋ 탭 → speed-dial 펼침(✋ 지도에서 찍기 / 🔍 검색해서 찾기). 인라인 추가 모드(isAddingPin) 중엔
            //  하단 InlineAddPlaceCard 가 떠 있으므로 speed-dial 전체를 숨긴다(중복 방지).
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    VStack(alignment: .trailing, spacing: 14) {
                        myLocationButton
                        if !viewModel.isAddingPin {
                            addPinSpeedDial
                                .transition(.scale.combined(with: .opacity))   // QE-1 등장·퇴장
                        }
                    }
                }
                // 맵은 full-bleed 라 TabView safe area 전파에 기대지 않고, 버튼 스택이 바를 직접 회피한다(PR리뷰).
                //  바 footprint(contentFootprint = barHeight+bottomGap) 만큼 올리고 버튼-바 숨 여백(bottomGap)을 더한다(AC-2). 수치 보정 DoD-B.
                .padding(.bottom, FloatingTabBar.Metrics.contentFootprint + FloatingTabBar.Metrics.bottomGap)
            }
            .padding(.horizontal, 16)

            // 좌하단 컨트롤 클러스터(웹 MapClient bottom/left 이식). 어디가지 FAB(위) + [!]범례·[▽]필터(아래).
            //  어디가지 FAB(IA 재설계 §5): 룰렛 탭 제거 후 접근 경로 보존 — 좌하단 FAB → 룰렛 시트(rouletteViewModel 재사용).
            //  speed-dial(우하단)과 같은 bottom 라인에 좌측 배치(대칭). 팝업은 버튼 위로 떠 상호배타(activeCornerPopup).
            //  필터/범례 상단 이동·맵 로딩 최적화는 C단계(이번 비범위) — 현 위치 유지.
            VStack(alignment: .leading, spacing: 12) {
                Spacer()
                rouletteFAB
                HStack(spacing: 8) {
                    TagLegendButton(isOpen: legendPopupBinding)
                    TagFilterButton(
                        activeFilters: $viewModel.activeFilters,
                        isOpen: filterPopupBinding
                    )
                    Spacer(minLength: 0)
                }
                .padding(.bottom, FloatingTabBar.Metrics.contentFootprint + FloatingTabBar.Metrics.bottomGap)
            }
            .padding(.horizontal, 16)
            .zIndex(1)
        }
    }

    // MARK: - 상단 그룹 오버레이(IA 재설계 §5, FR-4/AC-5)

    /// 그룹명 + 뒤로(→목록) + 그룹 전환 + ⋯(그룹관리). 레벨1(그룹 진입)에서 지도 상단에 떠 있다.
    ///  - 뒤로: groupContext.backToList() → 그룹 목록(레벨0). 더블탭과 동일 결과(AC-4 보조 경로).
    ///  - 그룹 전환: 내 그룹 목록 시트(switchGroup). - ⋯: 그룹관리 진입점(내용 D).
    private var groupTopOverlay: some View {
        HStack(spacing: 10) {
            Button {
                groupContext.backToList()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(WGColor.ink)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(WGColor.panel))
                    .shadow(color: WGColor.shadow, radius: 6, y: 2)
            }
            .accessibilityLabel("그룹 목록으로")

            // 그룹명 + 전환(드롭다운 시트). 탭 → 그룹 전환 시트.
            Button {
                Task { await groupContext.refresh() }
                showGroupSwitcher = true
            } label: {
                HStack(spacing: 6) {
                    Text(currentGroupName)
                        .font(WGFont.emo(16))
                        .foregroundStyle(WGColor.ink)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(WGColor.inkSoft)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(Capsule().fill(WGColor.panel))
                .shadow(color: WGColor.shadow, radius: 6, y: 2)
            }
            .accessibilityLabel("그룹 전환")

            Spacer(minLength: 0)

            Button {
                showGroupManage = true
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(WGColor.ink)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(WGColor.panel))
                    .shadow(color: WGColor.shadow, radius: 6, y: 2)
            }
            .accessibilityLabel("그룹 관리")
        }
        .padding(.horizontal, 16)
        // reelFocus 배너(MainTabView 최상단 overlay)와 겹치지 않도록 상단 여백 확보(DoD-B 수치 보정).
        .padding(.top, 8)
    }

    /// 현재 진입한 그룹명(groupContext.groups 에서 currentGroupId 매칭). 미매칭이면 폴백 라벨.
    private var currentGroupName: String {
        guard let id = groupContext.currentGroupId,
              let group = groupContext.groups.first(where: { $0.groupId == id }) else {
            return "그룹"
        }
        return group.name
    }

    // MARK: - 그룹 전환 시트(IA 재설계 §5)

    /// 내 그룹 목록 — 선택 시 switchGroup(지도 재로드) 후 시트 닫기. 현재 그룹은 체크 표시.
    private var groupSwitcherSheet: some View {
        List {
            ForEach(groupContext.groups) { group in
                Button {
                    groupContext.switchGroup(group.groupId)
                    showGroupSwitcher = false
                } label: {
                    HStack(spacing: 10) {
                        Circle().fill(WGColor.pinWish).frame(width: 10, height: 10)
                        Text(group.name)
                            .font(WGFont.sans(15))
                            .foregroundStyle(WGColor.ink)
                        Spacer(minLength: 0)
                        if group.groupId == groupContext.currentGroupId {
                            Image(systemName: "checkmark")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(WGColor.cta)
                        }
                    }
                }
            }
        }
        .navigationTitle("그룹 전환")
        .navigationBarTitleDisplayMode(.inline)
    }

    /// ⋯ 그룹관리 시트(진입점만 — 내용은 D단계, IA 재설계 비범위).
    private var groupManagePlaceholder: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()
            Text("그룹 관리 — 다음 단계에서 구현")
                .font(WGFont.sans(15))
                .foregroundStyle(WGColor.inkSoft)
        }
        .navigationTitle("그룹 관리")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - 플로팅 버튼(어디가지 좌하단 / 내 위치·＋ 추가 우하단, IA 재설계 §5 + P8 영역4 후속)

    /// 어디가지(룰렛) FAB(IA 재설계 §5). 좌하단. 탭 → 룰렛 시트(자동 추첨). 룰렛 탭 제거 후 접근 경로 보존.
    private var rouletteFAB: some View {
        Button {
            showRoulette = true
            Task { await rouletteViewModel.spin() }   // 진입 즉시 자동 추첨(구 어디갈까 탭 onChange 동치)
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "dice.fill")
                    .font(.system(size: 16, weight: .semibold))
                Text("어디가지")
                    .font(WGFont.sans(13))
            }
            .foregroundStyle(WGColor.panel)
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(Capsule().fill(WGColor.cta))
            .shadow(color: WGColor.shadowMd, radius: 8, y: 3)
        }
        .accessibilityLabel("어디가지 추천")
    }

    /// 내 위치 플로팅 버튼(FR-7). 우하단. one-shot 현재 위치로 지도 카메라 이동.
    private var myLocationButton: some View {
        Button {
            Task {
                if let sample = await viewModel.locationService.requestOneShot() {
                    viewModel.flyTo(
                        lat: sample.latitude,
                        lng: sample.longitude,
                        zoom: MapViewModel.currentLocationZoom
                    )
                }
            }
        } label: {
            Image(systemName: "location.fill")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(WGColor.cta)
                .frame(width: 48, height: 48)
                .background(Circle().fill(WGColor.panel))
                .shadow(color: WGColor.shadow, radius: 8, y: 3)
        }
        .accessibilityLabel("내 위치")
    }

    /// 장소 추가 ＋ speed-dial(P8 영역4 후속 — 탭바 센터 ＋ 분리·이동). 우하단 코너 주 액션.
    ///  닫힘=＋ 원형 FAB(56 주황). ＋ 탭 → 위로 2선택지 펼침(✋ 지도에서 찍기=콕찍기 / 🔍 검색해서 찾기=검색).
    ///  각 선택지가 enterAddPin(mode:)로 해당 모드 진입. isAddingPin 중엔 상위 스택에서 숨겨 중복을 막는다.
    private var addPinSpeedDial: some View {
        VStack(alignment: .trailing, spacing: 12) {
            if viewModel.isAddMenuExpanded {
                speedDialItem(
                    icon: "hand.draw.fill",
                    label: "지도에서 찍기",
                    accessibility: "지도에서 찍어 추가"
                ) { viewModel.enterAddPin(mode: .pinpoint) }
                speedDialItem(
                    icon: "magnifyingglass",
                    label: "검색해서 찾기",
                    accessibility: "검색해서 추가"
                ) { viewModel.enterAddPin(mode: .search) }
            }
            mainPlusButton
        }
        .animation(.easeOut(duration: 0.2), value: viewModel.isAddMenuExpanded)
    }

    /// speed-dial 메인 ＋ 버튼(56 주황). 펼침 시 45° 회전해 ✕ 처럼 보인다(열기/닫기 토글).
    private var mainPlusButton: some View {
        Button {
            viewModel.isAddMenuExpanded.toggle()
        } label: {
            Image(systemName: "plus")
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(WGColor.panel)
                .frame(width: 56, height: 56)
                .background(Circle().fill(WGColor.cta))
                .rotationEffect(.degrees(viewModel.isAddMenuExpanded ? 45 : 0))
                .shadow(color: WGColor.shadowMd, radius: 10, y: 4)
        }
        .accessibilityLabel(viewModel.isAddMenuExpanded ? "추가 메뉴 닫기" : "장소 추가")
    }

    /// speed-dial 펼침 항목: 좌측 라벨 캡슐 + 우측 원형 아이콘 버튼(48 주황). 픽셀 정렬 보정 DoD-B.
    private func speedDialItem(
        icon: String,
        label: String,
        accessibility: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text(label)
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.ink)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(WGColor.panel)
                    .clipShape(Capsule())
                    .shadow(color: WGColor.shadow, radius: 6, y: 2)
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(WGColor.panel)
                    .frame(width: 48, height: 48)
                    .background(Circle().fill(WGColor.cta))
                    .shadow(color: WGColor.shadow, radius: 6, y: 2)
            }
        }
        .accessibilityLabel(accessibility)
        .transition(.move(edge: .trailing).combined(with: .opacity))
    }
}

// MARK: - 인라인 추가 십자선 레이어(콕찍기 모드 전용)

/// 콕찍기 모드(inputMode==.pinpoint)일 때만 중앙 십자선을 노출하는 얇은 래퍼(P8 영역4 후속 모드 분리).
/// addPlaceVM 을 @ObservedObject 로 직접 관찰해, 검색↔콕찍기 전환(inputMode 변화) 시 십자선이 토글되게 한다
/// (MapView body 는 중첩 ObservableObject 인 addPlaceVM 내부 변화를 추종하지 못하므로 분리, QE-1).
private struct AddPinCrosshairLayer: View {
    @ObservedObject var viewModel: AddPlaceViewModel

    var body: some View {
        ZStack {
            if viewModel.inputMode == .pinpoint {
                CrosshairOverlay()
                    .transition(.opacity)   // QE-1
            }
        }
        .animation(.easeOut(duration: 0.2), value: viewModel.inputMode)
    }
}

// MARK: - 방문 축하 confetti(하트 fan-out, 600ms)

/// 화면 중앙에서 하트 3개가 위쪽으로 부채꼴(fan-out) 퍼지며 사라지는 1회 애니메이션.
/// 웹 MapboxView.runMarkerBounceAndConfetti(각도 -120°/-90°/-60°, 거리 36~44px, 600ms) 톤 이식.
/// MapView 가 confettiTrigger(.id) 변화로 재생성하여 onAppear 시 1회 발사 후 자연 소멸.
struct ConfettiHeartsView: View {
    @State private var progress: CGFloat = 0

    /// (각도 deg, 거리 pt). 웹 동치 — 위쪽으로 -120/-90/-60도 부채꼴.
    private let hearts: [(angle: Double, distance: CGFloat)] = [
        (-120, 38),
        (-90, 44),
        (-60, 36),
    ]

    var body: some View {
        ZStack {
            ForEach(Array(hearts.enumerated()), id: \.offset) { _, heart in
                let radians = heart.angle * .pi / 180
                let dx = cos(radians) * heart.distance * progress
                let dy = sin(radians) * heart.distance * progress
                Image(systemName: "heart.fill")
                    .font(.system(size: 16))
                    .foregroundStyle(WGColor.pinMemory)
                    .offset(x: dx, y: dy)
                    .opacity(Double(1 - progress))
                    .scaleEffect(0.6 + progress * 0.6)
            }
        }
        .onAppear {
            withAnimation(.easeOut(duration: 0.6)) {
                progress = 1
            }
        }
    }
}
