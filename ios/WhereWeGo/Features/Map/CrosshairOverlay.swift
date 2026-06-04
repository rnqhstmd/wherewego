import SwiftUI

// 중앙 고정 십자선 오버레이(설계 §컴포넌트①, FR-3/AC-2). 웹 CrosshairOverlay.tsx 1:1 이식.
// 핀 추가 인라인 모드(isAddingPin)에서만 노출되며, 화면 정중앙 = 지도 중심(cameraIdle center)이다.
// allowsHitTesting(false) 로 터치를 지도에 통과시켜 드래그가 가능하다(AC-2).
//
// 웹 동치 치수: 컨테이너 size 28, arm 10, thickness 2, 중앙 dot 3. 색 WGColor.cta.
// 웹은 28×28 박스 안에서 가로선은 left:0 / right:0(가장자리), 세로선은 top:0 / bottom:0 에 두고
// 각 arm 을 thickness 2 로 그린다 → SwiftUI 에서는 28×28 프레임 기준 절대 배치(offset)로 재현한다.
struct CrosshairOverlay: View {
    private let size: CGFloat = 28
    private let arm: CGFloat = 10
    private let thickness: CGFloat = 2
    private let dot: CGFloat = 3

    var body: some View {
        ZStack {
            // 가로선(좌/우): 폭 arm·높이 thickness. 박스 좌/우 가장자리에서 중심 방향으로.
            horizontalArm
                .offset(x: -(size / 2 - arm / 2))
            horizontalArm
                .offset(x: (size / 2 - arm / 2))
            // 세로선(상/하): 폭 thickness·높이 arm. 박스 상/하 가장자리에서 중심 방향으로.
            verticalArm
                .offset(y: -(size / 2 - arm / 2))
            verticalArm
                .offset(y: (size / 2 - arm / 2))
            // 중앙 점.
            Circle()
                .fill(WGColor.cta)
                .frame(width: dot, height: dot)
        }
        .frame(width: size, height: size)
        .allowsHitTesting(false)
    }

    private var horizontalArm: some View {
        Rectangle()
            .fill(WGColor.cta)
            .frame(width: arm, height: thickness)
    }

    private var verticalArm: some View {
        Rectangle()
            .fill(WGColor.cta)
            .frame(width: thickness, height: arm)
    }
}
