import SwiftUI
import UIKit

// 핀 공유 카드 시트. 웹 frontend/src/app/map/_components/PinShareSheet.tsx 이식(iOS 관용 UX).
// 마운트 즉시 카드(1080×1350 PNG)를 렌더 → 4:5 미리보기 + 시스템 공유(ShareLink) + 사진 저장.
//  - 웹의 "이미지 복사 / 이미지 저장 / 링크 복사"는 iOS 시스템 공유 시트가 포괄하므로 [공유] 하나로 통합하고,
//    웹 "이미지 저장" 대응으로 [사진 저장]을 별도 제공한다.
//  - Mapbox 실패는 렌더러가 단색 폴백 처리하므로 별도 분기 없음(웹 BR-6).
struct PinShareCardSheet: View {
    let pin: PinSummary
    /// 카드 배경 지도에 함께 표시할 그룹 핀(자기 핀은 렌더러가 자동 제외).
    let groupPins: [PinSummary]
    var onClose: () -> Void

    @State private var phase: Phase = .loading
    @State private var saved = false

    private enum Phase {
        case loading
        case ready(image: UIImage, fileURL: URL?)
        case failed
    }

    var body: some View {
        VStack(spacing: 16) {
            header
            preview
            actions
            Spacer(minLength: 0)
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(WGColor.panel)
        .presentationDetents([.large])
        .task { await load() }
    }

    // MARK: - 헤더

    private var header: some View {
        HStack {
            Text("공유 카드")
                .font(WGFont.sans(16))
                .fontWeight(.bold)
                .foregroundStyle(WGColor.ink)
            Spacer()
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(WGColor.inkSoft)
            }
            .accessibilityLabel("닫기")
        }
    }

    // MARK: - 미리보기(4:5)

    private var preview: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12).fill(WGColor.mapBg)
            switch phase {
            case .loading:
                VStack(spacing: 12) {
                    ProgressView()
                    Text("카드를 만들고 있어요…")
                        .font(WGFont.sans(13))
                        .foregroundStyle(WGColor.inkSoft)
                }
            case .ready(let image, _):
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            case .failed:
                Text("카드를 만들지 못했어요")
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkSoft)
            }
        }
        .aspectRatio(4.0 / 5.0, contentMode: .fit)
        .frame(maxWidth: 320)
    }

    // MARK: - 액션

    @ViewBuilder
    private var actions: some View {
        switch phase {
        case .ready(let image, let fileURL):
            HStack(spacing: 8) {
                shareLink(image: image, fileURL: fileURL)
                saveButton(image: image)
            }
        default:
            HStack(spacing: 8) {
                disabledAction(title: "공유", filled: true)
                disabledAction(title: "사진 저장", filled: false)
            }
        }
    }

    @ViewBuilder
    private func shareLink(image: UIImage, fileURL: URL?) -> some View {
        let label = Text("공유")
            .font(WGFont.sans(14)).fontWeight(.semibold)
            .frame(maxWidth: .infinity).padding(.vertical, 12)
            .background(WGColor.ink).foregroundStyle(WGColor.panel)
            .clipShape(RoundedRectangle(cornerRadius: 10))
        let imagePreview = SharePreview("공유 카드", image: Image(uiImage: image))
        if let fileURL {
            ShareLink(item: fileURL, preview: imagePreview) { label }
        } else {
            ShareLink(item: Image(uiImage: image), preview: imagePreview) { label }
        }
    }

    private func saveButton(image: UIImage) -> some View {
        Button {
            UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
            saved = true
        } label: {
            Text(saved ? "저장됨 ✓" : "사진 저장")
                .font(WGFont.sans(14)).fontWeight(.semibold)
                .frame(maxWidth: .infinity).padding(.vertical, 12)
                .foregroundStyle(WGColor.ink)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(WGColor.hairline, lineWidth: 1))
        }
    }

    private func disabledAction(title: String, filled: Bool) -> some View {
        Text(title)
            .font(WGFont.sans(14)).fontWeight(.semibold)
            .frame(maxWidth: .infinity).padding(.vertical, 12)
            .foregroundStyle(filled ? WGColor.panel : WGColor.inkSoft)
            .background(filled ? WGColor.hairline : Color.clear)
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(filled ? Color.clear : WGColor.hairline, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - 렌더

    private func load() async {
        let input = PinShareCardInput(pin: pin, groupPins: groupPins)
        guard let image = await PinShareCardRenderer.render(input) else {
            phase = .failed
            return
        }
        let fileURL = Self.writeTempPNG(image, placeName: pin.placeName)
        phase = .ready(image: image, fileURL: fileURL)
    }

    /// 카드 PNG 를 임시 파일로 저장(시스템 공유에 실제 이미지 파일 제공 — 인스타/메시지/저장 호환). 실패 시 nil.
    private static func writeTempPNG(_ image: UIImage, placeName: String) -> URL? {
        guard let data = image.pngData() else { return nil }
        let safe = sanitizeFilename(placeName)
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("우리가갈지도_\(safe).png")
        do {
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            return nil
        }
    }

    /// 파일명 정규화(웹 sanitizeFilename 근사) — 경로 구분자/제어문자 제거, 공백 정리, 최대 40자.
    private static func sanitizeFilename(_ name: String) -> String {
        let illegal = CharacterSet(charactersIn: "/\\:*?\"<>|")
            .union(.controlCharacters)
        let cleaned = name
            .components(separatedBy: illegal).joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let collapsed = cleaned.replacingOccurrences(
            of: "\\s+", with: " ", options: .regularExpression
        )
        let limited = String(collapsed.prefix(40)).trimmingCharacters(in: .whitespaces)
        return limited.isEmpty ? "공유카드" : limited
    }
}
