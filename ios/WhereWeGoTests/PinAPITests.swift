import XCTest
@testable import WhereWeGo

// AC-2: PinAPI 의 list(legacy items)/create/update/delete(204) 응답 검증(StubURLProtocol).
// MUST-3: uploadPhoto 의 multipart 401 → refresh → 재시도 시퀀스(performAuthorized 공통 헬퍼).
final class PinAPITests: XCTestCase {

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

    // MARK: - 테스트용 TokenStore 목

    /// refresh() 호출 여부·성공을 제어하는 목(Keychain 비의존). 401 재시도 검증용.
    private actor StubTokenStore: TokenStore {
        private var token: String
        private(set) var refreshCount = 0
        private let tokenAfterRefresh: String

        init(initialToken: String = "access-1", tokenAfterRefresh: String = "access-2") {
            self.token = initialToken
            self.tokenAfterRefresh = tokenAfterRefresh
        }

        func accessToken() async -> String? { token }

        func refresh() async throws {
            refreshCount += 1
            token = tokenAfterRefresh
        }

        func currentRefreshCount() async -> Int { refreshCount }
    }

    private func makeAPI(session: URLSession, tokens: TokenStore) -> PinAPI {
        let client = APIClient(baseURL: baseURL, tokens: tokens, session: session)
        return PinAPI(client: client)
    }

    // MARK: - 공통 응답 헬퍼

    /// legacy {items} 응답 본문(단일 핀).
    private func legacyListBody() -> Data {
        let body = """
        {"meta":{"result":"SUCCESS"},"data":{"items":[
          {"id":1,"groupId":2,"createdBy":3,"createdByNickname":null,"placeName":"A","address":null,
           "latitude":37.0,"longitude":127.0,"instagramUrl":null,"memo":null,"memoSource":null,
           "tag":"WISH","createdAt":"2026-01-01T00:00:00+09:00","visitedAt":null,
           "memoUpdatedBy":null,"memoUpdatedByNickname":null,"photoUrl":null,"photoThumbnailUrl":null}
        ],"totalCount":null,"hasNext":null}}
        """
        return Data(body.utf8)
    }

    private func pinSummaryBody(id: Int, tag: String, transitionedWrap: Bool = false) -> Data {
        let summary = """
        {"id":\(id),"groupId":2,"createdBy":3,"createdByNickname":null,"placeName":"A","address":null,
         "latitude":37.0,"longitude":127.0,"instagramUrl":null,"memo":null,"memoSource":null,
         "tag":"\(tag)","createdAt":"2026-01-01T00:00:00+09:00","visitedAt":null,
         "memoUpdatedBy":null,"memoUpdatedByNickname":null,"photoUrl":null,"photoThumbnailUrl":null}
        """
        if transitionedWrap {
            return Data("""
            {"meta":{"result":"SUCCESS"},"data":{"summary":\(summary),"transitionedToMemoryNow":true}}
            """.utf8)
        }
        return Data("""
        {"meta":{"result":"SUCCESS"},"data":\(summary)}
        """.utf8)
    }

    // MARK: - list (legacy items, AC-2)

    func test_list_legacyItems_returnsPins() async throws {
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { [self] _ in (200, legacyListBody()) }
        let api = makeAPI(session: session, tokens: StubTokenStore())

        let pins = try await api.list(groupId: 2)

        XCTAssertEqual(pins.count, 1)
        XCTAssertEqual(pins.first?.id, 1)
        XCTAssertEqual(pins.first?.placeName, "A")
        XCTAssertEqual(pins.first?.tag, .WISH)
    }

    // MARK: - create (201, AC-2)

    func test_create_returnsCreatedPin() async throws {
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { [self] _ in (201, pinSummaryBody(id: 9, tag: "REEL")) }
        let api = makeAPI(session: session, tokens: StubTokenStore())

        let request = CreatePinRequest(
            placeName: "새 장소", address: nil,
            latitude: 37.0, longitude: 127.0,
            instagramUrl: nil, memo: nil, tag: .REEL
        )
        let pin = try await api.create(groupId: 2, request: request)

        XCTAssertEqual(pin.id, 9)
        XCTAssertEqual(pin.tag, .REEL)
    }

    // MARK: - update (PATCH, AC-2)

    func test_update_returnsUpdateResponse() async throws {
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { [self] _ in
            (200, pinSummaryBody(id: 5, tag: "MEMORY", transitionedWrap: true))
        }
        let api = makeAPI(session: session, tokens: StubTokenStore())

        let response = try await api.update(
            groupId: 2, pinId: 5, request: UpdatePinRequest(tag: .set(.MEMORY))
        )

        XCTAssertEqual(response.summary.id, 5)
        XCTAssertEqual(response.summary.tag, .MEMORY)
        XCTAssertTrue(response.transitionedToMemoryNow)
    }

    // MARK: - delete (204, AC-2)

    func test_delete_204_succeeds() async throws {
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { _ in (204, Data()) }
        let api = makeAPI(session: session, tokens: StubTokenStore())

        // 204 는 정상 — throw 없이 반환.
        try await api.delete(groupId: 2, pinId: 5)
    }

    // MARK: - uploadPhoto multipart 401 재시도 (MUST-3)

    /// 호출 순서에 따라 다른 응답을 돌려주는 thread-safe 시퀀서.
    private final class ResponseSequencer: @unchecked Sendable {
        private let lock = NSLock()
        private var index = 0
        private let responses: [(Int, Data)]

        init(_ responses: [(Int, Data)]) { self.responses = responses }

        func next() -> (Int, Data) {
            lock.lock(); defer { lock.unlock() }
            let i = min(index, responses.count - 1)
            index += 1
            return responses[i]
        }
    }

    func test_uploadPhoto_401ThenRefreshThenRetry_succeeds() async throws {
        // 시퀀스: 1번째 호출 → 401, refresh 후 2번째 호출 → 200.
        let session = StubURLProtocol.makeSession()
        let tokens = StubTokenStore()

        let unauthorizedBody = """
        {"meta":{"result":"FAIL","errorCode":"UNAUTHORIZED","message":"unauthorized"},"data":null}
        """
        let sequencer = ResponseSequencer([
            (401, Data(unauthorizedBody.utf8)),
            (200, pinSummaryBody(id: 11, tag: "MEMORY"))
        ])
        StubURLProtocol.handler = { _ in sequencer.next() }
        let api = makeAPI(session: session, tokens: tokens)

        // image/jpeg 매직바이트 FF D8 FF 시작.
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0x00, 0x11, 0x22])
        let pin = try await api.uploadPhoto(groupId: 2, pinId: 11, imageData: jpeg)

        // 최종 성공 반환.
        XCTAssertEqual(pin.id, 11)
        XCTAssertEqual(pin.tag, .MEMORY)
        // 401 → refresh → 재시도: 네트워크 호출 2회(MUST-3).
        XCTAssertEqual(StubURLProtocol.requestCount, 2)
        // refresh 정확히 1회.
        let refreshCount = await tokens.currentRefreshCount()
        XCTAssertEqual(refreshCount, 1)
    }

    func test_uploadPhoto_immediateSuccess_noRefresh() async throws {
        // 첫 호출부터 200 → refresh 미발생, 호출 1회.
        let session = StubURLProtocol.makeSession()
        let tokens = StubTokenStore()
        StubURLProtocol.handler = { [self] _ in (200, pinSummaryBody(id: 12, tag: "MEMORY")) }
        let api = makeAPI(session: session, tokens: tokens)

        let jpeg = Data([0xFF, 0xD8, 0xFF, 0x00])
        let pin = try await api.uploadPhoto(groupId: 2, pinId: 12, imageData: jpeg)

        XCTAssertEqual(pin.id, 12)
        XCTAssertEqual(StubURLProtocol.requestCount, 1)
        let refreshCount = await tokens.currentRefreshCount()
        XCTAssertEqual(refreshCount, 0)
    }

    // MARK: - deletePhoto

    func test_deletePhoto_returnsSummary() async throws {
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { [self] _ in (200, pinSummaryBody(id: 13, tag: "MEMORY")) }
        let api = makeAPI(session: session, tokens: StubTokenStore())

        let pin = try await api.deletePhoto(groupId: 2, pinId: 13)

        XCTAssertEqual(pin.id, 13)
    }
}
