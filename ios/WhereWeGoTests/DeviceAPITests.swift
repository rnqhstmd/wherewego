import XCTest
@testable import WhereWeGo

// 설계 §8: DeviceAPI register(POST /devices, platform=IOS)/unregister(DELETE 204 흡수) 검증.
// PinAPITests 의 StubURLProtocol + StubTokenStore 패턴을 따른다.
final class DeviceAPITests: XCTestCase {

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

    private actor StubTokenStore: TokenStore {
        func accessToken() async -> String? { "access-1" }
        func refresh() async throws {}
    }

    private func makeAPI(session: URLSession) -> DeviceAPI {
        let client = APIClient(baseURL: baseURL, tokens: StubTokenStore(), session: session)
        return DeviceAPI(client: client)
    }

    // MARK: - register (POST /devices, platform=IOS)

    func test_register_postsPlatformAndToken() async throws {
        let session = StubURLProtocol.makeSession()
        // 요청 body 캡처용(thread-safe 박스).
        let capturedBody = CapturedBody()
        StubURLProtocol.handler = { request in
            capturedBody.set(Self.body(of: request))
            let body = """
            {"meta":{"result":"SUCCESS"},"data":{"deviceId":42}}
            """
            return (200, Data(body.utf8))
        }
        let api = makeAPI(session: session)

        try await api.register(deviceToken: "abc123token")

        // 요청 본문에 platform=IOS, deviceToken 이 직렬화됐는지 검증.
        let json = try capturedBody.json()
        XCTAssertEqual(json?["platform"] as? String, "IOS")
        XCTAssertEqual(json?["deviceToken"] as? String, "abc123token")
    }

    func test_register_propagatesError() async {
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { _ in
            let body = """
            {"meta":{"result":"FAIL","errorCode":"BAD_REQUEST","message":"bad"},"data":null}
            """
            return (400, Data(body.utf8))
        }
        let api = makeAPI(session: session)

        do {
            try await api.register(deviceToken: "x")
            XCTFail("등록 실패는 throw 되어야 한다")
        } catch let error as APIError {
            XCTAssertEqual(error.code, "BAD_REQUEST")
        } catch {
            XCTFail("APIError 가 아닌 예외: \(error)")
        }
    }

    // MARK: - unregister (DELETE 204 흡수)

    func test_unregister_204_succeeds() async throws {
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { _ in (204, Data()) }
        let api = makeAPI(session: session)

        // 204(빈 본문)는 정상 — NO_CONTENT 흡수로 throw 없이 반환.
        try await api.unregister(deviceToken: "abc123token")
    }

    func test_unregister_usesTokenInPath() async throws {
        let session = StubURLProtocol.makeSession()
        let capturedPath = CapturedPath()
        StubURLProtocol.handler = { request in
            capturedPath.set(request.url?.path ?? "")
            return (204, Data())
        }
        let api = makeAPI(session: session)

        try await api.unregister(deviceToken: "tok-9")

        XCTAssertTrue(capturedPath.value.hasSuffix("/api/v1/devices/tok-9"),
                      "DELETE path 가 토큰을 포함해야 한다: \(capturedPath.value)")
    }

    func test_unregister_propagatesNonNoContentError() async {
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { _ in
            let body = """
            {"meta":{"result":"FAIL","errorCode":"FORBIDDEN","message":"no"},"data":null}
            """
            return (403, Data(body.utf8))
        }
        let api = makeAPI(session: session)

        do {
            try await api.unregister(deviceToken: "x")
            XCTFail("403 은 흡수되지 않고 throw 되어야 한다")
        } catch let error as APIError {
            XCTAssertEqual(error.code, "FORBIDDEN")
        } catch {
            XCTFail("APIError 가 아닌 예외: \(error)")
        }
    }

    // MARK: - 헬퍼

    private static func body(of request: URLRequest) -> Data? {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let bufferSize = 1024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let read = stream.read(buffer, maxLength: bufferSize)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }

    /// 핸들러 클로저에서 캡처한 요청 본문을 테스트 본문으로 전달하는 thread-safe 박스.
    private final class CapturedBody: @unchecked Sendable {
        private let lock = NSLock()
        private var data: Data?
        func set(_ value: Data?) { lock.lock(); defer { lock.unlock() }; data = value }
        func json() throws -> [String: Any]? {
            lock.lock(); defer { lock.unlock() }
            guard let data else { return nil }
            return try JSONSerialization.jsonObject(with: data) as? [String: Any]
        }
    }

    /// 요청 path 캡처용 thread-safe 박스.
    private final class CapturedPath: @unchecked Sendable {
        private let lock = NSLock()
        private var path = ""
        func set(_ value: String) { lock.lock(); defer { lock.unlock() }; path = value }
        var value: String { lock.lock(); defer { lock.unlock() }; return path }
    }
}
