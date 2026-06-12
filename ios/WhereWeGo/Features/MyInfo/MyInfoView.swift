import SwiftUI

// 내정보 화면(설계 §8 / FR-3 인스타 프로필화). 인스타그램 프로필 문법으로 재구성.
//  - 상단: InstaNavBar("내 정보")(다른 탭 루트와 동일 — 경량 상단바).
//  - 프로필 헤더(중앙): 아바타 84 + 우하단 카메라 배지(프사 액션시트) + 닉네임 + 통계 2종(그룹·핀).
//  - 프로필 편집 풀폭 라이트 버튼 → ProfileEditView 시트(닉네임/프사 편집).
//  - 설정 플랫 리스트(hairline 구분 행): 알림 설정 · 로그아웃 · 계정 삭제(danger).
// 프사(GP-1 FR-3)·로그아웃·계정삭제 로직은 기존 그대로 유지(배선만 새 레이아웃에).
struct MyInfoView: View {
    // VM 수명은 MainTabView 가 @StateObject 로 소유(탭 전환·body 재계산에도 단일 인스턴스 유지).
    // 본 뷰는 @ObservedObject 로 관찰만 한다(NotificationInboxView 와 동일 패턴).
    @ObservedObject var viewModel: MyInfoViewModel
    /// 그룹 수 표기용(FR-3 통계). MainTabView 가 @StateObject 로 소유한 단일 인스턴스를 주입(DMListView 선례).
    @ObservedObject var groupContext: GroupContext
    private let authAPI: AuthAPI

    /// 프로필 편집 시트 트리거(닉네임/프사). 기존 닉네임 수정 시트 대체.
    @State private var showProfileEdit = false
    @State private var showDeleteConfirm = false
    /// 프사 액션시트(앨범에서 선택 / 사진 제거) 트리거(GP-1 FR-3).
    @State private var showPhotoOptions = false
    /// 사진 피커 시트 트리거.
    @State private var showPhotoPicker = false
    /// 피커에서 고른 원본(크롭 fullScreenCover 트리거). nil = 크롭 미진행.
    @State private var pickedImage: PickedProfileImage?

    init(authAPI: AuthAPI, viewModel: MyInfoViewModel, groupContext: GroupContext) {
        self.authAPI = authAPI
        self.viewModel = viewModel
        self.groupContext = groupContext
    }

    var body: some View {
        VStack(spacing: 0) {
            // 경량 상단바(IG-1, 다른 탭 루트 정합).
            InstaNavBar(title: "내 정보")

            ScrollView {
                VStack(spacing: 0) {
                    profileHeader

                    // 프로필 편집 풀폭 라이트 버튼.
                    Button {
                        showProfileEdit = true
                    } label: {
                        Text("프로필 편집")
                            .font(WGFont.sansSemiBold(14))
                            .foregroundStyle(WGColor.ink)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(WGColor.bg)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(WGColor.hairline, lineWidth: 1)
                            )
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 20)
                    .padding(.top, 20)

                    settingsList
                        .padding(.top, 28)

                    if let message = viewModel.errorMessage {
                        Text(message)
                            .font(WGFont.sans(13))
                            .foregroundStyle(WGColor.cta)
                            .frame(maxWidth: .infinity, alignment: .center)
                            .padding(.top, 16)
                    }
                }
                .padding(.bottom, 40)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(WGColor.bg)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.load() }
        // 프로필 편집 시트(닉네임/프사). 기존 닉네임 수정 시트 대체.
        .sheet(isPresented: $showProfileEdit) {
            NavigationStack {
                ProfileEditView(authAPI: authAPI, viewModel: viewModel)
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

    // MARK: - 프로필 헤더(중앙 정렬)

    /// 아바타(우하단 카메라 배지) + 닉네임 + 통계 2종(그룹·핀).
    private var profileHeader: some View {
        VStack(spacing: 14) {
            // 아바타 + 카메라 배지(우하단). 배지/아바타 탭 = 프사 액션시트. 업로드 중 로딩 오버레이.
            Button {
                showPhotoOptions = true
            } label: {
                AvatarView(
                    imageUrl: viewModel.profileImageUrl,
                    name: viewModel.nickname ?? "",
                    size: 84
                )
                .overlay {
                    if viewModel.isUploadingPhoto {
                        Circle().fill(.black.opacity(0.35))
                            .overlay(ProgressView().tint(.white))
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    cameraBadge
                }
            }
            .buttonStyle(.plain)
            .disabled(viewModel.isUploadingPhoto)

            Text(viewModel.nickname ?? "사용자")
                .font(WGFont.emo(20))
                .foregroundStyle(WGColor.ink)

            // 통계 2종(중앙, spacing 32): 그룹 수 / 핀 수.
            // 핀 수는 구서버(pinCount 미응답) 기간에 0 으로 오인되지 않도록 nil 이면 "–" 표시.
            HStack(spacing: 32) {
                statItem(value: "\(groupContext.groups.count)", label: "그룹")
                statItem(value: viewModel.pinCount.map(String.init) ?? "–", label: "핀")
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 12)
    }

    /// 우하단 카메라 배지(26pt Circle, panel 배경 + hairline 스트로크 + 그림자).
    private var cameraBadge: some View {
        Image(systemName: "camera.fill")
            .font(.system(size: 12))
            .foregroundStyle(WGColor.ink)
            .frame(width: 26, height: 26)
            .background(WGColor.panel)
            .clipShape(Circle())
            .overlay(Circle().stroke(WGColor.hairline, lineWidth: 1))
            .shadow(color: WGColor.shadow, radius: 3, y: 1)
    }

    /// 통계 1개(숫자 + 라벨, 세로 중앙). value 는 표시 문자열 — 미응답(nil)은 호출부가 "–" 로 전달.
    private func statItem(value: String, label: String) -> some View {
        VStack(spacing: 3) {
            Text(value)
                .font(WGFont.sansBold(17))
                .foregroundStyle(WGColor.ink)
            Text(label)
                .font(WGFont.sans(12))
                .foregroundStyle(WGColor.inkSoft)
        }
    }

    // MARK: - 설정 플랫 리스트

    /// 알림 설정 · 로그아웃 · 계정 삭제(danger). hairline 구분 플랫 행.
    private var settingsList: some View {
        VStack(spacing: 0) {
            rowButton(label: "알림 설정") {
                openSystemSettings()
            }
            divider
            rowButton(label: "로그아웃", disabled: viewModel.isBusy) {
                Task { await viewModel.logout() }
            }
            divider
            rowButton(label: "계정 삭제", danger: true, disabled: viewModel.isBusy) {
                showDeleteConfirm = true
            }
        }
        .padding(.horizontal, 20)
    }

    private var divider: some View {
        Rectangle()
            .fill(WGColor.hairline)
            .frame(height: 1)
    }

    /// 설정 플랫 행(라벨 + 우측 chevron). danger 면 cta 색.
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
            .padding(.vertical, 14)
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .opacity(disabled ? 0.5 : 1)
    }

    // MARK: - 시스템 설정

    /// 시스템 설정의 본 앱 페이지로 이동(알림 권한 등). URL 생성 실패 시 무동작(안전 처리).
    private func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

/// .fullScreenCover(item:) 용 Identifiable 래퍼(UIImage 자체는 Identifiable 아님). PinDetailContent.PickedImage 동치.
private struct PickedProfileImage: Identifiable {
    let id = UUID()
    let image: UIImage
}
