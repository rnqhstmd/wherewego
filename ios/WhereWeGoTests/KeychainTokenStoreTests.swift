import XCTest
@testable import WhereWeGo

// AC-5 / FR-5 / BR-5: Keychain 기반 토큰 저장소 단위 테스트.
// 저장→복원→삭제 라운드트립과 refresh() 성공/401 흐름을 검증한다.
// 테스트 격리: setUp/tearDown 에서 clear() 로 실 키체인 service(com.wherewego.tokens)를 정리한다.
final class KeychainTokenStoreTests: XCTestCase {

    private let baseURL = URL(string: "http://localhost:8080")!

    override func setUp() async throws {
        try await super.setUp()
        // 이전 테스트 잔여 토큰 정리.
        let store = KeychainTokenStore(baseURL: baseURL)
        await store.clear()
    }

    override func tearDown() async throws {
        let store = KeychainTokenStore(baseURL: baseURL)
        await store.clear()
        try await super.tearDown()
    }

    // MARK: - save → read → 복원

    func test_saveTokens_thenAccessToken_restoresValue() async throws {
        // Given
        let store = KeychainTokenStore(baseURL: baseURL)
        // When 저장
        try await store.saveTokens(access: "access-abc", refresh: "refresh-xyz")
        // Then 복원
        let restored = await store.accessToken()
        XCTAssertEqual(restored, "access-abc")
    }

    func test_saveTokens_overwritesPreviousValue() async throws {
        // Given 기존 토큰
        let store = KeychainTokenStore(baseURL: baseURL)
        try await store.saveTokens(access: "old", refresh: "old-r")
        // When 덮어쓰기
        try await store.saveTokens(access: "new", refresh: "new-r")
        // Then 최신 값
        let restored = await store.accessToken()
        XCTAssertEqual(restored, "new")
    }

    // MARK: - clear → nil

    func test_clear_removesAccessToken() async throws {
        // Given 저장된 토큰
        let store = KeychainTokenStore(baseURL: baseURL)
        try await store.saveTokens(access: "access-abc", refresh: "refresh-xyz")
        // When clear
        await store.clear()
        // Then nil
        let restored = await store.accessToken()
        XCTAssertNil(restored)
    }

    func test_accessToken_whenEmpty_returnsNil() async {
        // Given 저장 없음(setUp 에서 clear)
        let store = KeychainTokenStore(baseURL: baseURL)
        // When / Then
        let restored = await store.accessToken()
        XCTAssertNil(restored)
    }

    // MARK: - refresh: 성공 (URLProtocol 스텁)

    func test_refresh_success_savesNewTokens() async throws {
        // Given refreshToken 저장 + /auth/refresh 성공 응답 스텁
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { _ in
            let body = """
            {"meta":{"result":"SUCCESS"},"data":{"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":3600}}
            """
            return (200, Data(body.utf8))
        }
        defer { StubURLProtocol.handler = nil }

        let store = KeychainTokenStore(baseURL: baseURL, session: session)
        try await store.saveTokens(access: "stale", refresh: "valid-refresh")

        // When
        try await store.refresh()

        // Then 신규 accessToken 으로 갱신
        let restored = await store.accessToken()
        XCTAssertEqual(restored, "new-access")
    }

    // MARK: - refresh: 401 → clear + logoutHandler + throw

    func test_refresh_serverReturns401_clearsTokensAndCallsLogout() async throws {
        // Given refreshToken 저장 + /auth/refresh 401 스텁
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.handler = { _ in
            let body = """
            {"meta":{"result":"FAIL","errorCode":"UNAUTHORIZED","message":"expired"},"data":null}
            """
            return (401, Data(body.utf8))
        }
        defer { StubURLProtocol.handler = nil }

        let logoutCalled = LogoutFlag()
        let logoutBox = LogoutHandlerBox()
        logoutBox.handler = { await logoutCalled.set() }

        let store = KeychainTokenStore(baseURL: baseURL, session: session, logoutBox: logoutBox)
        try await store.saveTokens(access: "stale", refresh: "expired-refresh")

        // When / Then throw
        do {
            try await store.refresh()
            XCTFail("401 에서 throw 해야 함")
        } catch {
            // expected
        }

        // Then 토큰 삭제 + logoutHandler 호출
        let restored = await store.accessToken()
        XCTAssertNil(restored)
        let called = await logoutCalled.value
        XCTAssertTrue(called, "401 시 logoutHandler 가 호출되어야 함")
    }

    // MARK: - refresh: refreshToken 없음 → throw + logout

    func test_refresh_noRefreshToken_throwsAndCallsLogout() async {
        // Given refreshToken 없음(setUp clear)
        let session = StubURLProtocol.makeSession()

        let logoutCalled = LogoutFlag()
        let logoutBox = LogoutHandlerBox()
        logoutBox.handler = { await logoutCalled.set() }

        let store = KeychainTokenStore(baseURL: baseURL, session: session, logoutBox: logoutBox)

        // When / Then throw
        do {
            try await store.refresh()
            XCTFail("refreshToken 없으면 throw 해야 함")
        } catch let error as APIError {
            XCTAssertEqual(error.code, "NO_REFRESH_TOKEN")
            XCTAssertEqual(error.status, 401)
        } catch {
            XCTFail("APIError 가 아닌 에러: \(error)")
        }

        let called = await logoutCalled.value
        XCTAssertTrue(called)
    }

    // MARK: - refresh: 네트워크 오류 → 토큰 보존 + 로그아웃 없음(수정 #5)

    func test_refresh_networkError_preservesTokensAndNoLogout() async throws {
        // Given refreshToken 저장 + /auth/refresh 가 네트워크 오류(URLError)로 실패하는 스텁
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.errorToThrow = URLError(.timedOut)
        defer { StubURLProtocol.errorToThrow = nil }

        let logoutCalled = LogoutFlag()
        let logoutBox = LogoutHandlerBox()
        logoutBox.handler = { await logoutCalled.set() }

        let store = KeychainTokenStore(baseURL: baseURL, session: session, logoutBox: logoutBox)
        try await store.saveTokens(access: "stale", refresh: "valid-refresh")

        // When / Then throw(네트워크 오류는 rethrow)
        do {
            try await store.refresh()
            XCTFail("네트워크 오류 시 throw 해야 함")
        } catch {
            // expected
        }

        // Then 토큰 보존 + logoutHandler 미호출(일시 장애로 강제 로그아웃 금지)
        let restored = await store.accessToken()
        XCTAssertEqual(restored, "stale", "네트워크 오류 시 토큰을 보존해야 함")
        let called = await logoutCalled.value
        XCTAssertFalse(called, "네트워크 오류 시 logoutHandler 가 호출되면 안 됨")
    }

    // MARK: - refresh: 동시 2회 호출 → performRefresh 1회만 실행(설계 §13, inFlight 직렬화)

    func test_refresh_concurrentCalls_performRefreshRunsOnce() async throws {
        // Given refreshToken 저장 + 성공 스텁(요청 횟수 카운트)
        let session = StubURLProtocol.makeSession()
        StubURLProtocol.resetRequestCount()
        StubURLProtocol.handler = { _ in
            let body = """
            {"meta":{"result":"SUCCESS"},"data":{"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":3600}}
            """
            return (200, Data(body.utf8))
        }
        defer {
            StubURLProtocol.handler = nil
            StubURLProtocol.resetRequestCount()
        }

        let store = KeychainTokenStore(baseURL: baseURL, session: session)
        try await store.saveTokens(access: "stale", refresh: "valid-refresh")

        // When 동시 2회 refresh
        async let r1: Void = store.refresh()
        async let r2: Void = store.refresh()
        _ = try await (r1, r2)

        // Then 실제 네트워크 호출(performRefresh)은 1회만 발생(inFlight 직렬화)
        XCTAssertEqual(StubURLProtocol.requestCount, 1, "동시 refresh 시 performRefresh 는 1회만 실행되어야 함")
        let restored = await store.accessToken()
        XCTAssertEqual(restored, "new-access")
    }
}

// MARK: - 테스트 헬퍼

/// logoutHandler 호출 여부를 동시성 안전하게 기록.
private actor LogoutFlag {
    private(set) var value = false
    func set() { value = true }
}
