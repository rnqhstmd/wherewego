import SwiftUI

// 상단바(FR-2, FR-10). 지도·채팅 화면 상단에 오버레이된다.
//  - 좌측: 그룹 전환 칩(활성 그룹명, 0개면 "그룹 없음"). 탭 → GroupSwitcherSheet(onTapGroupChip).
//  - 우측: 🔔 알림(미읽음 빨간 점, unreadCount>0) + 👤 내정보. 각각 시트 오픈(onTapNotification/onTapMyInfo).
//
// 스타일은 디자인 토큰(liquidGlassCapsule/liquidGlassRound + WGColor/WGFont)으로 기존 플로팅 컨트롤과 통일한다.
// 지도 상단 우측 룰렛(🎲)·우하단 +/내위치 등 기존 chrome 과 충돌하지 않도록 상단 safe area 안에만 배치한다
// (TopBar 는 좌상단 칩 + 우상단 알림/내정보. 룰렛은 그 아래 우측에 별도 배치돼 세로로 겹치지 않는다).
struct TopBar: View {

    /// 활성 그룹명(GroupContext.activeGroupName). nil 이면 "그룹 없음"(BR-2).
    let groupName: String?
    /// 알림 미읽음 여부(NotificationInboxViewModel.unreadCount>0, BR-3 전역 합산).
    let hasUnread: Bool

    let onTapGroupChip: () -> Void
    let onTapNotification: () -> Void
    let onTapMyInfo: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            groupChip
            Spacer(minLength: 8)
            notificationButton
            myInfoButton
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    // MARK: - 좌측 그룹 전환 칩(FR-3, BR-2)

    /// 활성 그룹명 + 아래 화살표. 0개면 "그룹 없음". 글래스 캡슐 스타일.
    private var groupChip: some View {
        Button(action: onTapGroupChip) {
            HStack(spacing: 6) {
                Image(systemName: "person.2.fill")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(WGColor.cta)
                Text(groupName ?? "그룹 없음")
                    .font(WGFont.sans(14))
                    .fontWeight(.semibold)
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(1)
                Image(systemName: "chevron.down")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(WGColor.inkSoft)
            }
            .padding(.horizontal, 14)
            .frame(height: 40)
            .liquidGlassCapsule()
        }
        .accessibilityLabel("그룹 전환")
        // 긴 그룹명이 우측 버튼을 밀어내지 않도록 칩 최대 폭을 제한한다(우상단 알림/내정보 보존).
        .frame(maxWidth: 200, alignment: .leading)
    }

    // MARK: - 우측 알림 버튼(FR-8, BR-3)

    /// 🔔 알림. 미읽음 시 우상단 빨간 점(건수 미표시 — FloatingTabBar 와 동일 톤).
    private var notificationButton: some View {
        Button(action: onTapNotification) {
            Image(systemName: "bell.fill")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(WGColor.ink)
                .frame(width: 40, height: 40)
                .liquidGlassRound()
                .overlay(alignment: .topTrailing) {
                    if hasUnread {
                        Circle()
                            .fill(WGColor.pinNew)
                            .frame(width: 8, height: 8)
                            .offset(x: 1, y: -1)
                    }
                }
        }
        .accessibilityLabel("알림")
    }

    // MARK: - 우측 내정보 버튼(FR-9)

    /// 👤 내정보. 그룹 전환과 무관(전역).
    private var myInfoButton: some View {
        Button(action: onTapMyInfo) {
            Image(systemName: "person.fill")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(WGColor.ink)
                .frame(width: 40, height: 40)
                .liquidGlassRound()
        }
        .accessibilityLabel("내정보")
    }
}
