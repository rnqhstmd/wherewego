import SwiftUI

// 그룹 화면(설계 §11, FR-17). 온보딩 라우트 종착. P4 에서 구현.
struct GroupsView: View {
    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()
            Text("그룹 화면 — P4에서 구현")
                .font(WGFont.sans(15))
                .foregroundStyle(WGColor.inkSoft)
        }
        .navigationBarBackButtonHidden(true)
    }
}
