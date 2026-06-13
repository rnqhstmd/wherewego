import Foundation

// 핀 도메인 API 및 DTO(설계 §2). 모든 호출은 APIClient 경유.
// 백엔드 계약(PinV1Controller/PinV1Dto)과 1:1 정합:
// - id/groupId/createdBy/memoUpdatedBy: Long → Int (CONSIDER: iOS17 64bit, ActiveGroup.groupId 선례).
// - latitude/longitude: BigDecimal → Double (7자리 반올림 안전, 수용 리스크).
// - createdAt/visitedAt: ZonedDateTime → String (Jackson ISO-8601, application.yml 커스텀 없음).

// MARK: - 응답 DTO

/// 백엔드 PinV1Dto.VisitorResponse 와 1:1(정책 v2 FR-B4). 핀 방문자 1명.
/// source(SELF|TAGGED)는 표시에 쓰지 않으나 백엔드 계약 보존을 위해 String? 으로 받는다(미지값 안전).
/// - userId: Long → Int(PinSummary 선례). profileImageUrl 없으면 nil → AvatarView 이니셜 폴백.
struct PinVisitor: Decodable, Equatable, Identifiable {
    let userId: Int
    let nickname: String?
    let profileImageUrl: String?
    let source: String?

    /// Identifiable(아바타 스택 ForEach) — userId 식별.
    var id: Int { userId }
}

/// 백엔드 PinV1Dto.PinSummaryResponse 와 필드명·옵셔널 1:1(AC-3).
struct PinSummary: Decodable, Identifiable, Equatable {
    let id: Int
    let groupId: Int
    let createdBy: Int
    let createdByNickname: String?
    let placeName: String
    let address: String?
    let latitude: Double
    let longitude: Double
    let instagramUrl: String?
    let memo: String?
    let memoSource: MemoSource?
    let tag: PinTag
    let createdAt: String
    let visitedAt: String?
    let memoUpdatedBy: Int?
    let memoUpdatedByNickname: String?
    let photoUrl: String?
    let photoThumbnailUrl: String?
    /// 정책 v2 FR-B4: 방문자 목록(추가형 계약). 구서버는 키 부재 → nil(decodeIfPresent). 0명이면 빈 배열.
    let visitors: [PinVisitor]?

    private enum CodingKeys: String, CodingKey {
        case id, groupId, createdBy, createdByNickname, placeName, address
        case latitude, longitude, instagramUrl, memo, memoSource, tag
        case createdAt, visitedAt, memoUpdatedBy, memoUpdatedByNickname
        case photoUrl, photoThumbnailUrl, visitors
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(Int.self, forKey: .id)
        self.groupId = try c.decode(Int.self, forKey: .groupId)
        self.createdBy = try c.decode(Int.self, forKey: .createdBy)
        self.createdByNickname = try c.decodeIfPresent(String.self, forKey: .createdByNickname)
        self.placeName = try c.decode(String.self, forKey: .placeName)
        self.address = try c.decodeIfPresent(String.self, forKey: .address)
        self.latitude = try c.decode(Double.self, forKey: .latitude)
        self.longitude = try c.decode(Double.self, forKey: .longitude)
        self.instagramUrl = try c.decodeIfPresent(String.self, forKey: .instagramUrl)
        self.memo = try c.decodeIfPresent(String.self, forKey: .memo)
        self.memoSource = try c.decodeIfPresent(MemoSource.self, forKey: .memoSource)
        self.tag = try c.decode(PinTag.self, forKey: .tag)
        self.createdAt = try c.decode(String.self, forKey: .createdAt)
        self.visitedAt = try c.decodeIfPresent(String.self, forKey: .visitedAt)
        self.memoUpdatedBy = try c.decodeIfPresent(Int.self, forKey: .memoUpdatedBy)
        self.memoUpdatedByNickname = try c.decodeIfPresent(String.self, forKey: .memoUpdatedByNickname)
        self.photoUrl = try c.decodeIfPresent(String.self, forKey: .photoUrl)
        self.photoThumbnailUrl = try c.decodeIfPresent(String.self, forKey: .photoThumbnailUrl)
        // 추가형 계약(FR-B4) — 구서버는 키 부재 → nil.
        self.visitors = try c.decodeIfPresent([PinVisitor].self, forKey: .visitors)
    }

    /// 메모리 직접 생성(테스트/낙관 프레임용). custom init(from:) 도입으로 사라진 멤버와이즈 init 을
    /// 명시 제공하되, visitors 는 기본값(nil)을 주어 기존 호출을 무수정 유지한다.
    init(
        id: Int,
        groupId: Int,
        createdBy: Int,
        createdByNickname: String?,
        placeName: String,
        address: String?,
        latitude: Double,
        longitude: Double,
        instagramUrl: String?,
        memo: String?,
        memoSource: MemoSource?,
        tag: PinTag,
        createdAt: String,
        visitedAt: String?,
        memoUpdatedBy: Int?,
        memoUpdatedByNickname: String?,
        photoUrl: String?,
        photoThumbnailUrl: String?,
        visitors: [PinVisitor]? = nil
    ) {
        self.id = id
        self.groupId = groupId
        self.createdBy = createdBy
        self.createdByNickname = createdByNickname
        self.placeName = placeName
        self.address = address
        self.latitude = latitude
        self.longitude = longitude
        self.instagramUrl = instagramUrl
        self.memo = memo
        self.memoSource = memoSource
        self.tag = tag
        self.createdAt = createdAt
        self.visitedAt = visitedAt
        self.memoUpdatedBy = memoUpdatedBy
        self.memoUpdatedByNickname = memoUpdatedByNickname
        self.photoUrl = photoUrl
        self.photoThumbnailUrl = photoThumbnailUrl
        self.visitors = visitors
    }
}

/// 백엔드 PinV1Dto.DeclareVisitResponse 와 1:1(정책 v2 FR-B2/B3). 방문 선언 응답.
/// converted/alreadyConverted 로 클라이언트가 confetti/합산 토스트를 분기한다. visitors 는 갱신 후 명단.
struct DeclareVisitResponse: Decodable {
    let converted: Bool
    let alreadyConverted: Bool
    let visitors: [PinVisitor]
}

/// 백엔드 PinV1Dto.PinListResponse. legacy 모드는 items 만(totalCount/hasNext null).
struct PinListResponse: Decodable {
    let items: [PinSummary]
    let totalCount: Int?
    let hasNext: Bool?
}

/// 백엔드 PinV1Dto.UpdatePinResponse — PATCH 응답은 summary 를 중첩한 형태.
/// transitionedToMemoryNow 는 본 PATCH 가 실제 WISH/REEL → MEMORY 전환을 일으켰는지(AC-15 분기).
struct UpdatePinResponse: Decodable {
    let summary: PinSummary
    let transitionedToMemoryNow: Bool
}

// MARK: - 요청 DTO

/// 핀 직접 등록 요청(백엔드 CreatePinRequest 대칭). 단순 Encodable — 미설정 옵셔널은 null 직렬화되지만,
/// 백엔드 toCommand() 가 빈 문자열/null 을 정규화하므로 안전.
struct CreatePinRequest: Encodable {
    let placeName: String
    let address: String?
    let latitude: Double
    let longitude: Double
    let instagramUrl: String?
    let memo: String?
    let tag: PinTag
}

/// 방문 선언 요청(정책 v2 FR-B2/B3). 백엔드 DeclareVisitRequest({"companionUserIds":[...]}) 대칭.
/// companionUserIds 는 본인 제외 동행 명단. 빈 배열/생략 = 혼자(체크인 또는 1인 그룹 전환).
/// 서버가 본인 자동 제거·그룹 멤버 검증을 하므로 클라는 선택 명단을 그대로 전달한다.
struct DeclareVisitRequest: Encodable {
    let companionUserIds: [Int]
}

/// 부분 수정 요청(MUST-2). 백엔드 UpdatePinRequest 는 JsonNode 로 "키 없음 vs null vs 빈문자열" 을 구분한다.
/// Swift JSONEncoder 는 nil 옵셔널을 `{"memo":null}` 로 내보내 "미변경"이 "null 로 변경"으로 오인된다.
/// → custom encode(to:) 로 **설정된 필드 키만** 직렬화하고 미설정 키는 생략한다.
/// 설정/미설정 구분은 Field<T> 래퍼(.unset / .set(value))로 표현한다.
struct UpdatePinRequest: Encodable {

    /// 부분 수정 필드의 "미설정 vs 설정(값 포함, null 허용 안 함)" 표현.
    enum Field<Value: Encodable> {
        case unset
        case set(Value)
    }

    var memo: Field<String> = .unset
    var tag: Field<PinTag> = .unset
    var placeName: Field<String> = .unset

    private enum CodingKeys: String, CodingKey {
        case memo, tag, placeName
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        if case let .set(value) = memo {
            try container.encode(value, forKey: .memo)
        }
        if case let .set(value) = tag {
            try container.encode(value, forKey: .tag)
        }
        if case let .set(value) = placeName {
            try container.encode(value, forKey: .placeName)
        }
    }
}

// MARK: - PinAPIProtocol

protocol PinAPIProtocol: Sendable {
    /// GET /groups/{groupId}/pins (legacy {items} 모드 — page/size 미전달).
    func list(groupId: Int) async throws -> [PinSummary]
    /// POST /groups/{groupId}/pins (201).
    func create(groupId: Int, request: CreatePinRequest) async throws -> PinSummary
    /// PATCH /groups/{groupId}/pins/{pinId}.
    func update(groupId: Int, pinId: Int, request: UpdatePinRequest) async throws -> UpdatePinResponse
    /// DELETE /groups/{groupId}/pins/{pinId} (204).
    func delete(groupId: Int, pinId: Int) async throws
    /// POST /groups/{groupId}/pins/{pinId}/photo (multipart, image/jpeg).
    func uploadPhoto(groupId: Int, pinId: Int, imageData: Data) async throws -> PinSummary
    /// DELETE /groups/{groupId}/pins/{pinId}/photo.
    func deletePhoto(groupId: Int, pinId: Int) async throws -> PinSummary
    /// POST /groups/{groupId}/pins/{pinId}/visits (정책 v2 FR-B2/B3). 방문 선언(혼자=체크인/동행=전환).
    func declareVisit(groupId: Int, pinId: Int, companionUserIds: [Int]) async throws -> DeclareVisitResponse
}

/// declareVisit 기본 구현(정책 v2) — 기존 테스트 스텁(미구현)의 프로토콜 정합을 유지한다.
/// GroupAPIProtocol.previewBySlug 패턴 동치(스텁 무수정). 실제 호출 경로(MapViewModel.submitVisit)는 PinAPI 구현이 override 한다.
/// 방문 분기를 검증하는 스텁(StubVisitPinAPI)은 본 메서드를 명시 override 한다.
extension PinAPIProtocol {
    func declareVisit(groupId: Int, pinId: Int, companionUserIds: [Int]) async throws -> DeclareVisitResponse {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "declareVisit 미지원")
    }
}

// MARK: - PinAPI

final class PinAPI: PinAPIProtocol {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    func list(groupId: Int) async throws -> [PinSummary] {
        let response: PinListResponse = try await client.request(
            "/groups/\(groupId)/pins",
            type: PinListResponse.self
        )
        return response.items
    }

    func create(groupId: Int, request: CreatePinRequest) async throws -> PinSummary {
        let body = try JSONEncoder().encode(request)
        return try await client.request(
            "/groups/\(groupId)/pins",
            method: "POST",
            body: body,
            type: PinSummary.self
        )
    }

    func update(
        groupId: Int,
        pinId: Int,
        request: UpdatePinRequest
    ) async throws -> UpdatePinResponse {
        let body = try JSONEncoder().encode(request)
        return try await client.request(
            "/groups/\(groupId)/pins/\(pinId)",
            method: "PATCH",
            body: body,
            type: UpdatePinResponse.self
        )
    }

    func delete(groupId: Int, pinId: Int) async throws {
        // DELETE 는 204(빈 본문) 정상 성공 — APIClient.decodeEnvelope 는 빈 본문에서 data 키 부재로
        // NO_CONTENT 를 throw 한다. 204 자체는 성공이므로 NO_CONTENT 만 정상으로 흡수하고 나머지는 전파.
        do {
            _ = try await client.request(
                "/groups/\(groupId)/pins/\(pinId)",
                method: "DELETE",
                type: EmptyResponse.self
            )
        } catch let error as APIError where error.code == "NO_CONTENT" {
            return
        }
    }

    func uploadPhoto(groupId: Int, pinId: Int, imageData: Data) async throws -> PinSummary {
        try await client.upload(
            "/groups/\(groupId)/pins/\(pinId)/photo",
            fileData: imageData,
            fileName: "photo.jpg",
            fieldName: "file",
            mimeType: "image/jpeg",
            type: PinSummary.self
        )
    }

    func deletePhoto(groupId: Int, pinId: Int) async throws -> PinSummary {
        try await client.request(
            "/groups/\(groupId)/pins/\(pinId)/photo",
            method: "DELETE",
            type: PinSummary.self
        )
    }

    func declareVisit(groupId: Int, pinId: Int, companionUserIds: [Int]) async throws -> DeclareVisitResponse {
        let body = try JSONEncoder().encode(DeclareVisitRequest(companionUserIds: companionUserIds))
        return try await client.request(
            "/groups/\(groupId)/pins/\(pinId)/visits",
            method: "POST",
            body: body,
            type: DeclareVisitResponse.self
        )
    }
}

/// 본문 없는 성공 응답(204) 용 빈 디코더. APIClient 가 `{}` envelope 로 디코딩.
struct EmptyResponse: Decodable {}
