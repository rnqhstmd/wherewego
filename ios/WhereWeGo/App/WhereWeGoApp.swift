import SwiftUI
import KakaoSDKAuth
import KakaoSDKCommon

// 앱 진입점(설계 §12, FR-1/7/19/20).
@main
struct WhereWeGoApp: App {
    @State private var dependencies = AppDependencies()
    /// APNs 토큰 콜백·알림 델리게이트 연결용(설계 §8). SwiftUI 가 인스턴스를 생성한다.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        // 카카오 키가 설정된 경우에만 SDK 초기화(QE-3). placeholder 상태에서도 크래시 없음.
        if AppConfig.isKakaoKeyConfigured {
            KakaoSDK.initSDK(appKey: AppConfig.kakaoAppKey)
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView(dependencies: dependencies)
                // AppDelegate(adaptor 생성)에 푸시 의존 주입 + 알림 센터 연결 보장(설계 §8).
                // 앱 진입 시 1회. wire 는 멱등(센터 delegate 재할당 안전).
                .task {
                    dependencies.wire(appDelegate: appDelegate)
                }
                .onOpenURL { url in
                    handleOpenURL(url)
                }
        }
    }

    /// URL 진입 처리(설계 §9). Universal Link(딥링크) 우선 시도 → 실패 시 카카오 로그인 콜백.
    /// deepLinkRouter.handleUniversalLink 가 처리 가능하면 pending 세팅 후 종료(true).
    /// 처리 불가(false)면 기존 카카오톡 앱 로그인 콜백 처리로 폴백(BR-7 보존).
    private func handleOpenURL(_ url: URL) {
        if dependencies.deepLinkRouter.handleUniversalLink(url) {
            return
        }
        if AuthApi.isKakaoTalkLoginUrl(url) {
            _ = AuthController.handleOpenUrl(url: url)
        }
    }
}
