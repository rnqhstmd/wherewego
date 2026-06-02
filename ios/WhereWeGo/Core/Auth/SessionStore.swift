import Foundation

// 인증 세션 상태(설계 §6, BR-5).
// phase 만 인증을 담당하고, 온보딩 분기는 OnboardingRouter 가 맡는다(SRP 분리).
@MainActor
final class SessionStore: ObservableObject {
    enum Phase {
        case launching
        case unauthenticated
        case authenticated
    }

    @Published private(set) var phase: Phase = .launching

    private let tokens: KeychainTokenStore

    init(tokens: KeychainTokenStore) {
        self.tokens = tokens
    }

    /// Keychain accessToken 유무로 초기 phase 결정. .task 로 1회 호출.
    func bootstrap() async {
        let token = await tokens.accessToken()
        phase = (token != nil) ? .authenticated : .unauthenticated
    }

    /// 로그인 성공 → 토큰 저장 후 인증 상태로 전환.
    /// 토큰 저장 실패(KeychainError) 시 throw → 인증 전환하지 않음(저장 못한 토큰은 무의미).
    func didLogin(access: String, refresh: String) async throws {
        try await tokens.saveTokens(access: access, refresh: refresh)
        phase = .authenticated
    }

    /// 데모 로그인 성공(설계 §10, FR-26/AC-21) → 토큰 저장 후 인증 전환.
    /// 일반 로그인과 토큰 저장/전환 절차가 동일하므로 didLogin 을 재사용한다(별도 분기 불요).
    func didLoginDemo(access: String, refresh: String) async throws {
        try await didLogin(access: access, refresh: refresh)
    }

    /// 로그아웃. 멱등(이미 .unauthenticated 면 no-op) — 동시 refresh 실패 다중 호출 안전(CONSIDER).
    /// 디바이스 토큰 해제·CurrentUser.clear 는 AppDependencies.logoutBox 핸들러가 함께 수행한다(설계 §8/§12).
    func logout() async {
        guard phase != .unauthenticated else { return }
        await tokens.clear()
        phase = .unauthenticated
    }
}
