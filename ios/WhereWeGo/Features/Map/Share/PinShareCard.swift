import SwiftUI

// 핀 공유 카드 스펙·입력·글리프 헬퍼. 웹 frontend/src/lib/share/renderPinCard.ts 상수 1:1 이식.
// 값이 바뀌면 웹 renderPinCard.ts 와 함께 갱신할 것.

/// 카드 레이아웃/타이포 상수(웹 renderPinCard.ts 상수 동치). 단위는 1080×1350 캔버스 기준 px(=pt, scale 1).
enum PinShareCardSpec {
    // 카드 픽셀 사이즈(4:5)
    static let cardWidth: CGFloat = 1080
    static let cardHeight: CGFloat = 1350

    // 콘텐츠 패딩
    static let paddingX: CGFloat = 64
    static var contentMaxWidth: CGFloat { cardWidth - paddingX * 2 } // 952

    // Mapbox Static API 사이즈/줌
    static let mapboxAPIWidth = 1024
    static let mapboxAPIHeight = 1280
    static let mapboxZoom = 15
    static let mapboxTimeout: TimeInterval = 8

    // BR-6 폴백 단색(warm sand). WGColor.mapBg 와 동일 hex.
    static let fallbackBackgroundHex = "#EAE4D4"

    // 콘텐츠 시작 y(웹 CONTENT_START_Y)
    static let contentStartY: CGFloat = 540

    // 폰트 사이즈(웹 FONT_*_PX)
    static let fontMemo: CGFloat = 60
    static let fontPlace: CGFloat = 36
    static let fontMeta: CGFloat = 22
    static let fontWatermark: CGFloat = 24

    // 줄 높이(웹 LINE_HEIGHT_*)
    static let lineHeightMemo: CGFloat = 84
    static let lineHeightPlace: CGFloat = 43
    static let lineHeightMeta: CGFloat = 28

    // 요소 간 gap(웹 GAP_AFTER_*)
    static let gapAfterMemo: CGFloat = 32
    static let gapAfterPlace: CGFloat = 12
    static let gapAfterDate: CGFloat = 8

    // 워터마크 하단 오프셋(웹 WATERMARK_BOTTOM_OFFSET)
    static let watermarkBottomOffset: CGFloat = 64

    // 커스텀 핀 글리프 사이즈(웹 GLYPH_SIZE_* / BG_RADIUS_*)
    static let glyphSizeOther: CGFloat = 24
    static let glyphSizeSelf: CGFloat = 40
    static let bgRadiusOther: CGFloat = 16
    static let bgRadiusSelf: CGFloat = 24
    // 장소명 좌측 태그 글리프(웹 TAG_GLYPH_SIZE / GLYPH_TEXT_GAP)
    static let tagGlyphSize: CGFloat = 28
    static let glyphTextGap: CGFloat = 10

    // 최대 줄 수(웹 wrapAndEllipsize maxLines)
    static let memoMaxLines = 5
    static let placeMaxLines = 2

    // 가독성용 베이지 오버레이 불투명도(웹 rgba(234,228,212,0.35))
    static let beigeOverlayOpacity: Double = 0.35

    // 그룹 핀 최대 표시(웹 slice(0,16))
    static let maxGroupPins = 16

    // API 응답(1024×1280) → 카드(1080×1350) 좌표 변환 비율
    static var scaleX: CGFloat { cardWidth / CGFloat(mapboxAPIWidth) }
    static var scaleY: CGFloat { cardHeight / CGFloat(mapboxAPIHeight) }
}

/// 카드 렌더 입력(웹 RenderPinCardInput 동치).
struct PinShareCardInput {
    let pin: PinSummary
    /// 작성자 라벨(웹 BR-1: createdByNickname ?? "익명").
    let authorLabel: String
    /// "YYYY.MM.DD"(웹 BR-10).
    let formattedDate: String
    /// 카드 배경 지도에 함께 표시할 그룹 핀(자기 핀은 렌더러가 자동 제외).
    let groupPins: [PinSummary]

    /// 핀 + 그룹 컨텍스트로 입력을 구성한다(작성자/날짜 정규화 포함).
    init(pin: PinSummary, groupPins: [PinSummary]) {
        self.pin = pin
        self.authorLabel = Self.authorLabel(for: pin)
        self.formattedDate = Self.formattedDate(for: pin)
        self.groupPins = groupPins
    }

    /// 작성자 라벨 — 닉네임 없으면 "익명"(웹 PinShareSheet 동치).
    static func authorLabel(for pin: PinSummary) -> String {
        authorLabel(nickname: pin.createdByNickname)
    }

    /// 작성자 라벨 순수 로직(닉네임 nil/공백 → "익명"). 단위 테스트 진입점.
    static func authorLabel(nickname: String?) -> String {
        if let nickname,
           !nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return nickname
        }
        return "익명"
    }

    /// 카드 날짜 — 웹 PinShareSheet 와 동일하게 createdAt 사용(YYYY.MM.DD). 파싱 실패 시 빈 문자열.
    static func formattedDate(for pin: PinSummary) -> String {
        VisitDateFormatter.formatDate(pin.createdAt)
    }
}

/// 태그별 마커 색(웹 PIN_COLORS = WGColor.pin* 동치). 형상은 PinGlyphShape(웹 SVG 경로 이식).
enum PinShareGlyph {
    static func color(_ tag: PinTag) -> Color {
        switch tag {
        case .REEL: return WGColor.pinReel
        case .WISH: return WGColor.pinWish
        case .MEMORY: return WGColor.pinMemory
        }
    }
}
