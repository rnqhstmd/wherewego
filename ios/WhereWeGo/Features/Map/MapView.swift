import SwiftUI

// 지도 메인 화면(설계 §3·§7·§11, FR-2/6/7/8). 온보딩 종착 → 지도 진입(GroupsView 대체).
// frontend/src/app/map/MapClient.tsx 레이아웃 이식: 지도 배경 + 좌하단 [!]범례·[▽]필터 + 플로팅 버튼(룰렛 우상단 / 내위치·＋추가 우하단).
//  룰렛("어디갈까")은 독립 탭에서 지도 위 시트로 환원(웹 정합) — 우상단 🎲 버튼 → .sheet(RouletteView), 표시 시 spin() 자동.
//
// 지도 배경은 MapContainerView(선언적 바인딩 markers + cameraCommand + onEvent)로 그린다.
// 핀 상세는 말풍선 오버레이(MapContainerView, 설계 §6 D-1)로 표시한다.
// P7: 하단 액션바(검색/여기에추가/룰렛 3버튼) 제거 → 룰렛/내위치 플로팅 버튼.
// P8 영역1: ＋ 장소 추가는 시트가 아닌 인라인 오버레이(isAddingPin → CrosshairOverlay + InlineAddPlaceCard).
// P8 영역4 후속: ＋ 진입점을 탭바 센터에서 지도 우하단 speed-dial(addPinSpeedDial)로 이전. 탭바는 4탭 순수 네비게이션.
//   ＋ 탭 → 2선택지(✋ 지도에서 찍기=콕찍기 / 🔍 검색해서 찾기=검색) → enterAddPin(mode:). 모드별 UI 분리:
//   십자선은 콕찍기 모드(inputMode==.pinpoint)만, 검색바는 검색 모드(.search)만 노출.
struct MapView: View {
    /// 지도 메인 VM(MainTabView 소유 — 탭 전환에도 핀/카메라 상태 유지). MapView 는 관찰만 한다(@ObservedObject).
    ///  수명 소유는 MainTabView 의 @StateObject 이므로 여기서 @StateObject 로 이중 소유하면 안 된다(소유권 정정).
    @ObservedObject private var viewModel: MapViewModel
    /// 룰렛("어디갈까") VM(MainTabView 소유 — 탭 전환에도 결과 유지). 우상단 🎲 → 룰렛 시트 콘텐츠가 관찰.
    @ObservedObject private var rouletteViewModel: RouletteViewModel
    @Environment(\.scenePhase) private var scenePhase

    /// 룰렛 시트 표시 여부(우상단 🎲 트리거). 표시 시 spin() 자동 호출(구 어디갈까 탭 진입 동치).
    /// 그룹 전환 시 닫을 수 있도록 MainTabView 가 소유하고 Binding 으로 주입한다(BR-4 — 열려 있으면 닫고 전환).
    @Binding private var isRoulettePresented: Bool

    /// 인라인 확정 카드 하단 여백(QE-2/AC-14). FloatingTabBar(높이 64 + bottom 12 = 76) 위로 겹치지 않게 확보.
    private static let inlineCardBottomPadding: CGFloat = 88

    /// 좌하단 태그 컨트롤(범례/필터) 팝업 상호배타 상태(웹 정합). 동시 표시 금지 + 바깥 탭 닫힘 공유.
    @State private var activeCornerPopup: CornerPopup = .none

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

    /// 외부에서 생성·소유한 MapViewModel/RouletteViewModel 을 주입한다(설계 §9 — MainTabView 가 딥링크 flyTo·룰렛 결과 유지를 위해 VM 공유).
    /// 두 VM 모두 @MainActor 이며 MainTabView 가 @StateObject 로 수명을 보유한다(여기선 @ObservedObject 로 관찰만).
    /// isRoulettePresented 는 MainTabView 가 소유하는 룰렛 시트 표시 상태(그룹 전환 시 외부에서 닫기 위함, BR-4).
    init(viewModel: MapViewModel, rouletteViewModel: RouletteViewModel, isRoulettePresented: Binding<Bool>) {
        _viewModel = ObservedObject(wrappedValue: viewModel)
        _rouletteViewModel = ObservedObject(wrappedValue: rouletteViewModel)
        _isRoulettePresented = isRoulettePresented
    }

    var body: some View {
        ZStack {
            // 지도 배경(B2 선언적 바인딩) + 핀 상세 말풍선 오버레이(설계 §6, D-1).
            // 말풍선 좌표 정렬을 위해 ignoresSafeArea 는 MapContainerView 내부 ZStack 이 일괄 적용한다(MUST-ADDRESS①).
            // token 없으면 PlaceholderMapView 로 폴백.
            MapContainerView(viewModel: viewModel)

            content

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
        // 사진 업로드만 실패(BR-6) — 핀은 저장됐으나 사진만 실패한 경우 인라인 모드 종료 후 안내 토스트로 노출.
        // addPlaceVM 은 종료(exitAddPin) 직전 performCreate 가 photoWarning 을 세팅하므로, 종료 전 마지막 값을 흡수한다.
        .onChange(of: viewModel.addPlaceVM?.photoWarning) { _, message in
            if let message { viewModel.visitInfoMessage = message }
        }
        .navigationBarBackButtonHidden(true)
        .task {
            // 활성 그룹 해석 + 초기 핀 로드는 MainTabView 가 GroupContext.bootstrap() 결과를 주입해 주도한다
            //  (이중 myActiveGroup resolve 제거 — 칩과 지도가 항상 동일 그룹). 여기선 자체 load() 를 호출하지 않는다.
            //  단, 자식 task 가 부모보다 먼저 실행돼 아직 .idle 인 race 에서도, 주입 로드가 곧 .loading 으로
            //  전환하므로 중복 호출은 없다. 에러 후 재시도는 errorOverlay "다시 시도"(load()) 가 담당한다.
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
                // 글래스 통일: 콘텐츠 높이에 맞춘 medium detent + 드래그 인디케이터 + 글래스 배경(OS 기본 전체화면 방지).
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(24)
                .presentationBackground(.regularMaterial)
        }
        // 룰렛("어디갈까") 시트(작업 D — 독립 탭에서 지도 위 시트로 환원, 웹 MapClient activeSheet==="roulette" 정합).
        //  우상단 🎲 버튼 → isRoulettePresented. 표시 시 spin() 자동 호출(구 어디갈까 탭 진입 동치).
        //  "지도에서 보기"는 시트를 닫아 지도를 드러낸다(showOnMap 이 flyTo+정보창 후 dismiss).
        .sheet(isPresented: $isRoulettePresented) {
            RouletteSheetContent(
                viewModel: rouletteViewModel,
                onShowOnMap: { isRoulettePresented = false },
                onClose: { isRoulettePresented = false }
            )
            // 글래스 통일(클러스터 A): VisitMemoSheet 와 동일한 medium detent + 드래그 인디케이터 + 글래스 배경.
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(24)
            .presentationBackground(.regularMaterial)
        }
        // 룰렛 시트 표시 즉시 자동 추첨(GPS one-shot → 반경 10km 무작위 1곳). 매 표시마다 새로 돌린다(구 어디갈까 탭 진입 동치).
        .onChange(of: isRoulettePresented) { _, presented in
            if presented {
                Task { await rouletteViewModel.spin() }
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

            // 우상단 룰렛("어디갈까") 트리거(작업 D — 독립 탭에서 지도 위 시트로 환원). 🎲 탭 → 룰렛 시트 표시.
            //  우하단 ＋/내위치, 좌하단 범례/필터와 코너 분리(ZStack alignment) — 컨트롤 간 조화.
            VStack {
                HStack {
                    Spacer()
                    rouletteButton
                }
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)

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
                                // B-1 — 모드 진입 시 speed-dial 소멸과 InlineAddPlaceCard 등장(.move(edge:.bottom))의
                                //  이중 전환 출렁임을 줄이려 소멸/등장을 .opacity 로 단순화(scale 점프 제거).
                                .transition(.opacity)
                        }
                    }
                }
                // 맵은 full-bleed 라 TabView safe area 전파에 기대지 않고, 버튼 스택이 바를 직접 회피한다(PR리뷰).
                //  바 footprint(contentFootprint = barHeight+bottomGap) 만큼 올리고 버튼-바 숨 여백(bottomGap)을 더한다(AC-2). 수치 보정 DoD-B.
                .padding(.bottom, FloatingTabBar.Metrics.contentFootprint + FloatingTabBar.Metrics.bottomGap)
            }
            .padding(.horizontal, 16)

            // 좌하단 태그 컨트롤 클러스터(웹 MapClient bottom/left 이식). [!]마커 범례 + [▽]필터.
            //  speed-dial(우하단)과 같은 bottom 라인에 좌측 배치(대칭). 팝업은 버튼 위로 떠 상호배타(activeCornerPopup).
            //  팝업이 다른 오버레이 위로 뜨도록 zIndex 상향.
            VStack {
                Spacer()
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

    // MARK: - 플로팅 버튼(룰렛 우상단 / 내 위치·＋ 추가 우하단, FR-6/7 + P8 영역4 후속)

    /// 룰렛("어디갈까") 트리거 플로팅 버튼(작업 D, 우상단). 🎲 탭 → 룰렛 시트 표시(표시 시 spin() 자동).
    ///  내 위치 버튼(48 흰 원형)과 같은 형태 톤 — 아이콘만 🎲(주사위). 지도 위 보조 컨트롤로 조화.
    private var rouletteButton: some View {
        Button {
            isRoulettePresented = true
        } label: {
            Image(systemName: "dice.fill")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(WGColor.cta)
                .frame(width: 48, height: 48)
                .background(Circle().fill(WGColor.panel))
                .shadow(color: WGColor.shadow, radius: 8, y: 3)
                // 우하단 컬럼(내위치/＋ FAB)과 동일 56 레인 중심축 — 모든 플로팅 원형이 한 축에 정렬.
                .frame(width: 56)
        }
        .accessibilityLabel("어디갈까")
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
                // 메인 ＋ FAB(56)와 중심축을 맞춘다 — 48 원을 56 폭 레인 가운데(우하단 컬럼 정렬 어긋남 해소).
                .frame(width: 56)
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
                // B-1 — ＋ → ✕ 회전은 spring(.snappy)로 탄력 있게(선형 easeOut 대비 경쾌함).
                .animation(.snappy, value: viewModel.isAddMenuExpanded)
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
                    // ＋ FAB(56)·내위치와 동일 56 레인 가운데 정렬(중심축 일치).
                    .frame(width: 56)
            }
        }
        .accessibilityLabel(accessibility)
        // B-1 — speed-dial 은 세로로 펼쳐지므로 선택지 등장·퇴장도 세로축에 맞춘다.
        //  하단에서 솟아오르듯 bottomTrailing 앵커 scale + opacity(가로 .move(edge:.trailing) 축 불일치 제거).
        .transition(.scale(scale: 0.8, anchor: .bottomTrailing).combined(with: .opacity))
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
