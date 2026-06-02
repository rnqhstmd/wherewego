import SwiftUI

// 앱 루트 뷰(설계 §10, FR-10, BR-5).
// session.phase 분기 — launching→Splash, unauthenticated→Login, authenticated→OnboardingRouter.
// .task 로 bootstrap 1회. SessionStore 는 AppDependencies 가 소유한 인스턴스를 @StateObject 로 관찰.
@MainActor
struct RootView: View {
    private let dependencies: AppDependencies
    @StateObject private var session: SessionStore

    init(dependencies: AppDependencies) {
        self.dependencies = dependencies
        _session = StateObject(wrappedValue: dependencies.session)
    }

    var body: some View {
        Group {
            switch session.phase {
            case .launching:
                SplashView()
            case .unauthenticated:
                LoginView(
                    kakao: dependencies.kakao,
                    apple: dependencies.apple,
                    session: dependencies.session,
                    authAPI: dependencies.authAPI
                )
            case .authenticated:
                OnboardingRouter(dependencies: dependencies)
            }
        }
        .task {
            // logoutHandler 배선은 AppDependencies.init 에서 동기 완료됨(§12, box 패턴).
            // .task 는 bootstrap 만 수행 — 실행 순서 경쟁 없음.
            await session.bootstrap()
        }
    }
}
