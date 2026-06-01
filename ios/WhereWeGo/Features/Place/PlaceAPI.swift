import Foundation

// 장소 검색 API 및 DTO(설계 §2). 백엔드 PlaceV1Controller/PlaceV1Dto 와 1:1 정합.
// - latitude/longitude: BigDecimal → Double (PinSummary 와 동일 수용 리스크).

// MARK: - 응답 DTO

/// 백엔드 PlaceV1Dto.PlaceItem 과 필드명·옵셔널 1:1.
struct PlaceItem: Decodable, Identifiable, Equatable {
    let placeName: String
    let address: String?
    let latitude: Double
    let longitude: Double

    /// 검색 결과는 백엔드 ID 가 없으므로 좌표+이름 조합으로 식별(리스트 렌더용).
    var id: String { "\(placeName)|\(latitude),\(longitude)" }
}

/// 백엔드 PlaceV1Dto.PlaceSearchResponse — Single/Multiple/Empty 를 평탄화한 items 배열.
struct PlaceSearchResponse: Decodable {
    let items: [PlaceItem]
}

// MARK: - PlaceAPIProtocol

protocol PlaceAPIProtocol: Sendable {
    /// GET /places/search?q={keyword}.
    func search(_ keyword: String) async throws -> [PlaceItem]
}

// MARK: - PlaceAPI

final class PlaceAPI: PlaceAPIProtocol {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    /// q 값 전용 인코딩 문자셋(보안). urlQueryAllowed 에서 query 구분자(`=&+#?`)를 빼서
    /// makeURL 의 percentEncodedQuery 대입 시 파라미터 인젝션(예: `a&b=c`)을 차단한다.
    /// (`+` 는 일부 디코더가 공백으로 해석하므로 인코딩 대상에 포함.)
    private static let queryValueAllowed: CharacterSet =
        CharacterSet.urlQueryAllowed.subtracting(CharacterSet(charactersIn: "=&+#?"))

    func search(_ keyword: String) async throws -> [PlaceItem] {
        let encoded = keyword.addingPercentEncoding(
            withAllowedCharacters: Self.queryValueAllowed
        ) ?? keyword
        let response: PlaceSearchResponse = try await client.request(
            "/places/search?q=\(encoded)",
            type: PlaceSearchResponse.self
        )
        return response.items
    }
}
