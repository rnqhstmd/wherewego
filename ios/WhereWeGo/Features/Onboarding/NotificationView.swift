import SwiftUI
import UserNotifications
import UIKit

// 알림 권한 요청 화면(설계 §11, FR-15, BR-10, Q2).
// frontend/src/app/onboarding/notification/NotificationClient.tsx 1:1 이식.
// 권한 호출만 하므로 VM 불요(@State). 진입: 위저드 완료 직후 notifAsked==false 1회.
struct NotificationView: View {
    /// 허용/다음에 어느 쪽이든 완료 시 호출. Router 가 notifAsked=true → Groups.
    let onDone: () -> Void
    /// APNs 권한 요청 + 원격 등록 서비스(설계 §8, FR-17). 허용 시 호출.
    let pushRegistration: PushRegistrationServicing

    var body: some View {
        PermissionDialogView(
            icon: "bell.fill",
            title: "알림 받아볼래요?",
            description: "함께하는 사람이 핀을 추가하면\n알려드려요",
            primaryTitle: "알림 허용",
            secondaryTitle: "다음에",
            onPrimary: { Task { await onAllow() } },
            onSecondary: onDone
        )
    }

    private func onAllow() async {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()

        switch settings.authorizationStatus {
        case .denied:
            // 차단 상태는 시스템 설정에서만 변경 가능(BR-10).
            if let url = URL(string: UIApplication.openSettingsURLString) {
                await UIApplication.shared.open(url)
            }
            onDone()
        default:
            // 권한 요청 + 허용 시 원격 알림 등록(FR-17). 거부해도 진행(BR-9).
            await pushRegistration.requestAuthorizationAndRegister()
            onDone()
        }
    }
}
