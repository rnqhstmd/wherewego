import SwiftUI

// 빈 상태 카드(설계 §3, FR-7). 핀 0개 & loaded 일 때 표시.
// frontend/src/app/map/MapClient.tsx 빈 상태 안내 + 핀 추가 유도 CTA 이식.
struct EmptyMapCard: View {
    /// 장소 추가 진입 콜백(FR-8). MapView 가 MapViewModel.enterAddPin(인라인 추가 모드)에 연결(＋ 와 동일 모드).
    let onAddPin: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            Text("아직 저장한 핀이 없어요")
                .font(WGFont.emo(20))
                .foregroundStyle(WGColor.ink)

            Text("가고 싶은 곳을 검색해 첫 핀을 추가해 보세요.")
                .font(WGFont.sans(13))
                .foregroundStyle(WGColor.inkSoft)
                .multilineTextAlignment(.center)

            Button(action: onAddPin) {
                Text("장소 검색하기")
                    .font(WGFont.sans(14))
                    .padding(.horizontal, 22)
                    .padding(.vertical, 12)
                    .background(WGColor.cta)
                    .foregroundStyle(WGColor.panel)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            .padding(.top, 4)
        }
        .padding(EdgeInsets(top: 28, leading: 28, bottom: 28, trailing: 28))
        .frame(maxWidth: 320)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .shadow(color: WGColor.shadowMd, radius: 16, y: 6)
    }
}
