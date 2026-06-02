import Foundation
import UIKit
import UserNotifications

// APNs 디바이스 토큰 콜백 + 알림 델리게이트 연결(설계 §8, FR-17/FR-19/FR-21, BR-9).
//  - didFinishLaunching: UNUserNotificationCenter.delegate 를 주입된 AppNotificationDelegate 로 연결.
//  - didRegisterForRemoteNotifications: APNs 토큰(Data) → PushRegistrationService.didReceiveAPNsToken.
//  - didFailToRegister: 로그만(BR-9, 무크래시) — 푸시 미동작이 앱 흐름을 깨지 않는다.
//
// @UIApplicationDelegateAdaptor 로 SwiftUI 가 인스턴스를 직접 생성하므로 init 주입이 불가하다.
// 따라서 의존은 프로퍼티 setter 로 약결합 주입한다(전역 싱글톤 지양).
// C9 가 AppDependencies 조립 후 이 인스턴스에 pushRegistration·notificationDelegate 를 주입한다.
// 미주입 상태에서도 크래시 없음(옵셔널 가드 / no-op).
final class AppDelegate: NSObject, UIApplicationDelegate {

    /// APNs 토큰 등록을 위임할 서비스. C9 가 AppDependencies 조립 시 주입.
    /// 미주입 시 토큰 콜백은 no-op(무크래시, BR-9).
    var pushRegistration: PushRegistrationServicing?

    /// 알림 표시·탭 응답 델리게이트. C9 가 주입 → didFinishLaunching 에서 센터에 연결.
    /// 강참조 보유(센터 delegate 는 weak 이므로 수명 유지를 위해 AppDelegate 가 소유).
    var notificationDelegate: AppNotificationDelegate?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // 주입된 델리게이트가 있으면 알림 센터에 연결(미주입 시 스킵).
        if let notificationDelegate {
            UNUserNotificationCenter.current().delegate = notificationDelegate
        }
        return true
    }

    /// APNs 등록 성공 → 토큰 Data 를 서비스로 전달(hex 변환·서버 등록은 서비스 책임).
    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        // pushRegistration 은 nonisolated 콜백에서 호출 → async 메서드를 Task 로 브리지.
        // 미주입 시 no-op(무크래시, BR-9).
        Task {
            await pushRegistration?.didReceiveAPNsToken(deviceToken)
        }
    }

    /// APNs 등록 실패 → 로그만(BR-9). 푸시 미동작은 앱 흐름을 깨지 않으므로 무시.
    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        #if DEBUG
        print("[AppDelegate] APNs 등록 실패(무시, BR-9): \(error.localizedDescription)")
        #endif
    }
}
