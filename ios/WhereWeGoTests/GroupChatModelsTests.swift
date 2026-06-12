import XCTest
@testable import WhereWeGo

// GroupChatFrame/GroupMessagesResponse/GroupRoomSummary 디코딩(GC-2 설계 §3, 백엔드 계약 정합).
final class GroupChatModelsTests: XCTestCase {

    private func decodeFrame(_ json: String) throws -> GroupChatFrame {
        try JSONDecoder().decode(GroupChatFrame.self, from: Data(json.utf8))
    }

    func test_TEXT_프레임() throws {
        let f = try decodeFrame(#"{"messageId":1,"roomId":10,"senderUserId":5,"senderNickname":"민수","kind":"TEXT","payload":{"text":"안녕"},"registered":null,"createdAt":"2026-06-10T12:00:00+09:00"}"#)
        XCTAssertEqual(f.kind, .TEXT)
        XCTAssertEqual(f.text, "안녕")
        XCTAssertEqual(f.senderUserId, 5)
        XCTAssertEqual(f.senderNickname, "민수")
        XCTAssertNil(f.reelUrl)
        XCTAssertNil(f.registered)
    }

    func test_REEL_LINK_등록됨() throws {
        let f = try decodeFrame(#"{"messageId":2,"roomId":10,"senderUserId":5,"senderNickname":"민수","kind":"REEL_LINK","payload":{"url":"https://instagram.com/reel/ABC","thumbnailKey":null},"registered":true,"createdAt":"2026-06-10T12:01:00+09:00"}"#)
        XCTAssertEqual(f.kind, .REEL_LINK)
        XCTAssertEqual(f.reelUrl, "https://instagram.com/reel/ABC")
        XCTAssertEqual(f.registered, true)
        XCTAssertNil(f.text)
    }

    func test_REEL_LINK_미등록() throws {
        let f = try decodeFrame(#"{"messageId":3,"roomId":10,"senderUserId":7,"senderNickname":"영희","kind":"REEL_LINK","payload":{"url":"https://instagram.com/reel/XYZ"},"registered":false,"createdAt":"2026-06-10T12:02:00+09:00"}"#)
        XCTAssertEqual(f.registered, false)
        XCTAssertEqual(f.reelUrl, "https://instagram.com/reel/XYZ")
        XCTAssertNil(f.thumbnailUrl)   // thumbnailUrl 키 부재 → 안전하게 nil(GC-3 FR-GC3-2)
    }

    func test_REEL_LINK_썸네일URL() throws {
        // 백엔드 프레임 top-level thumbnailUrl(payload 밖) 디코드. 비동기 스크래핑 성공 시 채워진다.
        let f = try decodeFrame(#"{"messageId":5,"roomId":10,"senderUserId":5,"senderNickname":"민수","kind":"REEL_LINK","payload":{"url":"https://instagram.com/reel/ABC","thumbnailKey":null,"thumbnailUrl":"https://scontent.cdninstagram.com/v/cover.jpg"},"registered":true,"thumbnailUrl":"https://scontent.cdninstagram.com/v/cover.jpg","createdAt":"2026-06-10T12:05:00+09:00"}"#)
        XCTAssertEqual(f.thumbnailUrl, "https://scontent.cdninstagram.com/v/cover.jpg")
        XCTAssertEqual(f.reelUrl, "https://instagram.com/reel/ABC")
        XCTAssertEqual(f.registered, true)
    }

    func test_PIN_VISIT_프레임() throws {
        // 정책 v2 — PIN_VISIT: top-level pinSnapshot(payload 밖), visitParticipants 없음. payload {pinId}.
        let json = #"{"messageId":6,"roomId":10,"senderUserId":5,"senderNickname":"민수","kind":"PIN_VISIT","payload":{"pinId":42},"pinSnapshot":{"pinId":42,"placeName":"성수 카페","tag":"WISH","memo":null,"photoThumbnailUrl":null,"photoUrl":null,"deleted":false},"createdAt":"2026-06-12T10:00:00+09:00"}"#
        let f = try decodeFrame(json)
        XCTAssertEqual(f.kind, .PIN_VISIT)
        XCTAssertEqual(f.pinSnapshot?.pinId, 42)
        XCTAssertEqual(f.pinSnapshot?.placeName, "성수 카페")
        XCTAssertEqual(f.pinSnapshot?.tag, "WISH")
        XCTAssertNil(f.visitParticipants)   // PIN_VISIT 은 동행 명단 없음
        XCTAssertNil(f.text)
        XCTAssertNil(f.reelUrl)
    }

    func test_PIN_MEMORY_프레임() throws {
        // 정책 v2 — PIN_MEMORY: top-level pinSnapshot + visitParticipants(동행 명단). payload {pinId, userIds}.
        let json = #"{"messageId":7,"roomId":10,"senderUserId":5,"senderNickname":"민수","kind":"PIN_MEMORY","payload":{"pinId":42,"userIds":[5,7]},"pinSnapshot":{"pinId":42,"placeName":"성수 카페","tag":"MEMORY","memo":"좋았다","photoThumbnailUrl":"https://cdn/t.jpg","photoUrl":"https://cdn/p.jpg","deleted":false},"visitParticipants":[{"userId":5,"nickname":"민수","profileImageUrl":"https://cdn/u5.jpg"},{"userId":7,"nickname":"영희","profileImageUrl":null}],"createdAt":"2026-06-12T10:01:00+09:00"}"#
        let f = try decodeFrame(json)
        XCTAssertEqual(f.kind, .PIN_MEMORY)
        XCTAssertEqual(f.pinSnapshot?.pinId, 42)
        XCTAssertEqual(f.pinSnapshot?.tag, "MEMORY")
        XCTAssertEqual(f.visitParticipants?.count, 2)
        XCTAssertEqual(f.visitParticipants?.first?.userId, 5)
        XCTAssertEqual(f.visitParticipants?.first?.nickname, "민수")
        XCTAssertEqual(f.visitParticipants?.first?.profileImageUrl, "https://cdn/u5.jpg")
        XCTAssertNil(f.visitParticipants?.last?.profileImageUrl)   // 프사 없음 → nil(이니셜 폴백)
        XCTAssertNil(f.text)
        XCTAssertNil(f.reelUrl)
    }

    func test_탈퇴_발신자는_nil() throws {
        let f = try decodeFrame(#"{"messageId":4,"roomId":10,"senderUserId":null,"senderNickname":null,"kind":"TEXT","payload":{"text":"이전 메시지"},"createdAt":"2026-06-10T12:03:00+09:00"}"#)
        XCTAssertNil(f.senderUserId)
        XCTAssertNil(f.senderNickname)
        XCTAssertEqual(f.text, "이전 메시지")
    }

    func test_GroupMessagesResponse() throws {
        let json = #"{"groupId":99,"messages":[{"messageId":1,"roomId":10,"senderUserId":5,"senderNickname":"민수","kind":"TEXT","payload":{"text":"hi"},"createdAt":"2026-06-10T12:00:00+09:00"}],"hasMore":true,"nextCursor":1}"#
        let r = try JSONDecoder().decode(GroupMessagesResponse.self, from: Data(json.utf8))
        XCTAssertEqual(r.groupId, 99)
        XCTAssertEqual(r.messages.count, 1)
        XCTAssertTrue(r.hasMore)
        XCTAssertEqual(r.nextCursor, 1)
    }

    func test_GroupRoomSummary_가상항목() throws {
        let json = #"{"roomId":null,"groupId":3,"groupName":"여행팟","lastPreview":null,"lastSenderUserId":null,"hasUnread":false,"lastAt":null}"#
        let s = try JSONDecoder().decode(GroupRoomSummary.self, from: Data(json.utf8))
        XCTAssertNil(s.roomId)
        XCTAssertEqual(s.groupId, 3)
        XCTAssertEqual(s.id, 3)
        XCTAssertFalse(s.hasUnread)
        XCTAssertNil(s.lastSenderUserId)
    }

    func test_GroupRoomSummary_메시지있음() throws {
        let json = #"{"roomId":11,"groupId":3,"groupName":"여행팟","lastPreview":"릴스 링크","lastSenderUserId":5,"hasUnread":true,"lastAt":"2026-06-10T12:00:00+09:00"}"#
        let s = try JSONDecoder().decode(GroupRoomSummary.self, from: Data(json.utf8))
        XCTAssertEqual(s.roomId, 11)
        XCTAssertEqual(s.lastSenderUserId, 5)
        XCTAssertTrue(s.hasUnread)
        XCTAssertEqual(s.lastPreview, "릴스 링크")
    }
}
