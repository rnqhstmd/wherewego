import SwiftUI

// 그룹 목록(지도 탭 레벨0, 설계 §2·§3, GM-2 FR-2/FR-5 / IG-1 인스타 리디자인).
// 2레벨 지도 탭의 레벨0 — 내가 속한 그룹들을 플랫 행으로 나열하고, 탭하면 enterGroup(그 그룹 지도로 진입, AC-2).
//  - 상단: InstaNavBar("우리가 갈 지도") + 우측 ＋ 메뉴(새 그룹 만들기 / 초대 코드로 들어가기). 빈 상태에서도 상단바 노출.
//  - 그룹 행(IG-1 플랫화): 카드/테두리 제거 → 아바타 54 + 그룹명 + 멤버 일렬 + chevron. 여백이 행을 구분(구분선/카드 없음).
//  - 빈 상태(그룹 0개): 고운바탕 큰 제목 유지(브랜드 모먼트 허용) — 생성/합류 유도. 콜백으로 시트/네비 위임.
struct GroupListView: View {
    @ObservedObject var groupContext: GroupContext
    /// "새 그룹 만들기" 탭 — 상위(MainTabView)가 그룹 생성 진입(시트/네비)으로 위임.
    let onCreateGroup: () -> Void
    /// "초대 코드로 합류" 탭 — 상위가 초대 코드 입력 진입으로 위임.
    let onJoin: () -> Void

    var body: some View {
        // 빈 상태에서도 상단 InstaNavBar(＋ 메뉴)가 보이도록 VStack 최상단에 고정 + 아래 분기(IG-1).
        ZStack {
            WGColor.bg.ignoresSafeArea()

            VStack(spacing: 0) {
                InstaNavBar(title: "우리가 갈 지도") {
                    addMenu
                }

                if groupContext.groups.isEmpty {
                    emptyState
                } else {
                    groupList
                }
            }
        }
    }

    // MARK: - 상단 ＋ 메뉴(새 그룹 / 초대 코드)

    /// InstaNavBar 우측 ＋ — SwiftUI Menu 2항목(새 그룹 만들기 / 초대 코드로 들어가기). 하단 칩 행을 대체(IG-1).
    private var addMenu: some View {
        Menu {
            Button(action: onCreateGroup) {
                Label("새 그룹 만들기", systemImage: "plus")
            }
            Button(action: onJoin) {
                Label("초대 코드로 들어가기", systemImage: "person.badge.plus")
            }
        } label: {
            Image(systemName: "plus")
                .font(.system(size: 20, weight: .regular))
                .foregroundStyle(WGColor.ink)
                // HIG 최소 터치 영역 44pt(PR#124 리뷰) — 아이콘은 우측 정렬로 시각 위치 유지.
                .frame(width: 44, height: 44, alignment: .trailing)
                .contentShape(Rectangle())
        }
    }

    // MARK: - 그룹 목록(그룹 ≥ 1)

    private var groupList: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 10) {
                // 섹션 라벨(목업 ① "내 그룹"). 큰 제목/부제는 제거(IG-1 — 경량 상단바가 타이틀 담당).
                Text("내 그룹")
                    .font(WGFont.sansSemiBold(12))
                    .foregroundStyle(WGColor.inkSoft)
                    .padding(.horizontal, 4)
                    .padding(.top, 14)
                    .padding(.bottom, 2)

                ForEach(groupContext.groups) { group in
                    Button {
                        groupContext.enterGroup(group.groupId)
                    } label: {
                        groupRow(group)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
        }
    }

    /// 그룹 행(카드형 + 그룹별 accent 색). 흰 카드(라운드16+옅은 그림자) + 아바타 accent 링 + 그룹명 + 멤버 일렬 + chevron.
    ///  - accent: groupId 기반 안정 색(WGColor.groupAccent) → 단색 핑크 일색 탈피·시선 앵커.
    private func groupRow(_ group: GroupSummary) -> some View {
        let accent = WGColor.groupAccent(group.groupId)
        return HStack(spacing: 13) {
            GroupAvatarView(
                imageUrl: group.imageThumbUrl ?? group.imageUrl,
                members: group.members,
                size: 50
            )
            // 그룹별 accent 링(3pt 간격 두고 바깥에).
            .padding(3)
            .overlay(Circle().stroke(accent, lineWidth: 2.5))

            VStack(alignment: .leading, spacing: 5) {
                // Pretendard 고정 웨이트라 .fontWeight() 미적용 → 강조는 실제 SemiBold 페이스 사용.
                Text(group.name)
                    .font(WGFont.sansSemiBold(15))
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(1)
                memberStrip(group.members)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(WGColor.inkFaint)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 12)
        .padding(.horizontal, 14)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(WGColor.hairline, lineWidth: 1))
        .shadow(color: WGColor.shadow, radius: 6, y: 2)
        .contentShape(RoundedRectangle(cornerRadius: 16))
    }

    /// 활성 멤버 전원 프사 가입순 일렬(FR-4). -5pt 겹침 HStack. 각 셀 18pt AvatarView(프사/이니셜 폴백).
    /// 테두리 링은 플랫 행이라 panel → bg(배경색)로 겹침 경계 식별.
    /// 멤버 미로딩(구서버·빈 배열)이면 빈 자리(EmptyView) — 행 높이 리듬 유지를 위해 최소 높이 확보.
    private func memberStrip(_ members: [GroupMemberPreview]) -> some View {
        HStack(spacing: -5) {
            ForEach(members) { member in
                AvatarView(imageUrl: member.profileImageUrl, name: member.nickname, size: 18)
                    // 겹침 경계 식별을 위해 카드 배경(panel) 색 테두리 링.
                    .overlay(Circle().stroke(WGColor.panel, lineWidth: 1.5))
            }
        }
        .frame(height: 18, alignment: .leading)
    }

    // MARK: - 빈 상태(그룹 0개, GroupStartView 동치 유도)

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("어떻게 시작할까요")
                .font(WGFont.emo(28))
                .tracking(-1)
                .foregroundStyle(WGColor.ink)

            Text("혼자서도, 함께서도 괜찮아요")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
                .padding(.top, 10)

            // 새 그룹 만들기 카드(cta 테두리, GroupStartView 정합).
            Button(action: onCreateGroup) {
                optionCard(
                    dot: WGColor.pinWish,
                    title: "새 그룹 만들기",
                    description: "이름을 정하고 친구를 초대해서\n함께 핀을 찍어요"
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(WGColor.cta, lineWidth: 1.5)
                )
            }
            .buttonStyle(.plain)
            .padding(.top, 32)

            // 초대 코드로 합류 카드(hairline 테두리).
            Button(action: onJoin) {
                optionCard(
                    dot: WGColor.pinReel,
                    title: "초대 코드로 합류",
                    description: "받은 코드를 입력해서\n이미 만들어진 그룹에 들어가요"
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(WGColor.hairline, lineWidth: 1.5)
                )
            }
            .buttonStyle(.plain)
            .padding(.top, 12)

            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(EdgeInsets(top: 40, leading: 28, bottom: 32, trailing: 28))
    }

    /// 빈 상태 카드 — GroupStartView.optionCard 1:1 이식(동일 디자인 언어).
    private func optionCard(dot: Color, title: String, description: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Circle().fill(dot).frame(width: 14, height: 14)
                Text(title)
                    .font(WGFont.emo(17))
                    .foregroundStyle(WGColor.ink)
            }
            Text(description)
                .font(WGFont.sans(13))
                .foregroundStyle(WGColor.inkSoft)
                .lineSpacing(2)
                .multilineTextAlignment(.leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(EdgeInsets(top: 20, leading: 22, bottom: 20, trailing: 22))
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}
