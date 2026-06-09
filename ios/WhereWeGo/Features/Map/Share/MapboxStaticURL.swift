import Foundation

// Mapbox Static Images API URL 빌더. 웹 frontend/src/lib/share/mapboxStaticUrl.ts 이식.
// 공유 카드 배경 지도를 직접 다운로드할 URL을 만든다.
// iOS는 토큰을 보유하고 CORS 제약이 없으므로 웹의 Next.js 프록시(/api/mapbox-static) 대신 직접 호출한다.
enum MapboxStaticURL {

    /// Static Images API URL.
    /// 포맷: https://api.mapbox.com/styles/v1/{styleId}/static/{lng},{lat},{zoom},0/{w}x{h}?access_token={token}
    /// 좌표는 소수점 6자리로 안정화(웹 toFixed(6) 동치), 토큰은 URL 인코딩.
    static func build(
        latitude: Double,
        longitude: Double,
        zoom: Int,
        width: Int,
        height: Int,
        styleId: String,
        token: String
    ) -> String {
        let lng = String(format: "%.6f", longitude)
        let lat = String(format: "%.6f", latitude)
        let encodedToken =
            token.addingPercentEncoding(withAllowedCharacters: .wgTokenAllowed) ?? token
        return "https://api.mapbox.com/styles/v1/\(styleId)/static/"
            + "\(lng),\(lat),\(zoom),0/\(width)x\(height)?access_token=\(encodedToken)"
    }
}

private extension CharacterSet {
    /// 토큰 인코딩 허용 문자(웹 encodeURIComponent 근사). 영숫자 + `-._~` 만 통과시키고
    /// `+`, `/`, `=` 등 특수문자는 인코딩한다.
    static let wgTokenAllowed: CharacterSet = {
        var set = CharacterSet.alphanumerics
        set.insert(charactersIn: "-._~")
        return set
    }()
}
