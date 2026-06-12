import Foundation

// 그룹 관련 DTO 및 호출(설계 §9). 모든 호출은 APIClient.request 경유.

// MARK: - 응답 DTO

/// 활성 그룹. 없으면 nil(myActiveGroup 에서 정규화).
struct ActiveGroup: Decodable {
    let groupId: Int
    let name: String
    let memberCount: Int
}

/// 그룹 목록 멤버 프리뷰(GP-1 FR-4). 백엔드 GroupSummaryResponse.members[*] 와 1:1.
/// 가입순 아바타 일렬·콜라주 셀 입력. profileImageUrl 없으면 nil → 이니셜 폴백(AvatarView).
/// - userId: Long → Int(GroupMemberItem 선례). decodeIfPresent 불필요(목록 응답은 항상 포함).
struct GroupMemberPreview: Decodable, Equatable, Identifiable {
    let userId: Int
    let nickname: String
    let profileImageUrl: String?

    /// Identifiable(아바타 일렬 ForEach) — userId 식별.
    var id: Int { userId }
}

/// 내 그룹 목록 항목(GM-2, GET /groups). 백엔드 GroupSummary(record) 와 1:1 정합.
/// - createdAt: 그룹 생성 시각(ISO8601 문자열). 현재 목록 표시엔 미사용이나 백엔드 필드 보존(향후 정렬용).
/// - memberCount: 백엔드 long → iOS Int.
/// - imageUrl/imageThumbUrl/members: GP-1 FR-4(그룹 대표 이미지·멤버 프리뷰). 구서버 호환 위해 decodeIfPresent.
struct GroupSummary: Decodable, Identifiable {
    let groupId: Int
    let name: String
    let createdAt: String?
    let memberCount: Int
    /// 그룹 대표 이미지 원본 URL(미지정/구서버 → nil → 콜라주 폴백).
    let imageUrl: String?
    /// 그룹 대표 이미지 썸네일 URL(목록 렌더 우선). 미지정/구서버 → nil.
    let imageThumbUrl: String?
    /// 활성 멤버 프리뷰(가입순). 구서버 → 빈 배열.
    let members: [GroupMemberPreview]

    /// Identifiable(GroupListView ForEach) — groupId 를 식별자로 사용.
    var id: Int { groupId }

    private enum CodingKeys: String, CodingKey {
        case groupId, name, createdAt, memberCount, imageUrl, imageThumbUrl, members
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.groupId = try c.decode(Int.self, forKey: .groupId)
        self.name = try c.decode(String.self, forKey: .name)
        self.createdAt = try c.decodeIfPresent(String.self, forKey: .createdAt)
        self.memberCount = try c.decode(Int.self, forKey: .memberCount)
        self.imageUrl = try c.decodeIfPresent(String.self, forKey: .imageUrl)
        self.imageThumbUrl = try c.decodeIfPresent(String.self, forKey: .imageThumbUrl)
        self.members = try c.decodeIfPresent([GroupMemberPreview].self, forKey: .members) ?? []
    }

    /// 메모리 직접 생성(테스트/프리뷰용). custom init(from:) 도입으로 사라진 멤버와이즈 init 을
    /// 명시 제공하되, GP-1 신규 필드는 기본값을 주어 기존 호출(groupId/name/createdAt/memberCount)을 무수정 유지.
    init(
        groupId: Int,
        name: String,
        createdAt: String?,
        memberCount: Int,
        imageUrl: String? = nil,
        imageThumbUrl: String? = nil,
        members: [GroupMemberPreview] = []
    ) {
        self.groupId = groupId
        self.name = name
        self.createdAt = createdAt
        self.memberCount = memberCount
        self.imageUrl = imageUrl
        self.imageThumbUrl = imageThumbUrl
        self.members = members
    }
}

/// 그룹 생성 응답(GP-1 FR-1, POST /groups). 백엔드 GroupCreatedResponse 와 1:1.
/// - groupId: Long → Int. 생성 직후 대표 이미지 업로드(2단계)의 대상.
/// - createdAt: 생성 시각(ISO8601). 현재 미사용이나 백엔드 필드 보존.
struct GroupCreatedResponse: Decodable {
    let groupId: Int
    let name: String
    let createdAt: String?
}

/// 그룹 생성 요청 body(GP-1, POST /groups). 백엔드 CreateGroupRequest({"name": ...}) 대칭.
private struct CreateGroupRequest: Encodable {
    let name: String
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

/// 그룹 대표 이미지 업로드/제거 응답(GP-1 FR-1/FR-2). 백엔드 GroupImageResponse 와 1:1.
/// 제거 시 두 필드 모두 null → nil(콜라주 폴백).
struct GroupImageResponse: Decodable {
    let imageUrl: String?
    let imageThumbUrl: String?
}

/// 초대 코드(slug) 미리보기 응답(IC-2, GET /groups/invite-links/by-slug/{slug}).
/// 백엔드 InviteLinkPreviewResponse 와 1:1. 확보한 token 으로 합류(acceptInvite)를 진행한다.
struct InviteLinkPreview: Decodable {
    let token: String
    let groupName: String
    let inviterNickname: String?
    let expiresAt: String?
}

/// 그룹원 1명(D단계, GET /groups/{id}/members). 백엔드 MemberResponse 와 1:1 정합.
/// - userId: Long → Int. 방장 판정 키(CurrentUser.id 비교).
/// - joinedAt: 가입 시각(ISO8601). 현재 표시엔 미사용이나 백엔드 필드 보존.
/// - isOwner: 방장(활성 멤버 중 가입 순 첫 항목) 여부. 백엔드가 조회 시점 계산해 마킹.
struct GroupMemberItem: Decodable, Identifiable, Equatable {
    let userId: Int
    let nickname: String
    let joinedAt: String?
    let isOwner: Bool
    /// GP-1 FR-9: 유효 프사 URL(없음/구서버 → nil → 이니셜 폴백). 옵셔널이라 합성 Decodable 이 구서버 응답을 안전 흡수.
    let profileImageUrl: String?

    /// Identifiable(GroupManageView 멤버 목록 ForEach) — userId 를 식별자로 사용.
    var id: Int { userId }
}

/// 그룹명 수정 요청 body(D단계, PATCH /groups/{id}).
private struct UpdateGroupNameRequest: Encodable {
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

    /// POST /groups (201) — 새 그룹 생성(GP-1 FR-1). 생성자가 첫 멤버(방장). 응답 groupId 로 이미지 업로드(2단계).
    func createGroup(name: String) async throws -> GroupCreatedResponse {
        let body = try JSONEncoder().encode(CreateGroupRequest(name: name))
        return try await client.request("/groups", method: "POST", body: body, type: GroupCreatedResponse.self)
    }

    /// POST /groups/invite-links/{token}/accept
    func acceptInvite(token: String) async throws -> InviteAccept {
        try await client.request(
            "/groups/invite-links/\(token)/accept",
            method: "POST",
            type: InviteAccept.self
        )
    }

    /// GET /groups/invite-links/by-slug/{slug} (IC-2). slug → token·groupName 확보(공개 미리보기).
    /// slug 는 base56(URL-safe)이라 별도 인코딩 불필요. 401 은 상위(refresh/logout)로 전파.
    func previewBySlug(slug: String) async throws -> InviteLinkPreview {
        try await client.request(
            "/groups/invite-links/by-slug/\(slug)",
            type: InviteLinkPreview.self
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

    /// GET /groups/{groupId}/invite-links/current (IC-2 후속) — 현재 활성(미만료) 초대 링크.
    /// 발급과 달리 새 코드를 만들지 않는다(반복 호출 안전). 활성 코드 없음(data null/204) → nil(myActiveGroup 패턴).
    func currentInviteLink(groupId: Int) async throws -> InviteLink? {
        do {
            return try await client.request(
                "/groups/\(groupId)/invite-links/current",
                type: InviteLink.self
            )
        } catch let error as APIError {
            if error.status == 401 { throw error }
            if error.code == "HTTP_200" || error.code == "NO_CONTENT" { return nil }
            throw error
        }
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

    /// GET /groups/{groupId}/members (D단계). 가입 순 멤버 목록(첫 항목 방장).
    /// 백엔드는 List<MemberResponse>(data 배열)를 반환한다. 401 은 상위(refresh/logout)로 전파.
    func listMembers(groupId: Int) async throws -> [GroupMemberItem] {
        try await client.request(
            "/groups/\(groupId)/members",
            type: [GroupMemberItem].self
        )
    }

    /// PATCH /groups/{groupId} {name} (D단계). 활성 멤버면 누구나 이름 수정.
    // 백엔드는 성공 시 200(data 무관) 응답하므로 EmptyResponse 로 받되, data:null/204 는
    // leaveGroup 패턴대로 HTTP_200/NO_CONTENT 를 흡수한다(나머지 에러는 전파).
    func updateGroupName(groupId: Int, name: String) async throws {
        let body = try JSONEncoder().encode(UpdateGroupNameRequest(name: name))
        do {
            _ = try await client.request(
                "/groups/\(groupId)",
                method: "PATCH",
                body: body,
                type: EmptyResponse.self
            )
        } catch let error as APIError where error.code == "HTTP_200" || error.code == "NO_CONTENT" {
            return
        }
    }

    /// DELETE /groups/{groupId} (D단계). 방장만 — 비방장은 403(GROUP_OWNER_REQUIRED) 전파.
    // 200 success(data:null) 또는 204(빈 본문) 둘 다 성공 → HTTP_200/NO_CONTENT 흡수(leaveGroup 패턴).
    // 그 외(403 등)는 그대로 throw 해 호출측(VM)이 에러 메시지로 노출한다.
    func deleteGroup(groupId: Int) async throws {
        do {
            _ = try await client.request(
                "/groups/\(groupId)",
                method: "DELETE",
                type: EmptyResponse.self
            )
        } catch let error as APIError where error.code == "HTTP_200" || error.code == "NO_CONTENT" {
            return
        }
    }

    /// POST /groups/{groupId}/image (multipart, image/jpeg) — 그룹 대표 이미지 업로드(GP-1 FR-1).
    /// 핀 uploadPhoto 패턴(client.upload, fieldName "file"). 활성 멤버 아니면 403 전파.
    func uploadGroupImage(groupId: Int, jpegData: Data) async throws -> GroupImageResponse {
        try await client.upload(
            "/groups/\(groupId)/image",
            fileData: jpegData,
            fileName: "group.jpg",
            fieldName: "file",
            mimeType: "image/jpeg",
            type: GroupImageResponse.self
        )
    }

    /// DELETE /groups/{groupId}/image — 그룹 대표 이미지 제거(GP-1 FR-2). 응답 본문(null 필드) 디코드(핀 deletePhoto 패턴).
    func deleteGroupImage(groupId: Int) async throws -> GroupImageResponse {
        try await client.request(
            "/groups/\(groupId)/image",
            method: "DELETE",
            type: GroupImageResponse.self
        )
    }
}
