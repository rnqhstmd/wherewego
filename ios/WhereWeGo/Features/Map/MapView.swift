import SwiftUI

// 지도 메인 화면(설계 §3·§7·§11, FR-2/6/7/8). 온보딩 종착 → 지도 진입(GroupsView 대체).
// frontend/src/app/map/MapClient.tsx 레이아웃 이식: 지도 배경 + 상단 태그필터 + 플로팅 버튼(룰렛 우상단·내위치 우하단).
//
// 지도 배경은 MapContainerView(선언적 바인딩 markers + cameraCommand + onEvent)로 그린다.
// 정보창(PinDetailSheet)·장소추가(AddPlaceSheet)·룰렛(RouletteSheet) 시트를 activeSheet 로 연결한다.
// P7: 하단 액션바(검색/여기에추가/룰렛 3버튼) 제거 → 룰렛/내위치 플로팅 버튼 + ＋ 통합 추가(AddPlaceSheet).
struct MapView: View {
    @StateObject private var viewModel: MapViewModel
    @Environment(\.scenePhase) private var scenePhase

    /// 외부에서 생성·소유한 MapViewModel 을 주입한다(설계 §9 — MainTabView 가 딥링크 .pin/.map flyTo 를 위해 VM 공유).
    /// MapViewModel 은 @MainActor 이며 MainTabView 가 @StateObject 로 수명을 보유한다.
    init(viewModel: MapViewModel) {
        _viewModel = StateObject(wrappedValue: viewModel)
    }

    var body: some View {
        ZStack {
            // 지도 배경(B2 선언적 바인딩). token 없으면 PlaceholderMapView 로 폴백.
            MapContainerView(
                markers: viewModel.markers,
                cameraCommand: $viewModel.cameraCommand,
                fitBoundsCommand: $viewModel.fitBoundsCommand,
                onEvent: viewModel.handle
            )
            .ignoresSafeArea()

            content

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
        .navigationBarBackButtonHidden(true)
        .task {
            if case .idle = viewModel.loadState {
                await viewModel.load()
            }
            viewModel.startVisitDetection()
            viewModel.startPolling()
        }
        // 화면 이탈 시 폴링 정리(누수/중복 방지, BR-7).
        .onDisappear {
            viewModel.stopPolling()
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
        // 핀 상세(정보창): 마커 탭 → selectedPinId → PinDetailSheet(B4-1).
        // selectedPinId 를 PinSummary? 로 투영한 바인딩으로 .sheet(item:) 연결, 닫힘 시 선택 해제.
        .sheet(item: selectedPinBinding) { pin in
            PinDetailSheet(pin: pin, mapViewModel: viewModel)
        }
        // ＋ 통합 장소 추가 시트(activeSheet=.addPlace, FR-8/12~16). EmptyMapCard 진입점(MainTabView ＋ 와 동일 컴포넌트).
        .sheet(isPresented: addPlaceSheetBinding) {
            AddPlaceSheet(mapViewModel: viewModel)
        }
        // 룰렛 시트(activeSheet=.roulette, FR-20~24).
        .sheet(isPresented: rouletteSheetBinding) {
            RouletteSheet(mapViewModel: viewModel, locationService: viewModel.locationService)
        }
        // 방문 메모 시트(activeSheet=.visitMemo, FR-29/30, AC-15).
        // onDismiss: 시트가 완전히 닫힌 뒤 보류된 pendingDetailPinId 를 selectedPinId 로 소비한다.
        // (finish() 단일 사이클의 이중 .sheet(item:) 전환 경쟁 회피 — selectedPinBinding 이 PinDetail 을 연다.)
        .sheet(item: visitMemoSheetBinding, onDismiss: {
            if let pinId = viewModel.pendingDetailPinId {
                viewModel.pendingDetailPinId = nil
                viewModel.selectedPinId = pinId
            }
        }) { pin in
            VisitMemoSheet(pin: pin, mapViewModel: viewModel)
        }
    }

    // MARK: - activeSheet → 시트 표시 바인딩

    /// .addPlace 표시 바인딩. 닫힘 시 activeSheet=.none.
    private var addPlaceSheetBinding: Binding<Bool> {
        Binding(
            get: { viewModel.activeSheet == .addPlace },
            set: { isPresented in
                if !isPresented, viewModel.activeSheet == .addPlace { viewModel.activeSheet = .none }
            }
        )
    }

    /// .roulette 표시 바인딩. 닫힘 시 activeSheet=.none.
    private var rouletteSheetBinding: Binding<Bool> {
        Binding(
            get: { viewModel.activeSheet == .roulette },
            set: { isPresented in
                if !isPresented, viewModel.activeSheet == .roulette { viewModel.activeSheet = .none }
            }
        )
    }

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

    /// selectedPinId → 현재 선택 핀(PinSummary?) 바인딩. 시트 닫힘 시 selectedPinId 를 nil 로 리셋.
    private var selectedPinBinding: Binding<PinSummary?> {
        Binding(
            get: { viewModel.selectedPin },
            set: { newValue in viewModel.selectedPinId = newValue?.id }
        )
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
            // 상단 태그 필터 + (핀 0개 시) 빈 상태 카드.
            VStack(spacing: 0) {
                TagFilterBar(activeFilters: $viewModel.activeFilters)
                    .background(
                        WGColor.bg.opacity(0.92)
                            .clipShape(Capsule())
                    )
                    .padding(.top, 8)

                Spacer(minLength: 0)

                // 핀 0개(loaded) → 빈 상태 카드(FR-8). ＋ 와 동일하게 .addPlace 시트로 진입(단일 컴포넌트).
                if viewModel.pins.isEmpty {
                    EmptyMapCard(onAddPin: { viewModel.activeSheet = .addPlace })
                    Spacer(minLength: 0)
                }
            }

            // 룰렛: 지도 우상단 플로팅 원형 버튼(태그필터 바 아래, FR-6).
            VStack {
                HStack {
                    Spacer()
                    rouletteButton
                }
                .padding(.top, 60)
                Spacer()
            }

            // 내 위치: 지도 우하단 플로팅 버튼(FR-7). 룰렛(우상단)과 분리(ZStack alignment).
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    myLocationButton
                }
                .padding(.bottom, 28)
            }
        }
        .padding(.horizontal, 16)
    }

    // MARK: - 플로팅 버튼(룰렛 우상단 / 내 위치 우하단, FR-6/7)

    /// 룰렛 진입 플로팅 원형 버튼(FR-6). 우상단. stale 재조회는 RouletteViewModel.spin() 내부 await 가 단일 보장점.
    private var rouletteButton: some View {
        Button {
            viewModel.activeSheet = .roulette
        } label: {
            Image(systemName: "dice")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(WGColor.cta)
                .frame(width: 48, height: 48)
                .background(Circle().fill(WGColor.panel))
                .shadow(color: WGColor.shadow, radius: 8, y: 3)
        }
        .accessibilityLabel("가볼까 룰렛")
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
