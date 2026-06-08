import Foundation

// 채팅 도메인 모델(설계 §1). 백엔드 계약과 1:1 정합:
// - REST `GET /chat/{bot|couple}/messages` 의 messages[] 와 STOMP MESSAGE body 가 동일한
//   백엔드 `ChatMessageFrame` 구조 → 단일 `ChatFrame` 으로 통합한다.
// - messageId/roomId: Long → Int (PinSummary 선례, iOS17 64bit 안전 수용 리스크).
// - createdAt: ZonedDateTime → String (ISO-8601 offset, PinSummary 동일).
// - payload 는 백엔드에서 kind 별 객체:
//     · PLACE_CARDS → {"cards":[{kakaoPlaceId,name,address,latitude,longitude}, ...]}
//     · TEXT/SYSTEM/MEMO_PROMPT → {"text":"..."}
//     · PROCESSING → {} (payload 비어 있음)
//   → `ChatFrame` 커스텀 디코딩이 payload 컨테이너를 kind 로 분기해 placeCards/text 로 평탄화한다.

// MARK: - 열거형

/// 백엔드 MessageKind 와 1:1. payload 스키마를 결정한다.
enum MessageKind: String, Codable {
    case TEXT
    case PLACE_CARDS
    case MEMO_PROMPT
    case PROCESSING
    case SYSTEM
}

/// 백엔드 SenderType 와 1:1. USER 사람, BOT 앱 봇, SYSTEM 시스템 안내/오류.
enum SenderType: String, Codable {
    case USER
    case BOT
    case SYSTEM
}

// MARK: - PLACE_CARDS payload

/// 백엔드 BotPlaceCardsPayloadBuilder.PlaceCard 와 필드명 1:1.
/// 좌표(latitude/longitude)는 없을 수 있다(없으면 핀 저장 비활성, 설계 §5).
/// latitude/longitude: Double? (BigDecimal/Double → Double, PinSummary 동일 수용 리스크).
struct PlaceCard: Decodable, Identifiable, Equatable {
    let kakaoPlaceId: String?
    let name: String
    let address: String?
    let latitude: Double?
    let longitude: Double?

    /// kakaoPlaceId 가 없을 수 있으므로 좌표+이름 조합으로 식별(리스트 렌더·선택 토글용).
    var id: String { kakaoPlaceId ?? "\(name)|\(latitude ?? 0),\(longitude ?? 0)" }
}

/// 백엔드 BotPlaceCardsPayloadBuilder.PlaceCardsPayload 와 1:1 (PLACE_CARDS payload 루트).
/// sourceInstagramUrl: 추출 출처 릴스 URL(저장 시 핀 instagramUrl 기록·"보러가기"). 구버전 백엔드 응답 호환 위해 옵셔널(BR-7).
struct PlaceCardsPayload: Decodable {
    let cards: [PlaceCard]
    let sourceInstagramUrl: String?
}

// MARK: - ChatFrame

/// REST messages[] / STOMP MESSAGE body 공통 메시지 프레임(백엔드 ChatMessageFrame 통합).
/// payload 는 kind 로 분기 디코딩하여 placeCards(PLACE_CARDS) 또는 text(TEXT/SYSTEM/MEMO_PROMPT)로 평탄화한다.
struct ChatFrame: Decodable, Identifiable, Equatable {
    var id: Int { messageId }
    let messageId: Int
    let roomId: Int
    let senderType: SenderType
    let kind: MessageKind
    let createdAt: String
    /// PLACE_CARDS 일 때만 채워진다(그 외 nil).
    let placeCards: [PlaceCard]?
    /// PLACE_CARDS payload 루트의 출처 릴스 URL(저장 시 핀 instagramUrl·"보러가기"). 그 외 kind 는 nil. 구버전 응답에서도 nil(BR-7).
    let sourceInstagramUrl: String?
    /// TEXT/SYSTEM/MEMO_PROMPT 의 payload.text(그 외 nil).
    let text: String?

    private enum CodingKeys: String, CodingKey {
        case messageId, roomId, senderType, kind, payload, createdAt
    }

    /// payload 의 text 키(TEXT/SYSTEM/MEMO_PROMPT). ChatMessageAppender.TextPayload({"text":...}) 정합.
    private enum PayloadKeys: String, CodingKey {
        case text
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.messageId = try container.decode(Int.self, forKey: .messageId)
        self.roomId = try container.decode(Int.self, forKey: .roomId)
        self.senderType = try container.decode(SenderType.self, forKey: .senderType)
        self.kind = try container.decode(MessageKind.self, forKey: .kind)
        self.createdAt = try container.decode(String.self, forKey: .createdAt)

        // payload 는 kind 로 분기. 누락/형식 불일치는 방어적으로 nil 처리(PROCESSING={} 포함).
        switch kind {
        case .PLACE_CARDS:
            let payload = try? container.decode(PlaceCardsPayload.self, forKey: .payload)
            self.placeCards = payload?.cards
            self.sourceInstagramUrl = payload?.sourceInstagramUrl
            self.text = nil
        case .TEXT, .SYSTEM, .MEMO_PROMPT:
            self.placeCards = nil
            self.sourceInstagramUrl = nil
            let payload = try? container.nestedContainer(keyedBy: PayloadKeys.self, forKey: .payload)
            self.text = try? payload?.decodeIfPresent(String.self, forKey: .text)
        case .PROCESSING:
            self.placeCards = nil
            self.sourceInstagramUrl = nil
            self.text = nil
        }
    }
}

// MARK: - REST 응답 DTO

/// 백엔드 ChatV1Dto.MessagesResponse 와 1:1. messages 는 최신순(id DESC).
/// nextCursor: Long → Int? (없으면 nil — 더 과거 메시지 없음).
struct MessagesResponse: Decodable {
    let messages: [ChatFrame]
    let hasMore: Bool
    let nextCursor: Int?
}

/// 백엔드 ChatV1Dto.SendMessageResponse 와 1:1. 봇 방은 PROCESSING 플레이스홀더 id/kind.
struct SendMessageResponse: Decodable {
    let messageId: Int
    let kind: MessageKind
}

// MARK: - DM 목록 항목

/// DM 목록 항목(백엔드 ChatV1Dto.BotRoomSummaryResponse 와 1:1). 내 활성 그룹별 봇 방 요약(가입 순).
/// 봇 방이 아직 없는 활성 그룹은 가상항목(roomId=nil, lastPreview/lastSenderType/lastAt=nil, unread=false)으로 내려온다.
/// - roomId/lastPreview/lastSenderType/lastAt: 메시지 없음 → nil.
/// - unread: 백엔드 판정(마지막이 BOT & lastRead<latest) 그대로 신뢰(BR-4, 자체 계산 안 함).
/// - groupId/roomId: Long → Int (PinSummary/ChatFrame 선례, iOS17 64bit 안전 수용 리스크).
/// - Hashable: DMListView 의 `navigationDestination(item:)`이 Hashable 을 요구한다(Identifiable 만으로 부족).
struct BotRoomSummary: Decodable, Identifiable, Equatable, Hashable {
    let roomId: Int?
    let groupId: Int
    let groupName: String
    let lastPreview: String?
    let lastSenderType: SenderType?
    let unread: Bool
    let lastAt: String?

    /// 그룹당 봇 방 1개 — groupId 가 안정 식별자(가상항목 roomId=nil 회피, List 식별자 안정성 확보).
    var id: Int { groupId }
}
