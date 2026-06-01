import XCTest
@testable import WhereWeGo

// PlaceAPI.search → 백엔드 PlaceSearchResponse {items:[PlaceItem]} 디코딩 검증(StubURLProtocol).
final class PlaceAPITests: XCTestCase {

    private let baseURL = URL(string: "http://localhost:8080")!

    override func setUp() {
        super.setUp()
        StubURLProtocol.resetRequestCount()
    }

    override func tearDown() {
        StubURLProtocol.handler = nil
        super.tearDown()
    }

    private actor StubTokenStore: TokenStore {
        func accessToken() async -> String? { "access" }
        func refresh() async throws {}
    }

    private func makeAPI(session: URLSession) -> PlaceAPI {
        let client = APIClient(baseURL: baseURL, tokens: StubTokenStore(), session: session)
        return PlaceAPI(client: client)
    }

    func test_search_returnsItems() async throws {
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { _ in
            let body = """
            {"meta":{"result":"SUCCESS"},"data":{"items":[
              {"placeName":"성수 카페","address":"서울 성동구","latitude":37.5446,"longitude":127.0557},
              {"placeName":"한강공원","address":null,"latitude":37.5283,"longitude":126.9326}
            ]}}
            """
            return (200, Data(body.utf8))
        }
        let api = makeAPI(session: session)

        let items = try await api.search("카페")

        XCTAssertEqual(items.count, 2)
        XCTAssertEqual(items.first?.placeName, "성수 카페")
        XCTAssertEqual(items.first?.address, "서울 성동구")
        XCTAssertEqual(items.first?.latitude ?? 0, 37.5446, accuracy: 1e-4)
        XCTAssertEqual(items.first?.longitude ?? 0, 127.0557, accuracy: 1e-4)
        // address 옵셔널 nil 케이스
        XCTAssertNil(items.last?.address)
    }

    /// 캡처한 query 를 thread-safe 하게 보관(handler 클로저 → 메인 비교).
    private final class QueryCapture: @unchecked Sendable {
        private let lock = NSLock()
        private var value: String?
        func set(_ v: String?) { lock.lock(); defer { lock.unlock() }; value = v }
        func get() -> String? { lock.lock(); defer { lock.unlock() }; return value }
    }

    func test_search_passesQueryParam() async throws {
        // makeURL 이 query string(?q=) 을 보존하는지 회귀 방지(appendingPathComponent 가 ? 를 깨던 이슈).
        let session = StubURLProtocol.makeSession()
        let capture = QueryCapture()
        StubURLProtocol.handler = { request in
            capture.set(request.url?.query)
            let body = """
            {"meta":{"result":"SUCCESS"},"data":{"items":[]}}
            """
            return (200, Data(body.utf8))
        }
        let api = makeAPI(session: session)

        _ = try await api.search("카페")

        // q 파라미터가 percent-encoding 되어 query 로 전달(path 로 깨지지 않음)
        let query = try XCTUnwrap(capture.get())
        XCTAssertTrue(query.hasPrefix("q="), "query 가 q= 로 시작해야 함: \(query)")
        XCTAssertFalse(query.isEmpty)
    }

    func test_search_injectionKeyword_encodedAsSingleQueryValue() async throws {
        // 보안 회귀 방지: `&`/`=` 포함 키워드가 별도 파라미터로 분리되지 않고
        // 단일 q 값으로 percent-encoding 되어 전달되는지 단언(파라미터 인젝션 차단).
        let session = StubURLProtocol.makeSession()
        let capture = QueryCapture()
        StubURLProtocol.handler = { request in
            capture.set(request.url?.query)
            let body = """
            {"meta":{"result":"SUCCESS"},"data":{"items":[]}}
            """
            return (200, Data(body.utf8))
        }
        let api = makeAPI(session: session)

        _ = try await api.search("a&b=c")

        let query = try XCTUnwrap(capture.get())
        // 단일 q 파라미터만 존재(`&` 로 분리된 추가 파라미터가 없어야 함).
        XCTAssertTrue(query.hasPrefix("q="), "query 가 q= 로 시작해야 함: \(query)")
        let pairs = query.split(separator: "&")
        XCTAssertEqual(pairs.count, 1, "주입 키워드가 단일 q 값으로 인코딩돼야 함: \(query)")
        // `&` 와 `=` 가 percent-encoding(%26 / %3D) 되어 q 값 안에 포함.
        XCTAssertTrue(query.contains("%26"), "`&` 가 %26 으로 인코딩돼야 함: \(query)")
        XCTAssertTrue(query.contains("%3D"), "`=` 가 %3D 으로 인코딩돼야 함: \(query)")
    }

    func test_search_emptyItems() async throws {
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { _ in
            let body = """
            {"meta":{"result":"SUCCESS"},"data":{"items":[]}}
            """
            return (200, Data(body.utf8))
        }
        let api = makeAPI(session: session)

        let items = try await api.search("없는장소")

        XCTAssertTrue(items.isEmpty)
    }
}
