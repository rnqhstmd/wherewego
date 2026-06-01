import SwiftUI

// 공용 권한 안내 다이얼로그(설계 §11).
// frontend/src/components/ui/PermissionDialog.tsx 1:1 이식.
// Location/Notification 화면에서 재사용한다.
// 흰 카드 + 상단 원형 아이콘 + 제목(emo 22) + 설명(inkSoft) + primary/secondary 버튼(vertical).
struct PermissionDialogView: View {
    let icon: String
    let title: String
    let description: String
    let primaryTitle: String
    let secondaryTitle: String
    let onPrimary: () -> Void
    let onSecondary: () -> Void

    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()

            VStack(spacing: 0) {
                // 상단 원형 아이콘 박스(rust 톤 15%).
                ZStack {
                    Circle()
                        .fill(WGColor.cta.opacity(0.15))
                        .frame(width: 60, height: 60)
                    Image(systemName: icon)
                        .font(.system(size: 28))
                        .foregroundStyle(WGColor.cta)
                }
                .padding(.bottom, 18)

                Text(title)
                    .font(WGFont.emo(22))
                    .foregroundStyle(WGColor.ink)
                    .multilineTextAlignment(.center)
                    .padding(.bottom, 10)

                Text(description)
                    .font(WGFont.sans(13.5))
                    .foregroundStyle(WGColor.inkSoft)
                    .multilineTextAlignment(.center)
                    .lineSpacing(4)
                    .padding(.bottom, 24)

                VStack(spacing: 8) {
                    Button(action: onPrimary) {
                        Text(primaryTitle)
                            .font(WGFont.sans(15))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(WGColor.cta)
                            .foregroundStyle(WGColor.panel)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }

                    Button(action: onSecondary) {
                        Text(secondaryTitle)
                            .font(WGFont.sans(14))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 11)
                            .foregroundStyle(WGColor.inkSoft)
                    }
                }
            }
            .padding(EdgeInsets(top: 28, leading: 24, bottom: 20, trailing: 24))
            .frame(maxWidth: 320)
            .background(WGColor.panel)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .shadow(color: WGColor.shadowMd, radius: 16, x: 0, y: 10)
            .padding(20)
        }
    }
}
