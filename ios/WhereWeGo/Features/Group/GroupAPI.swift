import Foundation

// 그룹 관련 DTO 및 호출(설계 §9). 모든 호출은 APIClient.request 경유.

// MARK: - 응답 DTO

/// 활성 그룹. 없으면 nil(myActiveGroup 에서 정규화).
struct ActiveGroup: Decodable {
    let groupId: Int
    let name: String
    let memberCount: Int
}

/// 내 소속 그룹 1건(GET /groups 목록 항목, FR-6). 백엔드 GroupSummaryResponse 와 짝.
/// 그룹 전환 시트(GroupSwitcherSheet)가 목록·활성 표시에 사용한다. Identifiable=groupId(List 식별).
/// 백엔드는 createdAt(ZonedDateTime) 도 내려주지만 화면 미사용이라 ActiveGroup 과 동일하게 디코드 대상에서 제외한다
/// (Decodable 은 응답에 없는 키만 문제 — 추가 키는 무시되므로 안전).
struct GroupSummary: Decodable, Identifiable, Equatable {
    let groupId: Int
    let name: String
    let memberCount: Int

    /// List/ForEach 식별자. 그룹 id 가 유일하다.
    var id: Int { groupId }
}

/// 그룹 생성 응답(POST /groups). 생성 직후 멤버는 생성자 1명(FR-18).
struct GroupCreated: Decodable {
    let groupId: Int
    let name: String
    var createdAt: String? = nil
}

/// 초대 링크 발급 응답.
struct InviteLink: Decodable {
    let token: String
    let slug: String?
    let shareUrl: String?
    // expiresAt 기본값 nil — mock 의 멤버와이즈 호출(token:slug:shareUrl:) 호환 유지(FR-18).
    var expiresAt: String? = nil
}

/// 초대 수락 응답.
struct InviteAccept: Decodable {
    let groupId: Int
}

/// slug 프리뷰 응답(token 획득). 사용자는 slug 입력, token 은 여기서만 획득(BR-1).
struct InvitePreview: Decodable, Equatable {
    let token: String
    let groupName: String
    let inviterNickname: String?
    let expiresAt: String?
}

// MARK: - 요청 DTO

/// 그룹 생성 요청. 이름은 trim 후 1~30자 검증을 통과해야 한다(백엔드 GROUP_NAME_INVALID 규칙).
private struct CreateGroupRequest: Encodable {
    let name: String
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

    /// GET /groups — 내 소속 그룹 목록(FR-6). envelope.data 가 GroupSummaryResponse 배열.
    /// 그룹 0개면 빈 배열(백엔드가 success([]) → data 가 [] 이므로 throw 없이 [] 반환).
    /// 그룹 전환 시트 진입 시점에만 호출한다(BR-6 — 앱 시작 시 미리 로드 X).
    func listMyGroups() async throws -> [GroupSummary] {
        try await client.request("/groups", type: [GroupSummary].self)
    }

    /// POST /groups (201) — 그룹 생성, 생성자가 첫 멤버가 된다(FR-18).
    // 이름 검증(trim 1~30자)은 호출 전 ViewModel 에서 수행한다. 위반 시 백엔드가 GROUP_NAME_INVALID 반환.
    func createGroup(name: String) async throws -> GroupCreated {
        let body = try JSONEncoder().encode(CreateGroupRequest(name: name))
        return try await client.request(
            "/groups",
            method: "POST",
            body: body,
            type: GroupCreated.self
        )
    }

    /// GET /groups/invite-links/by-slug/{slug} — slug 로 프리뷰(token 획득, FR-3).
    // 백엔드는 만료/없음/그룹삭제를 모두 404 INVITE_LINK_NOT_FOUND 로 통합, 정원 도달 시 409 GROUP_CAPACITY_EXCEEDED.
    func previewBySlug(slug: String) async throws -> InvitePreview {
        // slug 는 path segment 이므로 percent-encoding. 실패 시 원본 fallback.
        let encoded = slug.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? slug
        return try await client.request(
            "/groups/invite-links/by-slug/\(encoded)",
            type: InvitePreview.self
        )
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
