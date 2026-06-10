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

/// GC-2 그룹 메시지 전송 요청(설계 §0). kind 분기 — TEXT 는 text(1~2000자), REEL_LINK 는 url(https+인스타).
/// 미사용 필드는 자동 합성 Encodable 의 encodeIfPresent 로 생략된다(nil → 키 누락 → 백엔드 record null).
struct GroupMessageRequest: Encodable {
    let kind: MessageKind
    let text: String?
    let url: String?
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

    // MARK: GC-2 그룹 채팅(설계 §0 — /chat/groups). 봇/커플 메서드는 GC-3 제거 예정 dead.

    /// GET /chat/groups — 내 활성 그룹별 그룹 채팅방 목록(FR-GC2-1). 그룹 0개 → 빈 배열.
    func groupRooms() async throws -> [GroupRoomSummary]
    /// GET /chat/groups/{groupId}/messages — 그룹 방 메시지 페이지(최신순, id DESC). cursor=nil 이면 최신 N건.
    func groupMessages(groupId: Int, cursor: Int?, limit: Int) async throws -> GroupMessagesResponse
    /// POST /chat/groups/{groupId}/messages — TEXT/REEL_LINK 전송. 저장 메시지 {messageId, kind} 반환.
    func sendGroupMessage(groupId: Int, kind: MessageKind, text: String?, url: String?) async throws -> SendMessageResponse
    /// POST /chat/groups/{groupId}/messages/{messageId}/extract — 발신자 온디맨드 릴스 장소 추출(동기, 15s).
    func extractGroupReelPlaces(groupId: Int, messageId: Int) async throws -> PlaceCardsPayload
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

    // MARK: - GC-2 그룹 채팅 구현(설계 §0)

    /// GET /chat/groups — 그룹 0개(data null/204)는 빈 배열로 정규화(botRooms 동치). 401 은 상위(refresh/logout) 전파.
    func groupRooms() async throws -> [GroupRoomSummary] {
        do {
            return try await client.request("/chat/groups", type: [GroupRoomSummary].self)
        } catch let error as APIError {
            if error.status == 401 { throw error }
            if error.code == "HTTP_200" || error.code == "NO_CONTENT" { return [] }
            throw error
        }
    }

    func groupMessages(groupId: Int, cursor: Int?, limit: Int) async throws -> GroupMessagesResponse {
        try await client.request(
            "/chat/groups/\(groupId)/messages" + Self.pageQuery(cursor: cursor, limit: limit),
            type: GroupMessagesResponse.self
        )
    }

    func sendGroupMessage(groupId: Int, kind: MessageKind, text: String?, url: String?) async throws -> SendMessageResponse {
        let body = try JSONEncoder().encode(GroupMessageRequest(kind: kind, text: text, url: url))
        return try await client.request(
            "/chat/groups/\(groupId)/messages",
            method: "POST",
            body: body,
            type: SendMessageResponse.self
        )
    }

    /// 추출은 body 없는 POST(서버가 messageId 경로로 식별). deadline 15초는 서버측(APIClient 타임아웃 내).
    func extractGroupReelPlaces(groupId: Int, messageId: Int) async throws -> PlaceCardsPayload {
        try await client.request(
            "/chat/groups/\(groupId)/messages/\(messageId)/extract",
            method: "POST",
            type: PlaceCardsPayload.self
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
