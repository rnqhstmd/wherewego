import SwiftUI

// 초대 코드 입력 화면(설계 §11, FR-14, BR-8).
// frontend/src/app/onboarding/invite-code/InviteCodeClient.tsx 1:1 이식.
struct InviteCodeView: View {
    @StateObject private var viewModel: InviteCodeViewModel
    let onJoined: () -> Void
    let onCancel: () -> Void

    init(
        groupAPI: GroupAPIProtocol,
        prefill: String = "",
        onJoined: @escaping () -> Void,
        onCancel: @escaping () -> Void
    ) {
        _viewModel = StateObject(
            wrappedValue: InviteCodeViewModel(groupAPI: groupAPI, prefill: prefill)
        )
        self.onJoined = onJoined
        self.onCancel = onCancel
    }

    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 0) {
                Text("초대 코드를 받았나요?")
                    .font(WGFont.emo(32))
                    .tracking(-1)   // 웹: letterSpacing:-1 (AC-7)
                    .foregroundStyle(WGColor.ink)

                Text("친구가 보낸 링크의 코드를 입력해요")
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.inkSoft)
                    .padding(.top, 12)

                VStack(spacing: 8) {
                    TextField("초대 코드", text: $viewModel.code)
                        .font(WGFont.emo(22))
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
                        Task { await viewModel.join(onJoined: onJoined) }
                    } label: {
                        Text(viewModel.isLoading ? "합류 중..." : "합류하기")
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
        .navigationBarBackButtonHidden(true)
    }
}
