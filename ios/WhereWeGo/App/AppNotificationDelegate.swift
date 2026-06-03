import Foundation
import UserNotifications

// 알림 표시·탭 응답 처리(설계 §8, FR-19/FR-21).
//  - 포그라운드 수신: 배너/목록/사운드로 노출(FR-21) — 앱 사용 중에도 사용자가 새 메시지를 인지.
//  - 탭 응답: notification userInfo(ApnsPushSender 직렬화 type/roomId) → DeepLinkRouter.handlePush(FR-19).
// DeepLinkRouter 는 주입(약결합). 미주입 상태에서도 크래시 없음(가드 후 completion 호출만).
// AppDelegate 가 UNUserNotificationCenter.delegate 로 연결한다(전역 싱글톤 지양).
final class AppNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {

    /// 푸시 탭 → 딥링크 라우팅 대상. C9 가 AppDependencies 조립 시 주입(@MainActor 격리이므로 Task 로 호출).
    /// 미주입 시 탭 응답은 무시(무크래시) — 푸시 미배선이 앱 흐름을 깨지 않는다.
    weak var deepLinkRouter: DeepLinkRouter?

    /// 포그라운드 수신 알림 표시 방식(FR-21). 앱 사용 중에도 배너·목록·사운드로 노출한다.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    /// 알림 탭 응답 → userInfo type 으로 딥링크 이동(FR-19).
    /// DeepLinkRouter 는 @MainActor 격리 → Task 로 메인 컨텍스트에서 호출.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        // Swift 6 동시성: non-Sendable self/userInfo 를 @MainActor Task 로 보내지 않도록,
        // nonisolated 컨텍스트에서 Sendable 값(type String, @MainActor 라우터)만 추출해 캡처한다.
        let pushType = userInfo[DeepLinkRouter.typeKey] as? String
        let router = deepLinkRouter
        Task { @MainActor in
            // 미주입 시 no-op(무크래시, BR-9).
            router?.handlePush(type: pushType)
        }
        completionHandler()
    }
}
