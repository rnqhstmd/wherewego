import Foundation

// Info.plist 에 주입된 빌드 설정값(xcconfig → Info.plist)을 읽는 단일 진입점.
// 키/계정 미보유 상태에서도 빌드·크래시가 없도록 폴백을 둔다(설계 §3, QE-3).
enum AppConfig {
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

    // MARK: - Private

    private static func infoString(_ key: String) -> String? {
        Bundle.main.object(forInfoDictionaryKey: key) as? String
    }
}
