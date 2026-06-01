import SwiftUI

// 장소 방문 감지 토스트(설계 §4, FR-27/29). 화면 정중앙 카드(웹 VisitToast.tsx 톤 이식).
// frontend/src/app/map/_components/VisitToast.tsx — 메모/장소+주소/날짜+작성자 + "네 다녀왔어요"/"나중에요".
//
// 표시 트리거: MapViewModel.visitToastPin(@Published). "네" → confirmVisit, "나중에" → dismiss.
// 자동 닫힘 없음. MapView 에서 .overlay 로 띄운다.
struct VisitToastView: View {
    let pin: PinSummary
    let onConfirm: () -> Void
    let onSkip: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("함께 방문하셨나요?")
                .font(WGFont.sans(13))
                .fontWeight(.semibold)
                .foregroundStyle(WGColor.cta)
                .padding(.bottom, 14)

            if let memo = pin.memo, !memo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(memo)
                    .font(WGFont.sans(15))
                    .foregroundStyle(WGColor.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 12)
            }

            HStack(spacing: 7) {
                Circle().fill(tagColor(pin.tag)).frame(width: 8, height: 8)
                Text(pin.placeName)
                    .font(WGFont.sans(13.5))
                    .fontWeight(.bold)
                    .foregroundStyle(WGColor.ink)
            }
            if let address = pin.address, !address.isEmpty {
                Text(address)
                    .font(WGFont.mono(11.5))
                    .foregroundStyle(WGColor.inkSoft)
                    .padding(.leading, 15)
                    .padding(.top, 2)
            }

            footer
                .padding(.top, 12)

            HStack(spacing: 8) {
                Button(action: onSkip) {
                    Text("나중에요")
                        .font(WGFont.sans(13))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .foregroundStyle(WGColor.ctaSub)
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(WGColor.hairline, lineWidth: 1))
                }
                Button(action: onConfirm) {
                    Text("네, 다녀왔어요")
                        .font(WGFont.sans(13))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(WGColor.cta)
                        .foregroundStyle(WGColor.panel)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                }
            }
            .padding(.top, 14)
        }
        .padding(EdgeInsets(top: 16, leading: 18, bottom: 14, trailing: 18))
        .frame(maxWidth: 380)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(WGColor.hairline, lineWidth: 1))
        .shadow(color: WGColor.shadowMd, radius: 14, y: 6)
        .padding(.horizontal, 12)
    }

    private var footer: some View {
        HStack(spacing: 6) {
            Text(Self.formatDate(pin.createdAt))
                .font(WGFont.mono(12))
                .foregroundStyle(WGColor.inkSoft)
            if let author = pin.createdByNickname, !author.isEmpty {
                Text("written by")
                    .font(WGFont.sans(11))
                    .foregroundStyle(WGColor.inkSoft)
                Text(author)
                    .font(WGFont.sans(12))
                    .fontWeight(.semibold)
                    .foregroundStyle(WGColor.ink)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 10)
        .overlay(alignment: .top) {
            Rectangle().fill(WGColor.hairline).frame(height: 1)
        }
    }

    private func tagColor(_ tag: PinTag) -> Color {
        switch tag {
        case .REEL: return WGColor.pinReel
        case .WISH: return WGColor.pinWish
        case .MEMORY: return WGColor.pinMemory
        }
    }

    /// ISO8601 createdAt → "YYYY.MM.DD"(웹 formatDate 동치). 파싱 실패 시 빈 문자열.
    static func formatDate(_ iso: String) -> String {
        guard let date = VisitDateFormatter.parse(iso) else { return "" }
        return VisitDateFormatter.dotted(date)
    }
}

// MARK: - 방문 날짜 포맷(VisitToast/VisitMemoSheet 공용)

/// ISO8601 파싱 + 점 구분 날짜 포맷(YYYY.MM.DD). 웹 formatDate / dateLabel 동치.
enum VisitDateFormatter {
    /// ISO8601 문자열 → Date. fractional seconds 유무 양쪽 시도.
    static func parse(_ iso: String) -> Date? {
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = withFraction.date(from: iso) { return date }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: iso)
    }

    /// Date → "YYYY.MM.DD".
    static func dotted(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy.MM.dd"
        return formatter.string(from: date)
    }
}
