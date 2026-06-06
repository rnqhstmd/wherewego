import SwiftUI

// 둥근 플로팅 필 바(설계 §1, FR-1).
//  - 시스템 탭바를 숨기고(MainTabView 에서 .toolbar(.hidden, for:.tabBar)) .overlay(alignment:.bottom) 으로 얹는다.
//    각 탭은 reserveFloatingTabBarSpace() 로 footprint 를 확보한다(TabView 는 safe area 를 자식 탭으로 전파하지 않음 — PR리뷰).
//  - 3칸 = 3탭 버튼(지도·채팅·어디갈까, 그룹 종속). 순수 네비게이션 바(maxWidth:.infinity 로 균등 분배).
//    알림·내정보는 하단 탭에서 제거되고 상단 TopBar(🔔·👤)로 이전됐다(내비 셸 재구성, FR-1/2).
//    미읽음 배지도 TopBar 의 🔔 로 이동했다(hasUnread 파라미터 제거).
//    지도=전체 핀 보기·관리. 어디갈까(룰렛)는 지도 위 시트가 아니라 하단 3번째 탭으로 편입됐다(룰렛 탭화).
//  - 선택 표시(FR-3): SF Symbols 외곽선↔채움 쌍. 선택=채움+WGColor.cta, 미선택=외곽선+WGColor.inkSoft. 알약 배경 없음.
//  - 배경(클러스터 A): iOS 17+ 전부 글래스 캡슐(.regularMaterial + hairline 보더 + 그림자)로 통일.
//    기존 iOS26+/17~25 버전 분기는 제거 — 전 버전에서 동일한 글래스 플로팅 룩(glassCapsule).
//  - ＋ 장소 추가는 이 바에서 제거하고 지도 화면 우하단 speed-dial(MapView.addPinSpeedDial)로 이동했다.
//    근거: "탭=화면 이동 / FAB=지도 컨텍스트 행동" 멘탈모델 분리.

/// 메인 탭 식별자(딥링크 탭 전환·FloatingTabBar selection 바인딩). 장소 추가(＋)는 지도 화면 FAB 이므로 탭 미포함.
/// 내비 셸 재구성(FR-1): 하단 탭은 그룹 종속 3개(.map/.chat/.roulette). 알림·내정보는 상단 TopBar 시트로 이전돼 탭 미포함.
/// .map=지도(전체 핀 보기·관리). .roulette=어디갈까(룰렛) — 지도 위 시트에서 하단 3번째 탭으로 편입됐다(룰렛 탭화).
enum MainTab: Hashable, CaseIterable {
    case map
    case chat
    case roulette
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

    init(selection: Binding<MainTab>) {
        self._selection = selection
    }

    var body: some View {
        HStack(spacing: 0) {
            tabButton(.map, outline: "map", fill: "map.fill", label: "지도")
            tabButton(.chat,
                      outline: "bubble.left.and.bubble.right",
                      fill: "bubble.left.and.bubble.right.fill",
                      label: "채팅")
            tabButton(.roulette, outline: "dice", fill: "dice.fill", label: "어디갈까")
        }
        .padding(.horizontal, 8)
        .frame(height: Metrics.barHeight)
        .liquidGlassCapsule()   // iOS26 진짜 Liquid Glass(.glassEffect), 미만은 .regularMaterial 캡슐 fallback
        // 3탭(지도·채팅·어디갈까)이라 2탭(48)보다 좌우 여백을 줄여 3버튼이 적정 폭으로 균등 분배되게 한다.
        .padding(.horizontal, 20)
        .padding(.bottom, Metrics.bottomGap)
    }

    // MARK: - 탭 버튼(외곽선↔채움, FR-3)

    @ViewBuilder
    private func tabButton(_ tab: MainTab,
                           outline: String,
                           fill: String,
                           label: String) -> some View {
        let isSelected = selection == tab
        Button {
            selection = tab
        } label: {
            Image(systemName: isSelected ? fill : outline)
                // 선택 강조 보강: 색 + 굵기 + 약한 scale 로 명확히(iOS 네이티브 탭바 느낌).
                .font(.system(size: 22, weight: isSelected ? .semibold : .regular))
                .foregroundColor(isSelected ? WGColor.cta : WGColor.inkSoft)
                .scaleEffect(isSelected ? 1.08 : 1.0)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                // 선택 탭 pill 하이라이트: 아이콘 뒤 은은한 cta 틴트 캡슐(선택 강조의 핵심).
                .background {
                    if isSelected {
                        Capsule()
                            .fill(WGColor.cta.opacity(0.12))
                            .frame(width: 56, height: 36)
                    }
                }
        }
        .animation(.easeOut(duration: 0.15), value: isSelected)
        .accessibilityLabel(label)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }
}
