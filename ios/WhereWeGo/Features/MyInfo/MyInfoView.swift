import SwiftUI

// 내정보 화면(설계 §8 / IA 재설계 D단계 내정보 축소, FR-23~27, BR-5, AC-11).
// frontend/src/app/settings/SettingsClient.tsx 이식 — 단 "챗봇 연동" 섹션은 제외(FR-27, AC-11).
// IA 재설계: 그룹(활성그룹·탈퇴)은 지도 탭 ⋯ 그룹관리(GroupManageView)로 이전 → 내정보는 사용자+계정 2섹션.
// 섹션: 1) 사용자(닉네임 + 닉네임 수정) 2) 계정(로그아웃 + 계정 삭제).
struct MyInfoView: View {
    // VM 수명은 MainTabView 가 @StateObject 로 소유(탭 전환·body 재계산에도 단일 인스턴스 유지).
    // 본 뷰는 @ObservedObject 로 관찰만 한다(NotificationInboxView 와 동일 패턴).
    @ObservedObject var viewModel: MyInfoViewModel
    private let authAPI: AuthAPI

    @State private var showNicknameEdit = false
    @State private var showDeleteConfirm = false
    /// 프사 액션시트(앨범에서 선택 / 사진 제거) 트리거(GP-1 FR-3).
    @State private var showPhotoOptions = false
    /// 사진 피커 시트 트리거.
    @State private var showPhotoPicker = false
    /// 피커에서 고른 원본(크롭 fullScreenCover 트리거). nil = 크롭 미진행.
    @State private var pickedImage: PickedProfileImage?

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
        // 프사 액션시트(GP-1 FR-3): 앨범에서 선택 / (사진 있으면) 사진 제거.
        .confirmationDialog(
            "프로필 사진",
            isPresented: $showPhotoOptions,
            titleVisibility: .visible
        ) {
            Button("앨범에서 선택") { showPhotoPicker = true }
            if viewModel.profileImageUrl != nil {
                Button("사진 제거", role: .destructive) {
                    Task { await viewModel.removeProfileImage() }
                }
            }
            Button("취소", role: .cancel) {}
        }
        // 사진 피커 → 원형 크롭(PinDetailContent 선례). 크롭 완료 시 업로드.
        .sheet(isPresented: $showPhotoPicker) {
            PhotoPickerView(
                onPicked: { pickedImage = PickedProfileImage(image: $0) },
                onDismiss: { showPhotoPicker = false }
            )
            .ignoresSafeArea()
        }
        .fullScreenCover(item: $pickedImage) { picked in
            SquareCropView(
                image: picked.image,
                maskShape: .circle,   // 프사·그룹 이미지는 원형 가이드(BR-1, 결과물은 1:1 정사각).
                onCropped: { cropped in
                    pickedImage = nil
                    Task { await viewModel.uploadProfileImage(cropped) }
                },
                onCancel: { pickedImage = nil }
            )
        }
    }

    // MARK: - 1) 사용자

    private var userSection: some View {
        section(label: "사용자") {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 12) {
                    // 탭 → 액션시트(앨범에서 선택 / 사진 제거). 업로드 중엔 위에 로딩 오버레이(GP-1 FR-3).
                    Button {
                        showPhotoOptions = true
                    } label: {
                        AvatarView(
                            imageUrl: viewModel.profileImageUrl,
                            name: viewModel.nickname ?? "",
                            size: 44
                        )
                        .overlay {
                            if viewModel.isUploadingPhoto {
                                Circle().fill(.black.opacity(0.35))
                                    .overlay(ProgressView().tint(.white))
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(viewModel.isUploadingPhoto)

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

    // MARK: - 2) 계정

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

/// .fullScreenCover(item:) 용 Identifiable 래퍼(UIImage 자체는 Identifiable 아님). PinDetailContent.PickedImage 동치.
private struct PickedProfileImage: Identifiable {
    let id = UUID()
    let image: UIImage
}
