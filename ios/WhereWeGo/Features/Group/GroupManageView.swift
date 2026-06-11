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
        imageUrl: String? = nil,
        onRenamed: @escaping () -> Void,
        onExit: @escaping () -> Void,
        onImageChanged: @escaping () -> Void = {}
    ) {
        _viewModel = StateObject(
            wrappedValue: GroupManageViewModel(
                groupAPI: groupAPI,
                currentUser: currentUser,
                groupId: groupId,
                groupName: groupName,
                imageUrl: imageUrl,
                onRenamed: onRenamed,
                onExit: onExit,
                onImageChanged: onImageChanged
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
    /// 그룹 이미지 액션시트(변경 / 제거) 트리거(GP-1 FR-2).
    @State private var showImageOptions = false
    /// 사진 피커 시트 트리거.
    @State private var showPhotoPicker = false
    /// 피커에서 고른 원본(크롭 fullScreenCover 트리거).
    @State private var pickedImage: PickedManageImage?
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
                    imageSection
                    nameSection
                    membersSection
                    inviteSection
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
        // 그룹 이미지 액션시트(GP-1 FR-2): 변경 / (이미지 있으면) 제거.
        .confirmationDialog(
            "그룹 대표 이미지",
            isPresented: $showImageOptions,
            titleVisibility: .visible
        ) {
            Button("이미지 변경") { showPhotoPicker = true }
            if viewModel.imageUrl != nil {
                Button("이미지 제거", role: .destructive) {
                    Task { await viewModel.removeImage() }
                }
            }
            Button("취소", role: .cancel) {}
        }
        // 사진 피커 → 원형 크롭(PinDetailContent 선례). 크롭 완료 시 업로드.
        .sheet(isPresented: $showPhotoPicker) {
            PhotoPickerView(
                onPicked: { pickedImage = PickedManageImage(image: $0) },
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
                    Task { await viewModel.uploadImage(cropped) }
                },
                onCancel: { pickedImage = nil }
            )
        }
    }

    // MARK: - 0) 그룹 대표 이미지(GP-1 FR-2, 활성 멤버 누구나 — isOwner 게이트 없음)

    private var imageSection: some View {
        section(label: "대표 이미지") {
            VStack(spacing: 14) {
                // GroupAvatarView 80pt(대표 이미지/멤버 콜라주). 업로드 중엔 로딩 오버레이.
                GroupAvatarView(
                    imageUrl: viewModel.imageUrl,
                    members: viewModel.memberPreviews,
                    size: 80
                )
                .overlay {
                    if viewModel.isUploadingImage {
                        Circle().fill(.black.opacity(0.35))
                            .overlay(ProgressView().tint(.white))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .center)

                HStack(spacing: 10) {
                    // 변경 — 멤버 누구나(권한은 백엔드). 제거 — 이미지 있을 때만 노출.
                    imageActionChip(label: "변경", icon: "camera.fill") {
                        showPhotoPicker = true
                    }
                    if viewModel.imageUrl != nil {
                        imageActionChip(label: "제거", icon: "trash", danger: true) {
                            Task { await viewModel.removeImage() }
                        }
                    }
                }
                .disabled(viewModel.isUploadingImage)
                .opacity(viewModel.isUploadingImage ? 0.5 : 1)
            }
            .frame(maxWidth: .infinity)
        }
    }

    /// 이미지 변경/제거 칩 — 그룹 추가행 칩 톤(아이콘+라벨, hairline 테두리).
    private func imageActionChip(
        label: String,
        icon: String,
        danger: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .semibold))
                Text(label)
                    .font(WGFont.sans(13))
            }
            .foregroundStyle(danger ? WGColor.cta : WGColor.ctaSub)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
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
            // 멤버 프사(GP-1 FR-9) — 없으면 닉네임 이니셜 폴백(AvatarView). 36pt 자리 유지.
            AvatarView(imageUrl: member.profileImageUrl, name: member.nickname, size: 36)
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

    // MARK: - 초대 코드(진입 시 자동 조회 + 코드 탭 복사 + 초대하기 공유창, IC-2 후속)

    private var inviteSection: some View {
        section(label: "초대") {
            VStack(alignment: .leading, spacing: 0) {
                if let code = viewModel.inviteCode {
                    // 코드 탭 = 바로 복사 + "복사됨" 표시(별도 복사 버튼 제거 — 모바일 친화).
                    inviteCodeRow(code: code)

                    Rectangle().fill(WGColor.hairline).frame(height: 1).padding(.vertical, 2)
                    // 초대하기 = 시스템 공유창(카톡/메신저 선택 → 링크 전송).
                    inviteShareRow(code: code)
                } else {
                    rowButton(
                        label: viewModel.isIssuing ? "만드는 중..." : "초대 코드 만들기",
                        disabled: viewModel.isIssuing
                    ) {
                        Task { await viewModel.issueInvite() }
                    }
                }
            }
        }
    }

    /// 초대코드 행 — 탭하면 바로 복사 + "복사됨" 토스트형 표시(별도 복사 버튼 불필요).
    private func inviteCodeRow(code: String) -> some View {
        Button {
            viewModel.copyInviteCode { UIPasteboard.general.string = $0 }
        } label: {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("초대코드")
                        .font(WGFont.sans(12))
                        .foregroundStyle(WGColor.inkSoft)
                    Text(code)
                        .font(WGFont.mono(22))
                        .foregroundStyle(WGColor.ink)
                }
                Spacer(minLength: 8)
                HStack(spacing: 4) {
                    Image(systemName: viewModel.inviteCopied ? "checkmark.circle.fill" : "doc.on.doc")
                        .font(.system(size: 13, weight: .semibold))
                    Text(viewModel.inviteCopied ? "복사됨" : "탭하여 복사")
                        .font(WGFont.sans(12))
                }
                .foregroundStyle(viewModel.inviteCopied ? WGColor.cta : WGColor.inkFaint)
            }
            .contentShape(Rectangle())
            .padding(.vertical, 10)
        }
        .buttonStyle(.plain)
    }

    /// 링크 공유 행 — shareUrl 이 절대 URL이면 URL 공유, 아니면 코드 텍스트 공유로 폴백.
    /// rowButton 톤(라벨 + 우측 아이콘)을 ShareLink 라벨로 재현한다.
    @ViewBuilder
    private func inviteShareRow(code: String) -> some View {
        let shareLabel = HStack {
            Text("초대하기")
                .font(WGFont.sans(14))
                .fontWeight(.semibold)
                .foregroundStyle(WGColor.cta)
            Spacer()
            Image(systemName: "square.and.arrow.up")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(WGColor.cta)
        }
        .contentShape(Rectangle())
        .padding(.vertical, 12)

        if let urlString = viewModel.inviteShareUrl,
           let url = URL(string: urlString), url.scheme != nil {
            ShareLink(item: url) { shareLabel }
        } else {
            ShareLink(item: code) { shareLabel }
        }
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

/// .fullScreenCover(item:) 용 Identifiable 래퍼(UIImage 자체는 Identifiable 아님). PinDetailContent.PickedImage 동치.
private struct PickedManageImage: Identifiable {
    let id = UUID()
    let image: UIImage
}
