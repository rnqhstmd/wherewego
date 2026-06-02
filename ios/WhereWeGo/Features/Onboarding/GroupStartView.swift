import SwiftUI

// 그룹 시작 화면(설계 §11, FR-13).
// frontend/src/app/onboarding/group-start/GroupStartClient.tsx 1:1 이식.
// 네비게이션만 담당하므로 VM 불요(@State). 카드 탭 → Router 가 push.
struct GroupStartView: View {
    let onCreateGroup: () -> Void
    let onJoin: () -> Void

    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 0) {
                Text("어떻게 시작할까요")
                    .font(WGFont.emo(28))
                    .tracking(-1)   // 웹: letterSpacing:-1 (AC-7)
                    .foregroundStyle(WGColor.ink)

                Text("혼자서도, 함께서도 괜찮아요")
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.inkSoft)
                    .padding(.top, 10)

                // 새 그룹 만들기 카드(cta 테두리).
                Button(action: onCreateGroup) {
                    optionCard(
                        dot: WGColor.pinWish,
                        title: "새 그룹 만들기",
                        description: "이름을 정하고 친구를 초대해서\n함께 핀을 찍어요"
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 16)
                            .stroke(WGColor.cta, lineWidth: 1.5)
                    )
                }
                .buttonStyle(.plain)
                .padding(.top, 32)

                // 초대 코드로 합류 카드(hairline 테두리).
                Button(action: onJoin) {
                    optionCard(
                        dot: WGColor.pinReel,
                        title: "초대 코드로 합류",
                        description: "받은 코드를 입력해서\n이미 만들어진 그룹에 들어가요"
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 16)
                            .stroke(WGColor.hairline, lineWidth: 1.5)
                    )
                }
                .buttonStyle(.plain)
                .padding(.top, 12)

                Spacer()

                Text("나중에 설정에서 변경할 수 있어요")
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkFaint)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
            .padding(EdgeInsets(top: 70, leading: 28, bottom: 32, trailing: 28))
        }
        .navigationBarBackButtonHidden(true)
    }

    private func optionCard(dot: Color, title: String, description: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Circle().fill(dot).frame(width: 14, height: 14)
                Text(title)
                    .font(WGFont.emo(17))
                    .foregroundStyle(WGColor.ink)
            }
            Text(description)
                .font(WGFont.sans(13))
                .foregroundStyle(WGColor.inkSoft)
                .lineSpacing(2)
                .multilineTextAlignment(.leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(EdgeInsets(top: 20, leading: 22, bottom: 20, trailing: 22))
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}
