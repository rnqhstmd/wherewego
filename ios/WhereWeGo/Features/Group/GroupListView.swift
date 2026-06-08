import SwiftUI

// 그룹 목록(지도 탭 레벨0, 설계 §2·§3, GM-2 FR-2/FR-5).
// 2레벨 지도 탭의 레벨0 — 내가 속한 그룹들을 카드로 나열하고, 탭하면 enterGroup(그 그룹 지도로 진입, AC-2).
//  - 그룹 카드: 이름 + 인원 수. 탭 → GroupContext.enterGroup(레벨1 전환 + 지도 재로드 트리거).
//  - 빈 상태(그룹 0개): 생성/합류 유도(GroupStartView 와 동일 멘탈모델 — 새 그룹/초대 코드). 콜백으로 시트/네비 위임.
// GroupStartView 카드 톤(optionCard) 이식 — 동일 디자인 언어 유지(WGColor/WGFont).
struct GroupListView: View {
    @ObservedObject var groupContext: GroupContext
    /// "새 그룹 만들기" 탭 — 상위(MainTabView)가 그룹 생성 진입(시트/네비)으로 위임.
    let onCreateGroup: () -> Void
    /// "초대 코드로 합류" 탭 — 상위가 초대 코드 입력 진입으로 위임.
    let onJoin: () -> Void

    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()

            if groupContext.groups.isEmpty {
                emptyState
            } else {
                groupList
            }
        }
    }

    // MARK: - 그룹 목록(그룹 ≥ 1)

    private var groupList: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("내 그룹")
                    .font(WGFont.emo(28))
                    .tracking(-1)   // 웹 letterSpacing:-1 정합(AC-7 동치)
                    .foregroundStyle(WGColor.ink)
                    .padding(.bottom, 4)

                Text("들어갈 그룹을 골라주세요")
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.inkSoft)
                    .padding(.bottom, 24)

                ForEach(groupContext.groups) { group in
                    Button {
                        groupContext.enterGroup(group.groupId)
                    } label: {
                        groupCard(group)
                    }
                    .buttonStyle(.plain)
                    .padding(.bottom, 12)
                }

                // 그룹 추가 진입(새 그룹/합류). 목록 하단에 보조 액션으로 둔다.
                addGroupRow
                    .padding(.top, 4)
            }
            .padding(EdgeInsets(top: 70, leading: 28, bottom: 32, trailing: 28))
        }
    }

    /// 그룹 카드 — 좌측 점 + 이름 + 인원 수. GroupStartView.optionCard 톤 이식.
    private func groupCard(_ group: GroupSummary) -> some View {
        HStack(spacing: 12) {
            Circle().fill(WGColor.pinWish).frame(width: 14, height: 14)
            VStack(alignment: .leading, spacing: 4) {
                Text(group.name)
                    .font(WGFont.emo(17))
                    .foregroundStyle(WGColor.ink)
                Text("멤버 \(group.memberCount)명")
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkSoft)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(WGColor.inkFaint)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(EdgeInsets(top: 20, leading: 22, bottom: 20, trailing: 22))
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(WGColor.hairline, lineWidth: 1))
    }

    /// 그룹 추가 진입 행(새 그룹 만들기 / 초대 코드로 합류). 목록 하단 보조 액션.
    private var addGroupRow: some View {
        HStack(spacing: 10) {
            Button(action: onCreateGroup) {
                addGroupChip(icon: "plus", label: "새 그룹")
            }
            .buttonStyle(.plain)

            Button(action: onJoin) {
                addGroupChip(icon: "person.badge.plus", label: "초대 코드로 합류")
            }
            .buttonStyle(.plain)
        }
    }

    private func addGroupChip(icon: String, label: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .semibold))
            Text(label)
                .font(WGFont.sans(13))
        }
        .foregroundStyle(WGColor.ctaSub)
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1.5))
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
        .padding(EdgeInsets(top: 70, leading: 28, bottom: 32, trailing: 28))
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
