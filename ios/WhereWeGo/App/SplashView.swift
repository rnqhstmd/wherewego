import SwiftUI

// 런치/로딩 화면(설계 §10, FR-10).
// 부트스트랩(Keychain accessToken 조회) 중 표시. 브랜드 + ProgressView.
struct SplashView: View {
    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()

            VStack(spacing: 28) {
                Text("우리가 갈 지도")
                    .font(WGFont.emo(40))
                    .foregroundStyle(WGColor.ink)

                ProgressView()
                    .tint(WGColor.cta)
            }
        }
    }
}
