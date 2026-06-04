import XCTest
@testable import WhereWeGo

// NotificationAPI 3엔드포인트 경로·메서드 캡처 + 좌표 Double?(number) 디코딩 검증(설계 §6, FR-19/FR-21, AC-7).
// StubURLProtocol 로 요청을 가로채 path/HTTP method 를 캡처하고, 응답 JSON 으로 DTO 디코딩을 확인한다.
final class NotificationAPITests: XCTestCase {

    private let baseURL = URL(string: "http://localhost:8080")!

    override func setUp() {
        super.setUp()
        StubURLProtocol.resetRequestCount()
    }

    override func tearDown() {
        StubURLProtocol.handler = nil
        StubURLProtocol.errorToThrow = nil
        super.tearDown()
    }

    // MARK: - 테스트용 TokenStore 목(Keychain 비의존)

    private actor DummyTokens: TokenStore {
        func accessToken() async -> String? { "access-1" }
        func refresh() async throws {}
    }

    private func makeAPI(session: URLSession) -> NotificationAPI {
        let client = APIClient(baseURL: baseURL, tokens: DummyTokens(), session: session)
        return NotificationAPI(client: client)
    }

    /// 마지막 요청의 (path, method) 를 thread-safe 하게 캡처하는 박스.
    private final class RequestCapture: @unchecked Sendable {
        private let lock = NSLock()
        private var _path: String?
        private var _method: String?
        func record(_ request: URLRequest) {
            lock.lock(); defer { lock.unlock() }
            _path = request.url?.path
            _method = request.httpMethod
        }
        var path: String? { lock.lock(); defer { lock.unlock() }; return _path }
        var method: String? { lock.lock(); defer { lock.unlock() }; return _method }
    }

    // MARK: - AC-7: list() → GET /notifications

    func test_list_callsGetNotifications() async throws {
        let session = StubURLProtocol.makeSession()
        let capture = RequestCapture()
        let body = """
        {"meta":{"result":"SUCCESS"},"data":{"items":[],"unreadCount":3}}
        """
        StubURLProtocol.handler = { request in
            capture.record(request)
            return (200, Data(body.utf8))
        }
        let api = makeAPI(session: session)

        let response = try await api.list()

        // 경로·메서드 캡처 — APIClient 가 "api/v1" prefix 부착.
        XCTAssertEqual(capture.path, "/api/v1/notifications")
        XCTAssertEqual(capture.method, "GET")
        XCTAssertEqual(response.unreadCount, 3)
        XCTAssertTrue(response.items.isEmpty)
    }

    // MARK: - AC-7: readAll() → POST /notifications/read-all

    func test_readAll_callsPostReadAll() async throws {
        let session = StubURLProtocol.makeSession()
        let capture = RequestCapture()
        let body = """
        {"meta":{"result":"SUCCESS"},"data":{"updatedCount":5}}
        """
        StubURLProtocol.handler = { request in
            capture.record(request)
            return (200, Data(body.utf8))
        }
        let api = makeAPI(session: session)

        let response = try await api.readAll()

        XCTAssertEqual(capture.path, "/api/v1/notifications/read-all")
        XCTAssertEqual(capture.method, "POST")
        XCTAssertEqual(response.updatedCount, 5)
    }

    // MARK: - AC-7: detail(id:) → GET /notifications/{id} + 좌표 Double?(number) 디코딩

    func test_detail_callsGetNotificationByIdAndDecodesCoordinatesAsDouble() async throws {
        let session = StubURLProtocol.makeSession()
        let capture = RequestCapture()
        // MUST-ADDRESS #1: latitude/longitude 는 number(BigDecimal WRITE_BIGDECIMAL_AS_PLAIN) → Double?.
        let body = """
        {"meta":{"result":"SUCCESS"},"data":{
          "id":42,
          "type":"MANUAL_PIN",
          "registeredByNickname":"보승",
          "createdAt":"2026-05-24T12:34:56.789+09:00",
          "pins":[
            {"pinId":7,"placeName":"성수 카페","address":"서울 성동구",
             "latitude":37.5446281,"longitude":127.0557234,
             "deleted":false,"instagramUrl":null,"memo":null,"tag":"WISH"},
            {"pinId":8,"placeName":"삭제된 장소","address":null,
             "latitude":null,"longitude":null,
             "deleted":true,"instagramUrl":null,"memo":null,"tag":null}
          ]
        }}
        """
        StubURLProtocol.handler = { request in
            capture.record(request)
            return (200, Data(body.utf8))
        }
        let api = makeAPI(session: session)

        let detail = try await api.detail(id: 42)

        // 경로·메서드 캡처.
        XCTAssertEqual(capture.path, "/api/v1/notifications/42")
        XCTAssertEqual(capture.method, "GET")

        // 상세 메타.
        XCTAssertEqual(detail.id, 42)
        XCTAssertEqual(detail.type, .MANUAL_PIN)
        XCTAssertEqual(detail.registeredByNickname, "보승")
        XCTAssertEqual(detail.pins.count, 2)

        // 좌표 number → Double? 정상 디코딩(첫 핀).
        let live = detail.pins[0]
        XCTAssertEqual(live.pinId, 7)
        XCTAssertEqual(live.latitude ?? 0, 37.5446281, accuracy: 1e-7)
        XCTAssertEqual(live.longitude ?? 0, 127.0557234, accuracy: 1e-7)
        XCTAssertFalse(live.deleted)

        // soft delete 핀 — 좌표 null → nil.
        let deleted = detail.pins[1]
        XCTAssertEqual(deleted.pinId, 8)
        XCTAssertNil(deleted.latitude)
        XCTAssertNil(deleted.longitude)
        XCTAssertTrue(deleted.deleted)
        XCTAssertNil(deleted.tag)
    }

    // MARK: - 목록 응답 아이템 디코딩(AC-7 보강)

    func test_list_decodesItems() async throws {
        let session = StubURLProtocol.makeSession()
        let body = """
        {"meta":{"result":"SUCCESS"},"data":{"items":[
          {"id":1,"type":"CHATBOT_PINS","registeredBy":42,"registeredByNickname":"지은",
           "firstPlaceName":"카페","totalPinCount":3,"wishCount":2,"reelCount":1,
           "createdAt":"2026-05-24T12:00:00+09:00","readAt":null}
        ],"unreadCount":1}}
        """
        StubURLProtocol.handler = { _ in (200, Data(body.utf8)) }
        let api = makeAPI(session: session)

        let response = try await api.list()

        XCTAssertEqual(response.items.count, 1)
        let item = response.items[0]
        XCTAssertEqual(item.id, 1)
        XCTAssertEqual(item.type, .CHATBOT_PINS)
        XCTAssertEqual(item.registeredBy, 42)
        XCTAssertEqual(item.registeredByNickname, "지은")
        XCTAssertEqual(item.firstPlaceName, "카페")
        XCTAssertEqual(item.totalPinCount, 3)
        XCTAssertEqual(item.wishCount, 2)
        XCTAssertEqual(item.reelCount, 1)
        XCTAssertNil(item.readAt)
        XCTAssertEqual(response.unreadCount, 1)
    }
}
