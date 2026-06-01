import SwiftUI

// Mapbox token 미보유/모듈 부재 시의 폴백 지도 화면(설계 §1, AC-1).
// frontend 의 "지도를 불러올 수 없어요" 빈 상태에 대응.
//
// 이 파일은 어떤 SDK 에도 의존하지 않으며 token 없이 항상 컴파일된다(MUST-1).
// EmptyMapCard(B3) 는 여기서 참조하지 않는다 — 배경 + 안내 메시지만으로 자체 완결한다.
struct PlaceholderMapView: View {
    var body: some View {
        ZStack {
            // 지도 영역 배경(Theme mapBg).
            WGColor.mapBg
                .ignoresSafeArea()

            VStack(spacing: 12) {
                Image(systemName: "map")
                    .font(.system(size: 44, weight: .light))
                    .foregroundStyle(WGColor.inkSoft)

                Text("지도를 불러올 수 없어요")
                    .font(WGFont.serif(18))
                    .foregroundStyle(WGColor.ink)

                Text("지도 설정이 완료되면\n핀을 볼 수 있어요")
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.inkSoft)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
        }
    }
}
