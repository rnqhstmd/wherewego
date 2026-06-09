import Foundation
import Security

/// 로그아웃 콜백을 담는 가변 박스(설계 §12, 2단계 조립의 순환 차단).
/// KeychainTokenStore 가 생성 시점에 박스를 주입받고, AppDependencies 가 SessionStore 생성 후
/// 동기적으로 handler 를 채운다. refresh() 가 handler 를 호출하는 시점(로그인 이후)엔 이미 채워져 있어
/// .task 실행 순서에 의존하지 않는다.
final class LogoutHandlerBox: @unchecked Sendable {
    /// handler 설정/호출은 @MainActor(AppDependencies) 및 actor(KeychainTokenStore) 경계에서만 일어나므로
    /// 데이터 경쟁이 없다. @unchecked 로 Swift 6 검사를 우회한다.
    var handler: (@Sendable () async -> Void)?

    init() {}
}

/// Keychain 쓰기 실패(설계 §5, MUST#2). 토큰 저장이 무음 실패하지 않도록 명시 throw.
enum KeychainError: Error {
    case writeFailed(OSStatus)
}

// Keychain 기반 토큰 저장소(설계 §5, MUST#2).
// 순환 부재: refresh() 는 APIClient 를 참조하지 않는다(/auth/refresh 는 Bearer 불요·refreshToken body·401 재시도 불요).
// → 별도 TokenRefresher 클래스 없이 내부 private 메서드로 refresh POST(URLSession 주입).
// APIClient.swift 의 TokenStore 프로토콜(accessToken()/refresh())을 정확히 conform 한다.
actor KeychainTokenStore: TokenStore {
    private let baseURL: URL
    private let session: URLSession
    private let service = "com.wherewego.tokens"
    /// 공유 keychain access group(App Group). nil이면 미사용(앱 단독·테스트). 설정 시 Share Extension 과 토큰 공유.
    private let accessGroup: String?

    private enum Account {
        static let access = "accessToken"
        static let refresh = "refreshToken"
    }

    /// 동시 401 에서 RefreshToken 1회만 회전하도록 직렬화(CONSIDER).
    private var inFlightRefresh: Task<Void, Error>?

    /// 토큰 갱신 최종 실패 시 호출(2단계 조립으로 후주입, §12).
    /// 생성 시점에 박스를 주입받아 .task 순서 경쟁을 차단한다.
    private let logoutBox: LogoutHandlerBox

    init(
        baseURL: URL,
        session: URLSession = .shared,
        logoutBox: LogoutHandlerBox = LogoutHandlerBox(),
        accessGroup: String? = nil
    ) {
        self.baseURL = baseURL
        self.session = session
        self.logoutBox = logoutBox
        self.accessGroup = accessGroup
    }

    // MARK: - TokenStore

    func accessToken() async -> String? {
        readItem(account: Account.access)
    }

    func refresh() async throws {
        // ① 이미 진행 중인 refresh 가 있으면 그 결과를 기다린다(동시 401 직렬화).
        if let inFlight = inFlightRefresh {
            try await inFlight.value
            return
        }

        let box = logoutBox
        let task = Task<Void, Error> { [self] in
            // ② refreshToken 없음 → 로그아웃 유도.
            guard let refreshToken = readItem(account: Account.refresh) else {
                clearItems()
                await box.handler?()
                throw APIError(code: "NO_REFRESH_TOKEN", status: 401, message: "세션이 만료되었어요.")
            }

            do {
                // ③ 갱신 POST.
                let response = try await performRefresh(refreshToken: refreshToken)
                try saveItems(access: response.accessToken, refresh: response.refreshToken)
            } catch let error as URLError {
                // ④-a 네트워크 오류(타임아웃/연결끊김 등) → 인증 실패가 아니므로 토큰 보존·로그아웃 금지(재시도 가능).
                throw error
            } catch let error as KeychainError {
                // ④-b 저장 실패 → 인증 실패가 아니므로 토큰 보존·로그아웃 금지. 상위로 전파.
                throw error
            } catch let error as APIError {
                // ④-c 서버 인증 거부(401 등 APIError) → 로그아웃 유도.
                clearItems()
                await box.handler?()
                throw error
            } catch {
                // ④-d JSON 파싱 실패 등 서버 응답 이상 → 보수적으로 토큰 보존(인증 실패 아님). rethrow only.
                throw error
            }
        }
        inFlightRefresh = task

        do {
            try await task.value
            inFlightRefresh = nil
        } catch {
            inFlightRefresh = nil
            throw error
        }
    }

    // MARK: - 토큰 저장/삭제

    func saveTokens(access: String, refresh: String) async throws {
        try saveItems(access: access, refresh: refresh)
    }

    func clear() async {
        clearItems()
    }

    // MARK: - Private refresh POST

    /// POST {baseURL}/api/v1/auth/refresh. Bearer 없음. APIEnvelope<TokenResponse> 디코딩.
    private func performRefresh(refreshToken: String) async throws -> TokenResponse {
        var request = URLRequest(url: baseURL.appendingPathComponent("api/v1/auth/refresh"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(["refreshToken": refreshToken])

        let (data, resp) = try await session.data(for: request)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0

        let env = try JSONDecoder().decode(APIEnvelope<TokenResponse>.self, from: data)
        let ok = (200..<300).contains(status) && env.meta?.result != "FAIL"
        guard ok, let payload = env.data else {
            throw APIError(
                code: env.meta?.errorCode ?? "HTTP_\(status)",
                status: status,
                message: env.meta?.message ?? "토큰 갱신에 실패했어요."
            )
        }
        return payload
    }

    // MARK: - Keychain (SecItem)

    private func baseQuery(account: String) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        // 공유 access group이 설정되면 Share Extension 과 동일 키체인 항목을 가리킨다(미설정 시 앱 전용 기본 그룹).
        if let accessGroup {
            query[kSecAttrAccessGroup as String] = accessGroup
        }
        return query
    }

    private func readItem(account: String) -> String? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            // errSecItemNotFound 는 정상(미저장) → nil.
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    /// SecItemAdd/SecItemUpdate 결과 OSStatus 를 검증하여 무음 저장 실패를 차단(MUST#2).
    /// 실패 시 KeychainError.writeFailed throw → 호출부에서 로그인 실패로 처리.
    private func writeItem(account: String, value: String) throws {
        let data = Data(value.utf8)
        var query = baseQuery(account: account)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock

        let addStatus = SecItemAdd(query as CFDictionary, nil)
        switch addStatus {
        case errSecSuccess:
            return
        case errSecDuplicateItem:
            // 이미 존재 → update 로 폴백.
            let attributes: [String: Any] = [
                kSecValueData as String: data,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
            ]
            let updateStatus = SecItemUpdate(baseQuery(account: account) as CFDictionary, attributes as CFDictionary)
            guard updateStatus == errSecSuccess else {
                throw KeychainError.writeFailed(updateStatus)
            }
        default:
            throw KeychainError.writeFailed(addStatus)
        }
    }

    private func deleteItem(account: String) {
        // errSecItemNotFound 는 허용(이미 없음).
        SecItemDelete(baseQuery(account: account) as CFDictionary)
    }

    private func saveItems(access: String, refresh: String) throws {
        try writeItem(account: Account.access, value: access)
        try writeItem(account: Account.refresh, value: refresh)
    }

    private func clearItems() {
        deleteItem(account: Account.access)
        deleteItem(account: Account.refresh)
    }
}
