import SwiftUI
import KakaoSDKAuth
import KakaoSDKCommon

// 앱 진입점(설계 §12, FR-1/7).
@main
struct WhereWeGoApp: App {
    @State private var dependencies = AppDependencies()

    init() {
        // 카카오 키가 설정된 경우에만 SDK 초기화(QE-3). placeholder 상태에서도 크래시 없음.
        if AppConfig.isKakaoKeyConfigured {
            KakaoSDK.initSDK(appKey: AppConfig.kakaoAppKey)
        }
    }

    var body: some Scene {
        WindowGroup {
            // RootView 는 B3 에서 생성된다. AppDependencies 를 주입한다.
            RootView(dependencies: dependencies)
                .onOpenURL { url in
                    // 카카오톡 앱 로그인 콜백 처리.
                    if AuthApi.isKakaoTalkLoginUrl(url) {
                        _ = AuthController.handleOpenUrl(url: url)
                    }
                }
        }
    }
}
