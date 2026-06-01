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
        do {
            return try await send(path, method: method, body: body)
        } catch let e as APIError where e.status == 401 {
            // 401 → 토큰 갱신 후 1회 재시도. 갱신 실패는 상위로 전파(로그아웃).
            try await tokens.refresh()
            return try await send(path, method: method, body: body)
        }
    }

    private func send<T: Decodable>(_ path: String, method: String, body: Data?) async throws -> T {
        var req = URLRequest(url: baseURL.appendingPathComponent("api/v1" + path))
        req.httpMethod = method
        req.httpBody = body
        if body != nil { req.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        if let token = await tokens.accessToken() {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let (data, resp) = try await session.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0

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
}
