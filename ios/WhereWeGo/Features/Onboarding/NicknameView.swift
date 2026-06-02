import SwiftUI

// 닉네임 설정 화면(설계 §11, FR-12, BR-1).
// frontend/src/app/onboarding/nickname/NicknameClient.tsx 1:1 이식.
struct NicknameView: View {
    @StateObject private var viewModel: NicknameViewModel
    let onDone: () -> Void

    init(authAPI: AuthAPI, onDone: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: NicknameViewModel(authAPI: authAPI))
        self.onDone = onDone
    }

    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 0) {
                Text("반가워요\n이름을 알려주세요")
                    .font(WGFont.emo(32))
                    .tracking(-1)   // 웹: letterSpacing:-1 (AC-7)
                    .foregroundStyle(WGColor.ink)
                    .lineSpacing(6)
                    .fixedSize(horizontal: false, vertical: true)

                Text("함께하는 사람에게 보여질 이름이에요")
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.inkSoft)
                    .padding(.top, 12)

                // 언더라인 TextField.
                VStack(spacing: 8) {
                    TextField("", text: $viewModel.nickname)
                        .font(WGFont.emo(24))
                        .foregroundStyle(WGColor.ink)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onChange(of: viewModel.nickname) { _, newValue in
                            viewModel.sanitizeInput(newValue)
                        }
                    Rectangle()
                        .fill(WGColor.cta)
                        .frame(height: 2)
                }
                .padding(.top, 40)

                Text("한글, 영문, 숫자 2~12자")
                    .font(WGFont.sans(12))
                    .foregroundStyle(WGColor.inkSoft)
                    .padding(.top, 8)

                if let message = viewModel.errorMessage {
                    Text(message)
                        .font(WGFont.sans(13))
                        .foregroundStyle(WGColor.cta)
                        .padding(.top, 12)
                }

                Spacer()

                Button {
                    Task { await viewModel.save(onDone: onDone) }
                } label: {
                    Text(viewModel.isLoading ? "저장 중..." : "다음")
                        .font(WGFont.sans(15))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(viewModel.canSubmit ? WGColor.cta : WGColor.inkFaint)
                        .foregroundStyle(WGColor.panel)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .disabled(!viewModel.canSubmit)
            }
            .padding(EdgeInsets(top: 80, leading: 32, bottom: 32, trailing: 32))
        }
        .navigationBarBackButtonHidden(true)
    }
}
