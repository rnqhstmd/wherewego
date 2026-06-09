import Foundation

// Info.plist 에 주입된 빌드 설정값(xcconfig → Info.plist)을 읽는 단일 진입점.
// 키/계정 미보유 상태에서도 빌드·크래시가 없도록 폴백을 둔다(설계 §3, QE-3).
enum AppConfig {
    /// App Group(키체인 공유 겸용) 식별자. Share Extension 과 토큰을 공유하기 위한 keychain access group.
    /// ⚠️ ShareExtension 측 ShareKeychain 의 동일 상수와 반드시 일치해야 한다(기기/릴스는 portal App Group 등록 필요).
    static let appGroupIdentifier = "group.com.wherewego.app"

    /// Info "API_BASE_URL" → URL. 파싱 실패 시 http://localhost:8080 폴백.
    static var apiBaseURL: URL {
        resolveBaseURL(from: infoString("API_BASE_URL"))
    }

    /// Info "KAKAO_NATIVE_APP_KEY".
    static var kakaoAppKey: String {
        infoString("KAKAO_NATIVE_APP_KEY") ?? ""
    }

    /// 카카오 키가 실제로 설정됐는지 단일 판단점(QE-3).
    static var isKakaoKeyConfigured: Bool {
        isConfigured(kakaoKey: infoString("KAKAO_NATIVE_APP_KEY"))
    }

    /// 데모 로그인용 refreshToken(설계 §10, FR-26/BR-7/AC-21). Info "DEMO_REFRESH_TOKEN" → 비하드코딩.
    /// placeholder(`DEMO_REFRESH_TOKEN_NOT_SET`)/빈값이면 nil → "데모 로그인" 버튼 비표시·비활성.
    static var demoRefreshToken: String? {
        normalizeDemoRefreshToken(infoString("DEMO_REFRESH_TOKEN"))
    }

    /// Universal Links 도메인(설계 §9, FR-20). Info "APP_LINKS_DOMAIN" → 순수 호스트명.
    /// 미설정/빈값이면 nil(AASA 미호스팅 시 Universal Link 미작동, P5 전제).
    static var appLinksDomain: String? {
        normalizeAppLinksDomain(infoString("APP_LINKS_DOMAIN"))
    }

    // MARK: - 순수 함수(테스트 가능)

    /// 문자열을 URL 로 해석. nil/공백/파싱 실패 시 localhost 폴백.
    static func resolveBaseURL(from raw: String?) -> URL {
        let fallback = URL(string: "http://localhost:8080")!
        guard let raw else { return fallback }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let url = URL(string: trimmed), url.scheme != nil else {
            return fallback
        }
        return url
    }

    /// 카카오 키 설정 여부 판단. placeholder 또는 빈 값이면 false.
    static func isConfigured(kakaoKey: String?) -> Bool {
        guard let key = kakaoKey?.trimmingCharacters(in: .whitespacesAndNewlines) else {
            return false
        }
        return key != "KAKAO_APP_KEY_NOT_SET" && !key.isEmpty
    }

    /// 데모 refreshToken 정규화(순수). placeholder/빈값 → nil, 그 외 trim 한 값.
    static func normalizeDemoRefreshToken(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty,
              trimmed != "DEMO_REFRESH_TOKEN_NOT_SET" else {
            return nil
        }
        return trimmed
    }

    /// AppLinks 도메인 정규화(순수). 빈값/placeholder → nil, 그 외 trim 한 호스트명.
    static func normalizeAppLinksDomain(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty,
              trimmed != "APP_LINKS_DOMAIN_NOT_SET" else {
            return nil
        }
        return trimmed
    }

    // MARK: - Private

    private static func infoString(_ key: String) -> String? {
        Bundle.main.object(forInfoDictionaryKey: key) as? String
    }
}
