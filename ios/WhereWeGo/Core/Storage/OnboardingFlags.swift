import Foundation

// 온보딩 진행 플래그(UserDefaults 래퍼).
// 웹 frontend/src/lib/storage/local-flags.ts 의 maygo:* 키와 1:1 대응(설계 §11, BR-4).
//   maygo:location-asked     → locationAsked
//   maygo:nickname-set       → nicknameSet
//   maygo:notif-asked        → notifAsked
//   onboarding-welcome-seen  → welcomeSeen (웹 WelcomeWizardClient 의 동일 키. 위저드 1회 노출 마킹)
// 테스트에서 UserDefaults 를 교체할 수 있도록 store 를 주입 가능하게 둔다.
enum OnboardingFlags {
    enum Key {
        static let locationAsked = "locationAsked"
        static let nicknameSet = "nicknameSet"
        static let notifAsked = "notifAsked"
        static let welcomeSeen = "welcomeSeen"
    }

    /// 내부 저장소. 테스트에서 별도 suite UserDefaults 로 교체 가능.
    /// UserDefaults 는 자체 thread-safe 이므로 nonisolated(unsafe) 로 Swift 6 동시성 검사를 우회한다.
    nonisolated(unsafe) static var store: UserDefaults = .standard

    static var locationAsked: Bool {
        get { store.bool(forKey: Key.locationAsked) }
        set { store.set(newValue, forKey: Key.locationAsked) }
    }

    static var nicknameSet: Bool {
        get { store.bool(forKey: Key.nicknameSet) }
        set { store.set(newValue, forKey: Key.nicknameSet) }
    }

    static var notifAsked: Bool {
        get { store.bool(forKey: Key.notifAsked) }
        set { store.set(newValue, forKey: Key.notifAsked) }
    }

    /// 환영 위저드(WelcomeWizard)를 1회 노출했는지 여부(웹 onboarding-welcome-seen 패리티).
    /// true 이면 재방문 시 위저드를 건너뛰고 곧장 메인 탭(지도)으로 진입한다.
    static var welcomeSeen: Bool {
        get { store.bool(forKey: Key.welcomeSeen) }
        set { store.set(newValue, forKey: Key.welcomeSeen) }
    }
}
