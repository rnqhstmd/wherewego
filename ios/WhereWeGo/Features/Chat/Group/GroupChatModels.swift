import Foundation

// 그룹 채팅 도메인 모델(GC-2 설계 §3). 백엔드 GroupChatMessageFrame/GroupRoomSummary/GroupMessagesResponse 와 1:1.
// 봇 ChatFrame(senderType USER/BOT/SYSTEM)과 분리 — 멤버 채팅은 senderUserId/senderNickname 식별 + REEL_LINK registered 파생.
//  - messageId/roomId/senderUserId: Long → Int (PinSummary/ChatFrame 선례, iOS17 64bit 안전 수용 리스크).
//  - createdAt: ZonedDateTime → String (ISO-8601 offset).
//  - payload 는 kind 별: TEXT → {"text":"..."}, REEL_LINK → {"url":"...", "thumbnailKey":null}.
//    커스텀 디코딩이 payload 를 kind 로 분기해 text / reelUrl 로 평탄화한다.

// MARK: - GroupChatFrame

/// 그룹 메시지 프레임(백엔드 GroupChatMessageFrame, FR-GC1-4). 멀티유저 발신자 식별 + REEL_LINK registered.
struct GroupChatFrame: Decodable, Identifiable, Equatable {
    var id: Int { messageId }
    let messageId: Int
    let roomId: Int
    /// 발신 사용자. 계정 삭제(NULL)된 메시지는 nil — 타인 취급 + 닉네임 "(알 수 없음)".
    let senderUserId: Int?
    /// 발신자 닉네임(서버 배치 조회). 발신자 NULL 이면 nil.
    let senderNickname: String?
    let kind: MessageKind
    let createdAt: String
    /// REEL_LINK payload.url(그 외 kind 는 nil).
    let reelUrl: String?
    /// REEL_LINK 만 — 이 릴스의 핀이 그룹에 존재하면 true(파생). 그 외 kind 는 nil.
    let registered: Bool?
    /// REEL_LINK 만 — 비동기 스크래핑한 og:image 썸네일 URL(GC-3, FR-GC3-2). 백엔드 프레임의 top-level 계약 필드.
    /// 스크래핑 전/실패/만료/flag off, 그 외 kind 는 nil → 버블에서 기본 회색 타일로 폴백.
    let thumbnailUrl: String?
    /// TEXT/SYSTEM 의 payload.text(그 외 nil).
    let text: String?

    private enum CodingKeys: String, CodingKey {
        case messageId, roomId, senderUserId, senderNickname, kind, payload, registered, thumbnailUrl, createdAt
    }
    private enum TextKeys: String, CodingKey { case text }
    private enum ReelKeys: String, CodingKey { case url }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.messageId = try c.decode(Int.self, forKey: .messageId)
        self.roomId = try c.decode(Int.self, forKey: .roomId)
        self.senderUserId = try c.decodeIfPresent(Int.self, forKey: .senderUserId)
        self.senderNickname = try c.decodeIfPresent(String.self, forKey: .senderNickname)
        self.kind = try c.decode(MessageKind.self, forKey: .kind)
        self.createdAt = try c.decode(String.self, forKey: .createdAt)
        self.registered = try c.decodeIfPresent(Bool.self, forKey: .registered)
        // thumbnailUrl 은 백엔드 프레임 top-level(payload 밖) — registered 와 동형으로 kind 무관하게 디코드(비-REEL_LINK 는 서버가 null).
        self.thumbnailUrl = try c.decodeIfPresent(String.self, forKey: .thumbnailUrl)

        // payload 는 kind 로 분기. 누락/형식 불일치는 방어적으로 nil 처리.
        switch kind {
        case .REEL_LINK:
            let p = try? c.nestedContainer(keyedBy: ReelKeys.self, forKey: .payload)
            self.reelUrl = try? p?.decodeIfPresent(String.self, forKey: .url)
            self.text = nil
        case .TEXT, .SYSTEM, .MEMO_PROMPT:
            let p = try? c.nestedContainer(keyedBy: TextKeys.self, forKey: .payload)
            self.text = try? p?.decodeIfPresent(String.self, forKey: .text)
            self.reelUrl = nil
        case .PLACE_CARDS, .PROCESSING:
            // 그룹 방에서 생성되지 않는 봇 kind — 안전 폴백(렌더는 GroupMessageRow 가 무시).
            self.reelUrl = nil
            self.text = nil
        }
    }

    /// 메모리 직접 생성(테스트/낙관 프레임용). 디코더만 있는 위 init 과 별개의 멤버와이즈 생성자.
    init(
        messageId: Int,
        roomId: Int,
        senderUserId: Int?,
        senderNickname: String?,
        kind: MessageKind,
        createdAt: String,
        reelUrl: String? = nil,
        registered: Bool? = nil,
        thumbnailUrl: String? = nil,
        text: String? = nil
    ) {
        self.messageId = messageId
        self.roomId = roomId
        self.senderUserId = senderUserId
        self.senderNickname = senderNickname
        self.kind = kind
        self.createdAt = createdAt
        self.reelUrl = reelUrl
        self.registered = registered
        self.thumbnailUrl = thumbnailUrl
        self.text = text
    }
}

// MARK: - GroupMessagesResponse

/// 백엔드 ChatV1Dto.GroupMessagesResponse 와 1:1. messages 는 최신순(id DESC). nextCursor 없으면 nil.
struct GroupMessagesResponse: Decodable {
    let groupId: Int
    let messages: [GroupChatFrame]
    let hasMore: Bool
    let nextCursor: Int?
}

// MARK: - GroupRoomSummary

/// 채팅(그룹 방) 목록 항목(백엔드 GroupRoomSummaryResponse, FR-GC1-7). 그룹당 1방.
/// 방이 아직 없는 활성 그룹은 가상항목(roomId/lastPreview/lastSenderUserId/lastAt=nil, hasUnread=false).
/// 봇 BotRoomSummary 대비 lastSenderType→lastSenderUserId(내 메시지 판정 == currentUser.id), unread→hasUnread.
struct GroupRoomSummary: Decodable, Identifiable, Equatable, Hashable {
    let roomId: Int?
    let groupId: Int
    let groupName: String
    let lastPreview: String?
    /// 마지막 메시지 발신자. 탈퇴/메시지 없음 → nil. 내 메시지("나: …") 판정용.
    let lastSenderUserId: Int?
    let hasUnread: Bool
    let lastAt: String?

    /// 그룹당 1방 — groupId 가 안정 식별자(가상항목 roomId=nil 회피, List 식별 안정성).
    var id: Int { groupId }
}

// MARK: - ReelSaveResult

/// 릴스 위저드 저장 완료 결과(결과 카드 렌더 입력, FR-GC2-4). 409 중복은 목록에서 제외되고 duplicateCount 로만 집계.
/// sourceInstagramUrl 이 non-nil 일 때만 결과에 "지도에서 보기" 노출(저장 성공 핀 1개 이상 + URL 존재).
/// (봇·그룹 공용 — 봇 코드 GC-3 제거 후에도 보존되도록 GroupChatModels 에 둔다.)
struct ReelSaveResult: Equatable {
    let wishNames: [String]
    let reelNames: [String]
    let duplicateCount: Int
    let memo: String?
    let sourceInstagramUrl: String?
}
