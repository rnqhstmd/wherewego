import SwiftUI

// 사용자 1명 아바타 공용 컴포넌트(GP-1 §2.1). 프로필 이미지 URL 이 있으면 원형 클립 AsyncImage,
// 없거나 로드 실패 시 이름 첫 글자 + 안정 해시 틴트 원으로 폴백(GroupMessageRow.senderAvatar 스타일 일반화, AC-8).
// 틴트색은 이름 안정 해시 → 고정 팔레트 선정이라 동일 사용자는 항상 같은 색(다크/라이트 무난한 디자인 토큰 계열).
struct AvatarView: View {
    /// 유효 프사 URL(없으면 nil → 이니셜 폴백). thumb 우선은 호출측이 결정.
    let imageUrl: String?
    /// 폴백 이니셜·틴트 해시의 입력(닉네임). 빈 문자열이면 "?" 표기.
    let name: String
    /// 한 변 지름(pt). 폴백 글자 크기는 이에 비례(size*0.4).
    let size: CGFloat

    var body: some View {
        Group {
            if let imageUrl, let url = URL(string: imageUrl) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFill()
                    case .empty, .failure:
                        // 로딩 중·실패 모두 이니셜 폴백(AC-8 안전망 — 카카오 URL 만료 등).
                        initialFallback
                    @unknown default:
                        initialFallback
                    }
                }
            } else {
                initialFallback
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }

    // MARK: - 이니셜 폴백

    /// 틴트 원 + 이름 첫 글자(senderAvatar 스타일). 색은 이름 안정 해시로 고정 팔레트 선정.
    private var initialFallback: some View {
        let tint = Self.tintColor(for: name)
        return Circle()
            .fill(tint.opacity(0.15))
            .overlay(
                Text(Self.initial(of: name))
                    .font(WGFont.sansSemiBold(size * 0.4))
                    .foregroundStyle(tint)
            )
    }

    /// 이름 첫 글자(공백 트림). 비면 "?".
    private static func initial(of name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "?" : String(trimmed.prefix(1))
    }

    /// 틴트 팔레트 — 디자인 토큰(WGColor) 계열에서 다크/라이트 무난한 6색.
    /// cta(테라코타)·핀 4색(릴/위시/메모리/뉴)·kakaoInk 대신 ink 톤으로 채도 분산.
    private static let palette: [Color] = [
        WGColor.cta,
        WGColor.pinReel,
        WGColor.pinWish,
        WGColor.pinMemory,
        WGColor.pinNew,
        WGColor.ctaSub,
    ]

    /// 이름 안정 해시 → 팔레트 인덱스. Swift Hasher 는 실행마다 시드가 달라 unicodeScalar 합으로 고정 해시.
    static func tintColor(for name: String) -> Color {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return palette[0] }
        var hash: UInt32 = 5381
        for scalar in trimmed.unicodeScalars {
            hash = (hash &* 31) &+ scalar.value
        }
        return palette[Int(hash % UInt32(palette.count))]
    }
}
