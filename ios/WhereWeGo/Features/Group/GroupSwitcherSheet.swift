import SwiftUI

// 그룹 전환 시트(FR-4, FR-11, BR-1/2/6). 상단 그룹 칩 탭 → 표시.
//  - 진입 시 GroupContext.loadGroups()(listMyGroups) 호출 → 로딩 스피너(BR-6) → 목록/에러.
//  - 목록: 그룹명 + 멤버수, 현재 활성 그룹에 체크 표시(FR-11). 항목 탭 → onSelect(전환 콜백) 후 시트 닫힘.
//  - 하단: "그룹 추가/만들기"(onCreateGroup) — 그룹 1개여도/0개여도 항상 노출(BR-1/2).
//  - 그룹 0개: 빈 안내 + "그룹 만들기"만(BR-2).
//
// 스타일은 디자인 토큰(WGColor/WGFont/DragHandle)으로 다른 시트(VisitMemoSheet 등)와 통일한다.
struct GroupSwitcherSheet: View {

    @ObservedObject var context: GroupContext

    /// 그룹 선택 → 활성 전환 콜백(MainTabView 가 GroupContext.setActiveGroup + 지도/채팅 재로드 연결).
    let onSelect: (GroupSummary) -> Void
    /// "그룹 추가/만들기" 콜백(기존 그룹 생성/초대 진입 경로 연결, 비범위 — 진입만).
    let onCreateGroup: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            DragHandle()

            Text("그룹 전환")
                .font(WGFont.emo(22))
                .foregroundStyle(WGColor.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 24)
                .padding(.top, 8)

            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            createGroupButton
        }
        .background(WGColor.bg.ignoresSafeArea())
        // 진입 시 목록 로드(BR-6 — 앱 시작 시 미리 로드 X, 시트 진입 시 호출).
        .task {
            await context.loadGroups()
        }
    }

    // MARK: - 상태 분기

    @ViewBuilder
    private var content: some View {
        switch context.listState {
        case .idle, .loading:
            loadingView
        case .loaded:
            if context.groups.isEmpty {
                emptyView
            } else {
                listView
            }
        case let .error(message):
            errorView(message)
        }
    }

    // MARK: - 로딩(BR-6)

    private var loadingView: some View {
        VStack {
            Spacer()
            ProgressView()
                .tint(WGColor.cta)
            Spacer()
        }
    }

    // MARK: - 빈 상태(BR-2 — 0개)

    private var emptyView: some View {
        VStack(spacing: 8) {
            Spacer()
            Image(systemName: "person.2")
                .font(.system(size: 32))
                .foregroundStyle(WGColor.inkFaint)
            Text("아직 속한 그룹이 없어요")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
            Spacer()
        }
    }

    // MARK: - 에러 + 재시도

    private func errorView(_ message: String) -> some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 28))
                .foregroundStyle(WGColor.inkFaint)
            Text(message)
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Button {
                Task { await context.loadGroups() }
            } label: {
                Text("다시 시도")
                    .font(WGFont.sans(14))
                    .padding(.horizontal, 22)
                    .padding(.vertical, 12)
                    .background(WGColor.cta)
                    .foregroundStyle(WGColor.panel)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            Spacer()
        }
    }

    // MARK: - 목록(FR-4, FR-11)

    private var listView: some View {
        ScrollView {
            LazyVStack(spacing: 10) {
                ForEach(context.groups) { group in
                    Button {
                        onSelect(group)
                    } label: {
                        groupRow(group)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, 8)
        }
    }

    /// 그룹 1건 행: 그룹명 + 멤버수, 활성 그룹은 우측 체크(FR-11).
    private func groupRow(_ group: GroupSummary) -> some View {
        let isActive = context.isActive(group)
        return HStack(spacing: 12) {
            Circle()
                .fill(WGColor.bg)
                .frame(width: 40, height: 40)
                .overlay(
                    Image(systemName: "person.2.fill")
                        .font(.system(size: 16))
                        .foregroundStyle(WGColor.cta)
                )
            VStack(alignment: .leading, spacing: 3) {
                Text(group.name)
                    .font(WGFont.emo(17))
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(1)
                HStack(spacing: 4) {
                    Image(systemName: "person.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(WGColor.inkSoft)
                    Text("\(group.memberCount)명 참여 중")
                        .font(WGFont.sans(12))
                        .foregroundStyle(WGColor.inkSoft)
                }
            }
            Spacer(minLength: 8)
            if isActive {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(WGColor.cta)
            }
        }
        .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(isActive ? WGColor.cta.opacity(0.5) : WGColor.hairline, lineWidth: 1)
        )
        .shadow(color: WGColor.shadow, radius: 4, y: 2)
        .contentShape(Rectangle())
    }

    // MARK: - 하단 "그룹 추가/만들기"(BR-1/2)

    private var createGroupButton: some View {
        Button(action: onCreateGroup) {
            HStack(spacing: 8) {
                Image(systemName: "plus.circle.fill")
                    .font(.system(size: 18, weight: .semibold))
                Text(context.groups.isEmpty ? "그룹 만들기" : "그룹 추가/만들기")
                    .font(WGFont.sans(15))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(WGColor.cta)
            .foregroundStyle(WGColor.panel)
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 12)
    }
}
