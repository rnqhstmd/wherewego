import SwiftUI

// 초대 코드 합류 화면(설계 §5). slug 입력 → 확인 → 이미멤버, 동일 화면 내 step 분기(push/sheet 없음).
struct InviteCodeView: View {
    @StateObject private var viewModel: InviteCodeViewModel
    let onJoined: () -> Void
    let onCancel: () -> Void

    init(
        groupAPI: GroupAPIProtocol,
        onJoined: @escaping () -> Void,
        onCancel: @escaping () -> Void
    ) {
        _viewModel = StateObject(
            wrappedValue: InviteCodeViewModel(groupAPI: groupAPI)
        )
        self.onJoined = onJoined
        self.onCancel = onCancel
    }

    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()

            switch viewModel.step {
            case .input, .previewing:
                inputSection
            case .confirm(let preview), .accepting(let preview):
                confirmSection(preview)
            case .alreadyMember:
                alreadyMemberSection
            }
        }
        .navigationBarBackButtonHidden(true)
    }

    // MARK: - 입력 화면(.input/.previewing)

    private var inputSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("초대 코드를 받았나요?")
                .font(WGFont.emo(32))
                .tracking(-1)   // 웹: letterSpacing:-1 (AC-7)
                .foregroundStyle(WGColor.ink)

            Text("친구가 보낸 코드를 입력해요")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
                .padding(.top, 12)

            VStack(spacing: 8) {
                TextField("초대 코드", text: $viewModel.code)
                    .font(WGFont.mono(22))
                    .foregroundStyle(WGColor.ink)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onChange(of: viewModel.code) { _, _ in
                        viewModel.clearErrorOnEdit()
                    }
                Rectangle()
                    .fill(WGColor.cta)
                    .frame(height: 2)
            }
            .padding(.top, 40)

            if let message = viewModel.errorMessage {
                Text(message)
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.cta)
                    .padding(.top, 12)
            }

            Spacer()

            VStack(spacing: 10) {
                Button {
                    Task { await viewModel.submitPreview() }
                } label: {
                    Text(viewModel.isPreviewing ? "확인 중..." : "합류하기")
                        .font(WGFont.sans(15))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(viewModel.canSubmit ? WGColor.cta : WGColor.inkFaint)
                        .foregroundStyle(WGColor.panel)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .disabled(!viewModel.canSubmit)

                Button(action: onCancel) {
                    Text("취소")
                        .font(WGFont.sans(14))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .foregroundStyle(WGColor.inkSoft)
                }
            }
        }
        .padding(EdgeInsets(top: 80, leading: 32, bottom: 32, trailing: 32))
    }

    // MARK: - 확인 화면(.confirm/.accepting)

    private func confirmSection(_ preview: InvitePreview) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("\(preview.groupName) 그룹에 합류할까요?")
                .font(WGFont.emo(28))
                .tracking(-1)
                .foregroundStyle(WGColor.ink)

            if let nickname = preview.inviterNickname {
                Text("\(nickname)님이 초대했어요.")
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.inkSoft)
                    .padding(.top, 12)
            }

            if let expiresAt = preview.expiresAt,
               let until = InviteDateFormatter.untilMonthDay(expiresAt) {
                Text("코드 유효기간: \(until)")
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkFaint)
                    .padding(.top, 8)
            }

            if let message = viewModel.confirmErrorMessage {
                Text(message)
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.cta)
                    .padding(.top, 12)
            }

            Spacer()

            VStack(spacing: 10) {
                Button {
                    Task { await viewModel.confirmJoin(onJoined: onJoined) }
                } label: {
                    Text(viewModel.isAccepting ? "합류 중..." : "합류하기")
                        .font(WGFont.sans(15))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(viewModel.isAccepting ? WGColor.inkFaint : WGColor.cta)
                        .foregroundStyle(WGColor.panel)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .disabled(viewModel.isAccepting)

                Button {
                    viewModel.cancelToInput()
                } label: {
                    Text("취소")
                        .font(WGFont.sans(14))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .foregroundStyle(WGColor.inkSoft)
                }
                .disabled(viewModel.isAccepting)
            }
        }
        .padding(EdgeInsets(top: 80, leading: 32, bottom: 32, trailing: 32))
    }

    // MARK: - 이미 멤버 화면(.alreadyMember)

    private var alreadyMemberSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("이미 이 그룹의 멤버예요.")
                .font(WGFont.emo(28))
                .tracking(-1)
                .foregroundStyle(WGColor.ink)

            Spacer()

            Button {
                viewModel.acknowledgeAlreadyMember(onJoined: onJoined)
            } label: {
                Text("확인")
                    .font(WGFont.sans(15))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(WGColor.cta)
                    .foregroundStyle(WGColor.panel)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
        .padding(EdgeInsets(top: 80, leading: 32, bottom: 32, trailing: 32))
    }
}
