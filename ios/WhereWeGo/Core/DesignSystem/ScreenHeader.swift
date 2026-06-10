import SwiftUI

// 탭 루트 화면 상단의 큰 제목 헤더(앱 공통 디자인 언어).
// 그룹 목록("내 그룹")·마이페이지("마이페이지")가 쓰던 emo 큰 제목 + 부제 톤을 재사용해
// DM·알림 등 다른 탭 화면의 "밋밋함 / 콘텐츠 상단 직착" 을 해소한다.
//
// 사용 규칙: NavigationStack 루트에서 navigationTitle("") 로 인라인 바를 비우고
// 이 헤더를 콘텐츠 최상단에 둔다(기존 GroupListView/MyInfoView 패턴과 동일한 멘탈모델).
struct ScreenHeader: View {
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(WGFont.emo(28))
                .tracking(-1)   // 웹 letterSpacing:-1 정합(GroupListView/MyInfoView 동치)
                .foregroundStyle(WGColor.ink)
            if let subtitle {
                Text(subtitle)
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.inkSoft)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 16)
    }
}
