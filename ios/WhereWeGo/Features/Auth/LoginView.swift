import SwiftUI
import AuthenticationServices

// 로그인 화면(설계 §10, FR-7, BR-7, QE-2).
// frontend/src/app/login/LoginClient.tsx 1:1 이식(워드마크/태그라인/카카오 버튼).
// Apple 버튼은 네이티브 추가(설계 B3 범위). 로그인 중 버튼 disabled(QE-2), 오류 인라인.
struct LoginView: View {
    @StateObject private var viewModel: LoginViewModel

    init(kakao: KakaoAuthServicing, apple: AppleAuthServicing, session: SessionStore) {
        _viewModel = StateObject(
            wrappedValue: LoginViewModel(kakao: kakao, apple: apple, session: session)
        )
    }

    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer()

                // 브랜드 워드마크.
                Text("우리가 갈 지도")
                    .font(WGFont.emo(48))
                    .foregroundStyle(WGColor.ink)

                // 태그라인.
                Text("우리의 장소를 지도 위에 아카이빙해요")
                    .font(WGFont.sans(15.5))
                    .foregroundStyle(WGColor.inkSoft)
                    .padding(.top, 28)

                // 디바이더 닷.
                HStack(spacing: 8) {
                    Circle().fill(WGColor.pinReel).frame(width: 8, height: 8)
                    Circle().fill(WGColor.pinMemory).frame(width: 11, height: 11)
                    Circle().fill(WGColor.pinReel).frame(width: 8, height: 8)
                }
                .padding(.top, 32)

                // 오류 메시지(인라인).
                if let message = viewModel.errorMessage {
                    Text(message)
                        .font(WGFont.sans(12.5))
                        .foregroundStyle(WGColor.cta)
                        .multilineTextAlignment(.center)
                        .padding(.top, 18)
                }

                // 카카오 버튼.
                Button {
                    Task { await viewModel.loginKakao() }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "message.fill")
                        Text("카카오로 시작하기")
                            .font(WGFont.sans(15))
                    }
                    .frame(maxWidth: 320)
                    .padding(.vertical, 14)
                    .background(WGColor.kakao)
                    .foregroundStyle(WGColor.kakaoInk)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .disabled(viewModel.isLoading)
                .padding(.top, 32)

                // Apple 버튼(네이티브 추가).
                SignInWithAppleButton(.signIn) { _ in
                    // 실제 요청은 AppleAuthService 가 ASAuthorizationController 로 직접 수행.
                } onCompletion: { _ in }
                .signInWithAppleButtonStyle(.black)
                .frame(maxWidth: 320)
                .frame(height: 50)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .allowsHitTesting(false)
                .overlay {
                    // SignInWithAppleButton 은 자체 콜백이 ASAuthorizationController 를
                    // 별도 흐름으로 띄우므로, 탭 처리는 투명 버튼으로 위임한다.
                    Button {
                        Task { await viewModel.loginApple() }
                    } label: {
                        Color.clear
                    }
                    .disabled(viewModel.isLoading)
                }
                .padding(.top, 10)

                // 약관 안내.
                Text("시작하면 서비스 이용약관 및 개인정보처리방침에 동의합니다")
                    .font(WGFont.sans(12))
                    .foregroundStyle(WGColor.inkFaint)
                    .multilineTextAlignment(.center)
                    .padding(.top, 18)

                Spacer()
            }
            .padding(20)

            if viewModel.isLoading {
                ProgressView().tint(WGColor.cta)
            }
        }
    }
}
