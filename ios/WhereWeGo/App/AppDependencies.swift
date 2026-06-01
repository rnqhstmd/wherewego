import Foundation

// 앱 의존성 조립(설계 §12). 초기화 순환을 차단하기 위해 2단계로 구성한다.
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

        // 5) 박스에 logout 핸들러 동기 주입(§12, 순환 차단). refresh 가 호출하는 시점(로그인 이후)엔
        //    이미 채워져 있어 RootView.task 순서에 의존하지 않는다.
        logoutBox.handler = { [weak session] in
            await session?.logout()
        }
    }
}
