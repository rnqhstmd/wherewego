import SwiftUI

// 그룹 관리 시트 호스트(D단계). 시트가 열릴 때 GroupManageViewModel 을 @StateObject 로 1회 생성·소유한다.
//  GroupManageView 는 @ObservedObject 로 관찰만 하므로, 시트 표시 중 body 재계산에도 VM 인스턴스를 보존하려면
//  호출측(MapView)이 매번 VM 을 새로 만들지 않고 여기서 소유해야 한다(MainTabView 의 다른 VM 소유 패턴 동치).
struct GroupManageHost: View {
    @StateObject private var viewModel: GroupManageViewModel

    init(
        groupAPI: GroupAPIProtocol,
        currentUser: CurrentUser,
        groupId: Int,
        groupName: String,
        onRenamed: @escaping () -> Void,
        onExit: @escaping () -> Void
    ) {
        _viewModel = StateObject(
            wrappedValue: GroupManageViewModel(
                groupAPI: groupAPI,
                currentUser: currentUser,
                groupId: groupId,
                groupName: groupName,
                onRenamed: onRenamed,
                onExit: onExit
            )
        )
    }

    var body: some View {
        GroupManageView(viewModel: viewModel)
    }
}

// 그룹 관리 화면(D단계, IA 재설계 §3.4). MapView 상단 ⋯ → 시트로 표시.
// 섹션: 1) 그룹 이름(인라인 TextField + 저장, 모든 멤버) 2) 그룹원(닉네임 + 방장 뱃지)
//       3) 위험(그룹 탈퇴 — 모든 멤버 / 그룹 삭제 — 방장만).
// MyInfoView 의 section/카드/rowButton 스타일을 재사용해 톤을 통일한다(WGColor/WGFont 토큰 엄수).
struct GroupManageView: View {
    @ObservedObject var viewModel: GroupManageViewModel

    @State private var showLeaveConfirm = false
    @State private var showDeleteConfirm = false
    /// 그룹명 TextField 포커스(저장 후 키보드 내림).
    @FocusState private var nameFieldFocused: Bool

    init(viewModel: GroupManageViewModel) {
        self.viewModel = viewModel
    }

    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    nameSection
                    membersSection
                    dangerSection

                    if let message = viewModel.errorMessage {
                        Text(message)
                            .font(WGFont.sans(13))
                            .foregroundStyle(WGColor.cta)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                }
                .padding(EdgeInsets(top: 24, leading: 24, bottom: 40, trailing: 24))
            }
        }
        .navigationTitle("그룹 관리")
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.load() }
        .confirmationDialog(
            "이 그룹에서 나가시겠어요?",
            isPresented: $showLeaveConfirm,
            titleVisibility: .visible
        ) {
            Button("그룹 탈퇴", role: .destructive) {
                Task { await viewModel.leave() }
            }
            Button("취소", role: .cancel) {}
        } message: {
            Text("이 그룹의 핀에 더 이상 접근할 수 없어요.")
        }
        .confirmationDialog(
            "정말 그룹을 삭제하시겠어요?",
            isPresented: $showDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button("그룹 삭제", role: .destructive) {
                Task { await viewModel.delete() }
            }
            Button("취소", role: .cancel) {}
        } message: {
            Text("그룹의 모든 핀과 정보가 사라지고 되돌릴 수 없어요.")
        }
    }

    // MARK: - 1) 그룹 이름(모든 멤버 수정 가능)

    private var nameSection: some View {
        section(label: "그룹 이름") {
            VStack(alignment: .leading, spacing: 14) {
                TextField("그룹 이름", text: $viewModel.groupNameDraft)
                    .font(WGFont.emo(18))
                    .foregroundStyle(WGColor.ink)
                    .focused($nameFieldFocused)
                    .submitLabel(.done)
                    .onSubmit { saveName() }

                rowButton(label: "저장", disabled: viewModel.isBusy) {
                    saveName()
                }
            }
        }
    }

    private func saveName() {
        nameFieldFocused = false
        let name = viewModel.groupNameDraft
        Task { await viewModel.rename(name) }
    }

    // MARK: - 2) 그룹원

    private var membersSection: some View {
        section(label: "그룹원") {
            VStack(alignment: .leading, spacing: 0) {
                switch viewModel.loadState {
                case .idle, .loading:
                    HStack {
                        Spacer()
                        ProgressView().tint(WGColor.cta)
                        Spacer()
                    }
                    .padding(.vertical, 8)
                case .error(let message):
                    VStack(alignment: .leading, spacing: 10) {
                        Text(message)
                            .font(WGFont.sans(13))
                            .foregroundStyle(WGColor.inkSoft)
                        rowButton(label: "다시 시도") {
                            Task { await viewModel.load() }
                        }
                    }
                case .loaded:
                    ForEach(Array(viewModel.members.enumerated()), id: \.element.id) { index, member in
                        if index > 0 {
                            Rectangle()
                                .fill(WGColor.hairline)
                                .frame(height: 1)
                                .padding(.vertical, 2)
                        }
                        memberRow(member)
                    }
                }
            }
        }
    }

    private func memberRow(_ member: GroupMemberItem) -> some View {
        HStack(spacing: 10) {
            Circle()
                .fill(WGColor.bg)
                .frame(width: 36, height: 36)
                .overlay(
                    Image(systemName: "person.fill")
                        .font(.system(size: 16))
                        .foregroundStyle(WGColor.inkSoft)
                )
            Text(member.nickname)
                .font(WGFont.sans(15))
                .foregroundStyle(WGColor.ink)
            Spacer(minLength: 0)
            if member.isOwner {
                Text("방장")
                    .font(WGFont.sans(11))
                    .foregroundStyle(WGColor.cta)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4)
                    .background(WGColor.cta.opacity(0.1))
                    .clipShape(Capsule())
            }
        }
        .padding(.vertical, 10)
    }

    // MARK: - 3) 위험(탈퇴 — 모든 멤버 / 삭제 — 방장만)

    private var dangerSection: some View {
        section(label: "위험") {
            VStack(alignment: .leading, spacing: 0) {
                rowButton(label: "그룹 탈퇴", danger: true, disabled: viewModel.isBusy) {
                    showLeaveConfirm = true
                }
                if viewModel.isOwner {
                    Rectangle()
                        .fill(WGColor.hairline)
                        .frame(height: 1)
                        .padding(.vertical, 2)
                    rowButton(label: "그룹 삭제", danger: true, disabled: viewModel.isBusy) {
                        showDeleteConfirm = true
                    }
                }
            }
        }
    }

    // MARK: - 공통 컴포넌트(MyInfoView 이식 — 톤 통일)

    private func section<Content: View>(
        label: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(WGFont.sans(12))
                .foregroundStyle(WGColor.inkSoft)
                .padding(.leading, 4)
            content()
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(EdgeInsets(top: 18, leading: 22, bottom: 18, trailing: 22))
                .background(WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(WGColor.hairline, lineWidth: 1)
                )
                .shadow(color: WGColor.shadow, radius: 4, y: 2)
        }
    }

    /// 섹션 카드 내부 단일 행(라벨 + 우측 chevron). MyInfoView.rowButton 이식.
    private func rowButton(
        label: String,
        danger: Bool = false,
        disabled: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack {
                Text(label)
                    .font(WGFont.sans(14))
                    .foregroundStyle(danger ? WGColor.cta : WGColor.ink)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(WGColor.inkFaint)
            }
            .contentShape(Rectangle())
            .padding(.vertical, 12)
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .opacity(disabled ? 0.5 : 1)
    }
}
