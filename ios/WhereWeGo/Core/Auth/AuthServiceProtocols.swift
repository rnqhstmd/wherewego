import Foundation

// 외부 의존(Kakao/Apple/그룹 네트워크)을 프로토콜 뒤로 숨겨 테스트 목 주입을 가능하게 한다(설계 §7~9, DI).

protocol KakaoAuthServicing: Sendable {
    func login() async throws -> TokenResponse
}

protocol AppleAuthServicing: Sendable {
    func login() async throws -> TokenResponse
}

protocol GroupAPIProtocol: Sendable {
    /// 활성 그룹 없으면 nil, 401 throw(MUST#1).
    func myActiveGroup() async throws -> ActiveGroup?
    func acceptInvite(token: String) async throws -> InviteAccept
    func issueInviteLink(groupId: Int) async throws -> InviteLink
    /// 그룹 탈퇴(DELETE /groups/{groupId}/members/me, FR-25).
    func leaveGroup(groupId: Int) async throws
}

/// 인증 흐름 에러. View 친화 메시지(BR-7).
enum AuthError: LocalizedError {
    case kakaoNotConfigured
    case cancelled
    case appleUnavailable
    case server(APIError)

    var errorDescription: String? {
        switch self {
        case .kakaoNotConfigured:
            return "카카오 로그인을 사용할 수 없어요. 잠시 후 다시 시도해 주세요."
        case .cancelled:
            return "로그인을 취소했어요."
        case .appleUnavailable:
            return "Apple 로그인을 사용할 수 없어요. 잠시 후 다시 시도해 주세요."
        case .server(let error):
            return error.message.isEmpty ? "로그인에 실패했어요. 다시 시도해 주세요." : error.message
        }
    }
}
