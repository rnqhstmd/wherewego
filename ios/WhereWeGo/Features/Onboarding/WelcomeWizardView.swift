import SwiftUI
import UIKit

// 환영 위저드 화면(설계 §11, FR-16, BR-9). 2스텝(챗봇 제거).
// frontend/src/app/onboarding/welcome/WelcomeWizardClient.tsx + _steps/{Step1Group,Step2Invite} 이식.
struct WelcomeWizardView: View {
    @StateObject private var viewModel: WelcomeWizardViewModel
    let onCreateGroup: () -> Void
    let onJoin: () -> Void
    let onFinish: () -> Void

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

    // MARK: - 스텝2: 초대 링크

    private var step2Invite: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("짝꿍에게 링크를 보내요")
                .font(WGFont.emo(26))
                .tracking(-1)   // 웹: letterSpacing:-1 (AC-7)
                .foregroundStyle(WGColor.ink)

            Text("이 링크를 받은 사람이 합류하면 함께 지도를 만들 수 있어요.")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
                .padding(.top, 10)

            // 링크 박스.
            linkBox
                .padding(.top, 32)

            Spacer(minLength: 40)

            VStack(spacing: 10) {
                // 초대하기 = 시스템 공유창(카톡/메신저 선택 → 링크 전송). 복사 단계 없이 바로 공유.
                inviteShareButton

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
        }
        .frame(minHeight: 400)
    }

    /// 초대하기 버튼 — 링크가 준비되면 ShareLink(공유창), 준비 전/실패면 비활성 라벨.
    @ViewBuilder
    private var inviteShareButton: some View {
        let label = Text("초대하기")
            .font(WGFont.sans(15))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(WGColor.cta)
            .foregroundStyle(WGColor.panel)
            .clipShape(RoundedRectangle(cornerRadius: 12))

        if let shareText = viewModel.shareText, !shareText.isEmpty {
            if let url = URL(string: shareText), url.scheme != nil {
                ShareLink(item: url) { label }
            } else {
                ShareLink(item: shareText) { label }
            }
        } else {
            Text(viewModel.isLoading ? "초대 링크 준비 중…" : "초대하기")
                .font(WGFont.sans(15))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(WGColor.inkFaint)
                .foregroundStyle(WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
    }

    private var linkBox: some View {
        HStack {
            if viewModel.isLoading {
                Text("초대 링크를 만들고 있어요...")
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkFaint)
            } else if let message = viewModel.errorMessage {
                Text(message)
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.cta)
            } else if let text = viewModel.shareText {
                Text(text)
                    .font(WGFont.mono(13))
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(3)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 0)
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
