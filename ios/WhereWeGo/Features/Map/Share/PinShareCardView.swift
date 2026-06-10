import SwiftUI

// 1080×1350 공유 카드 SwiftUI 뷰. 웹 renderPinCard.ts 의 텍스트 레이어(Step 6~7) 이식.
// 배경(흑백+핀 글리프+blur 처리된 지도)은 PinShareCardRenderer 가 UIImage 로 주입한다.
// 이 뷰는 베이지 오버레이 + 메모/장소명/날짜·주소/작성자/워터마크만 얹고, ImageRenderer 가 PNG 로 추출한다.
struct PinShareCardView: View {
    let input: PinShareCardInput
    /// 흑백+글리프+blur 처리된 배경 지도(1080×1350). nil 이면 단색 폴백(웹 BR-6).
    let background: UIImage?

    var body: some View {
        ZStack(alignment: .topLeading) {
            // 배경 지도(이미 흑백+blur 처리됨) 또는 단색 폴백.
            Group {
                if let bg = background {
                    Image(uiImage: bg)
                        .resizable()
                        .frame(width: PinShareCardSpec.cardWidth, height: PinShareCardSpec.cardHeight)
                } else {
                    WGColor.mapBg // #EAE4D4
                }
            }

            // 가독성용 베이지 오버레이(웹 Step 6: rgba(234,228,212,0.35)).
            WGColor.mapBg.opacity(PinShareCardSpec.beigeOverlayOpacity)

            // 콘텐츠(메모/장소명/날짜·주소/작성자). 좌측 정렬, 시작 y ≈ 540.
            content
                .frame(width: PinShareCardSpec.contentMaxWidth, alignment: .leading)
                .padding(.leading, PinShareCardSpec.paddingX)
                .padding(.top, PinShareCardSpec.contentStartY)

            // 워터마크(좌하단 고정).
            Text("우리가 갈 지도")
                .font(WGFont.sans(PinShareCardSpec.fontWatermark))
                .fontWeight(.semibold)
                .foregroundStyle(WGColor.ink.opacity(0.55))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                .padding(.leading, PinShareCardSpec.paddingX)
                .padding(.bottom, PinShareCardSpec.watermarkBottomOffset)
        }
        .frame(width: PinShareCardSpec.cardWidth, height: PinShareCardSpec.cardHeight)
        .background(WGColor.mapBg)
        .clipped()
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 0) {
            // 메모(웹 BR-2, 최대 5줄).
            if let memo = input.pin.memo,
               !memo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(memo)
                    .font(WGFont.sans(PinShareCardSpec.fontMemo))
                    .fontWeight(.semibold)
                    .foregroundStyle(WGColor.ink.opacity(0.95))
                    .lineLimit(PinShareCardSpec.memoMaxLines)
                    .lineSpacing(PinShareCardSpec.lineHeightMemo - PinShareCardSpec.fontMemo * 1.2)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, PinShareCardSpec.gapAfterMemo)
            }

            // 장소명 + 좌측 태그 글리프(웹 BR-3, 최대 2줄).
            HStack(alignment: .firstTextBaseline, spacing: PinShareCardSpec.glyphTextGap) {
                PinGlyph(tag: input.pin.tag)
                    .fill(PinShareGlyph.color(input.pin.tag))
                    .frame(width: PinShareCardSpec.tagGlyphSize, height: PinShareCardSpec.tagGlyphSize)
                    .shadow(color: PinShareGlyph.color(input.pin.tag).opacity(0.5), radius: 1.5, y: 1)
                    .alignmentGuide(.firstTextBaseline) { $0[.bottom] }
                Text(input.pin.placeName)
                    .font(WGFont.sans(PinShareCardSpec.fontPlace))
                    .fontWeight(.bold)
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(PinShareCardSpec.placeMaxLines)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.bottom, PinShareCardSpec.gapAfterPlace)

            // 날짜 · 주소(웹 BR-9, 1줄).
            Text(dateAddress)
                .font(WGFont.sans(PinShareCardSpec.fontMeta))
                .foregroundStyle(WGColor.ink.opacity(0.6))
                .lineLimit(1)
                .truncationMode(.tail)
                .padding(.bottom, PinShareCardSpec.gapAfterDate)

            // 작성자.
            Text("written by \(input.authorLabel)")
                .font(WGFont.sans(PinShareCardSpec.fontMeta))
                .foregroundStyle(WGColor.ink.opacity(0.6))
                .lineLimit(1)
        }
    }

    /// 날짜 · 주소(주소 없으면 날짜만). 웹 BR-9 동치.
    private var dateAddress: String {
        if let address = input.pin.address, !address.isEmpty {
            return "\(input.formattedDate) · \(address)"
        }
        return input.formattedDate
    }
}
