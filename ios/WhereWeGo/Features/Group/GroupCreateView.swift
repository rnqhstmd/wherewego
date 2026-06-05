import SwiftUI

// 그룹 생성 화면(FR-18). GroupStart/위저드 스텝1 진입.
// 이름 입력(trim 1~30자) → 만들기 → createGroup → 성공 시 onCreated 로 온보딩 다음 단계 진행.
// 스타일은 InviteCodeView/GroupStartView(WGColor/WGFont)와 일치시킨다.
struct GroupCreateView: View {
    @StateObject private var viewModel: GroupCreateViewModel
    let onCreated: () -> Void
    let onCancel: () -> Void

    init(
        groupAPI: GroupAPIProtocol,
        onCreated: @escaping () -> Void,
        onCancel: @escaping () -> Void
    ) {
        _viewModel = StateObject(
            wrappedValue: GroupCreateViewModel(groupAPI: groupAPI)
        )
        self.onCreated = onCreated
        self.onCancel = onCancel
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            WGColor.bg.ignoresSafeArea()

            // 좌상단 ← 뒤로가기(GroupStartView 로).
            Button(action: onCancel) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(WGColor.ink)
                    .frame(width: 40, height: 40)
            }
            .disabled(viewModel.isCreating)
            .padding(.leading, 16)
            .padding(.top, 8)

            VStack(alignment: .leading, spacing: 0) {
                Text("어떤 그룹인가요")
                    .font(WGFont.emo(32))
                    .tracking(-1)   // 웹: letterSpacing:-1 (AC-7)
                    .foregroundStyle(WGColor.ink)

                Text("그룹 이름을 정해 주세요")
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.inkSoft)
                    .padding(.top, 12)

                VStack(spacing: 8) {
                    TextField("그룹 이름", text: $viewModel.name)
                        .font(WGFont.emo(22))
                        .foregroundStyle(WGColor.ink)
                        .autocorrectionDisabled()
                        .submitLabel(.done)
                        .onChange(of: viewModel.name) { _, _ in
                            viewModel.clearErrorOnEdit()
                        }
                        .onSubmit {
                            Task { await viewModel.submit(onCreated: onCreated) }
                        }
                    Rectangle()
                        .fill(WGColor.cta)
                        .frame(height: 2)
                }
                .padding(.top, 40)

                Text("\(viewModel.name.trimmingCharacters(in: .whitespacesAndNewlines).count)/\(GroupCreateViewModel.maxNameLength)")
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkFaint)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .padding(.top, 8)

                if let message = viewModel.errorMessage {
                    Text(message)
                        .font(WGFont.sans(13))
                        .foregroundStyle(WGColor.cta)
                        .padding(.top, 4)
                }

                Spacer()

                Button {
                    Task { await viewModel.submit(onCreated: onCreated) }
                } label: {
                    Text(viewModel.isCreating ? "만드는 중..." : "만들기")
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
