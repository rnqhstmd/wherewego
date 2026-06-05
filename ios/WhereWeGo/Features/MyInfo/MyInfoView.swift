import SwiftUI

// 내정보 화면(설계 §8, FR-23~27, BR-5, AC-10/AC-11).
// frontend/src/app/settings/SettingsClient.tsx 이식 — 단 "챗봇 연동" 섹션은 제외(FR-27, AC-11).
// 섹션: 1) 사용자(닉네임 + 닉네임 수정) 2) 활성 그룹(보유 시) 3) 계정(로그아웃 + 계정 삭제).
struct MyInfoView: View {
    // VM 수명은 MainTabView 가 @StateObject 로 소유(탭 전환·body 재계산에도 단일 인스턴스 유지).
    // 본 뷰는 @ObservedObject 로 관찰만 한다(NotificationInboxView 와 동일 패턴).
    @ObservedObject var viewModel: MyInfoViewModel
    private let authAPI: AuthAPI

    @State private var showNicknameEdit = false
    @State private var showLeaveConfirm = false
    @State private var showDeleteConfirm = false

    init(authAPI: AuthAPI, viewModel: MyInfoViewModel) {
        self.authAPI = authAPI
        self.viewModel = viewModel
    }

    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("마이페이지")
                        .font(WGFont.emo(32))
                        .tracking(-1)
                        .foregroundStyle(WGColor.ink)

                    Text("계정과 그룹을 관리할 수 있어요")
                        .font(WGFont.sans(14))
                        .foregroundStyle(WGColor.inkSoft)
                        .padding(.top, 12)

                    VStack(alignment: .leading, spacing: 18) {
                        userSection
                        if viewModel.shouldShowGroupSection {
                            groupSection
                        }
                        accountSection

                        if let message = viewModel.errorMessage {
                            Text(message)
                                .font(WGFont.sans(13))
                                .foregroundStyle(WGColor.cta)
                                .frame(maxWidth: .infinity, alignment: .center)
                        }
                    }
                    .padding(.top, 32)
                }
                .padding(EdgeInsets(top: 80, leading: 32, bottom: 40, trailing: 32))
            }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.load() }
        .sheet(isPresented: $showNicknameEdit) {
            NavigationStack {
                NicknameView(authAPI: authAPI) {
                    showNicknameEdit = false
                    Task { await viewModel.refreshNickname() }
                }
            }
            // 글래스 통일: NicknameView 는 Spacer 기반 풀스크린 레이아웃 + 자체 WGColor.bg 불투명 배경을
            //  가진다(OnboardingRouter 풀스크린 재사용). 그 배경을 제거하면 온보딩이 깨지므로 보존하고,
            //  시트에서는 large detent + 드래그 인디케이터 + 코너로 "OS 기본 전체화면이 아닌 시트"로 표현한다.
            //  (콘텐츠가 불투명 bg 를 채우므로 .presentationBackground 머티리얼은 가려져 생략.)
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(24)
        }
        .confirmationDialog(
            leaveDialogTitle,
            isPresented: $showLeaveConfirm,
            titleVisibility: .visible
        ) {
            Button("그룹 탈퇴", role: .destructive) {
                Task { await viewModel.leaveGroup() }
            }
            Button("취소", role: .cancel) {}
        } message: {
            Text("이 그룹의 핀에 더 이상 접근할 수 없어요.")
        }
        .confirmationDialog(
            "정말 계정을 삭제하시겠어요?",
            isPresented: $showDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button("계정 삭제", role: .destructive) {
                Task { await viewModel.deleteAccount() }
            }
            Button("취소", role: .cancel) {}
        } message: {
            Text("내 핀과 그룹 정보가 모두 사라지고 되돌릴 수 없어요.")
        }
    }

    private var leaveDialogTitle: String {
        if let name = viewModel.activeGroup?.name {
            return "'\(name)' 그룹에서 나가시겠어요?"
        }
        return "그룹에서 나가시겠어요?"
    }

    // MARK: - 1) 사용자

    private var userSection: some View {
        section(label: "사용자") {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 12) {
                    Circle()
                        .fill(WGColor.bg)
                        .frame(width: 44, height: 44)
                        .overlay(
                            Image(systemName: "person.fill")
                                .font(.system(size: 20))
                                .foregroundStyle(WGColor.inkSoft)
                        )
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(viewModel.nickname ?? "사용자")
                            .font(WGFont.emo(18))
                            .foregroundStyle(WGColor.ink)
                        Text("님")
                            .font(WGFont.sans(12))
                            .foregroundStyle(WGColor.inkSoft)
                    }
                }
                rowButton(label: "닉네임 수정") {
                    showNicknameEdit = true
                }
                .padding(.top, 14)
            }
        }
    }

    // MARK: - 2) 활성 그룹 (shouldShowGroupSection 일 때만)

    private var groupSection: some View {
        section(label: "활성 그룹") {
            VStack(alignment: .leading, spacing: 0) {
                Text(viewModel.activeGroup?.name ?? "")
                    .font(WGFont.emo(18))
                    .foregroundStyle(WGColor.ink)
                HStack(spacing: 5) {
                    Image(systemName: "person.2.fill")
                        .font(.system(size: 11))
                        .foregroundStyle(WGColor.inkSoft)
                    Text("\(viewModel.activeGroup?.memberCount ?? 0)명 참여 중")
                        .font(WGFont.sans(12))
                        .foregroundStyle(WGColor.inkSoft)
                }
                .padding(.top, 4)

                rowButton(label: "그룹 탈퇴", danger: true, disabled: viewModel.isBusy) {
                    showLeaveConfirm = true
                }
                .padding(.top, 14)
            }
        }
    }

    // MARK: - 3) 계정

    private var accountSection: some View {
        section(label: "계정") {
            VStack(alignment: .leading, spacing: 0) {
                rowButton(label: "로그아웃", disabled: viewModel.isBusy) {
                    Task { await viewModel.logout() }
                }
                Rectangle()
                    .fill(WGColor.hairline)
                    .frame(height: 1)
                    .padding(.vertical, 2)
                rowButton(label: "계정 삭제", danger: true, disabled: viewModel.isBusy) {
                    showDeleteConfirm = true
                }
            }
        }
    }

    // MARK: - 공통 컴포넌트

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

    /// 섹션 카드 내부 단일 행(라벨 + 우측 chevron). 웹 Row 이식.
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
