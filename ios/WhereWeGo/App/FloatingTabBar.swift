import SwiftUI

// 둥근 플로팅 필 바(설계 §1, FR-1~4).
//  - 시스템 탭바를 숨기고(MainTabView 에서 .toolbar(.hidden, for:.tabBar)) .overlay(alignment:.bottom) 으로 얹는다.
//    각 탭은 reserveFloatingTabBarSpace() 로 footprint 를 확보한다(TabView 는 safe area 를 자식 탭으로 전파하지 않음 — PR리뷰).
//  - 5칸 = 5탭 버튼(지도·어디갈까·채팅·알림·내정보). 순수 네비게이션 바(maxWidth:.infinity 로 균등 분배).
//    지도=전체 핀 보기, 어디갈까=위치기반 룰렛 추천(구 지도 우상단 🎲 시트에서 탭으로 승격).
//  - 선택 표시(FR-3): SF Symbols 외곽선↔채움 쌍. 선택=채움+WGColor.cta, 미선택=외곽선+WGColor.inkSoft. 알약 배경 없음.
//  - 미읽음(FR-22): hasUnread 시 알림(bell) 아이콘 우상단 빨간 점(WGColor.pinNew). 건수 미표시.
//  - 버전 분기(FR-4): iOS 26+ Liquid Glass(DoD-B 보정) / iOS 17~25 솔리드 둥근 필(WGColor.panel) 폴백.
//  - ＋ 장소 추가는 이 바에서 제거하고 지도 화면 우하단 speed-dial(MapView.addPinSpeedDial)로 이동했다.
//    근거: "탭=화면 이동 / FAB=지도 컨텍스트 행동" 멘탈모델 분리. 기존 센터 ＋는 selection 불변이라
//    채팅/알림/내정보 탭에서 누르면 (안 보이는 지도에만 작용해) 무반응이 되는 비대칭이 있었다.

/// 메인 탭 식별자(딥링크 탭 전환·FloatingTabBar selection 바인딩). 장소 추가(＋)는 지도 화면 FAB 이므로 탭 미포함.
/// .map=지도(전체 핀 보기·관리) / .discover=어디갈까(위치기반 룰렛 추천) — 둘은 레벨이 다른 별개 기능이라 탭 분리.
enum MainTab: Hashable, CaseIterable {
    case map
    case discover
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

    init(selection: Binding<MainTab>, hasUnread: Bool) {
        self._selection = selection
        self.hasUnread = hasUnread
    }

    var body: some View {
        HStack(spacing: 0) {
            tabButton(.map, outline: "map", fill: "map.fill", label: "지도")
            tabButton(.discover, outline: "dice", fill: "dice.fill", label: "어디갈까")
            tabButton(.chat,
                      outline: "bubble.left.and.bubble.right",
                      fill: "bubble.left.and.bubble.right.fill",
                      label: "채팅")
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
            selection = tab
        } label: {
            Image(systemName: isSelected ? fill : outline)
                .font(.system(size: 22, weight: .regular))
                .foregroundColor(isSelected ? WGColor.cta : WGColor.inkSoft)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                // 미읽음 점(FR-22): 아이콘 우상단 작은 빨간 점. 건수 미표시.
                .overlay(alignment: .topTrailing) {
                    if showUnread {
                        Circle()
                            .fill(WGColor.pinNew)
                            .frame(width: 8, height: 8)
                            .offset(x: 10, y: -6)
                    }
                }
        }
        .accessibilityLabel(label)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
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
        // TODO(DoD-B): Xcode 26 SDK에서 .glassEffect 계열 정확 파라미터로 교체. iOS 26.5 시뮬 '불투명 흰 캡슐'은 이 분기가 폴백과 동일했던 탓 → 반투명 분리.
        content
            .background(
                Capsule()
                    .fill(.ultraThinMaterial)   // AC-5: 폴백(solid panel)과 다른 반투명 머티리얼
                    .shadow(color: WGColor.shadowMd, radius: 12, x: 0, y: 4)
            )
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
