import Foundation

// 백엔드 공통 응답 envelope: { "meta": {...}, "data": T }
// frontend/src/lib/api/http-client.ts 의 parseResponse 와 동치 로직을 Swift 로 옮긴다.

struct APIMeta: Decodable {
    let result: String?        // "SUCCESS" | "FAIL"
    let errorCode: String?
    let message: String?
}

struct APIEnvelope<T: Decodable>: Decodable {
    let meta: APIMeta?
    let data: T?
}

struct APIError: Error, LocalizedError {
    let code: String
    let status: Int
    let message: String
    var errorDescription: String? { message }
}

/// Bearer 토큰을 보관/주입하는 추상화. B1 에서 Keychain 구현으로 교체.
protocol TokenStore: Sendable {
    func accessToken() async -> String?
    func refresh() async throws            // 401 시 호출. 실패하면 throw → 로그아웃 유도.
}

/// 백엔드 직접 호출 클라이언트. Bearer 자동 부착 + 401 → refresh → 1회 재시도.
actor APIClient {
    private let baseURL: URL
    private let tokens: TokenStore
    private let session: URLSession

    init(baseURL: URL, tokens: TokenStore, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.tokens = tokens
        self.session = session
    }

    func request<T: Decodable>(
        _ path: String,
        method: String = "GET",
        body: Data? = nil,
        type: T.Type = T.self
    ) async throws -> T {
        // MUST-3: 토큰 주입/401 재시도를 performAuthorized 공통 헬퍼로 위임.
        // request 의 공개 동작(envelope 언랩·204 처리·APIError 매핑)은 100% 동일 유지.
        let url = makeURL(path)
        let (data, resp) = try await performAuthorized {
            var req = URLRequest(url: url)
            req.httpMethod = method
            req.httpBody = body
            if body != nil { req.setValue("application/json", forHTTPHeaderField: "Content-Type") }
            return req
        }
        return try decodeEnvelope(data, status: resp.statusCode)
    }

    /// 멀티파트 업로드(MUST-3). performAuthorized 경유로 401 재시도를 공유한다.
    /// boundary/body 는 build 클로저 밖에서 1회 생성 → 401 재시도 시 동일 body 재전송.
    func upload<T: Decodable>(
        _ path: String,
        fileData: Data,
        fileName: String,
        fieldName: String,
        mimeType: String,
        type: T.Type = T.self
    ) async throws -> T {
        let url = makeURL(path)
        let boundary = "Boundary-\(UUID().uuidString)"
        let httpBody = Self.multipartBody(
            fileData: fileData,
            fileName: fileName,
            fieldName: fieldName,
            mimeType: mimeType,
            boundary: boundary
        )
        let (data, resp) = try await performAuthorized {
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.httpBody = httpBody
            req.setValue(
                "multipart/form-data; boundary=\(boundary)",
                forHTTPHeaderField: "Content-Type"
            )
            return req
        }
        return try decodeEnvelope(data, status: resp.statusCode)
    }

    /// `api/v1` prefix 부착 + query string 보존 URL 구성.
    /// query 없는 path 는 기존 `appendingPathComponent` 결과와 동일 URL(공개 동작 불변).
    /// query 있는 path(`?` 포함)는 path 부분만 percent-safe 결합하고 query 를 보존한다.
    private func makeURL(_ path: String) -> URL {
        guard let queryIndex = path.firstIndex(of: "?") else {
            return baseURL.appendingPathComponent("api/v1" + path)
        }
        let pathPart = String(path[path.startIndex..<queryIndex])
        let queryPart = String(path[path.index(after: queryIndex)...])
        let base = baseURL.appendingPathComponent("api/v1" + pathPart)
        var components = URLComponents(url: base, resolvingAgainstBaseURL: false)
        // 단일 쿼리 파라미터만 지원 — 기존 쿼리는 덮어써짐. 다중 파라미터는 URLComponents.queryItems로 확장 필요.
        components?.percentEncodedQuery = queryPart
        return components?.url ?? base
    }

    /// 공통 인증 실행기(MUST-3): Bearer 주입 → session.data → 401 이면 refresh 후 build 재호출로
    /// 동일 요청을 1회 재시도. request/upload 가 공유한다(공개 동작 불변).
    private func performAuthorized(
        _ build: @Sendable () -> URLRequest
    ) async throws -> (Data, HTTPURLResponse) {
        let (data, resp) = try await perform(build())
        if resp.statusCode == 401 {
            // 401 → 토큰 갱신 후 1회 재시도. 갱신 실패는 상위로 전파(로그아웃).
            try await tokens.refresh()
            return try await perform(build())
        }
        return (data, resp)
    }

    /// 단일 요청 실행: Bearer 부착 후 전송. 상태코드 포함 원시 응답 반환.
    private func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        var req = request
        if let token = await tokens.accessToken() {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let (data, resp) = try await session.data(for: req)
        let http = (resp as? HTTPURLResponse)
            ?? HTTPURLResponse(url: req.url ?? baseURL, statusCode: 0, httpVersion: nil, headerFields: nil)!
        return (data, http)
    }

    /// envelope 언랩 + 204/빈본문 처리 + APIError 매핑(기존 send 의 디코딩 동작과 동일).
    private func decodeEnvelope<T: Decodable>(_ data: Data, status: Int) throws -> T {
        if status == 204 || data.isEmpty {
            // 본문 없는 성공 — T 가 옵셔널/Empty 가정. 호출부에서 처리.
            return try JSONDecoder().decode(APIEnvelope<T>.self, from: Data("{}".utf8)).data
                ?? { throw APIError(code: "NO_CONTENT", status: status, message: "no content") }()
        }

        let env = try JSONDecoder().decode(APIEnvelope<T>.self, from: data)
        let ok = (200..<300).contains(status) && env.meta?.result != "FAIL"
        guard ok, let payload = env.data else {
            throw APIError(
                code: env.meta?.errorCode ?? "HTTP_\(status)",
                status: status,
                message: env.meta?.message ?? "요청이 실패했습니다 (status=\(status))."
            )
        }
        return payload
    }

    /// multipart/form-data 단일 파일 본문 구성.
    private static func multipartBody(
        fileData: Data,
        fileName: String,
        fieldName: String,
        mimeType: String,
        boundary: String
    ) -> Data {
        var body = Data()
        body.append(Data("--\(boundary)\r\n".utf8))
        body.append(Data(
            "Content-Disposition: form-data; name=\"\(fieldName)\"; filename=\"\(fileName)\"\r\n".utf8
        ))
        body.append(Data("Content-Type: \(mimeType)\r\n\r\n".utf8))
        body.append(fileData)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        return body
    }
}
