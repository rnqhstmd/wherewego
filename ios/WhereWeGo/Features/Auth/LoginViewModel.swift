import Foundation

// 로그인 화면 ViewModel(설계 §10, BR-7). 비동기 API + 에러 + 로딩 → VM 분리.
@MainActor
final class LoginViewModel: ObservableObject {
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let kakao: KakaoAuthServicing
    private let apple: AppleAuthServicing
    private let session: SessionStore

    init(kakao: KakaoAuthServicing, apple: AppleAuthServicing, session: SessionStore) {
        self.kakao = kakao
        self.apple = apple
        self.session = session
    }

    func loginKakao() async {
        await login { try await self.kakao.login() }
    }

    func loginApple() async {
        await login { try await self.apple.login() }
    }

    /// 공통 로그인 흐름. 성공 시 SessionStore.didLogin → phase 전환(RootView 리렌더).
    private func login(_ perform: @escaping () async throws -> TokenResponse) async {
        guard !isLoading else { return }
        isLoading = true
        errorMessage = nil
        do {
            let token = try await perform()
            try await session.didLogin(access: token.accessToken, refresh: token.refreshToken)
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
