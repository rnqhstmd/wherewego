import SwiftUI
import CoreLocation

// 위치 권한 요청 화면(설계 §11, FR-11, BR-6).
// frontend/src/app/onboarding/location/LocationPermClient.tsx 1:1 이식.
// 권한 호출만 하므로 VM 불요(@State).
struct LocationPermView: View {
    /// 두 버튼 모두 locationAsked=true 후 호출. Router 가 resolveRoute.
    let onDone: () -> Void

    @State private var locationManager = CLLocationManager()

    var body: some View {
        PermissionDialogView(
            icon: "location.fill",
            title: "위치를 알려주세요",
            description: "근처에 어떤 핀이 있는지\n랜덤 뽑기에서 활용할 거예요",
            primaryTitle: "위치 사용 허용",
            secondaryTitle: "나중에",
            onPrimary: onAllow,
            onSecondary: proceed
        )
        .onAppear {
            // 이미 결정된 상태면 prompt 없이 즉시 진행(BR-6).
            if locationManager.authorizationStatus != .notDetermined {
                proceed()
            }
        }
    }

    private func onAllow() {
        // fire-forget: 권한 prompt 만 트리거하고 결과 대기 없이 진행.
        locationManager.requestWhenInUseAuthorization()
        proceed()
    }

    private func proceed() {
        OnboardingFlags.locationAsked = true
        onDone()
    }
}
