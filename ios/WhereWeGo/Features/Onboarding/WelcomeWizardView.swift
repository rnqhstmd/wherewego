import SwiftUI
import UIKit

// 환영 위저드 화면(설계 §11, FR-16, BR-9). 2스텝(챗봇 제거).
// frontend/src/app/onboarding/welcome/WelcomeWizardClient.tsx + _steps/{Step1Group,Step2Invite} 이식.
struct WelcomeWizardView: View {
    @StateObject private var viewModel: WelcomeWizardViewModel
    let onCreateGroup: () -> Void
    let onJoin: () -> Void
    let onFinish: () -> Void

    /// 시스템 공유시트 표시 여부(공유하기 버튼, AC-20).
    @State private var isSharePresented = false

    init(
        groupAPI: GroupAPIProtocol,
        initialGroup: ActiveGroup? = nil,
        onCreateGroup: @escaping () -> Void,
        onJoin: @escaping () -> Void,
        onFinish: @escaping () -> Void
    ) {
        _viewModel = StateObject(
            wrappedValue: WelcomeWizardViewModel(groupAPI: groupAPI, initialGroup: initialGroup)
        )
        self.onCreateGroup = onCreateGroup
        self.onJoin = onJoin
        self.onFinish = onFinish
    }

    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()

            VStack(spacing: 0) {
                switch viewModel.step {
                case .loading:
                    loadingState
                case .group:
                    VStack(spacing: 0) {
                        stepProgress
                            .padding(.top, 16)
                        step1Group
                            .padding(.top, 32)
                    }
                case .invite:
                    VStack(spacing: 0) {
                        stepProgress
                            .padding(.top, 16)
                        step2Invite
                            .padding(.top, 32)
                    }
                }

                Spacer(minLength: 0)
            }
            .padding(EdgeInsets(top: 32, leading: 24, bottom: 32, trailing: 24))
            .frame(maxWidth: 460)
        }
        .navigationBarBackButtonHidden(true)
        .task { await viewModel.start() }
    }

    // MARK: - 로딩 상태(그룹 유무 결정 전, 깜빡임 방지)

    private var loadingState: some View {
        VStack {
            Spacer(minLength: 0)
            ProgressView()
                .tint(WGColor.cta)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, minHeight: 400)
    }

    // MARK: - 진행 인디케이터(2/2)

    private var stepProgress: some View {
        let current = viewModel.step
        return HStack(spacing: 8) {
            progressSegment(index: 1, label: "그룹", active: current == .group, done: current == .invite)
            progressSegment(index: 2, label: "초대", active: current == .invite, done: false)
        }
    }

    private func progressSegment(index: Int, label: String, active: Bool, done: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            RoundedRectangle(cornerRadius: 2)
                .fill(active || done ? WGColor.cta : WGColor.hairline)
                .frame(height: 4)
            Text("\(index). \(label)")
                .font(WGFont.sans(11))
                .foregroundStyle(active ? WGColor.ink : WGColor.inkFaint)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - 스텝1: 그룹

    private var step1Group: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("함께 갈 곳을 모아봐요")
                .font(WGFont.emo(26))
                .tracking(-1)   // 웹: letterSpacing:-1 (AC-7)
                .foregroundStyle(WGColor.ink)

            Text("새 그룹을 만들거나 초대받은 그룹에 합류할 수 있어요.")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
                .padding(.top, 10)

            Spacer(minLength: 40)

            VStack(spacing: 10) {
                Button(action: onCreateGroup) {
                    Text("새 그룹 만들기")
                        .font(WGFont.sans(15))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(WGColor.cta)
                        .foregroundStyle(WGColor.panel)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }

                Button(action: onJoin) {
                    Text("초대 코드로 합류")
                        .font(WGFont.sans(14))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .foregroundStyle(WGColor.ink)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(WGColor.hairline, lineWidth: 1)
                        )
                }

                Button {
                    Task { await viewModel.goToInvite() }
                } label: {
                    Text("다음에 할게요")
                        .font(WGFont.sans(13))
                        .foregroundStyle(WGColor.inkFaint)
                        .padding(.vertical, 6)
                }
                .padding(.top, 4)
            }
        }
        .frame(minHeight: 400)
    }

    // MARK: - 스텝2: 초대 코드

    private var step2Invite: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("짝꿍에게 코드를 보내요")
                .font(WGFont.emo(26))
                .tracking(-1)   // 웹: letterSpacing:-1 (AC-7)
                .foregroundStyle(WGColor.ink)

            Text("이 코드를 받은 사람이 합류하면 함께 지도를 만들 수 있어요.")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
                .padding(.top, 10)

            // 코드 박스.
            linkBox
                .padding(.top, 32)

            Spacer(minLength: 40)

            VStack(spacing: 10) {
                HStack(spacing: 10) {
                    Button {
                        viewModel.copyCode { UIPasteboard.general.string = $0 }
                        // 복사됨 표시를 2초 후 원복(AC-19).
                        Task {
                            try? await Task.sleep(for: .seconds(2))
                            viewModel.copied = false
                        }
                    } label: {
                        Text(viewModel.copied ? "복사됨" : "코드 복사")
                            .font(WGFont.sans(15))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(canShare ? WGColor.cta : WGColor.inkFaint)
                            .foregroundStyle(WGColor.panel)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    .disabled(!canShare)

                    Button {
                        isSharePresented = true
                    } label: {
                        Text("공유하기")
                            .font(WGFont.sans(15))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .foregroundStyle(WGColor.ink)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(WGColor.hairline, lineWidth: 1)
                            )
                    }
                    .disabled(!canShare)
                }

                Button(action: onFinish) {
                    Text("다음 단계")
                        .font(WGFont.sans(14))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .foregroundStyle(WGColor.ink)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(WGColor.hairline, lineWidth: 1)
                        )
                }

                Button(action: onFinish) {
                    Text("다음에 할게요")
                        .font(WGFont.sans(13))
                        .foregroundStyle(WGColor.inkFaint)
                        .padding(.vertical, 6)
                }
                .padding(.top, 4)
            }
            .sheet(isPresented: $isSharePresented) {
                ActivityShareSheet(items: [viewModel.shareMessage].compactMap { $0 })
            }
        }
        .frame(minHeight: 400)
    }

    /// 코드 복사/공유 가능 여부(로딩 아님 + slug 보유).
    private var canShare: Bool {
        !viewModel.isLoading && (viewModel.slug?.isEmpty == false)
    }

    private var linkBox: some View {
        VStack(alignment: .leading, spacing: 8) {
            if viewModel.isLoading {
                Text("초대 코드를 만들고 있어요...")
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkFaint)
            } else if let message = viewModel.errorMessage {
                Text(message)
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.cta)
            } else if let code = viewModel.slug {
                Text(code)
                    .font(WGFont.mono(13))
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if let expires = viewModel.expiresLabel {
                    Text(expires)
                        .font(WGFont.sans(11))
                        .foregroundStyle(WGColor.inkFaint)
                }
            }
        }
        .frame(maxWidth: .infinity, minHeight: 80, alignment: .leading)
        .padding(EdgeInsets(top: 18, leading: 22, bottom: 18, trailing: 22))
        .background(WGColor.panel)
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(WGColor.hairline, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}
