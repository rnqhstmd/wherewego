import Foundation

// Share Extension 경량 네트워킹(설계 §3·§4). 앱 APIClient 미사용 — 자족.
// Bearer(공유 키체인 access token) + 401 시 refresh 1회 후 재시도. `/api/v1` 프리픽스 부착.
protocol ShareAPIClientProtocol: Sendable {
    func botRooms() async throws -> [ShareGroup]
    func sendBotMessage(groupId: Int, text: String) async throws
}

final class ShareAPIClient: ShareAPIClientProtocol {
    private let baseURL: URL
    private let session: URLSession
    private let tokens: ShareTokenStore

    init(baseURL: URL, tokens: ShareTokenStore, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.tokens = tokens
        self.session = session
    }

    /// GET /chat/bot/rooms → 내 활성 그룹별 봇 방. 그룹 0개(data null/204) → 빈 배열.
    func botRooms() async throws -> [ShareGroup] {
        let env: ShareEnvelope<[ShareGroup]> = try await request(path: "/chat/bot/rooms", method: "GET", body: nil)
        return env.data ?? []
    }

    /// POST /chat/bot/{groupId}/messages {text} — 봇이 URL→장소 추출(릴스 저장). 2xx면 성공.
    func sendBotMessage(groupId: Int, text: String) async throws {
        let body = try JSONSerialization.data(withJSONObject: ["text": text])
        let _: ShareEnvelope<ShareIgnore> = try await request(path: "/chat/bot/\(groupId)/messages", method: "POST", body: body)
    }

    /// 본문 무시용(전송 응답 형태와 무관하게 2xx 성공 판정).
    private struct ShareIgnore: Decodable {}

    // MARK: - 공통 요청(Bearer + 401 refresh 1회 재시도)
    private func request<T: Decodable>(
        path: String,
        method: String,
        body: Data?,
        retried: Bool = false
    ) async throws -> ShareEnvelope<T> {
        guard let token = tokens.accessToken() else {
            throw ShareAPIError(code: "NO_TOKEN", status: 401, message: "로그인이 필요해요")
        }
        var req = URLRequest(url: baseURL.appendingPathComponent("api/v1" + path))
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let body {
            req.httpBody = body
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        let (data, resp) = try await session.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0

        // 401 → refresh 1회 후 재시도.
        if status == 401, !retried {
            try await refresh()
            return try await request(path: path, method: method, body: body, retried: true)
        }

        guard (200..<300).contains(status) else {
            let meta = (try? JSONDecoder().decode(ShareEnvelope<T>.self, from: data))?.meta
            throw ShareAPIError(code: meta?.errorCode ?? "HTTP_\(status)", status: status,
                                message: meta?.message ?? "요청에 실패했어요")
        }
        // 2xx + 빈 본문(204) → data nil 래퍼.
        guard !data.isEmpty else { return ShareEnvelope<T>(meta: nil, data: nil) }
        let env = try JSONDecoder().decode(ShareEnvelope<T>.self, from: data)
        if env.meta?.result == "FAIL" {
            throw ShareAPIError(code: env.meta?.errorCode ?? "FAIL", status: status,
                                message: env.meta?.message ?? "요청에 실패했어요")
        }
        return env
    }

    /// POST /api/v1/auth/refresh {refreshToken} — Bearer 불요. 성공 시 공유 키체인 갱신.
    private func refresh() async throws {
        guard let refreshToken = tokens.refreshToken() else {
            throw ShareAPIError(code: "NO_REFRESH", status: 401, message: "로그인이 필요해요")
        }
        var req = URLRequest(url: baseURL.appendingPathComponent("api/v1/auth/refresh"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["refreshToken": refreshToken])

        let (data, resp) = try await session.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let env = try JSONDecoder().decode(ShareEnvelope<ShareTokenResponse>.self, from: data)
        guard (200..<300).contains(status), let payload = env.data else {
            throw ShareAPIError(code: env.meta?.errorCode ?? "HTTP_\(status)", status: status,
                                message: "세션이 만료되었어요")
        }
        tokens.save(access: payload.accessToken, refresh: payload.refreshToken)
    }
}
