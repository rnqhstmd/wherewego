import Foundation

// 핀 도메인 API 및 DTO(설계 §2). 모든 호출은 APIClient 경유.
// 백엔드 계약(PinV1Controller/PinV1Dto)과 1:1 정합:
// - id/groupId/createdBy/memoUpdatedBy: Long → Int (CONSIDER: iOS17 64bit, ActiveGroup.groupId 선례).
// - latitude/longitude: BigDecimal → Double (7자리 반올림 안전, 수용 리스크).
// - createdAt/visitedAt: ZonedDateTime → String (Jackson ISO-8601, application.yml 커스텀 없음).

// MARK: - 응답 DTO

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
}

/// 본문 없는 성공 응답(204) 용 빈 디코더. APIClient 가 `{}` envelope 로 디코딩.
struct EmptyResponse: Decodable {}
