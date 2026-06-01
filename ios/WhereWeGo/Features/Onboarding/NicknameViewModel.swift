import Foundation

// 닉네임 설정 ViewModel(설계 §11, FR-12, BR-1). 비동기 API + 에러 + 로딩 → VM 분리.
@MainActor
final class NicknameViewModel: ObservableObject {
    @Published var nickname: String = ""
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let authAPI: AuthAPI

    init(authAPI: AuthAPI) {
        self.authAPI = authAPI
    }

    var canSubmit: Bool {
        Nickname.validate(nickname) == .valid && !isLoading
    }

    /// 한글 IME 조합 깜빡임 방지: sanitize 결과가 다를 때만 바인딩 되돌림.
    func sanitizeInput(_ value: String) {
        let cleaned = Nickname.sanitize(value)
        if cleaned != value {
            nickname = cleaned
        }
        if errorMessage != nil { errorMessage = nil }
    }

    /// PUT /users/me {nickname} → 성공 시 nicknameSet=true + onDone.
    func save(onDone: @escaping () -> Void) async {
        guard canSubmit else { return }
        isLoading = true
        errorMessage = nil
        do {
            _ = try await authAPI.updateNickname(nickname)
            OnboardingFlags.nicknameSet = true
            isLoading = false
            onDone()
        } catch {
            isLoading = false
            errorMessage = "저장에 실패했어요. 잠시 후 다시 시도해 주세요"
        }
    }
}
