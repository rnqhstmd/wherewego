import Foundation

// 네트워크 없이 URLSession 응답을 시뮬레이션하는 URLProtocol 스텁.
// KeychainTokenStore.refresh()/GroupAPI 등 URLSession 주입 가능 경로의 단위 테스트에 사용.
//
// 사용법:
//   let session = StubURLProtocol.makeSession()
//   StubURLProtocol.handler = { request in (statusCode, bodyData) }
//   defer { StubURLProtocol.handler = nil }
final class StubURLProtocol: URLProtocol {

    /// (statusCode, body) 를 반환하는 핸들러. 테스트마다 설정/해제.
    /// nonisolated(unsafe): 테스트 단일 스레드 사용 가정, Swift 6 동시성 검사 우회.
    nonisolated(unsafe) static var handler: ((URLRequest) -> (Int, Data))?

    /// 설정 시 응답 대신 이 에러로 실패시킨다(네트워크 오류 시뮬레이션, 수정 #5 검증).
    nonisolated(unsafe) static var errorToThrow: Error?

    /// 실제 네트워크 호출(startLoading) 횟수를 thread-safe 하게 센다(inFlight 직렬화 검증용, 설계 §13).
    /// 동시 refresh 시 performRefresh 가 1회만 실행되는지 확인한다.
    private static let lock = NSLock()
    nonisolated(unsafe) private static var _requestCount = 0

    static var requestCount: Int {
        lock.lock(); defer { lock.unlock() }
        return _requestCount
    }

    static func resetRequestCount() {
        lock.lock(); defer { lock.unlock() }
        _requestCount = 0
    }

    private static func incrementRequestCount() {
        lock.lock(); defer { lock.unlock() }
        _requestCount += 1
    }

    /// 스텁 프로토콜만 사용하는 ephemeral 세션 생성.
    static func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: config)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.incrementRequestCount()
        if let error = StubURLProtocol.errorToThrow {
            // 네트워크 오류 시뮬레이션(URLError 등) → URLSession 이 해당 에러를 throw.
            client?.urlProtocol(self, didFailWithError: error)
            return
        }
        guard let handler = StubURLProtocol.handler else {
            client?.urlProtocol(
                self,
                didFailWithError: NSError(domain: "StubURLProtocol", code: -1)
            )
            return
        }
        let (status, body) = handler(request)
        let response = HTTPURLResponse(
            url: request.url ?? URL(string: "http://localhost")!,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
