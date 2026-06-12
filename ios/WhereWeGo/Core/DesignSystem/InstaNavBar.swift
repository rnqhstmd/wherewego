import SwiftUI

// 경량 상단바(IG-1, 목업 .navbar). ScreenHeader(고운바탕 큰 제목) 대체 — 인스타그램식 얇은 타이틀 바.
//  - 높이 48pt 고정, 배경 WGColor.bg, 좌측 타이틀(Pretendard Bold 21 + tracking -0.5), 우측 액션 슬롯.
//  - 고운바탕 큰 제목은 온보딩/빈 화면 등 "브랜드 모먼트"에만 남기고, 탭 루트는 이 경량 바로 통일한다.
//  - 우측 슬롯은 @ViewBuilder 제네릭(Trailing). 액션이 없으면 trailing 인자 없이 호출(EmptyView 편의 이니셜라이저).
//
// 사용 규칙: NavigationStack 루트에서 navigationTitle("") 로 인라인 바를 비우고 콘텐츠 최상단에 둔다
// (기존 ScreenHeader 와 동일한 멘탈모델 — 화면 자체 헤더).
struct InstaNavBar<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 0) {
            // 좌측 타이틀: Pretendard 는 고정 웨이트라 .fontWeight() 합성이 안 먹음 → 실제 Bold 페이스 사용.
            Text(title)
                .font(WGFont.sansBold(21))
                .tracking(-0.5)   // 목업 .navbar 타이틀 letter-spacing 정합.
                .foregroundStyle(WGColor.ink)

            Spacer(minLength: 0)

            // 우측 액션 슬롯(아이콘 권장 19~20pt, WGColor.ink). 없으면 EmptyView.
            trailing()
        }
        .frame(height: 48)
        .padding(.horizontal, 16)
        .background(WGColor.bg)
    }
}

// 우측 액션이 없는 편의 이니셜라이저(Trailing == EmptyView). InstaNavBar(title:) 한 인자 호출 허용.
extension InstaNavBar where Trailing == EmptyView {
    init(title: String) {
        self.title = title
        self.trailing = { EmptyView() }
    }
}
