import Foundation

// 그룹 관련 DTO 및 호출(설계 §9). 모든 호출은 APIClient.request 경유.

// MARK: - 응답 DTO

/// 활성 그룹. 없으면 nil(myActiveGroup 에서 정규화).
struct ActiveGroup: Decodable {
    let groupId: Int
    let name: String
    let memberCount: Int
}

/// 초대 링크 발급 응답.
struct InviteLink: Decodable {
    let token: String
    let slug: String?
    let shareUrl: String?
}

/// 초대 수락 응답.
struct InviteAccept: Decodable {
    let groupId: Int
}

// MARK: - GroupAPI

final class GroupAPI: GroupAPIProtocol {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    // MUST#1 — groups/me nil 정규화(설계 §9).
    // - 현재(data:null) → APIEnvelope<ActiveGroup?> 이중옵셔널 → throw 없이 nil(catch 미도달).
    // - 미래(키 부재) → APIClient 가 200+data nil 을 code 기반으로 throw → catch 가 그룹없음만 nil 매핑.
    // - 그룹없음 vs 서버에러 구분(code 기반):
    //   · 200 SUCCESS + data null → APIClient 가 errorCode nil fallback 으로 code="HTTP_200" → nil.
    //   · 204/빈응답 → code="NO_CONTENT" → nil.
    //   · 200 FAIL(errorCode 존재) 또는 4xx/5xx → 진짜 에러 → throw.
    // - 401 → throw(상위 refresh/logout).
    func myActiveGroup() async throws -> ActiveGroup? {
        do {
            return try await client.request("/groups/me", type: ActiveGroup.self)
        } catch let error as APIError {
            if error.status == 401 { throw error }              // 인증 만료 → 상위(refresh/logout)
            // 그룹 없음: 200 SUCCESS + data null → code="HTTP_200" / 204 → "NO_CONTENT".
            if error.code == "HTTP_200" || error.code == "NO_CONTENT" { return nil }
            throw error                                          // errorCode 있는 FAIL, 4xx/5xx 등 진짜 에러
        }
    }

    /// POST /groups/invite-links/{token}/accept
    func acceptInvite(token: String) async throws -> InviteAccept {
        try await client.request(
            "/groups/invite-links/\(token)/accept",
            method: "POST",
            type: InviteAccept.self
        )
    }

    /// POST /groups/{groupId}/invite-links (201)
    func issueInviteLink(groupId: Int) async throws -> InviteLink {
        try await client.request(
            "/groups/\(groupId)/invite-links",
            method: "POST",
            type: InviteLink.self
        )
    }
}
