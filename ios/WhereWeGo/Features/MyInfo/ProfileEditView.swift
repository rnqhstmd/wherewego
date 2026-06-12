import SwiftUI

// 프로필 편집 시트(FR-3 내정보 인스타 프로필화). 인스타그램 프로필 편집 문법 —
// 상단 중앙 아바타 + "사진 변경" 텍스트 버튼 + 닉네임 입력 필드.
//  - 사진 변경: MyInfoView 와 동일한 액션시트(앨범에서 선택 / 사진 제거) → PhotoPickerView → SquareCropView(.circle) 플로우.
//    상태(@State)·업로드 호출은 MyInfoViewModel 을 주입 재사용(uploadProfileImage/removeProfileImage/isUploadingPhoto/profileImageUrl).
//  - 닉네임: NicknameView 와 동일한 검증(Core/Validation/Nickname). 변경+유효 시 완료에서 authAPI.updateNickname 호출.
//  - 완료(trailing): 닉네임 변경됐고 유효 → updateNickname → 성공 시 viewModel.refreshNickname + dismiss. 미변경이면 dismiss.
//  - 취소(leading): 그냥 닫기. 기존 NicknameView 는 무수정(온보딩 전용).
struct ProfileEditView: View {
    // MyInfoView 가 소유한 VM 을 주입받아 프사 상태·업로드 호출을 공유한다(별도 인스턴스 금지).
    @ObservedObject var viewModel: MyInfoViewModel
    private let authAPI: AuthAPI

    @Environment(\.dismiss) private var dismiss

    /// 편집 중 닉네임(초기값 = viewModel.nickname). sanitize 로 허용 문자/길이 제한.
    @State private var nickname: String
    /// 닉네임 저장(updateNickname) 진행 중 — 완료 버튼 비활성·로딩 표기.
    @State private var isSaving = false
    /// 인라인 에러 문구(저장 실패). nil 이면 미표시.
    @State private var errorMessage: String?

    /// 프사 액션시트(앨범에서 선택 / 사진 제거) 트리거.
    @State private var showPhotoOptions = false
    /// 사진 피커 시트 트리거.
    @State private var showPhotoPicker = false
    /// 피커에서 고른 원본(크롭 fullScreenCover 트리거). nil = 크롭 미진행.
    @State private var pickedImage: PickedEditImage?

    init(authAPI: AuthAPI, viewModel: MyInfoViewModel) {
        self.authAPI = authAPI
        self.viewModel = viewModel
        _nickname = State(initialValue: viewModel.nickname ?? "")
    }

    /// 닉네임이 유효한가(NicknameView 와 동일 규칙). 미변경이어도 dismiss 는 항상 가능하므로 완료 버튼 활성 조건에 사용.
    private var isNicknameValid: Bool {
        Nickname.validate(nickname) == .valid
    }

    /// 닉네임이 초기값에서 바뀌었는가(변경 시에만 updateNickname 호출).
    private var isNicknameChanged: Bool {
        nickname != (viewModel.nickname ?? "")
    }

    /// 완료 버튼 활성: 저장 중이 아니고, (닉네임 미변경) 또는 (변경됐고 유효).
    private var canDone: Bool {
        !isSaving && (!isNicknameChanged || isNicknameValid)
    }

    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()

            VStack(spacing: 0) {
                // 상단 중앙 아바타 + "사진 변경" 텍스트 버튼.
                VStack(spacing: 12) {
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

                    Button {
                        showPhotoOptions = true
                    } label: {
                        Text("사진 변경")
                            .font(WGFont.sansSemiBold(13))
                            .foregroundStyle(WGColor.cta)
                    }
                    .buttonStyle(.plain)
                    .disabled(viewModel.isUploadingPhoto)
                }
                .padding(.top, 32)

                // 닉네임 입력(언더라인 TextField — NicknameView 정합).
                VStack(alignment: .leading, spacing: 8) {
                    TextField("", text: $nickname)
                        .font(WGFont.emo(24))
                        .foregroundStyle(WGColor.ink)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onChange(of: nickname) { _, newValue in
                            sanitizeNickname(newValue)
                        }
                    Rectangle()
                        .fill(WGColor.cta)
                        .frame(height: 2)

                    Text("한글, 영문, 숫자 2~12자")
                        .font(WGFont.sans(12))
                        .foregroundStyle(WGColor.inkSoft)
                        .padding(.top, 2)

                    if let errorMessage {
                        Text(errorMessage)
                            .font(WGFont.sans(13))
                            .foregroundStyle(WGColor.cta)
                            .padding(.top, 4)
                    }
                }
                .padding(.top, 40)
                .padding(.horizontal, 4)

                Spacer()
            }
            .padding(EdgeInsets(top: 8, leading: 32, bottom: 32, trailing: 32))
        }
        .navigationTitle("프로필 편집")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("닫기") { dismiss() }
                    .foregroundStyle(WGColor.inkSoft)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task { await save() }
                } label: {
                    Text(isSaving ? "저장 중..." : "완료")
                        .font(WGFont.sansSemiBold(15))
                        .foregroundStyle(canDone ? WGColor.cta : WGColor.inkFaint)
                }
                .disabled(!canDone)
            }
        }
        // 프사 액션시트(MyInfoView 정합): 앨범에서 선택 / (사진 있으면) 사진 제거.
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
        // 사진 피커 → 원형 크롭(MyInfoView 정합). 크롭 완료 시 업로드.
        .sheet(isPresented: $showPhotoPicker) {
            PhotoPickerView(
                onPicked: { pickedImage = PickedEditImage(image: $0) },
                onDismiss: { showPhotoPicker = false }
            )
            .ignoresSafeArea()
        }
        .fullScreenCover(item: $pickedImage) { picked in
            SquareCropView(
                image: picked.image,
                maskShape: .circle,   // 프사는 원형 가이드(BR-1, 결과물은 1:1 정사각).
                onCropped: { cropped in
                    pickedImage = nil
                    Task { await viewModel.uploadProfileImage(cropped) }
                },
                onCancel: { pickedImage = nil }
            )
        }
    }

    // MARK: - 닉네임 검증/저장

    /// 한글 IME 조합 깜빡임 방지: sanitize 결과가 다를 때만 바인딩 되돌림(NicknameViewModel 정합).
    private func sanitizeNickname(_ value: String) {
        let cleaned = Nickname.sanitize(value)
        if cleaned != value {
            nickname = cleaned
        }
        if errorMessage != nil { errorMessage = nil }
    }

    /// 완료 처리: 닉네임 변경+유효 시 PUT /users/me → 성공 시 표시 갱신 후 dismiss. 미변경이면 그냥 dismiss.
    private func save() async {
        guard !isSaving else { return }
        // 미변경: 닉네임 호출 없이 닫기(사진은 즉시 반영됐으므로 추가 작업 불필요).
        if !isNicknameChanged {
            dismiss()
            return
        }
        guard isNicknameValid else { return }
        isSaving = true
        errorMessage = nil
        defer { isSaving = false }
        do {
            _ = try await authAPI.updateNickname(nickname)
            await viewModel.refreshNickname()
            dismiss()
        } catch {
            errorMessage = "저장에 실패했어요. 잠시 후 다시 시도해 주세요"
        }
    }
}

/// .fullScreenCover(item:) 용 Identifiable 래퍼(UIImage 자체는 Identifiable 아님). MyInfoView.PickedProfileImage 동치.
private struct PickedEditImage: Identifiable {
    let id = UUID()
    let image: UIImage
}
