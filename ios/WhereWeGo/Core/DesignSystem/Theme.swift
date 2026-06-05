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

// MARK: - 글래스 디자인 시스템(클러스터 A — 글래스 플로팅 룩 통일)
//
// 웹의 불투명 카드 대신 iOS 네이티브 글래스(Material) 룩으로 통일한다.
// 탭바·시트·인라인 카드가 동일한 "글래스 플로팅"(Material 배경 + hairline 보더 + 그림자)을 공유한다.
// 지도 위에서도 가독성 있도록 .regularMaterial 을 사용한다(.ultraThinMaterial 은 너무 투명).

extension View {
    /// 글래스 플로팅 카드: Material 배경 + hairline 보더 + 그림자.
    /// 웹 Sheet/패널의 iOS 글래스 대응물. 지도 위에서도 떠 보이도록 보더+그림자를 함께 둔다.
    func glassCard(cornerRadius: CGFloat = 20) -> some View {
        self
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).stroke(WGColor.hairline, lineWidth: 1))
            .shadow(color: Color.black.opacity(0.12), radius: 16, x: 0, y: 8)
    }

    /// 글래스 캡슐: 탭바·칩 등 알약형 플로팅 요소용. Material 배경 + hairline 보더 + 그림자.
    func glassCapsule() -> some View {
        self
            .background(.regularMaterial, in: Capsule(style: .continuous))
            .overlay(Capsule(style: .continuous).stroke(WGColor.hairline, lineWidth: 1))
            .shadow(color: Color.black.opacity(0.12), radius: 16, x: 0, y: 8)
    }
}

/// 시트·카드 상단의 공통 드래그 핸들(36x4, inkFaint, Capsule). 바텀시트임을 시각적으로 표시한다.
struct DragHandle: View {
    var body: some View {
        Capsule()
            .fill(WGColor.inkFaint)
            .frame(width: 36, height: 4)
            .padding(.top, 8)
            .frame(maxWidth: .infinity)
    }
}
