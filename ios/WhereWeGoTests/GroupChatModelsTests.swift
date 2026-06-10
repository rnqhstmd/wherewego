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
