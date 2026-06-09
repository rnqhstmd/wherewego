import Foundation

// 채팅 도메인 API(설계 §2 / DM 그룹별 전환). 모든 호출은 APIClient.request 경유(`api/v1` 자동 부착).
// 백엔드 계약(ChatV1Controller/ChatV1Dto)과 1:1 정합:
// - GET  /chat/bot/rooms                                  → [BotRoomSummary](활성 그룹별, 가상항목 포함)
// - GET  /chat/bot/{groupId}/messages?cursor=&limit=
// - POST /chat/bot/{groupId}/messages              body {"text": ...}
// - GET  /chat/couple/{groupId}/messages?cursor=&limit=
// - POST /chat/couple/{groupId}/messages          body {"text": ...}
// 구버전 비그룹 봇 엔드포인트(/chat/bot/messages)는 백엔드 @Deprecated — iOS 미소비(그룹별로 전환).
// cursor+limit 는 path 에 직접 조합한다(Q6 확정, PlaceAPI 선례). cursor nil 이면 생략, limit 항상 부착.
// limit 기본 20(BR-4) — 호출부에서 전달, 서버가 1~50 클램프.

// MARK: - 요청 DTO

/// 봇/커플 공통 메시지 전송 요청. 백엔드 BotMessageRequest/CoupleMessageRequest 대칭({"text": ...}).
/// 길이 제약(봇 2000자/커플 1000자, BR-3)은 ViewModel 에서 검증·서버가 재검증.
struct SendMessageRequest: Encodable {
    let text: String
}

// MARK: - ChatAPIProtocol

protocol ChatAPIProtocol: Sendable {
    /// GET /chat/bot/rooms — 내 활성 그룹별 봇 방 요약(가입 순, 봇 방 없는 그룹은 가상항목 포함).
    func botRooms() async throws -> [BotRoomSummary]
    /// GET /chat/bot/{groupId}/messages — 그룹별 봇 방 메시지 페이지(최신순). cursor=null 이면 최신 N건.
    func botMessages(groupId: Int, cursor: Int?, limit: Int) async throws -> MessagesResponse
    /// POST /chat/bot/{groupId}/messages — 그룹별 봇 방 전송. 응답은 PROCESSING 플레이스홀더 messageId/kind.
    func sendBotMessage(groupId: Int, text: String) async throws -> SendMessageResponse
    /// GET /chat/couple/{groupId}/messages — 커플 방 메시지 페이지(최신순).
    func coupleMessages(groupId: Int, cursor: Int?, limit: Int) async throws -> MessagesResponse
    /// POST /chat/couple/{groupId}/messages — 커플 방 전송. 응답은 저장된 메시지 messageId/kind.
    func sendCoupleMessage(groupId: Int, text: String) async throws -> SendMessageResponse
}

// MARK: - ChatAPI

final class ChatAPI: ChatAPIProtocol {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    /// GET /chat/bot/rooms — 내 활성 그룹별 봇 방 요약(가입 순). 그룹 0개(data null/204)는 빈 배열로 정규화.
    // 그룹 없음(data null/204) → 빈 목록. 나머지(FAIL·4xx/5xx)는 진짜 에러 전파(GroupAPI.listMyGroups 동치).
    //  · 200 SUCCESS + data null → code="HTTP_200" / 204 → "NO_CONTENT" → [].
    //  · 401 → throw(상위 refresh/logout). 그 외 errorCode·4xx/5xx → throw.
    func botRooms() async throws -> [BotRoomSummary] {
        do {
            return try await client.request("/chat/bot/rooms", type: [BotRoomSummary].self)
        } catch let error as APIError {
            if error.status == 401 { throw error }                       // 인증 만료 → 상위(refresh/logout)
            if error.code == "HTTP_200" || error.code == "NO_CONTENT" { return [] }
            throw error
        }
    }

    func botMessages(groupId: Int, cursor: Int?, limit: Int) async throws -> MessagesResponse {
        try await client.request(
            "/chat/bot/\(groupId)/messages" + Self.pageQuery(cursor: cursor, limit: limit),
            type: MessagesResponse.self
        )
    }

    func sendBotMessage(groupId: Int, text: String) async throws -> SendMessageResponse {
        let body = try JSONEncoder().encode(SendMessageRequest(text: text))
        return try await client.request(
            "/chat/bot/\(groupId)/messages",
            method: "POST",
            body: body,
            type: SendMessageResponse.self
        )
    }

    func coupleMessages(groupId: Int, cursor: Int?, limit: Int) async throws -> MessagesResponse {
        try await client.request(
            "/chat/couple/\(groupId)/messages" + Self.pageQuery(cursor: cursor, limit: limit),
            type: MessagesResponse.self
        )
    }

    func sendCoupleMessage(groupId: Int, text: String) async throws -> SendMessageResponse {
        let body = try JSONEncoder().encode(SendMessageRequest(text: text))
        return try await client.request(
            "/chat/couple/\(groupId)/messages",
            method: "POST",
            body: body,
            type: SendMessageResponse.self
        )
    }

    /// cursor+limit query string 조합(설계 §2). cursor nil 이면 생략, limit 항상 부착.
    /// 값은 Int 라 인젝션 위험이 없어 percent 인코딩 불필요(APIClient.makeURL 의 percentEncodedQuery 보존).
    private static func pageQuery(cursor: Int?, limit: Int) -> String {
        if let cursor {
            return "?cursor=\(cursor)&limit=\(limit)"
        }
        return "?limit=\(limit)"
    }
}
