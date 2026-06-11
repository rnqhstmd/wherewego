import SwiftUI

// 그룹 대표 아바타 공용 컴포넌트(GP-1 §2.1). 그룹 이미지 URL 이 있으면 원형 클립 AsyncImage,
// 없거나 로드 실패 시 멤버 프사 콜라주로 폴백:
//  1명 = 단일 원, 2명 = 대각(좌상+우하), 3명 = 삼각(상1+하2), 4명+ = 2×2 그리드.
// 콜라주 셀은 미니 AvatarView 재사용(멤버 프사/이니셜)이며 가입순 ≤4명만 사용(GroupMemberPreview 정렬은 호출측 보장).
struct GroupAvatarView: View {
    /// 그룹 대표 이미지 URL(없으면 nil → 콜라주). thumb 우선은 호출측이 결정.
    let imageUrl: String?
    /// 콜라주 입력 멤버(가입순 권장). 앞에서 ≤4명만 사용.
    let members: [GroupMemberPreview]
    /// 한 변 지름(pt). 전체를 이 크기 원형으로 클립.
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
                        collage
                    @unknown default:
                        collage
                    }
                }
            } else {
                collage
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }

    // MARK: - 콜라주

    /// 가입순 앞 4명만 사용한 인원수별 배치.
    @ViewBuilder
    private var collage: some View {
        let cells = Array(members.prefix(4))
        switch cells.count {
        case 0:
            // 멤버 미로딩/빈 그룹 — 빈 이니셜 원(이름 "" → "?").
            cell(nil, full: size)
        case 1:
            cell(cells[0], full: size)
        case 2:
            // 대각: 좌상 + 우하. 각 셀은 절반 크기, 모서리에 배치.
            ZStack {
                cell(cells[0], full: size / 2)
                    .frame(width: size, height: size, alignment: .topLeading)
                cell(cells[1], full: size / 2)
                    .frame(width: size, height: size, alignment: .bottomTrailing)
            }
        case 3:
            // 삼각: 상단 중앙 1 + 하단 2.
            VStack(spacing: 0) {
                cell(cells[0], full: size / 2)
                HStack(spacing: 0) {
                    cell(cells[1], full: size / 2)
                    cell(cells[2], full: size / 2)
                }
            }
        default:
            // 4명+: 2×2 그리드(가입순 앞 4명).
            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    cell(cells[0], full: size / 2)
                    cell(cells[1], full: size / 2)
                }
                HStack(spacing: 0) {
                    cell(cells[2], full: size / 2)
                    cell(cells[3], full: size / 2)
                }
            }
        }
    }

    /// 콜라주 셀 — 미니 AvatarView 재사용(멤버 프사/이니셜). nil 이면 빈 폴백.
    private func cell(_ member: GroupMemberPreview?, full: CGFloat) -> some View {
        AvatarView(
            imageUrl: member?.profileImageUrl,
            name: member?.nickname ?? "",
            size: full
        )
    }
}
