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
    /// 내가 속한 그룹 목록(GM-2, GET /groups). 그룹 0개면 빈 배열, 401 throw.
    func listMyGroups() async throws -> [GroupSummary]
    func acceptInvite(token: String) async throws -> InviteAccept
    func issueInviteLink(groupId: Int) async throws -> InviteLink
    /// 그룹 탈퇴(DELETE /groups/{groupId}/members/me, FR-25).
    func leaveGroup(groupId: Int) async throws
    /// 그룹원 목록 조회(GET /groups/{id}/members, D단계). 가입 순, 첫 항목 isOwner=true(방장).
    func listMembers(groupId: Int) async throws -> [GroupMemberItem]
    /// 그룹명 수정(PATCH /groups/{id} {name}, D단계). 활성 멤버면 누구나.
    func updateGroupName(groupId: Int, name: String) async throws
    /// 그룹 삭제(DELETE /groups/{id}, D단계). 방장만(비방장 403 GROUP_OWNER_REQUIRED 전파).
    func deleteGroup(groupId: Int) async throws
    /// 초대 코드(slug) 공개 미리보기(GET /groups/invite-links/by-slug/{slug}, IC-2).
    /// token·groupName 확보용 — 합류는 확보한 token 으로 acceptInvite 호출(2단계).
    func previewBySlug(slug: String) async throws -> InviteLinkPreview
}

/// previewBySlug 기본 구현 — 기존 테스트 스텁(미구현)의 프로토콜 정합을 유지한다(12개 스텁 무수정).
/// 실제 호출 경로(InviteCodeViewModel)는 GroupAPI 의 구현이 override 한다.
extension GroupAPIProtocol {
    func previewBySlug(slug: String) async throws -> InviteLinkPreview {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "previewBySlug 미지원")
    }
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
