import XCTest
@testable import WhereWeGo

// STOMP 1.2 프레임 인코딩/디코딩 순수 로직 검증(설계 §3).
// - 인코딩: COMMAND\n headers\n\n body\0 와이어 포맷.
// - 디코딩: 단일/다중 프레임 NUL 분할, 헤더 파싱, 본문 분리.
final class StompFrameTests: XCTestCase {

    // MARK: - 인코딩

    func test_encode_commandHeadersBody_wireFormat() {
        // Given CONNECT 프레임(헤더 2개 + 본문 없음).
        let frame = StompFrame(
            command: "CONNECT",
            headers: ["accept-version": "1.2", "host": "example.com"]
        )

        // When
        let text = frame.encodedText()

        // Then 헤더는 키 정렬(accept-version < host), 빈 줄로 종료, NUL 종단.
        XCTAssertEqual(
            text,
            "CONNECT\naccept-version:1.2\nhost:example.com\n\n\u{00}"
        )
    }

    func test_encode_withBody() {
        // Given 본문 있는 SEND 형 프레임.
        let frame = StompFrame(command: "MESSAGE", headers: ["destination": "/topic/x"], body: "hello")

        // When
        let text = frame.encodedText()

        // Then 본문이 빈 줄 뒤에 위치하고 NUL 로 끝난다.
        XCTAssertEqual(text, "MESSAGE\ndestination:/topic/x\n\nhello\u{00}")
    }

    func test_encode_message_returnsStringMessage() {
        // Given
        let frame = StompFrame(command: "DISCONNECT")

        // When
        let message = frame.encode()

        // Then URLSessionWebSocketTask.Message.string 으로 인코딩.
        guard case let .string(value) = message else {
            return XCTFail("expected .string message")
        }
        XCTAssertEqual(value, "DISCONNECT\n\n\u{00}")
    }

    // MARK: - 라운드트립

    func test_roundTrip_encodeDecode_preservesFrame() {
        // Given
        let original = StompFrame(
            command: "SUBSCRIBE",
            headers: ["id": "sub-bot", "destination": "/topic/chat/bot/42"]
        )

        // When 인코딩 → 디코딩.
        let decoded = StompFrame.decode(original.encodedText())

        // Then 단일 프레임으로 복원, command/headers 동일(body 없음).
        XCTAssertEqual(decoded.count, 1)
        XCTAssertEqual(decoded.first?.command, "SUBSCRIBE")
        XCTAssertEqual(decoded.first?.headers["id"], "sub-bot")
        XCTAssertEqual(decoded.first?.headers["destination"], "/topic/chat/bot/42")
        XCTAssertEqual(decoded.first?.body, "")
    }

    func test_roundTrip_withBody() {
        // Given JSON 본문(채팅 MESSAGE).
        let body = #"{"messageId":1,"kind":"TEXT"}"#
        let original = StompFrame(command: "MESSAGE", headers: ["subscription": "sub-bot"], body: body)

        // When
        let decoded = StompFrame.decode(original.encodedText())

        // Then 본문 보존.
        XCTAssertEqual(decoded.first?.body, body)
        XCTAssertEqual(decoded.first?.headers["subscription"], "sub-bot")
    }

    // MARK: - 디코딩

    func test_decode_single_connectedFrame() {
        // Given 서버 CONNECTED 프레임.
        let text = "CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\u{00}"

        // When
        let frames = StompFrame.decode(text)

        // Then
        XCTAssertEqual(frames.count, 1)
        XCTAssertEqual(frames.first?.command, "CONNECTED")
        XCTAssertEqual(frames.first?.headers["version"], "1.2")
        XCTAssertEqual(frames.first?.headers["heart-beat"], "0,0")
    }

    func test_decode_multipleFrames_nulSplit() {
        // Given 한 메시지에 합쳐진 2개 프레임(NUL 종단 각각).
        let text = "MESSAGE\nsubscription:sub-bot\n\nfirst\u{00}"
            + "MESSAGE\nsubscription:sub-couple\n\nsecond\u{00}"

        // When
        let frames = StompFrame.decode(text)

        // Then 2개로 분할, 본문 각각 보존.
        XCTAssertEqual(frames.count, 2)
        XCTAssertEqual(frames[0].body, "first")
        XCTAssertEqual(frames[0].headers["subscription"], "sub-bot")
        XCTAssertEqual(frames[1].body, "second")
        XCTAssertEqual(frames[1].headers["subscription"], "sub-couple")
    }

    func test_decode_multipleFrames_withInterFrameNewline() {
        // Given 프레임 종단(NUL) 뒤 개행이 끼는 실제 서버 패턴.
        let text = "CONNECTED\nversion:1.2\n\n\u{00}\nMESSAGE\nsubscription:s\n\nbody\u{00}"

        // When
        let frames = StompFrame.decode(text)

        // Then 선행 개행 무시하고 2개 프레임 파싱.
        XCTAssertEqual(frames.count, 2)
        XCTAssertEqual(frames[0].command, "CONNECTED")
        XCTAssertEqual(frames[1].command, "MESSAGE")
        XCTAssertEqual(frames[1].body, "body")
    }

    func test_decode_emptyAndWhitespaceFragmentsIgnored() {
        // Given NUL 만 있거나(빈 프레임) 공백/개행만 있는 trailing 조각.
        let text = "\u{00}\n\u{00}"

        // When
        let frames = StompFrame.decode(text)

        // Then 유효 command 없는 조각은 모두 무시.
        XCTAssertTrue(frames.isEmpty)
    }

    func test_decode_headerValueWithColon_keepsAfterFirstColon() {
        // Given value 에 ':' 포함(시각 등).
        let text = "MESSAGE\ndestination:/topic/x\ncustom:a:b:c\n\n\u{00}"

        // When
        let frames = StompFrame.decode(text)

        // Then 첫 콜론만 분리, 나머지는 value 로 보존.
        XCTAssertEqual(frames.first?.headers["custom"], "a:b:c")
        XCTAssertEqual(frames.first?.headers["destination"], "/topic/x")
    }

    func test_decode_errorFrame_messageHeader() {
        // Given 서버 ERROR 프레임(구독 인가 실패 등).
        let text = "ERROR\nmessage:Subscription to this destination is not allowed\n\n\u{00}"

        // When
        let frames = StompFrame.decode(text)

        // Then
        XCTAssertEqual(frames.first?.command, "ERROR")
        XCTAssertEqual(
            frames.first?.headers["message"],
            "Subscription to this destination is not allowed"
        )
    }

    func test_decode_crlfNormalized_headerAndBodySeparated() {
        // Given CRLF(\r\n) 줄 종단을 쓰는 브로커 프레임(헤더 구분 \r\n\r\n).
        let text = "MESSAGE\r\nsubscription:sub-bot\r\n\r\nhello\u{00}"

        // When
        let frames = StompFrame.decode(text)

        // Then \r\n → \n 정규화로 command/헤더/본문이 올바르게 분리된다.
        XCTAssertEqual(frames.count, 1)
        XCTAssertEqual(frames.first?.command, "MESSAGE")
        XCTAssertEqual(frames.first?.headers["subscription"], "sub-bot")
        XCTAssertEqual(frames.first?.body, "hello")
    }

    func test_decode_bodyWithJsonContainingNoNul_preserved() {
        // Given 본문에 개행이 포함된 멀티라인 JSON.
        let body = "{\n  \"text\": \"hi\"\n}"
        let text = "MESSAGE\nsubscription:s\n\n\(body)\u{00}"

        // When
        let frames = StompFrame.decode(text)

        // Then 헤더/본문 경계(첫 빈 줄) 이후 전체가 본문으로 보존.
        XCTAssertEqual(frames.first?.body, body)
    }

    // MARK: - websocketURL 변환(StompClient 순수 헬퍼)

    func test_websocketURL_https_toWss() throws {
        let base = URL(string: "https://api.example.com")!
        let ws = try XCTUnwrap(StompClient.websocketURL(from: base))
        XCTAssertEqual(ws.absoluteString, "wss://api.example.com/ws/chat")
    }

    func test_websocketURL_http_toWs() throws {
        let base = URL(string: "http://localhost:8080")!
        let ws = try XCTUnwrap(StompClient.websocketURL(from: base))
        XCTAssertEqual(ws.absoluteString, "ws://localhost:8080/ws/chat")
    }

    func test_websocketURL_basePathPreserved() throws {
        let base = URL(string: "https://api.example.com/backend")!
        let ws = try XCTUnwrap(StompClient.websocketURL(from: base))
        XCTAssertEqual(ws.absoluteString, "wss://api.example.com/backend/ws/chat")
    }
}
