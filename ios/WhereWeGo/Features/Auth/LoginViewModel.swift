import Foundation

// 로그인 화면 ViewModel(설계 §10, BR-7). 비동기 API + 에러 + 로딩 → VM 분리.
@MainActor
final class LoginViewModel: ObservableObject {
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let kakao: KakaoAuthServicing
    private let apple: AppleAuthServicing
    private let session: SessionStore
    /// 데모 로그인(설계 §10) refresh 호출용. 일반 로그인 흐름엔 미사용.
    private let authAPI: AuthAPI

    init(kakao: KakaoAuthServicing, apple: AppleAuthServicing, session: SessionStore, authAPI: AuthAPI) {
        self.kakao = kakao
        self.apple = apple
        self.session = session
        self.authAPI = authAPI
    }

    func loginKakao() async {
        await login {
            try await self.kakao.login()
        } finalize: { token in
            try await self.session.didLogin(access: token.accessToken, refresh: token.refreshToken)
        }
    }

    func loginApple() async {
        await login {
            try await self.apple.login()
        } finalize: { token in
            try await self.session.didLogin(access: token.accessToken, refresh: token.refreshToken)
        }
    }

    /// 데모 로그인(설계 §10, FR-26/AC-21). AppConfig.demoRefreshToken 으로 /auth/refresh 호출 →
    /// 토큰 저장 후 인증 전환. 토큰 nil(placeholder) 이면 no-op(버튼은 비활성이지만 방어).
    func loginDemo() async {
        guard let demoToken = AppConfig.demoRefreshToken else { return }
        await login {
            try await self.authAPI.refresh(refreshToken: demoToken)
        } finalize: { token in
            try await self.session.didLoginDemo(access: token.accessToken, refresh: token.refreshToken)
        }
    }

    /// 공통 로그인 흐름. perform 으로 토큰을 얻고 finalize 로 세션 전환한다(RootView 리렌더).
    /// 일반 로그인은 finalize=didLogin, 데모 로그인은 finalize=didLoginDemo 를 주입한다.
    private func login(
        _ perform: @escaping () async throws -> TokenResponse,
        finalize: @escaping (TokenResponse) async throws -> Void
    ) async {
        guard !isLoading else { return }
        isLoading = true
        errorMessage = nil
        do {
            let token = try await perform()
            try await finalize(token)
        } catch let error as AuthError {
            // 사용자 취소는 조용히 무시(BR-7).
            if case .cancelled = error {
                errorMessage = nil
            } else {
                errorMessage = error.errorDescription
            }
        } catch {
            errorMessage = "로그인에 실패했어요. 다시 시도해 주세요."
        }
        isLoading = false
    }
}
