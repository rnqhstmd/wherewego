import Foundation

// Info.plist 에 주입된 Mapbox 빌드 설정값(xcconfig → Info.plist)을 읽는 단일 진입점.
// token 미보유 상태에서도 빌드·크래시가 없도록 placeholder 감지·폴백을 둔다(설계 §1·§8, AC-1).
// AppConfig 와 동일 스타일(Info.plist 키 읽기 + 순수 판단 함수).
enum MapConfig {
    /// Info "MAPBOX_ACCESS_TOKEN".
    static var accessToken: String {
        infoString("MAPBOX_ACCESS_TOKEN") ?? ""
    }

    /// Info "MAPBOX_STYLE_URL" → 비어있으면 standard 스타일 폴백(웹 동일).
    static var styleURL: String {
        resolveStyleURL(infoString("MAPBOX_STYLE_URL"))
    }

    /// Mapbox token 이 실제로 설정됐는지 단일 판단점(AC-1).
    static var isMapboxConfigured: Bool {
        isConfigured(token: accessToken)
    }

    // MARK: - 순수 함수(테스트 가능)

    /// Mapbox token 설정 여부 판단. placeholder 또는 빈 값이면 false.
    static func isConfigured(token: String) -> Bool {
        let trimmed = token.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed != "MAPBOX_TOKEN_NOT_SET" && !trimmed.isEmpty
    }

    /// style URL 해석. nil/공백이면 standard 스타일 폴백.
    static func resolveStyleURL(_ raw: String?) -> String {
        let fallback = "mapbox://styles/mapbox/standard"
        guard let raw else { return fallback }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? fallback : trimmed
    }

    // MARK: - Private

    private static func infoString(_ key: String) -> String? {
        Bundle.main.object(forInfoDictionaryKey: key) as? String
    }
}
