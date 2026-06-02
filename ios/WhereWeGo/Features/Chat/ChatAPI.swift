import Foundation

// 채팅 도메인 API(설계 §2). 모든 호출은 APIClient.request 경유(`api/v1` 자동 부착).
// 백엔드 계약(ChatV1Controller/ChatV1Dto)과 1:1 정합:
// - GET  /chat/bot/messages?cursor=&limit=
// - POST /chat/bot/messages                       body {"text": ...}
// - GET  /chat/couple/{groupId}/messages?cursor=&limit=
// - POST /chat/couple/{groupId}/messages          body {"text": ...}
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
    /// GET /chat/bot/messages — 봇 방 메시지 페이지(최신순). cursor=null 이면 최신 N건.
    func botMessages(cursor: Int?, limit: Int) async throws -> MessagesResponse
    /// POST /chat/bot/messages — 봇 방 전송. 응답은 PROCESSING 플레이스홀더 messageId/kind.
    func sendBotMessage(text: String) async throws -> SendMessageResponse
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

    func botMessages(cursor: Int?, limit: Int) async throws -> MessagesResponse {
        try await client.request(
            "/chat/bot/messages" + Self.pageQuery(cursor: cursor, limit: limit),
            type: MessagesResponse.self
        )
    }

    func sendBotMessage(text: String) async throws -> SendMessageResponse {
        let body = try JSONEncoder().encode(SendMessageRequest(text: text))
        return try await client.request(
            "/chat/bot/messages",
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
