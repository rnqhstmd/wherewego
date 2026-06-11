import SwiftUI

// 온보딩 라우트 가드 상태머신(설계 §10, FR-10/15, BR-5/6, Q2).
// phase=.authenticated 일 때 RootView 가 표시. 단계 전이는 이 Router 가 중앙 관리한다.
//
// 흐름(Q2): Location → Nickname → (그룹 없음) GroupStart → 생성/합류 → WelcomeWizard(초대 스텝)
//          → 완료 직후 notifAsked==false면 Notification 1회 → Groups.
//          (그룹 이미 있음 = 복귀 사용자, 재설치 포함) → 위저드 생략, 알림 게이트만 거쳐 바로 Groups.
struct OnboardingRouter: View {
    enum Route: Hashable {
        case location
        case nickname
        case resolvingGroup
        case groupStart
        case groups
        case welcome
        case notification
    }

    /// GroupStart 에서 push 되는 네비게이션 목적지.
    enum Destination: Hashable {
        case inviteCode
        case groupCreate
    }

    // MARK: - 순수 라우트 결정 규칙(테스트 가능, 설계 §10)
    //
    // body/@State 에 의존하지 않는 결정 규칙만 분리한다. content/네비게이션은 그대로 둔다.
    // 아래 함수들은 initialResolve/resolveRoute/resolveGroupRoute/finishOnboarding 이 그대로 사용한다.

    /// 플래그 단계 판단(그룹 단계 이전). 그룹 단계면 nil 반환(비동기 조회 필요).
    /// - location: locationAsked == false
    /// - nickname: locationAsked == true && nicknameSet == false
    /// - nil: 두 플래그 모두 true → 그룹 조회 단계
    static func resolveFlagRoute(locationAsked: Bool, nicknameSet: Bool) -> Route? {
        if !locationAsked { return .location }
        if !nicknameSet { return .nickname }
        return nil
    }

    /// 그룹 조회 결과 → 라우트.
    /// - group == nil → groupStart(그룹 생성/합류 유도)
    /// - group 있음 → groups(바로 메인 지도)
    ///
    /// 온보딩 "진입 시점"에 이미 활성 그룹이 있다는 것 자체가 복귀 사용자라는 뜻이다(재설치/기기 변경 포함).
    /// 이전에는 로컬 notifAsked 플래그로 복귀를 판정해서, 재설치하면 플래그가 초기화돼 "그룹을 만든 적 없는데
    /// 초대 화면이 뜨는" 어색한 흐름이 재발했다. 서버 상태(그룹 유무)만으로 판정하면 이 구멍이 사라진다.
    /// 초대 위저드(.welcome)는 이 함수가 아니라 GroupStart 의 생성/합류 성공 콜백(afterGroupResolved)에서만
    /// 진입한다 — "방금 그룹을 확보한" 첫 1회에만 노출.
    static func resolveGroupRoute(group: ActiveGroup?) -> Route {
        group == nil ? .groupStart : .groups
    }

    /// 위저드 완료/스킵(Q2): notifAsked==false면 알림 1회, 아니면 Groups(AC-17).
    static func resolveFinishRoute(notifAsked: Bool) -> Route {
        notifAsked ? .groups : .notification
    }

    let dependencies: AppDependencies

    // route 초기값을 플래그 기반으로 동기 결정(AC-11). UserDefaults read 는 동기이므로 @State 초기화 시점 안전.
    // 신규 사용자(locationAsked=false)는 즉시 .location 으로 — SplashView 를 거치지 않는다.
    // 두 플래그 통과 시에만 .resolvingGroup(그룹 비동기 조회 단계)으로 폴백.
    @State private var route: Route = OnboardingRouter.resolveFlagRoute(
        locationAsked: OnboardingFlags.locationAsked,
        nicknameSet: OnboardingFlags.nicknameSet
    ) ?? .resolvingGroup
    @State private var path: [Destination] = []
    /// resolveGroupRoute 에서 조회한 활성 그룹(welcome 전달용, 중복 조회 제거).
    @State private var resolvedGroup: ActiveGroup?

    var body: some View {
        NavigationStack(path: $path) {
            content
                .navigationDestination(for: Destination.self) { destination in
                    switch destination {
                    case .inviteCode:
                        InviteCodeView(
                            groupAPI: dependencies.groupAPI,
                            onJoined: { _ in afterGroupResolved() },   // 온보딩은 그룹 재조회로 진행(groupId 무시)
                            onCancel: { path.removeLast() }
                        )
                    case .groupCreate:
                        GroupCreateView(
                            groupAPI: dependencies.groupAPI,
                            // 온보딩: 그룹 생성 성공 → 위저드(초대 스텝)로 진행(.inviteCode onJoined 패턴 동치, groupId 무시).
                            onCreated: { _ in afterGroupResolved() }
                        )
                    }
                }
        }
        .task { await initialResolve() }
    }

    @ViewBuilder
    private var content: some View {
        switch route {
        case .location:
            LocationPermView(onDone: { resolveRoute() })

        case .nickname:
            NicknameView(
                authAPI: dependencies.authAPI,
                onDone: { resolveRoute() }
            )

        case .resolvingGroup:
            SplashView()

        case .groupStart:
            GroupStartView(
                onCreateGroup: { path.append(.groupCreate) },
                onJoin: { path.append(.inviteCode) }
            )

        case .welcome:
            WelcomeWizardView(
                groupAPI: dependencies.groupAPI,
                initialGroup: resolvedGroup,
                onCreateGroup: { path.append(.groupCreate) },
                onJoin: { path.append(.inviteCode) },
                onFinish: { finishOnboarding() }
            )

        case .notification:
            NotificationView(
                onDone: { onNotificationDone() },
                pushRegistration: dependencies.pushRegistration
            )

        case .groups:
            // 온보딩 종착 = 메인 탭(설계 §10, FR-10). 지도/봇방/커플방 + 딥링크 탭 전환.
            MainTabView(dependencies: dependencies)
        }
    }

    // MARK: - 상태머신

    /// 진입 시 1회. 플래그 동기 판단 후, 그룹 단계면 비동기 조회.
    private func initialResolve() async {
        if let flagRoute = Self.resolveFlagRoute(
            locationAsked: OnboardingFlags.locationAsked,
            nicknameSet: OnboardingFlags.nicknameSet
        ) {
            route = flagRoute
        } else {
            await resolveGroupRoute()
        }
    }

    /// 플래그 변경(Location/Nickname 완료) 후 재평가.
    private func resolveRoute() {
        if let flagRoute = Self.resolveFlagRoute(
            locationAsked: OnboardingFlags.locationAsked,
            nicknameSet: OnboardingFlags.nicknameSet
        ) {
            route = flagRoute
        } else {
            route = .resolvingGroup
            Task { await resolveGroupRoute() }
        }
    }

    /// 활성 그룹 유무로 분기: nil → groupStart, 있음 → afterGroupResolved.
    /// 에러는 401 과 비-401 로 분리해 처리한다:
    /// - 401(refresh 1회 실패 후 전파): route 유지(.resolvingGroup/SplashView) → logoutHandler 가
    ///   phase=.unauthenticated 로 전환하면 RootView 가 LoginView 로 자동 리렌더(BR-5).
    ///   try? 로 nil 변환 시 GroupStart 깜빡임 발생하므로 금지.
    /// - 비-401(네트워크 타임아웃/오프라인/5xx/파싱): .task 1회 실행이라 재시도가 없어 route 가
    ///   .resolvingGroup 에 머물면 SplashView 무한 stuck. 그룹 없음으로 간주해 GroupStart 로 폴백
    ///   (사용자가 계속 진행 가능).
    private func resolveGroupRoute() async {
        do {
            let group = try await dependencies.groupAPI.myActiveGroup()
            // 그룹 있음 = 복귀 사용자(재설치 포함) → 초대 위저드 생략. 단 알림 사전 안내(1회)는 보존:
            // notifAsked==false(재설치 포함)면 Notification 을 먼저 거쳐 Groups 로(resolveFinishRoute 재사용).
            // 초대 위저드는 GroupStart 생성/합류 성공 콜백(afterGroupResolved)에서만 진입한다.
            switch Self.resolveGroupRoute(group: group) {
            case .groupStart:
                route = .groupStart
            default:
                route = Self.resolveFinishRoute(notifAsked: OnboardingFlags.notifAsked)
            }
        } catch let apiError as APIError where apiError.status == 401 {
            // 401 → refresh 실패 시 logoutHandler 가 phase 전환 처리(RootView 가 LoginView 로).
            // route 유지(깜빡임 방지).
            return
        } catch {
            // 비-401(네트워크 타임아웃/오프라인/5xx/파싱) → SplashView 무한 stuck 방지.
            // 그룹 없는 것으로 간주하여 GroupStart 로 폴백(사용자가 계속 진행 가능).
            route = .groupStart
        }
    }

    /// 그룹 확보(생성/합류/기존) 후 → 위저드. 위저드 내부에서 자동스킵(AC-19).
    /// 조회로 확보한 그룹을 전달하여 위저드의 중복 조회를 제거(전달 없으면 위저드가 재조회).
    private func afterGroupResolved(_ group: ActiveGroup? = nil) {
        resolvedGroup = group
        path.removeAll()
        route = .welcome
    }

    /// 위저드 완료/스킵(Q2): notifAsked==false면 알림 1회 → 아니면 Groups.
    private func finishOnboarding() {
        path.removeAll()
        route = Self.resolveFinishRoute(notifAsked: OnboardingFlags.notifAsked)
    }

    /// 알림 완료 → notifAsked=true → Groups(AC-17 다음화면=Groups).
    private func onNotificationDone() {
        OnboardingFlags.notifAsked = true
        route = .groups
    }
}
