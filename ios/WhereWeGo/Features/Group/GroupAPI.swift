import Foundation

// 그룹 관련 DTO 및 호출(설계 §9). 모든 호출은 APIClient.request 경유.

// MARK: - 응답 DTO

/// 활성 그룹. 없으면 nil(myActiveGroup 에서 정규화).
struct ActiveGroup: Decodable {
    let groupId: Int
    let name: String
    let memberCount: Int
}

/// 내 그룹 목록 항목(GM-2, GET /groups). 백엔드 GroupSummary(record) 와 1:1 정합.
/// - createdAt: 그룹 생성 시각(ISO8601 문자열). 현재 목록 표시엔 미사용이나 백엔드 필드 보존(향후 정렬용).
/// - memberCount: 백엔드 long → iOS Int.
struct GroupSummary: Decodable, Identifiable {
    let groupId: Int
    let name: String
    let createdAt: String?
    let memberCount: Int

    /// Identifiable(GroupListView ForEach) — groupId 를 식별자로 사용.
    var id: Int { groupId }
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

    /// GET /groups — 내가 속한 그룹 목록(GM-2, FR-5). 1인 N그룹 지원(GM-1 제약 해제).
    /// 그룹 0개면 빈 배열(data:[]). 200 + data null/204 는 빈 배열로 정규화(myActiveGroup nil 패턴 동치).
    func listMyGroups() async throws -> [GroupSummary] {
        do {
            return try await client.request("/groups", type: [GroupSummary].self)
        } catch let error as APIError {
            if error.status == 401 { throw error }                       // 인증 만료 → 상위(refresh/logout)
            // 그룹 없음(data null/204) → 빈 목록. 나머지(FAIL·4xx/5xx)는 진짜 에러 전파.
            if error.code == "HTTP_200" || error.code == "NO_CONTENT" { return [] }
            throw error
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

    /// DELETE /groups/{groupId}/members/me (그룹 탈퇴, FR-25).
    // 백엔드는 200 success()(data:null) 또는 204(빈 본문) 가능 — APIClient.decodeEnvelope 는
    // data 키 부재로 HTTP_200/NO_CONTENT 를 throw 한다. 둘 다 성공이므로 흡수하고 나머지는 전파
    // (myActiveGroup nil 정규화 패턴 모방).
    func leaveGroup(groupId: Int) async throws {
        do {
            _ = try await client.request(
                "/groups/\(groupId)/members/me",
                method: "DELETE",
                type: EmptyResponse.self
            )
        } catch let error as APIError where error.code == "HTTP_200" || error.code == "NO_CONTENT" {
            return
        }
    }
}
