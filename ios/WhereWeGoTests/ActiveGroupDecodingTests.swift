import XCTest
@testable import WhereWeGo

// MUST#1 검증(설계 §9): groups/me 의 data:null 디코딩과 GroupAPI 의 code 기반 nil 정규화.
// ① 정상 객체 디코딩 ② data:null → APIEnvelope.data == nil
// ③~⑥ GroupAPI.myActiveGroup() 분기: 200→nil, 204→nil, 401→throw, 실에러→throw (URLProtocol 스텁).
final class ActiveGroupDecodingTests: XCTestCase {

    private let baseURL = URL(string: "http://localhost:8080")!

    override func tearDown() {
        StubURLProtocol.handler = nil
        super.tearDown()
    }

    // MARK: - ① 정상 객체 디코딩

    func test_decode_activeGroup_success() throws {
        // Given SUCCESS + data 객체
        let json = """
        {"meta":{"result":"SUCCESS"},"data":{"groupId":1,"name":"여행팀","memberCount":2}}
        """
        // When
        let env = try JSONDecoder().decode(
            APIEnvelope<ActiveGroup>.self,
            from: Data(json.utf8)
        )
        // Then data 정상 디코딩
        XCTAssertEqual(env.meta?.result, "SUCCESS")
        XCTAssertEqual(env.data?.groupId, 1)
        XCTAssertEqual(env.data?.name, "여행팀")
        XCTAssertEqual(env.data?.memberCount, 2)
    }

    // MARK: - ② data:null 디코딩

    func test_decode_activeGroup_dataNull_returnsNil() throws {
        // Given SUCCESS + data:null (그룹 없음 시나리오)
        let json = """
        {"meta":{"result":"SUCCESS"},"data":null}
        """
        // When
        let env = try JSONDecoder().decode(
            APIEnvelope<ActiveGroup>.self,
            from: Data(json.utf8)
        )
        // Then data == nil (throw 없이)
        XCTAssertEqual(env.meta?.result, "SUCCESS")
        XCTAssertNil(env.data)
    }

    // MARK: - GroupAPI.myActiveGroup() 분기 (URLProtocol 스텁)

    private func makeGroupAPI(session: URLSession) -> GroupAPI {
        // KeychainTokenStore 는 accessToken nil(저장 없음) — Bearer 미부착, 호출은 스텁이 흡수.
        let tokens = KeychainTokenStore(baseURL: baseURL, session: session)
        let client = APIClient(baseURL: baseURL, tokens: tokens, session: session)
        return GroupAPI(client: client)
    }

    func test_myActiveGroup_successObject_returnsGroup() async throws {
        // Given 200 + 그룹 객체
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { _ in
            let body = """
            {"meta":{"result":"SUCCESS"},"data":{"groupId":7,"name":"우리팀","memberCount":3}}
            """
            return (200, Data(body.utf8))
        }
        let api = makeGroupAPI(session: session)

        // When
        let group = try await api.myActiveGroup()

        // Then 그룹 반환
        XCTAssertEqual(group?.groupId, 7)
        XCTAssertEqual(group?.name, "우리팀")
    }

    func test_myActiveGroup_200WithNullData_returnsNil() async throws {
        // Given 200 SUCCESS + data:null (그룹 없음 — APIClient 가 HTTP_200 code 로 throw → GroupAPI 가 nil 매핑)
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { _ in
            let body = """
            {"meta":{"result":"SUCCESS"},"data":null}
            """
            return (200, Data(body.utf8))
        }
        let api = makeGroupAPI(session: session)

        // When
        let group = try await api.myActiveGroup()

        // Then nil(그룹 없음)
        XCTAssertNil(group)
    }

    func test_myActiveGroup_204NoContent_returnsNil() async throws {
        // Given 204 빈 응답 → APIClient code="NO_CONTENT" → GroupAPI nil
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { _ in (204, Data()) }
        let api = makeGroupAPI(session: session)

        // When
        let group = try await api.myActiveGroup()

        // Then nil
        XCTAssertNil(group)
    }

    func test_myActiveGroup_401_rethrows() async {
        // Given 401 — APIClient refresh 시도(refreshToken 없음) → 재시도도 401 → throw
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { _ in
            let body = """
            {"meta":{"result":"FAIL","errorCode":"UNAUTHORIZED","message":"unauthorized"},"data":null}
            """
            return (401, Data(body.utf8))
        }
        let api = makeGroupAPI(session: session)

        // When / Then throw (그룹 없음으로 흡수하지 않음)
        do {
            _ = try await api.myActiveGroup()
            XCTFail("401 은 throw 해야 함")
        } catch {
            // expected — APIError 전파
        }
    }

    func test_myActiveGroup_serverError500_rethrows() async {
        // Given 500 서버 에러 → 진짜 에러 throw
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { _ in
            let body = """
            {"meta":{"result":"FAIL","errorCode":"INTERNAL_ERROR","message":"oops"},"data":null}
            """
            return (500, Data(body.utf8))
        }
        let api = makeGroupAPI(session: session)

        // When / Then throw
        do {
            _ = try await api.myActiveGroup()
            XCTFail("500 은 throw 해야 함")
        } catch let error as APIError {
            XCTAssertEqual(error.status, 500)
        } catch {
            XCTFail("APIError 가 아닌 에러: \(error)")
        }
    }
}
