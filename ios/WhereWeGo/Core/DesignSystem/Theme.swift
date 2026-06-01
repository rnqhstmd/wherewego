import SwiftUI

// frontend/src/lib/design/tokens.ts 의 1:1 이식.
// 웹 globals.css @theme 변수와 짝을 이루던 컬러/폰트 토큰을 SwiftUI 상수로 옮긴다.
// 색 값이 바뀌면 웹 tokens.ts 와 함께 갱신할 것.

extension Color {
    /// "#RRGGBB" 또는 "#RRGGBBAA" 16진 문자열로 Color 생성.
    init(hex: String, opacity: Double = 1.0) {
        let s = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        var v: UInt64 = 0
        Scanner(string: s).scanHexInt64(&v)
        let r, g, b, a: Double
        switch s.count {
        case 8: // RRGGBBAA
            r = Double((v >> 24) & 0xFF) / 255
            g = Double((v >> 16) & 0xFF) / 255
            b = Double((v >> 8) & 0xFF) / 255
            a = Double(v & 0xFF) / 255
        default: // RRGGBB
            r = Double((v >> 16) & 0xFF) / 255
            g = Double((v >> 8) & 0xFF) / 255
            b = Double(v & 0xFF) / 255
            a = opacity
        }
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}

/// 디자인 토큰 컬러. 웹 `colors` 와 키 1:1.
enum WGColor {
    static let bg        = Color(hex: "#FAF8F5")
    static let panel     = Color(hex: "#FFFFFF")
    static let mapBg     = Color(hex: "#EAE4D4")
    static let mapWater  = Color(hex: "#D4E8F0")
    static let mapPark   = Color(hex: "#D5E5CB")
    static let mapBlock  = Color(hex: "#F0EBE0")
    static let mapRoad   = Color(hex: "#FFFFFF")
    static let pinReel   = Color(hex: "#7BB3E8")
    static let pinWish   = Color(hex: "#F4C842")
    static let pinMemory = Color(hex: "#FFB3C6")
    static let pinNew    = Color(hex: "#E05A5A")
    static let cta       = Color(hex: "#C4622D")
    static let ctaHover  = Color(hex: "#A84E23")
    static let ctaSub    = Color(hex: "#8B8B9E")
    static let kakao     = Color(hex: "#FEE500")
    static let kakaoInk  = Color(hex: "#191600")
    static let ink       = Color(hex: "#1A1A2E")
    static let inkSoft   = Color(hex: "#8B8B9E")
    static let inkFaint  = Color(hex: "#C5C5D0")
    static let hairline  = Color(hex: "#E8E4DE")
    static let shadow    = Color(hex: "#1A1A2E", opacity: 0.08)
    static let shadowMd  = Color(hex: "#1A1A2E", opacity: 0.13)
}

/// 폰트 패밀리. 커스텀 폰트 파일을 ios/WhereWeGo/Resources/Fonts 에 넣고
/// Info.plist 의 `UIAppFonts` 에 등록해야 한다. PostScript 명은 실제 폰트 파일 기준으로 보정.
enum WGFont {
    static func serif(_ size: CGFloat) -> Font { .custom("NotoSerifKR-Regular", size: size) } // 웹: Noto Serif KR
    static func emo(_ size: CGFloat) -> Font   { .custom("GowunBatang-Regular", size: size) }  // 웹: Gowun Batang
    static func sans(_ size: CGFloat) -> Font  { .custom("Pretendard-Regular", size: size) }   // 웹: Pretendard
    static func mono(_ size: CGFloat) -> Font  { .custom("JetBrainsMono-Regular", size: size) }// 웹: JetBrains Mono
}
