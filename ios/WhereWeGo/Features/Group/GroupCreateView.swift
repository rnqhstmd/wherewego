import SwiftUI

// 그룹 생성 화면(설계 §11, FR-18). GroupStart/위저드 스텝1 진입. P4 에서 구현.
struct GroupCreateView: View {
    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()
            Text("그룹 생성 — P4에서 구현")
                .font(WGFont.sans(15))
                .foregroundStyle(WGColor.inkSoft)
        }
    }
}
