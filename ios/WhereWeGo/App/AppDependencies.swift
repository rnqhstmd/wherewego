import Foundation
import UserNotifications

// 앱 의존성 조립(설계 §12). 초기화 순환을 차단하기 위해 2단계로 구성한다.
// P5 채팅·푸시·딥링크(설계 §4·§8·§9·§11): chatAPI/deviceAPI/pushRegistration/currentUser/
// chatRealtime(단일 인스턴스)/deepLinkRouter 조립 + 로그아웃 디바이스 해제·currentUser.clear 훅 배선.
@MainActor
final class AppDependencies {
    let tokens: KeychainTokenStore
    let client: APIClient
    let session: SessionStore
    let authAPI: AuthAPI
    let groupAPI: GroupAPI
    let kakao: KakaoAuthService
    let apple: AppleAuthService
    let pinAPI: PinAPIProtocol
    let placeAPI: PlaceAPIProtocol
    let locationService: LocationServiceProtocol
    /// 알림 도메인 API(알림함 목록/읽음/상세, 설계 §6). NotificationInboxViewModel 조립에 사용.
    let notificationAPI: NotificationAPIProtocol

    // MARK: - P5 채팅·푸시·딥링크(설계 §4·§8·§9·§11)

    /// 채팅 REST API(봇/커플 메시지 로드·전송).
    let chatAPI: ChatAPIProtocol
    /// 디바이스 토큰 등록/해제 API.
    let deviceAPI: DeviceAPIProtocol
    /// APNs 권한·토큰 등록/해제 서비스(NotificationView·AppDelegate·logout 에서 사용).
    let pushRegistration: PushRegistrationServicing
    /// 현재 사용자 식별자/닉네임 캐시(봇 토픽 path·내 메시지 판별).
    let currentUser: CurrentUser
    /// 채팅 실시간 단일 인스턴스(앱 수명, 설계 §4). 두 방이 공유한다.
    let chatRealtime: ChatRealtimeService
    /// 푸시 탭/Universal Link → 앱 내 이동 라우터(MainTabView 가 pending 소비).
    let deepLinkRouter: DeepLinkRouter
    /// AppDelegate 가 알림 센터에 연결하는 델리게이트(강참조 보유는 AppDelegate).
    let notificationDelegate: AppNotificationDelegate

    /// 앱 표준 로그아웃 경로(설계 §11/§12). logoutBox.handler 와 동일 — 디바이스 토큰 해제·CurrentUser.clear·SessionStore.logout 일괄.
    /// MyInfo 로그아웃/계정삭제가 SessionStore.logout 단독이 아니라 표준 경로를 타도록 MainTabView 가 MyInfoViewModel 에 주입한다.
    let logout: @Sendable () async -> Void

    init() {
        let baseURL = AppConfig.apiBaseURL

        // 0) logout 콜백 박스. 생성 시점에 tokens 에 주입하고, session 생성 후 동기적으로 채운다(§12).
        //    박스 주입·설정이 모두 init 내 동기 완료되어 .task 실행 순서 경쟁이 없다.
        let logoutBox = LogoutHandlerBox()

        // 1) 토큰 저장소(refresh 자체 내포, §5). 생성 시점에 logoutBox 주입(순환 차단).
        let tokens = KeychainTokenStore(baseURL: baseURL, logoutBox: logoutBox)
        // 2) APIClient 에 tokens 주입(APIClient 무변경).
        let client = APIClient(baseURL: baseURL, tokens: tokens)
        // 3) 세션 상태.
        let session = SessionStore(tokens: tokens)

        self.tokens = tokens
        self.client = client
        self.session = session

        // 4) API/서비스 조립.
        self.authAPI = AuthAPI(client: client)
        self.groupAPI = GroupAPI(client: client)
        self.kakao = KakaoAuthService(authAPI: authAPI)
        self.apple = AppleAuthService(authAPI: authAPI)
        // P4 지도/핀/장소/위치(설계 §7·§12). client 주입(API), CoreLocationService 단독 생성.
        self.pinAPI = PinAPI(client: client)
        self.placeAPI = PlaceAPI(client: client)
        self.locationService = CoreLocationService()
        // 알림 도메인 API(설계 §6) — 기존 API 조립 스타일(client 주입) 동일.
        self.notificationAPI = NotificationAPI(client: client)

        // 5) P5 채팅·푸시·딥링크(설계 §4·§8·§9·§11).
        let currentUser = CurrentUser(authAPI: authAPI)
        let deviceAPI = DeviceAPI(client: client)
        let pushRegistration = PushRegistrationService(deviceAPI: deviceAPI)
        // 단일 STOMP 연결(actor) → 단일 ChatRealtimeService(앱 수명). TokenStore·CurrentUser 주입.
        let stompClient = StompClient(baseURL: baseURL)
        let chatRealtime = ChatRealtimeService(
            client: stompClient,
            tokens: tokens,
            currentUser: currentUser
        )
        let deepLinkRouter = DeepLinkRouter()
        // 알림 델리게이트 — 탭 응답을 deepLinkRouter 로 위임(약결합 setter 주입).
        let notificationDelegate = AppNotificationDelegate()
        notificationDelegate.deepLinkRouter = deepLinkRouter

        self.chatAPI = ChatAPI(client: client)
        self.deviceAPI = deviceAPI
        self.pushRegistration = pushRegistration
        self.currentUser = currentUser
        self.chatRealtime = chatRealtime
        self.deepLinkRouter = deepLinkRouter
        self.notificationDelegate = notificationDelegate

        // 6) 박스에 logout 핸들러 동기 주입(§12, 순환 차단). refresh 가 호출하는 시점(로그인 이후)엔
        //    이미 채워져 있어 RootView.task 순서에 의존하지 않는다.
        //    로그아웃 시 디바이스 토큰 해제(FR-18/AC-12, 멱등)·CurrentUser 캐시 비움(다음 사용자 오염 방지)도 함께.
        //    동일 핸들러를 self.logout 으로도 노출(설계 §11) — MyInfo 로그아웃/계정삭제가 표준 경로를 타도록 MainTabView 가 주입.
        let logoutHandler: @Sendable () async -> Void = { [weak session, weak currentUser] in
            await pushRegistration.unregisterCurrentToken()
            await currentUser?.clear()
            await session?.logout()
        }
        logoutBox.handler = logoutHandler
        self.logout = logoutHandler
    }

    /// AppDelegate(@UIApplicationDelegateAdaptor 가 생성)에 푸시 의존을 setter 주입한다(설계 §8).
    /// WhereWeGoApp 이 앱 진입 시 1회 호출 — didFinishLaunching 에서 UNUserNotificationCenter.delegate 연결을 보장한다.
    func wire(appDelegate: AppDelegate) {
        appDelegate.pushRegistration = pushRegistration
        appDelegate.notificationDelegate = notificationDelegate
        // 이미 didFinishLaunching 이 지났을 수 있으므로(adaptor 생성 타이밍) 센터 연결을 한 번 더 보장한다.
        UNUserNotificationCenter.current().delegate = notificationDelegate
    }
}
