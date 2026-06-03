import SwiftUI

// 둥근 플로팅 필 바(설계 §1, FR-1~4, BR-1, AC-2).
//  - 시스템 탭바를 숨기고(MainTabView 에서 .toolbar(.hidden, for:.tabBar)) ZStack 하단에 커스텀 바를 띄운다.
//  - 5칸 = 4탭 버튼(어디갈까·채팅·알림·내정보) + 가운데 ＋ FAB(주황 원, 바 안에 flush).
//  - 선택 표시(FR-3): SF Symbols 외곽선↔채움 쌍. 선택=채움+WGColor.cta, 미선택=외곽선+WGColor.inkSoft. 알약 배경 없음.
//  - 미읽음(FR-22): hasUnread 시 알림(bell) 아이콘 우상단 빨간 점(WGColor.pinNew). 건수 미표시.
//  - 버전 분기(FR-4): iOS 26+ Liquid Glass(DoD-B 보정) / iOS 17~25 솔리드 둥근 필(WGColor.panel) 폴백.
//  - ＋(BR-1/AC-2): MainTab 에 미포함. onPlusTap 만 호출하고 selection 은 변경하지 않는다.

/// 메인 탭 식별자(딥링크 탭 전환·FloatingTabBar selection 바인딩). ＋ 는 액션이므로 미포함.
enum MainTab: Hashable, CaseIterable {
    case map
    case chat
    case notification
    case myInfo
}

struct FloatingTabBar: View {

    @Binding private var selection: MainTab
    private let hasUnread: Bool
    private let onPlusTap: () -> Void

    init(selection: Binding<MainTab>, hasUnread: Bool, onPlusTap: @escaping () -> Void) {
        self._selection = selection
        self.hasUnread = hasUnread
        self.onPlusTap = onPlusTap
    }

    var body: some View {
        HStack(spacing: 0) {
            tabButton(.map, outline: "map", fill: "map.fill", label: "어디갈까")
            tabButton(.chat,
                      outline: "bubble.left.and.bubble.right",
                      fill: "bubble.left.and.bubble.right.fill",
                      label: "채팅")
            plusButton
            tabButton(.notification, outline: "bell", fill: "bell.fill", label: "알림", showUnread: hasUnread)
            tabButton(.myInfo, outline: "person", fill: "person.fill", label: "내정보")
        }
        .padding(.horizontal, 8)
        .frame(height: 64)
        .modifier(FloatingBarBackground())
        .padding(.horizontal, 24)
        .padding(.bottom, 12)
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

    // MARK: - 센터 ＋ FAB(주황 원, 바 안에 flush, BR-1/AC-2)

    private var plusButton: some View {
        Button {
            // BR-1/AC-2: selection 을 변경하지 않고 추가 액션만 호출.
            onPlusTap()
        } label: {
            Image(systemName: "plus")
                .font(.system(size: 22, weight: .semibold))
                .foregroundColor(WGColor.panel)
                .frame(width: 48, height: 48)
                .background(Circle().fill(WGColor.cta))
                .frame(maxWidth: .infinity)
        }
        .accessibilityLabel("장소 추가")
    }
}

// MARK: - 배경(버전 분기, FR-4)

/// 바 배경: iOS 26+ Liquid Glass(DoD-B 보정) / iOS 17~25 솔리드 둥근 흰색 필 폴백.
private struct FloatingBarBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            // TODO(DoD-B): iOS 26 Liquid Glass modifier 적용.
            //  - SDK 미확정으로 정확한 modifier(예: glassEffect 계열)는 Mac/Xcode 26 환경에서 보정.
            //  - 그 전까지 폴백과 동일한 형태(솔리드 둥근 필 + 그림자)를 유지해 레이아웃·시각 회귀를 막는다.
            content
                .background(
                    Capsule()
                        .fill(WGColor.panel)
                        .shadow(color: WGColor.shadowMd, radius: 12, x: 0, y: 4)
                )
        } else {
            // 폴백(기본 경로): 솔리드 불투명 흰색 둥근 필 + 그림자.
            content
                .background(
                    Capsule()
                        .fill(WGColor.panel)
                        .shadow(color: WGColor.shadowMd, radius: 12, x: 0, y: 4)
                )
        }
    }
}
