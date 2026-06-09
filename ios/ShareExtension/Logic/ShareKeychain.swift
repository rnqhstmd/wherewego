import Foundation
import Security

// 공유 키체인(App Group access group)에서 메인 앱이 저장한 토큰을 읽고, refresh 후 갱신한다(설계 §3).
// 메인 앱 KeychainTokenStore 와 동일 service + accessGroup 을 가리켜야 토큰이 공유된다.
protocol ShareTokenStore: Sendable {
    func accessToken() -> String?
    func refreshToken() -> String?
    func save(access: String, refresh: String)
}

struct ShareKeychain: ShareTokenStore {
    private let service = "com.wherewego.tokens"
    /// ⚠️ 메인 앱 AppConfig.appGroupIdentifier 와 반드시 일치(토큰 공유 키).
    private let accessGroup = "group.com.wherewego.app"

    private enum Account {
        static let access = "accessToken"
        static let refresh = "refreshToken"
    }

    func accessToken() -> String? { read(Account.access) }
    func refreshToken() -> String? { read(Account.refresh) }

    func save(access: String, refresh: String) {
        write(Account.access, access)
        write(Account.refresh, refresh)
    }

    private func baseQuery(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: accessGroup
        ]
    }

    private func read(_ account: String) -> String? {
        var query = baseQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func write(_ account: String, _ value: String) {
        let data = Data(value.utf8)
        var add = baseQuery(account)
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecDuplicateItem {
            SecItemUpdate(
                baseQuery(account) as CFDictionary,
                [kSecValueData as String: data] as CFDictionary
            )
        }
    }
}
