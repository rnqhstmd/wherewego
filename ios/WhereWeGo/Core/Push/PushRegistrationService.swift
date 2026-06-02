import Foundation
import UserNotifications
import UIKit

// APNs 푸시 권한 요청 + 디바이스 토큰 등록/해제(설계 §8, FR-17/FR-18, BR-9).
// 흐름:
//  - 권한 요청(Q4: 온보딩 NotificationView) → 허용 시 registerForRemoteNotifications() 호출.
//  - AppDelegate 가 받은 APNs 토큰(Data) → hex 변환 → deviceAPI.register + 토큰 UserDefaults 보관.
//  - 로그아웃/계정삭제(FR-18) → 보관 토큰 unregister(없으면 no-op).
// 권한 거부/시스템 오류 시 스킵(BR-9, 무크래시) — 푸시 미동작은 앱 흐름을 깨지 않는다.

protocol PushRegistrationServicing: Sendable {
    /// 알림 권한 요청 → 허용 시 원격 알림 등록(FR-17/Q4). 거부 시 스킵(BR-9).
    func requestAuthorizationAndRegister() async
    /// AppDelegate didRegisterForRemoteNotifications 콜백 → 토큰 hex 변환 후 서버 등록 + 보관.
    func didReceiveAPNsToken(_ data: Data) async
    /// 보관 토큰 해제(FR-18). 보관 토큰 없으면 no-op.
    func unregisterCurrentToken() async
}

final class PushRegistrationService: PushRegistrationServicing {

    /// 마지막으로 서버에 등록한 APNs 토큰(hex) 보관 키. 로그아웃 시 해제 대상 식별에 사용.
    private static let tokenDefaultsKey = "push.apns.deviceToken"

    private let deviceAPI: DeviceAPIProtocol
    private let defaults: UserDefaults

    init(deviceAPI: DeviceAPIProtocol, defaults: UserDefaults = .standard) {
        self.deviceAPI = deviceAPI
        self.defaults = defaults
    }

    func requestAuthorizationAndRegister() async {
        let center = UNUserNotificationCenter.current()
        do {
            let granted = try await center.requestAuthorization(options: [.alert, .badge, .sound])
            // 거부 시 스킵(BR-9) — 원격 등록을 호출하지 않는다.
            guard granted else { return }
            // registerForRemoteNotifications 는 메인 스레드에서 호출해야 한다.
            await MainActor.run {
                UIApplication.shared.registerForRemoteNotifications()
            }
        } catch {
            // 권한 요청 실패(시스템 오류) — 스킵(BR-9, 무크래시).
        }
    }

    func didReceiveAPNsToken(_ data: Data) async {
        let token = Self.hexString(from: data)
        do {
            try await deviceAPI.register(deviceToken: token)
            // 서버 등록 성공 후에만 보관 — 로그아웃 해제 대상은 실제 등록된 토큰으로 한정.
            defaults.set(token, forKey: Self.tokenDefaultsKey)
        } catch {
            // 등록 실패 — 스킵(BR-9). 다음 토큰 콜백/재로그인에서 재시도.
        }
    }

    func unregisterCurrentToken() async {
        // 보관 토큰 없으면 no-op(FR-18/AC-12 멱등).
        guard let token = defaults.string(forKey: Self.tokenDefaultsKey) else { return }
        do {
            try await deviceAPI.unregister(deviceToken: token)
        } catch {
            // 해제 실패는 무시(best-effort) — 서버 죽은 토큰 정리/410 흐름으로도 회수된다.
        }
        // 성공/실패와 무관하게 로컬 보관은 비운다(중복 해제 방지).
        defaults.removeObject(forKey: Self.tokenDefaultsKey)
    }

    /// APNs 디바이스 토큰 Data → 소문자 hex 문자열 변환(순수). 백엔드 device_token 컬럼 형식.
    static func hexString(from data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}
