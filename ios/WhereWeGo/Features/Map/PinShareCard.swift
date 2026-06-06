import SwiftUI
import UIKit

// 핀 공유 카드(신규 ★). frontend/src/app/map/_components/PinShareSheet.tsx +
// frontend/src/lib/share/renderPinCard.ts 의 1차(텍스트+그라데이션) 폴백을 SwiftUI 로 이식.
//
// 웹은 Canvas 로 1080×1350(4:5) PNG 를 만들고, 지도 스냅샷 합성을 시도한다.
// 1차 이식 범위(설계 ★): 지도 스냅샷 합성은 제외하고 "핀 카드(장소명·태그·주소·날짜·작성자
// 텍스트 + 그라데이션 배경)"만 SwiftUI 로 렌더 → ImageRenderer 로 UIImage 생성한다.
// 지도 스냅샷 합성은 후속(MapSnapshotter 도입 시).
//
// 사용처: PinDetailContent 의 공유 카드 모달(PinShareCardSheet)이
//  - 미리보기로 PinShareCardView 를 직접 렌더(4:5 비율 박스)
//  - "이미지 복사/저장/링크 복사" 시 PinShareCardRenderer.render(...) 로 UIImage 생성
// 를 모두 이 파일의 구성요소로 처리한다.

// MARK: - 카드 본체 뷰(미리보기 & ImageRenderer 공용)

/// 핀 정보 카드(4:5). 그라데이션 배경 위에 장소명·태그·주소·날짜·작성자 텍스트.
/// 미리보기(화면)와 ImageRenderer(이미지 생성) 양쪽에서 동일하게 사용한다 — 1:1 시각 일치.
struct PinShareCardView: View {
    let pin: PinSummary
    /// 카드 한 변 기준 폭(미리보기 vs 렌더 해상도 스케일에 따라 달라진다). 높이는 5/4.
    let width: CGFloat

    /// 작성자 라벨(웹 BR-1 동치): 닉네임 없으면 "익명".
    private var authorLabel: String {
        if let nickname = pin.createdByNickname, !nickname.isEmpty { return nickname }
        return "익명"
    }

    /// 표시 날짜: MEMORY + visitedAt 이면 다녀온 날, 그 외 createdAt(웹 PinPopup dateSource 동치).
    private var dateLabel: String {
        let iso = (pin.tag == .MEMORY ? pin.visitedAt : nil) ?? pin.createdAt
        guard let date = VisitDateFormatter.parse(iso) else { return "" }
        return VisitDateFormatter.dotted(date)
    }

    /// 콘텐츠 스케일 기준값(width=320 일 때 1.0). 렌더 시 큰 폭이면 폰트/여백이 비례 확대.
    private var scale: CGFloat { width / 320 }

    var body: some View {
        ZStack {
            // 그라데이션 배경 — 태그색 → 어두운 잉크. 웹 단색/그라데이션 폴백 톤 이식.
            LinearGradient(
                colors: [tagColor(pin.tag), WGColor.ink],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            VStack(alignment: .leading, spacing: 0) {
                Spacer(minLength: 0)

                // 태그 배지(점 + 라벨).
                HStack(spacing: 7 * scale) {
                    Circle()
                        .fill(.white)
                        .frame(width: 9 * scale, height: 9 * scale)
                    Text(tagLabel(pin.tag))
                        .font(WGFont.sans(13 * scale))
                        .fontWeight(.semibold)
                        .foregroundStyle(.white.opacity(0.92))
                }
                .padding(.horizontal, 12 * scale)
                .padding(.vertical, 7 * scale)
                .background(.white.opacity(0.16), in: Capsule())
                .padding(.bottom, 16 * scale)

                // 장소명(좌표만 있는 핀은 좌표 표기).
                Text(placeText)
                    .font(WGFont.serif(30 * scale))
                    .foregroundStyle(.white)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)

                // 주소(mono).
                if let address = pin.address, !address.isEmpty {
                    Text(address)
                        .font(WGFont.mono(12.5 * scale))
                        .foregroundStyle(.white.opacity(0.78))
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 10 * scale)
                }

                Spacer(minLength: 0)

                // 하단: 날짜 + 작성자.
                HStack(spacing: 6 * scale) {
                    Text(dateLabel)
                        .font(WGFont.mono(12 * scale))
                        .italic()
                        .foregroundStyle(.white.opacity(0.7))
                    if !authorLabel.isEmpty {
                        Text("by")
                            .font(WGFont.sans(11 * scale))
                            .italic()
                            .foregroundStyle(.white.opacity(0.7))
                        Text(authorLabel)
                            .font(WGFont.sans(12 * scale))
                            .fontWeight(.semibold)
                            .foregroundStyle(.white)
                    }
                }
                .padding(.top, 14 * scale)
                .overlay(alignment: .top) {
                    Rectangle()
                        .fill(.white.opacity(0.22))
                        .frame(height: 1)
                }

                // 앱 워터마크.
                Text("우리가 갈 지도")
                    .font(WGFont.sans(11 * scale))
                    .foregroundStyle(.white.opacity(0.55))
                    .padding(.top, 12 * scale)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .padding(28 * scale)
        }
        .frame(width: width, height: width * 5 / 4)
    }

    /// 장소명 표시값 — 비어 있으면 좌표(웹 place 폴백 동치).
    private var placeText: String {
        let name = pin.placeName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !name.isEmpty { return name }
        return String(format: "%.5f, %.5f", pin.latitude, pin.longitude)
    }

    private func tagColor(_ tag: PinTag) -> Color {
        switch tag {
        case .REEL: return WGColor.pinReel
        case .WISH: return WGColor.pinWish
        case .MEMORY: return WGColor.pinMemory
        }
    }

    private func tagLabel(_ tag: PinTag) -> String {
        switch tag {
        case .REEL: return "발견"
        case .WISH: return "위시"
        case .MEMORY: return "추억"
        }
    }
}

// MARK: - 이미지 렌더러(ImageRenderer → UIImage)

/// PinShareCardView → UIImage 변환(공유/저장/복사용). iOS 16+ ImageRenderer.
/// 1080×1350(4:5) 해상도로 렌더해 웹 PNG 해상도와 동일 톤을 맞춘다.
enum PinShareCardRenderer {
    /// 카드 한 변(렌더 폭). 높이는 5/4 = 1350.
    static let renderWidth: CGFloat = 1080

    /// PinShareCardView 를 1080×1350 UIImage 로 렌더. 실패 시 nil.
    @MainActor
    static func render(pin: PinSummary) -> UIImage? {
        let renderer = ImageRenderer(
            content: PinShareCardView(pin: pin, width: renderWidth)
                .environment(\.colorScheme, .light)
        )
        // ImageRenderer 는 content 의 고유 크기를 그대로 픽셀로 매핑한다(scale=1).
        // PinShareCardView 가 width=1080 으로 고정 프레임이라 별도 proposedSize 불필요.
        renderer.scale = 1
        return renderer.uiImage
    }
}

// MARK: - 공유 카드 모달(중앙 모달)

/// 핀 공유 카드 모달(웹 PinShareSheet.tsx 동치). 중앙 다이얼로그로 카드 미리보기(4:5) +
/// [이미지 복사][이미지 저장][링크 복사]. 이미지/링크는 UIPasteboard·UIActivityViewController 로 처리.
/// 지도 스냅샷 합성은 후속 — 1차는 텍스트 카드만(설계 ★).
struct PinShareCardSheet: View {
    let pin: PinSummary
    var onClose: () -> Void

    /// 하단 인라인 안내(복사/저장 결과). 3초 후 자동 소거.
    @State private var notice: String?
    /// 시스템 공유시트(이미지 저장/공유) 표시 여부 + 대상 이미지(Identifiable 래퍼).
    @State private var shareImage: ShareImage?
    /// "복사됨 ✓" 일시 표시.
    @State private var justCopiedImage = false
    @State private var justCopiedLink = false

    /// 공유할 딥링크 URL — https://{domain}/map?pinId=N(딥링크 라우터 정합).
    /// 도메인은 AppConfig.appLinksDomain(빌드 설정). 미설정 시 운영 도메인 폴백.
    private var shareLink: String {
        let domain = AppConfig.appLinksDomain ?? "wherewego.app"
        return "https://\(domain)/map?pinId=\(pin.id)"
    }

    var body: some View {
        ZStack {
            // backdrop — 바깥 탭 닫기.
            Color.black.opacity(0.55)
                .ignoresSafeArea()
                .onTapGesture { onClose() }

            VStack(spacing: 16) {
                // 헤더: 타이틀 + 닫기 X.
                HStack {
                    Text("공유 카드")
                        .font(WGFont.sans(16))
                        .fontWeight(.bold)
                        .foregroundStyle(WGColor.ink)
                    Spacer()
                    Button(action: onClose) {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(WGColor.inkSoft)
                            .frame(width: 28, height: 28)
                            .contentShape(Rectangle())
                    }
                }

                // 미리보기(4:5). 카드 본체 뷰를 그대로 화면 폭에 맞춰 렌더.
                PinShareCardView(pin: pin, width: 260)
                    .clipShape(RoundedRectangle(cornerRadius: 12))

                // 액션: 이미지 복사 / 이미지 저장.
                HStack(spacing: 8) {
                    Button(action: copyImage) {
                        Text(justCopiedImage ? "복사됨 ✓" : "이미지 복사")
                            .font(WGFont.sans(14))
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(WGColor.ink)
                            .foregroundStyle(WGColor.panel)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                    Button(action: saveOrShareImage) {
                        Text("이미지 저장")
                            .font(WGFont.sans(14))
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .foregroundStyle(WGColor.ink)
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(WGColor.hairline, lineWidth: 1))
                    }
                }

                // 링크 복사(별도 row).
                Button(action: copyLink) {
                    HStack(spacing: 8) {
                        Image(systemName: justCopiedLink ? "checkmark" : "doc.on.doc")
                            .font(.system(size: 13, weight: .semibold))
                        Text(justCopiedLink ? "복사됨" : "링크 복사")
                            .font(WGFont.sans(13))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .foregroundStyle(WGColor.inkSoft)
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(WGColor.hairline, lineWidth: 1))
                }

                // 인라인 안내.
                if let notice {
                    Text(notice)
                        .font(WGFont.sans(12))
                        .foregroundStyle(WGColor.inkSoft)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(WGColor.bg)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(WGColor.hairline, lineWidth: 1))
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }
            .padding(20)
            .frame(maxWidth: 360)
            .background(WGColor.panel)
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .shadow(color: .black.opacity(0.22), radius: 30, y: 12)
            .padding(.horizontal, 16)
        }
        .sheet(item: $shareImage) { wrapped in
            ActivityShareSheet(items: [wrapped.image])
        }
        .onChange(of: notice) { _, value in
            guard value != nil else { return }
            // 3초 후 자동 소거.
            Task {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                await MainActor.run { if notice == value { notice = nil } }
            }
        }
    }

    // MARK: - 액션

    /// 카드 이미지를 클립보드에 복사(UIPasteboard.image).
    @MainActor
    private func copyImage() {
        guard let image = PinShareCardRenderer.render(pin: pin) else {
            notice = "이미지를 만들지 못했어요."
            return
        }
        UIPasteboard.general.image = image
        justCopiedImage = true
        notice = "이미지가 복사되었어요"
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            await MainActor.run { justCopiedImage = false }
        }
    }

    /// 카드 이미지를 시스템 공유시트로(사진 저장/타 앱 공유). 웹의 "이미지 저장" 대응.
    @MainActor
    private func saveOrShareImage() {
        guard let image = PinShareCardRenderer.render(pin: pin) else {
            notice = "이미지를 만들지 못했어요."
            return
        }
        shareImage = ShareImage(image: image)
    }

    /// 딥링크 복사(UIPasteboard.string). 그룹 멤버가 열면 해당 핀으로 이동(DeepLinkRouter).
    @MainActor
    private func copyLink() {
        UIPasteboard.general.string = shareLink
        justCopiedLink = true
        notice = "핀 링크가 복사되었어요. 그룹 멤버에게 보내면 바로 이 핀이 열려요"
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            await MainActor.run { justCopiedLink = false }
        }
    }
}

// sheet(item:) 바인딩용 UIImage Identifiable 래퍼(UIImage 는 Identifiable 이 아님).
private struct ShareImage: Identifiable {
    let id = UUID()
    let image: UIImage
}
