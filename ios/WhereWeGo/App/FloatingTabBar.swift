import SwiftUI

// 둥근 플로팅 필 바(설계 §1·§4, FR-1~4 / IA 재설계 4탭).
//  - 시스템 탭바를 숨기고(MainTabView 에서 .toolbar(.hidden, for:.tabBar)) .overlay(alignment:.bottom) 으로 얹는다.
//    각 탭은 reserveFloatingTabBarSpace() 로 footprint 를 확보한다(TabView 는 safe area 를 자식 탭으로 전파하지 않음 — PR리뷰).
//  - 4칸 = 4탭 버튼(지도·DM·알림·내정보). 순수 네비게이션 바(maxWidth:.infinity 로 균등 분배).
//    지도=2레벨(그룹 목록↔그룹 지도), DM=봇방(구 채팅 라벨/아이콘만 "DM"으로 변경). 어디갈까(룰렛)는 탭 제거 → 지도 좌하단 FAB 로 이동.
//  - 선택 표시(FR-3): SF Symbols 외곽선↔채움 쌍. 선택=채움+WGColor.cta, 미선택=외곽선+WGColor.inkSoft. 알약 배경 없음.
//  - 지도 더블탭(IA 재설계 FR-3/AC-4): 이미 .map 선택 중 지도 탭 재탭 → onReselectMap 콜백 → 그룹 목록(레벨0).
//  - 미읽음(FR-22): hasUnread 시 알림(bell) 아이콘 우상단 빨간 점(WGColor.pinNew). 건수 미표시.
//    hasChatUnread 시 DM(paperplane, 인스타 DM 아이콘) 아이콘에도 동일 빨간 점(DM 그룹별 전환 FR-10/AC-9 — 안 읽은 봇 방 존재).
//  - 버전 분기(FR-4): iOS 26+ Liquid Glass(DoD-B 보정) / iOS 17~25 솔리드 둥근 필(WGColor.panel) 폴백.
//  - ＋ 장소 추가는 이 바에서 제거하고 지도 화면 우하단 speed-dial(MapView.addPinSpeedDial)로 이동했다.
//    근거: "탭=화면 이동 / FAB=지도 컨텍스트 행동" 멘탈모델 분리. 기존 센터 ＋는 selection 불변이라
//    채팅/알림/내정보 탭에서 누르면 (안 보이는 지도에만 작용해) 무반응이 되는 비대칭이 있었다.

/// 메인 탭 식별자(딥링크 탭 전환·FloatingTabBar selection 바인딩). 장소 추가(＋)는 지도 화면 FAB 이므로 탭 미포함.
/// .map=지도(2레벨: 그룹 목록↔그룹 지도) / .chat=DM(봇방, 라벨만 "DM") / .notification / .myInfo.
/// 어디갈까(룰렛)는 탭 제거 → 지도 좌하단 어디가지 FAB 로 이동(IA 재설계, 접근 경로 보존).
enum MainTab: Hashable, CaseIterable {
    case map
    case chat
    case notification
    case myInfo
}

struct FloatingTabBar: View {

    /// 바 레이아웃 상수 SSOT(설계 §1, FR-1/AC-1·AC-2). barHeight/bottomGap 으로 footprint(바 점유 높이)를 정의한다.
    /// 각 탭은 contentFootprint 만큼 하단 safe area 를 확보(reserveFloatingTabBarSpace)하고, 바는 overlay 로 얹는다.
    /// MapView 내위치 버튼 등 외부에서도 동일 상수를 참조한다(매직넘버 분산 금지, AC-2).
    enum Metrics {
        static let barHeight: CGFloat = 64   // 필 바 높이
        static let bottomGap: CGFloat = 12   // 바와 safe area 사이 최소 여백(AC-4) / 공통 간격 단위
        /// 콘텐츠가 바를 회피하기 위해 확보할 하단 footprint(safe area 위 기준 = barHeight + bottomGap).
        static var contentFootprint: CGFloat { barHeight + bottomGap }
    }

    @Binding private var selection: MainTab
    private let hasUnread: Bool
    /// DM 탭 미읽음 배지(DM 그룹별 전환 FR-10/AC-9) — 안 읽은 봇 방 1개 이상. 알림 점과 동일 패턴.
    private let hasChatUnread: Bool
    /// 지도 탭 재탭(이미 .map 선택 중) 콜백(IA 재설계 FR-3/AC-4) → 그룹 목록(레벨0). 미배선이면 no-op.
    private let onReselectMap: () -> Void

    init(
        selection: Binding<MainTab>,
        hasUnread: Bool,
        hasChatUnread: Bool = false,
        onReselectMap: @escaping () -> Void = {}
    ) {
        self._selection = selection
        self.hasUnread = hasUnread
        self.hasChatUnread = hasChatUnread
        self.onReselectMap = onReselectMap
    }

    var body: some View {
        HStack(spacing: 0) {
            tabButton(.map, outline: "globe.asia.australia", fill: "globe.asia.australia.fill", label: "지도")
            tabButton(.chat,
                      outline: "paperplane",
                      fill: "paperplane.fill",
                      label: "채팅",
                      showUnread: hasChatUnread)
            tabButton(.notification, outline: "bell", fill: "bell.fill", label: "알림", showUnread: hasUnread)
            tabButton(.myInfo, outline: "person", fill: "person.fill", label: "내정보")
        }
        .padding(.horizontal, 8)
        .frame(height: Metrics.barHeight)
        .modifier(FloatingBarBackground())
        .padding(.horizontal, 24)
        .padding(.bottom, Metrics.bottomGap)
    }

    // MARK: - 탭 버튼(외곽선↔채움, FR-3)

    @ViewBuilder
    private func tabButton(_ tab: MainTab,
                           outline: String,
                           fill: String,
                           label: String,
                           showUnread: Bool = false) -> some View {
        let isSelected = selection == tab
        Button {
            // 지도 탭 재탭(이미 .map 선택 중) → 그룹 목록(레벨0) 콜백(IA 재설계 FR-3/AC-4).
            //  그 외 탭은 selection 전환. selection 미변경 경로라 onChange(selection) 의존이 아닌 명시 콜백을 둔다.
            if tab == .map, selection == .map {
                onReselectMap()
            } else {
                selection = tab
            }
        } label: {
            iconView(tab, isSelected: isSelected, outline: outline, fill: fill)
                .foregroundColor(isSelected ? WGColor.cta : WGColor.inkSoft)
                // 미읽음 점(FR-22): 아이콘 '자체' 우측 하단(인스타식). 셀 프레임에 걸면 필 모서리로 떠버린다.
                .overlay(alignment: .bottomTrailing) {
                    if showUnread {
                        Circle()
                            .fill(WGColor.pinNew)
                            .frame(width: 8, height: 8)
                            .offset(x: 5, y: 2)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .accessibilityLabel(label)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }

    /// 탭 아이콘. 채팅 탭은 SF paperplane 대신 인스타그램 최신 DM 글리프(각진 종이비행기, 커스텀 Path).
    /// 선택 상태는 채움 대신 굵은 스트로크(+cta 색)로 구분 — 인스타 원본도 외곽선 유지.
    @ViewBuilder
    private func iconView(_ tab: MainTab, isSelected: Bool, outline: String, fill: String) -> some View {
        if tab == .chat {
            InstaSendShape()
                .stroke(style: StrokeStyle(
                    lineWidth: isSelected ? 2.4 : 1.8,
                    lineCap: .round,
                    lineJoin: .round
                ))
                .frame(width: 21, height: 21)
        } else {
            Image(systemName: isSelected ? fill : outline)
                .font(.system(size: 22, weight: .regular))
        }
    }
}

/// 인스타그램 최신 DM(종이비행기) 글리프 — 24×24 좌표계의 각진 send 아이콘.
/// 대각 폴드 라인(22,2→11,13) + 외곽 폴리곤(22,2→15,22→11,13→2,9) 두 서브패스를 스트로크로 그린다.
private struct InstaSendShape: Shape {
    func path(in rect: CGRect) -> Path {
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: rect.minX + x / 24 * rect.width, y: rect.minY + y / 24 * rect.height)
        }
        var path = Path()
        path.move(to: pt(22, 2))
        path.addLine(to: pt(11, 13))
        path.move(to: pt(22, 2))
        path.addLine(to: pt(15, 22))
        path.addLine(to: pt(11, 13))
        path.addLine(to: pt(2, 9))
        path.closeSubpath()
        return path
    }
}

// MARK: - 배경(버전 분기, FR-4)

/// 바 배경: iOS 26+ Liquid Glass(DoD-B 보정) / iOS 17~25 솔리드 둥근 흰색 필 폴백.
/// glass/solid 를 별도 메서드로 분리(설계 §1, FR-5/AC-5/BR-5) — iOS26 경로가 폴백과 시각적으로 달라야 한다.
private struct FloatingBarBackground: ViewModifier {
    // @ViewBuilder 명시(cross-review RISK): glass/solid 분기가 서로 다른 구체 View 타입을 반환하므로,
    //  if/#available 를 _ConditionalContent 로 묶어 some View 불투명 타입 충돌을 원천 차단한다.
    //  (ViewModifier.body 는 기본 @ViewBuilder 이나, 분기 타입 상이를 고려해 의도를 명시.)
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            glassBackground(content)   // iOS26 전용 경로(반투명)
        } else {
            solidBackground(content)   // 17~25 폴백(BR-5: 반투명화 금지)
        }
    }

    @available(iOS 26.0, *)
    private func glassBackground(_ content: Content) -> some View {
        // iOS 26 Liquid Glass 정식 API. 이전엔 `Capsule().fill(.ultraThinMaterial).shadow(...)` 였는데,
        // 반투명 머티리얼 도형에 그림자를 직접 걸면 그림자가 머티리얼 본체를 투과해 비쳐 "두 겹"으로 보였다.
        // glassEffect 는 광택·그림자가 통합된 단일 글라스 레이어라 그 아티팩트가 없다(AC-5: 폴백과 시각적 분리).
        content
            .glassEffect(.regular, in: Capsule())
    }

    private func solidBackground(_ content: Content) -> some View {
        // 폴백(17~25): 솔리드 흰 필 + 그림자(P7 의도 보존, BR-5 — 반투명화 금지).
        content
            .background(
                Capsule()
                    .fill(WGColor.panel)
                    .shadow(color: WGColor.shadowMd, radius: 12, x: 0, y: 4)
            )
    }
}
