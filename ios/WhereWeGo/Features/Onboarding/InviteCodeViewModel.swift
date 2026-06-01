import Foundation

// 초대 코드 합류 ViewModel(설계 §11, FR-14, BR-8). 비동기 API + 에러 + 로딩 → VM 분리.
@MainActor
final class InviteCodeViewModel: ObservableObject {
    @Published var code: String = ""
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let groupAPI: GroupAPIProtocol

    init(groupAPI: GroupAPIProtocol, prefill: String = "") {
        self.groupAPI = groupAPI
        self.code = prefill
    }

    var canSubmit: Bool {
        !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isLoading
    }

    func clearErrorOnEdit() {
        if errorMessage != nil { errorMessage = nil }
    }

    /// POST /groups/invite-links/{token}/accept → 성공 시 onJoined.
    func join(onJoined: @escaping () -> Void) async {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isLoading else { return }
        isLoading = true
        errorMessage = nil
        do {
            _ = try await groupAPI.acceptInvite(token: trimmed)
            isLoading = false
            onJoined()
        } catch {
            isLoading = false
            errorMessage = "잘못된 코드이거나 만료되었어요"
        }
    }
}
