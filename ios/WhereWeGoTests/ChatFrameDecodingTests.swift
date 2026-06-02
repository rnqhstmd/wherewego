import XCTest
@testable import WhereWeGo

// 백엔드 ChatMessageFrame payload 샘플 → ChatFrame 디코딩 검증(설계 §1).
// payload 는 kind 별 객체이며 ChatFrame 이 커스텀 디코딩으로 placeCards/text 로 평탄화한다:
//   PLACE_CARDS → {"cards":[{kakaoPlaceId,name,address,latitude,longitude}, ...]}
//   TEXT/SYSTEM/MEMO_PROMPT → {"text":"..."}  (ChatMessageAppender.TextPayload)
//   PROCESSING → {}
// messageId/roomId: Long → Int, createdAt: ISO offset String.
final class ChatFrameDecodingTests: XCTestCase {

    // MARK: - PLACE_CARDS

    func test_decode_placeCards_mapsCards() throws {
        // Given 봇 PLACE_CARDS 프레임(BotPlaceCardsPayloadBuilder payload).
        let json = """
        {
          "messageId": 10,
          "roomId": 3,
          "senderType": "BOT",
          "kind": "PLACE_CARDS",
          "payload": {
            "cards": [
              {"kakaoPlaceId":"123","name":"성수 카페","address":"서울 성동구 성수동","latitude":37.5446281,"longitude":127.0557234},
              {"kakaoPlaceId":null,"name":"이름만 장소","address":null,"latitude":null,"longitude":null}
            ]
          },
          "createdAt": "2026-05-24T12:34:56.789+09:00"
        }
        """

        // When
        let frame = try JSONDecoder().decode(ChatFrame.self, from: Data(json.utf8))

        // Then
        XCTAssertEqual(frame.id, 10)
        XCTAssertEqual(frame.messageId, 10)
        XCTAssertEqual(frame.roomId, 3)
        XCTAssertEqual(frame.senderType, .BOT)
        XCTAssertEqual(frame.kind, .PLACE_CARDS)
        XCTAssertEqual(frame.createdAt, "2026-05-24T12:34:56.789+09:00")
        XCTAssertNil(frame.text)

        let cards = try XCTUnwrap(frame.placeCards)
        XCTAssertEqual(cards.count, 2)
        XCTAssertEqual(cards[0].kakaoPlaceId, "123")
        XCTAssertEqual(cards[0].name, "성수 카페")
        XCTAssertEqual(cards[0].address, "서울 성동구 성수동")
        XCTAssertEqual(cards[0].latitude ?? 0, 37.5446281, accuracy: 1e-7)
        XCTAssertEqual(cards[0].longitude ?? 0, 127.0557234, accuracy: 1e-7)
        // 좌표 없는 카드 — 저장 비활성 대상(설계 §5).
        XCTAssertNil(cards[1].kakaoPlaceId)
        XCTAssertNil(cards[1].address)
        XCTAssertNil(cards[1].latitude)
        XCTAssertNil(cards[1].longitude)
    }

    func test_decode_placeCards_emptyCards() throws {
        // Given 추출 결과 없음(빈 cards).
        let json = """
        {"messageId":11,"roomId":3,"senderType":"BOT","kind":"PLACE_CARDS",
         "payload":{"cards":[]},"createdAt":"2026-05-24T12:00:00+09:00"}
        """

        // When
        let frame = try JSONDecoder().decode(ChatFrame.self, from: Data(json.utf8))

        // Then 빈 배열(nil 아님).
        XCTAssertEqual(frame.placeCards?.count, 0)
        XCTAssertNil(frame.text)
    }

    // MARK: - TEXT / SYSTEM / MEMO_PROMPT (payload.text)

    func test_decode_text_userMessage() throws {
        // Given 사용자 TEXT(봇/커플 공통 payload {"text":...}).
        let json = """
        {"messageId":5,"roomId":3,"senderType":"USER","kind":"TEXT",
         "payload":{"text":"안녕하세요"},"createdAt":"2026-05-24T11:00:00+09:00"}
        """

        // When
        let frame = try JSONDecoder().decode(ChatFrame.self, from: Data(json.utf8))

        // Then
        XCTAssertEqual(frame.kind, .TEXT)
        XCTAssertEqual(frame.senderType, .USER)
        XCTAssertEqual(frame.text, "안녕하세요")
        XCTAssertNil(frame.placeCards)
    }

    func test_decode_system_message() throws {
        // Given 봇 SYSTEM 안내/오류.
        let json = """
        {"messageId":6,"roomId":3,"senderType":"BOT","kind":"SYSTEM",
         "payload":{"text":"장소를 찾지 못했어요."},"createdAt":"2026-05-24T11:01:00+09:00"}
        """

        // When
        let frame = try JSONDecoder().decode(ChatFrame.self, from: Data(json.utf8))

        // Then
        XCTAssertEqual(frame.kind, .SYSTEM)
        XCTAssertEqual(frame.text, "장소를 찾지 못했어요.")
        XCTAssertNil(frame.placeCards)
    }

    func test_decode_memoPrompt_message() throws {
        // Given MEMO_PROMPT(메모 입력 유도, payload {"text":...}).
        let json = """
        {"messageId":7,"roomId":3,"senderType":"BOT","kind":"MEMO_PROMPT",
         "payload":{"text":"이 장소에 메모를 남겨보세요."},"createdAt":"2026-05-24T11:02:00+09:00"}
        """

        // When
        let frame = try JSONDecoder().decode(ChatFrame.self, from: Data(json.utf8))

        // Then
        XCTAssertEqual(frame.kind, .MEMO_PROMPT)
        XCTAssertEqual(frame.text, "이 장소에 메모를 남겨보세요.")
        XCTAssertNil(frame.placeCards)
    }

    // MARK: - PROCESSING (빈 payload)

    func test_decode_processing_emptyPayload() throws {
        // Given 봇 처리중 플레이스홀더(payload={}).
        let json = """
        {"messageId":8,"roomId":3,"senderType":"BOT","kind":"PROCESSING",
         "payload":{},"createdAt":"2026-05-24T11:03:00+09:00"}
        """

        // When
        let frame = try JSONDecoder().decode(ChatFrame.self, from: Data(json.utf8))

        // Then placeCards/text 모두 nil.
        XCTAssertEqual(frame.kind, .PROCESSING)
        XCTAssertNil(frame.placeCards)
        XCTAssertNil(frame.text)
    }

    // MARK: - MessagesResponse 통합

    func test_decode_messagesResponse_withCursor() throws {
        // Given REST 페이지 응답(최신순 + hasMore + nextCursor).
        let json = """
        {
          "messages": [
            {"messageId":8,"roomId":3,"senderType":"BOT","kind":"PROCESSING","payload":{},"createdAt":"2026-05-24T11:03:00+09:00"},
            {"messageId":5,"roomId":3,"senderType":"USER","kind":"TEXT","payload":{"text":"hi"},"createdAt":"2026-05-24T11:00:00+09:00"}
          ],
          "hasMore": true,
          "nextCursor": 5
        }
        """

        // When
        let response = try JSONDecoder().decode(MessagesResponse.self, from: Data(json.utf8))

        // Then
        XCTAssertEqual(response.messages.count, 2)
        XCTAssertEqual(response.messages.first?.kind, .PROCESSING)
        XCTAssertTrue(response.hasMore)
        XCTAssertEqual(response.nextCursor, 5)
    }

    func test_decode_messagesResponse_noMore_nullCursor() throws {
        // Given 더 과거 메시지 없음(nextCursor null).
        let json = """
        {"messages":[],"hasMore":false,"nextCursor":null}
        """

        // When
        let response = try JSONDecoder().decode(MessagesResponse.self, from: Data(json.utf8))

        // Then
        XCTAssertTrue(response.messages.isEmpty)
        XCTAssertFalse(response.hasMore)
        XCTAssertNil(response.nextCursor)
    }

    // MARK: - SendMessageResponse

    func test_decode_sendMessageResponse_processing() throws {
        // Given 봇 전송 응답(PROCESSING 플레이스홀더 id/kind).
        let json = """
        {"messageId":8,"kind":"PROCESSING"}
        """

        // When
        let response = try JSONDecoder().decode(SendMessageResponse.self, from: Data(json.utf8))

        // Then
        XCTAssertEqual(response.messageId, 8)
        XCTAssertEqual(response.kind, .PROCESSING)
    }

    func test_decode_sendMessageResponse_coupleText() throws {
        // Given 커플 전송 응답(저장된 TEXT 메시지).
        let json = """
        {"messageId":42,"kind":"TEXT"}
        """

        // When
        let response = try JSONDecoder().decode(SendMessageResponse.self, from: Data(json.utf8))

        // Then
        XCTAssertEqual(response.messageId, 42)
        XCTAssertEqual(response.kind, .TEXT)
    }
}
