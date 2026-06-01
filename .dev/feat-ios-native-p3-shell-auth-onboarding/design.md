# 설계서(확정): P3 — iOS 골격 + 인증 + 온보딩

> architect 초안 + design-critic(MUST 2건/CONSIDER 4건) + 사용자 Q&A(4건) 반영 확정본. 베이스 develop. `ios/` 단독 변경(backend/web/CI 무영향).
> 초안: design.draft.md. PRD: prd.md.

## 설계 규모
**대형** — 신규 파일 33개(앱 골격 전체), 인증 보안(Keychain·Apple nonce·토큰 갱신) 직결, XcodeGen/xcconfig/SPM 인프라 신설.

## 확정 결정 (Q&A + critic 반영)
| # | 결정 |
|---|------|
| Q1 상태관리 | **ObservableObject + @StateObject/@EnvironmentObject** (@Observable 미사용). SessionStore·모든 VM은 @MainActor final class ObservableObject |
| Q2 NotificationView 위치 | **Welcome 위저드 완료 직후** notifAsked==false일 때 1회 → Groups. AC-17 "다음 화면"=GroupsView 확정. BR-4 플래그에 notifAsked 추가 |
| Q3 Kakao 로그인 | 카카오톡 앱 우선(loginWithKakaoTalk)/미설치 시 loginWithKakaoAccount 폴백(isKakaoTalkLoginAvailable 분기) |
| Q4 Kakao SDK 버전 | SPM **exactVersion** 최신 안정버 고정 후 Swift6 빌드 검증(실패 시 직전 호환버) |
| MUST#1 groups/me null | backend Jackson 전역 inclusion 부재 확인 → 기본 ALWAYS → **data:null 명시**. 현재 APIEnvelope<ActiveGroup?> 이중옵셔널로 안전. 미래 NON_NULL 리스크는 GroupAPI가 status 200→nil 매핑. **APIClient 무변경** |
| MUST#2 TokenRefresher | 순환 부재(TokenStore→APIClient 역참조 없음) → 별도 클래스 폐기. KeychainTokenStore 내부 private refresh POST(URLSession 주입) |

## 배경 및 목적
- P1 백엔드 인증 확장(/auth/kakao/native, /auth/apple/native, /auth/refresh) 완료 상태에서 iOS 앱 기반 구조 확립.
- P3 완료 기준 = (a) 시뮬레이터 컴파일/빌드 성공, (b) UI·플로우·Keychain·라우트가드 로직 완성, (c) 키/계정 주입 시 즉시 동작 배선. 실로그인 검증은 발급 후 분리.
- **키/계정 미보유에서도 빌드·크래시 없는 구조(placeholder/방어)**가 1급 목표.

## 변경 범위

영향: `ios/` 단독. backend/frontend 무영향. deploy.yml(paths:backend/**) 미트리거.

### 신규 파일 (33개)
```
ios/
├── project.yml                                XcodeGen 매니페스트
├── Config/{Shared,Debug,Release}.xcconfig
├── WhereWeGo/
│   ├── App/{WhereWeGoApp,AppDependencies,RootView,OnboardingRouter,SplashView}.swift
│   ├── Info.plist
│   ├── Core/
│   │   ├── Config/AppConfig.swift
│   │   ├── Keychain/KeychainTokenStore.swift          (refresh 내포, TokenRefresher 폐기)
│   │   ├── Auth/{SessionStore,AuthServiceProtocols,KakaoAuthService,AppleAuthService,NonceGenerator,AuthAPI}.swift
│   │   ├── Validation/Nickname.swift
│   │   └── Storage/OnboardingFlags.swift              (location/nickname/notif)
│   ├── Features/
│   │   ├── Auth/{LoginView,LoginViewModel}.swift
│   │   ├── Onboarding/{LocationPermView,NicknameView,NicknameViewModel,GroupStartView,InviteCodeView,InviteCodeViewModel,NotificationView,WelcomeWizardView,WelcomeWizardViewModel}.swift
│   │   ├── Group/{GroupsView,GroupCreateView,GroupAPI}.swift
│   │   └── Common/PermissionDialogView.swift
│   └── Resources/{Assets.xcassets, Fonts/(.gitkeep)}
└── WhereWeGoTests/{NicknameTests,NonceGeneratorTests,KeychainTokenStoreTests,AppConfigTests,ActiveGroupDecodingTests,RouteGuardTests}.swift
```

### 수정 파일 (2)
- `ios/.gitignore` — `*.xcodeproj` 추가(기존 패턴 확인 후 보정)
- `ios/README.md` — XcodeGen 절차로 갱신([Could], 선택)
- **APIClient.swift 무변경**(MUST#1·#2 검증으로 성립). **Theme.swift 무변경**(PostScript 보정은 폰트 확보 후, AC-22).

## 적용 컨벤션
- 네이밍: Swift 표준(타입 PascalCase, 멤버 lowerCamelCase). 디자인 토큰 WGColor/WGFont 접두어. 디렉토리 App/Core/Features/Resources.
- 코드 구조: 기존 APIClient(actor + async/await + APIEnvelope<T>). **인증된 호출은 모두 APIClient 경유**(자체 URLSession 금지). 예외: /auth/refresh(Bearer 불요·401재시도 불요 → KeychainTokenStore 내부 자체 호출).
- 상태관리(Q1): ObservableObject + @StateObject/@EnvironmentObject. @MainActor final class 통일. @Observable 미사용.
- **ViewModel 분할 기준**(CONSIDER 반영): "비동기 API + 에러 상태 + 로딩 상태 중 2개 이상" → VM 분리. VM 보유: Login/Nickname/InviteCode/WelcomeWizard. VM 불요(@State): LocationPerm/Notification(권한 호출만)/GroupStart(네비)/Groups/GroupCreate(stub).
- 에러: 기존 APIError(code/status/message, LocalizedError) 재사용. View 친화 메시지는 VM에서 매핑.
- DI: 생성자 주입. AppDependencies 조립 → RootView에 @StateObject. 외부 의존(Kakao/Apple/네트워크)은 프로토콜(AuthServiceProtocols.swift) 뒤 → 테스트 목 주입.

## 상세 설계

### 1. project.yml — XcodeGen (FR-1/3, Q4)
단일 app + test 타깃. 핵심 키:
```yaml
name: WhereWeGo
options:
  deploymentTarget: { iOS: "17.0" }
  bundleIdPrefix: com.wherewego
configs: { Debug: debug, Release: release }
settings:
  base: { SWIFT_VERSION: "6.0", MARKETING_VERSION: "0.1.0", CURRENT_PROJECT_VERSION: "1" }
configFiles: { Debug: Config/Debug.xcconfig, Release: Config/Release.xcconfig }
packages:
  KakaoOpenSDK:
    url: https://github.com/kakao/kakao-ios-sdk
    exactVersion: "2.24.5"     # Q4: 최신 안정 exact 핀. Swift6 빌드 실패 시 직전 호환버 조정
targets:
  WhereWeGo:
    type: application
    platform: iOS
    sources: [{ path: WhereWeGo }]
    info:
      path: WhereWeGo/Info.plist
      properties:
        UILaunchScreen: {}
        UIAppFonts: [NotoSerifKR-Regular.otf, GowunBatang-Regular.ttf, Pretendard-Regular.otf, JetBrainsMono-Regular.ttf]
        NSLocationWhenInUseUsageDescription: "근처에 어떤 핀이 있는지 보여드릴게요"
        API_BASE_URL: $(API_BASE_URL)
        KAKAO_NATIVE_APP_KEY: $(KAKAO_NATIVE_APP_KEY)
        CFBundleURLTypes: [{ CFBundleURLSchemes: [kakao$(KAKAO_NATIVE_APP_KEY)] }]
        LSApplicationQueriesSchemes: [kakaokompassauth, kakaolink]
    dependencies:
      - { package: KakaoOpenSDK, product: KakaoSDKAuth }
      - { package: KakaoOpenSDK, product: KakaoSDKUser }
      - { package: KakaoOpenSDK, product: KakaoSDKCommon }
  WhereWeGoTests:
    type: bundle.unit-test
    platform: iOS
    sources: [{ path: WhereWeGoTests }]
    dependencies: [{ target: WhereWeGo }]
```
AuthenticationServices/CryptoKit/Security/CoreLocation/UserNotifications는 내장(SPM 불요). URL Scheme placeholder는 OAuth 왕복만 실패(§7 graceful).

### 2. xcconfig 3종 (FR-2, CONSIDER 증명)
```
// Shared.xcconfig
PRODUCT_BUNDLE_IDENTIFIER = com.wherewego.app
PRODUCT_NAME = WhereWeGo
// Debug.xcconfig
#include "Shared.xcconfig"
API_BASE_URL = http:/$()/localhost:8080      // // 주석 회피: :// → /$()/ 이스케이프
KAKAO_NATIVE_APP_KEY = KAKAO_APP_KEY_NOT_SET
// Release.xcconfig
#include "Shared.xcconfig"
API_BASE_URL = https:/$()/api.wherewego.app
KAKAO_NATIVE_APP_KEY = KAKAO_APP_KEY_NOT_SET   // 키 발급 후 교체(or local secret override)
```
$() 빈 치환으로 최종 Info.plist 값은 정상 http://localhost:8080. AppConfigTests에서 복원 증명.

### 3. AppConfig.swift (FR-2)
```swift
enum AppConfig {
    static var apiBaseURL: URL          // Info "API_BASE_URL" → URL. 실패 시 http://localhost:8080 폴백
    static var kakaoAppKey: String
    static var isKakaoKeyConfigured: Bool  // != "KAKAO_APP_KEY_NOT_SET" && !isEmpty (QE-3 단일 판단점)
}
```
inclusion 로직을 순수 함수로 분리(resolveBaseURL(from:)/isConfigured(kakaoKey:)) → AppConfigTests 임의 입력 증명(CONSIDER).

### 4. Nickname.swift (BR-1, FR-12)
웹 nickname.ts 1:1. 정규식 `^[가-힣a-zA-Z0-9]+$`, 2~12자. 백엔드 UserV1Dto `@Pattern("^[가-힣a-zA-Z0-9]+$")`+`@Size(2,12)` 일치 확인.
```swift
enum NicknameValidationResult: Equatable { case valid; case tooShort; case tooLong; case invalidChar }
enum Nickname {
    static func sanitize(_ v: String) -> String   // 허용외 제거 + 12자 절단(Character 단위)
    static func validate(_ v: String) -> NicknameValidationResult
}
```
한글 IME: .onChange에서 sanitize 결과가 다를 때만 바인딩 되돌려 조합 깜빡임 방지. 12자 절단 Character 단위(이모지/결합문자 안전).

### 5. KeychainTokenStore.swift (FR-5/9, BR-3/5) [MUST#2 해소]
**순환 부재:** KeychainTokenStore.refresh()는 APIClient 미참조(/auth/refresh는 Bearer 불요·refreshToken body·401재시도 불요). APIClient→TokenStore 단방향만 존재. → 별도 TokenRefresher 폐기, 내부 private 메서드로 refresh POST(URLSession 주입). 테스트 주입성은 URLSession/URLProtocol 스텁으로 충족.
```swift
actor KeychainTokenStore: TokenStore {            // actor → 동시성 안전(TokenStore: Sendable)
    init(baseURL: URL, session: URLSession = .shared)
    func setLogoutHandler(_ h: @escaping @Sendable () async -> Void)   // 2단계 조립(§12)
    func accessToken() async -> String?            // Keychain read
    func refresh() async throws                     // 아래 흐름
    func saveTokens(access: String, refresh: String) async
    func clear() async                              // access+refresh 삭제
    private func performRefresh(refreshToken: String) async throws -> TokenResponse  // session POST /auth/refresh
    private func read/write/deleteItem(...)         // SecItem
    private var inFlightRefresh: Task<Void, Error>? // 동시 refresh 직렬화(CONSIDER)
}
```
Keychain: kSecClassGenericPassword, service=com.wherewego.tokens, account=accessToken/refreshToken, kSecAttrAccessibleAfterFirstUnlock.
refresh() 흐름(BR-5): ①inFlight 있으면 await 후 반환(동시 401에서 RT 1회만 회전 → 불필요 logout 방지, CONSIDER). ②refreshToken 없음 → clear()+logoutHandler?()+throw. ③performRefresh 401/실패 → clear()+logoutHandler?()+throw. ④성공 → 신규 토큰 저장. logoutHandler는 @Sendable, SessionStore.logout() 호출.

### 6. SessionStore.swift (FR-9/10, BR-5)
```swift
@MainActor final class SessionStore: ObservableObject {
    enum Phase { case launching, unauthenticated, authenticated }
    @Published private(set) var phase: Phase = .launching
    init(tokens: KeychainTokenStore)
    func bootstrap() async              // Keychain accessToken 유무로 phase
    func didLogin(access:refresh:) async // 저장 → phase=.authenticated
    func logout() async                 // 멱등: .unauthenticated면 no-op, 아니면 clear()+전환(BR-5)
}
```
logout 멱등(동시 refresh 실패 다중 호출 안전, CONSIDER). setLogoutHandler로 연결. phase만 인증 담당, 온보딩 분기는 OnboardingRouter(SRP 분리).

### 7. KakaoAuthService.swift (FR-7, BR-7, QE-3, Q3)
```swift
protocol KakaoAuthServicing: Sendable { func login() async throws -> TokenResponse }
@MainActor final class KakaoAuthService: KakaoAuthServicing {
    // 1) isKakaoKeyConfigured false → throw .kakaoNotConfigured (QE-3)
    // 2) Q3: isKakaoTalkLoginAvailable() ? loginWithKakaoTalk : loginWithKakaoAccount
    // 3) oauthToken.accessToken → authAPI.kakaoNative(kakaoAccessToken:)
}
enum AuthError: LocalizedError { case kakaoNotConfigured, cancelled, appleUnavailable, server(APIError) }
```
SDK init: WhereWeGoApp.init에서 isKakaoKeyConfigured일 때만 KakaoSDK.initSDK(QE-3). onOpenURL에서 isKakaoTalkLoginUrl→handleOpenUrl. completion→withCheckedThrowingContinuation. 취소(SdkError .Cancelled)→cancelled→BR-7 메시지.

### 8. AppleAuthService.swift + NonceGenerator.swift (FR-8, BR-2, QE-4)
```swift
enum NonceGenerator {
    static func randomNonce(length: Int = 32) -> String   // SecRandomCopyBytes → URL-safe
    static func sha256Hex(_ s: String) -> String          // CryptoKit SHA256 → 소문자 hex
}
protocol AppleAuthServicing: Sendable { func login() async throws -> TokenResponse }
@MainActor final class AppleAuthService: NSObject, AppleAuthServicing, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    // 1) rawNonce=randomNonce() 2) request.nonce=sha256Hex(rawNonce) ← Apple엔 해시(BR-2)
    // 3) scopes=[.fullName,.email] 4) 콜백: identityToken/code/fullName/email
    // 5) authAPI.appleNative(identityToken:, nonce: rawNonce(평문!), ...) ← 서버엔 평문(BR-2)
}
```
nonce 계약(이중해시 없음): Apple request.nonce=sha256Hex(rawNonce), 서버 body nonce=rawNonce 평문. 서버가 sha256Hex(받은 nonce)==claims.nonce 검증. capability 미설정→ASAuthorizationError catch→appleUnavailable(QE-4). presentation anchor=활성 UIWindowScene keyWindow.

### 9. AuthAPI.swift + GroupAPI.swift (FR-7~9/12/14/16) [MUST#1 해소]
인증 호출은 APIClient.request 경유. 백엔드 경로 전수 확인:

| 호출 | 메서드/경로 | 요청 | 응답 data | 근거 |
|------|------------|------|-----------|------|
| 카카오 네이티브 | POST /auth/kakao/native | {kakaoAccessToken} | TokenResponse | AuthV1Controller:63 |
| Apple 네이티브 | POST /auth/apple/native | {identityToken,nonce,authorizationCode,fullName{givenName,familyName},email} | TokenResponse | :72 |
| 토큰 갱신 | POST /auth/refresh | {refreshToken} | TokenResponse | :81 |
| 닉네임 저장 | **PUT /users/me** | {nickname} | UserResponse{id,nickname,profileImageUrl} | UserV1Controller:30 |
| 활성 그룹 | GET /groups/me | — | ActiveGroupResponse?{groupId,name,createdAt,memberCount} (없으면 null) | GroupV1Controller:82 |
| 초대 수락 | POST /groups/invite-links/{token}/accept | — | InviteAcceptResponse{groupId,acceptedAt} | :51 |
| 초대 발급 | POST /groups/{groupId}/invite-links (201) | — | InviteLinkResponse{token,slug,expiresAt,shareUrl} | :38 |

```swift
struct TokenResponse: Decodable { let accessToken: String; let refreshToken: String; let expiresIn: Int }
struct UserResponse: Decodable { let id: Int; let nickname: String; let profileImageUrl: String? }
struct ActiveGroup: Decodable { let groupId: Int; let name: String; let memberCount: Int }
struct InviteLink: Decodable { let token: String; let slug: String?; let shareUrl: String? }
struct InviteAccept: Decodable { let groupId: Int }
```

**MUST#1 검증 결과(코드 확인):** backend 전역 Jackson inclusion 부재(application.yml jackson 0건, setSerializationInclusion/Customizer/전역 @JsonInclude 0건). @JsonInclude(NON_NULL)은 ChatbotV1Dto에만 국소. → 기본 ALWAYS → 활성 그룹 없으면 `{"meta":{...},"data":null}` **명시 직렬화**. 단 통합테스트(GroupV1ControllerIntegrationTest:332) "data 누락/NullNode 가능" 방어적 주석 → 미래 NON_NULL 리스크 인정.

**결론 — APIClient 무변경, GroupAPI가 두 케이스 흡수:**
```swift
protocol GroupAPIProtocol: Sendable {
    func myActiveGroup() async throws -> ActiveGroup?   // 그룹 없음 nil, 401 throw
    func acceptInvite(token: String) async throws -> InviteAccept
    func issueInviteLink(groupId: Int) async throws -> InviteLink
}
final class GroupAPI: GroupAPIProtocol {
    func myActiveGroup() async throws -> ActiveGroup? {
        do { return try await client.request("/groups/me", type: ActiveGroup.self) }
        catch let e as APIError {
            if e.status == 401 { throw e }            // 인증 만료 → 상위 refresh/logout
            if e.status == 200 || e.status == 204 { return nil }  // SUCCESS+data없음 = 빈 그룹
            throw e                                   // 진짜 에러
        }
    }
}
```
- 현재(data:null) → APIEnvelope<ActiveGroup?> 이중옵셔널 `.some(.none)` → throw 없이 nil(catch 미도달).
- 미래(키 부재) → APIClient status 200+data nil throw → catch가 200→nil 매핑.
- 빈 그룹 vs 에러 구분: 401→throw, 200/204→nil, 그 외→throw.

### 10. RootView.swift + OnboardingRouter.swift (FR-10/15, BR-5/6, Q2)
RootView: phase 분기 — launching→SplashView, unauthenticated→LoginView, authenticated→OnboardingRouter. .task로 bootstrap 1회.
OnboardingRouter(NavigationStack, 의사코드):
```
enum OnboardingRoute { case location, nickname, resolvingGroup, groupStart, groups, welcome, notification }
resolveRoute():
  !locationAsked → .location (BR-6)
  else !nicknameSet → .nickname
  else: .resolvingGroup(로딩) → group=try? groupAPI.myActiveGroup()
        group==nil ? .groupStart : afterGroupResolved()
afterGroupResolved(): route=.welcome   // 위저드 내부 자동스킵(AC-19)
finishOnboarding():                     // 위저드 완료/스킵(Q2)
  !notifAsked ? .notification : .groups
onNotificationDone(): notifAsked=true; route=.groups   // AC-17 다음화면=Groups
```
흐름(Q2): Location→Nickname→GroupStart(또는 그룹있음)→WelcomeWizard(2스텝)→완료 직후 notifAsked==false면 Notification 1회→Groups. 401→APIClient auto refresh→실패 시 logoutHandler→SessionStore.logout→phase전환→LoginView 자동 리렌더(BR-5 완결). 로딩 중 SplashView/ProgressView(FR-10).

### 11. 온보딩 6화면 + stub 2 (FR-11~18)
WGColor/WGFont만 사용. 웹 1:1:
- LocationPermView(FR-11/BR-6): PermissionDialogView. "위치 허용"→requestWhenInUseAuthorization(fire-forget), "나중에"→진행. 두 버튼 모두 locationAsked=true+resolveRoute. 이미 결정됨(!=notDetermined)→prompt 없이 진행. @State.
- NicknameView+VM(FR-12/BR-1): "반가워요\n이름을 알려주세요"(emo32), 언더라인 TextField(emo24/cta), 힌트 "한글,영문,숫자 2~12자". .onChange sanitize, validate.valid로 "다음" 활성. PUT /users/me {nickname}→성공 시 nicknameSet=true+resolveRoute(GroupStart). 실패 인라인 "저장에 실패했어요...".
- GroupStartView(FR-13): "어떻게 시작할까요"(emo28)/"혼자서도, 함께서도 괜찮아요". "새 그룹 만들기"(cta테두리)→GroupCreate, "초대 코드로 합류"(hairline)→InviteCode. 네비(@State).
- InviteCodeView+VM(FR-14/BR-8): "초대 코드를 받았나요?"(emo32). trimmed>0&&!submitting→"합류하기" 활성. POST /groups/invite-links/{trimmed}/accept→성공 시 afterGroupResolved. 실패 "잘못된 코드이거나 만료되었어요". "취소"→pop.
- NotificationView(FR-15/BR-10/Q2): PermissionDialogView. "알림 받아볼래요?"/"함께하는 사람이 핀을 추가하면\n알려드려요". "알림 허용"→requestAuthorization([.alert,.badge,.sound]). denied면 openSettingsURLString(BR-10). 완료→onNotificationDone→Groups(AC-17). @State. 진입: 위저드 완료 직후 notifAsked==false 1회.
- WelcomeWizardView+VM(FR-16/BR-9): 2스텝(챗봇 제거). 2/2 인디케이터. 스텝1(그룹): "함께 갈 곳을 모아봐요"—새그룹/초대코드/다음에. 스텝2(초대링크): "짝꿍에게 링크를 보내요"—onAppear myActiveGroup→issueInviteLink 자동발급→shareUrl(없으면 token 폴백) 표시·복사(UIPasteboard)/다음단계·다음에→finishOnboarding. 자동스킵: 그룹있으면 스텝1 스킵 스텝2부터(AC-19). 발급실패 "초대 링크를 만들지 못했어요"(mono 링크).
- GroupsView stub(FR-17): "그룹 화면 — P4에서 구현"(bg 배경). 라우트 종착.
- GroupCreateView stub(FR-18): "그룹 생성 — P4에서 구현". GroupStart/위저드 스텝1 진입.
- PermissionDialogView 공용: 아이콘+제목+설명+primary/secondary 버튼 vertical. Location/Notification 재사용.

### 12. WhereWeGoApp.swift + AppDependencies.swift (FR-1/7)
@main. init에서 isKakaoKeyConfigured면 KakaoSDK.initSDK(QE-3). .onOpenURL 카카오 콜백.
2단계 조립(초기화 순환 차단):
```swift
final class AppDependencies {
    init() {
        let baseURL = AppConfig.apiBaseURL
        tokens = KeychainTokenStore(baseURL: baseURL)          // 1) refresh 자체(§5)
        client = APIClient(baseURL: baseURL, tokens: tokens)   // 2) tokens 주입(무변경)
        session = SessionStore(tokens: tokens)                 // 3)
        Task { await tokens.setLogoutHandler { [weak session] in await session?.logout() } }  // 4) 후주입 순환차단
        authAPI = AuthAPI(client: client); groupAPI = GroupAPI(client: client)
        kakao = KakaoAuthService(authAPI: authAPI); apple = AppleAuthService(authAPI: authAPI)
    }
}
```
logoutHandler(@Sendable, weak session, @MainActor 격리, CONSIDER). RootView에 session @StateObject, Service @EnvironmentObject/생성자.

## 의존성 및 영향도
- 새 의존성: Kakao iOS SDK(SPM exactVersion, Auth/User/Common). 나머지 내장. XcodeGen 시스템 도구.
- 기존 영향: APIClient.swift·Theme.swift 무변경(MUST#1·#2 검증). 
- 하위호환: iOS 17+ 단독. backend/web/CI 무영향. 웹 쿠키 경로와 별개 엔드포인트.
- 빌드 안전성: Kakao placeholder→init 생략·graceful 실패(QE-3). Apple capability 없음→ASAuthorizationError catch(QE-4). 폰트 없음→시스템 폴백(QE-1). URL 파싱 실패→localhost. groups/me 형태 변동→nil 정규화(MUST#1).

## 구현 순서 ([P]=병렬)
1. [Must] 인프라: project.yml+xcconfig+Info.plist+.gitignore→xcodegen generate→빈 빌드 [최우선]
2. [Must] Core 기반 [P]: AppConfig/Nickname/OnboardingFlags(location/nickname/notif)/NonceGenerator + 단위테스트
3. [Must] Keychain/토큰: KeychainTokenStore(refresh 내포·inFlight)→AuthAPI/GroupAPI(MUST#1 nil 정규화). APIClient 주입 검증
4. [Must] 인증 상태: SessionStore(멱등 logout)+AppDependencies(2단계)+WhereWeGoApp(SDK init/onOpenURL)
5. [Must] 인증 서비스 [P]: KakaoAuthService(Q3), AppleAuthService(BR-2)
6. [Must] 진입 UI: LoginView+VM, RootView, OnboardingRouter, SplashView
7. [Must] 온보딩 [P]: PermissionDialogView→Location/Nickname/GroupStart/InviteCode/Notification
8. [Must] 위저드: WelcomeWizard(2스텝 자동스킵)
9. [Should] stub [P]: Groups/GroupCreate
10. [Must] 통합: xcodegen+시뮬빌드+단위테스트+라우트가드 확인

## 테스트 전략
- Mechanical gate(P3 상한, destination 명시):
  1. `cd ios && xcodegen generate` — 오류 0(AC-1)
  2. `xcodebuild -project WhereWeGo.xcodeproj -scheme WhereWeGo -destination 'generic/platform=iOS Simulator' build` — 컴파일(AC-1/3). import KakaoSDKAuth 확인
  3. `xcodebuild test ... -destination 'platform=iOS Simulator,name=iPhone 15,OS=17.x'` — 단위테스트
- 단위:
  - NicknameTests: sanitize/validate(1자 tooShort/13자 tooLong/이모지 invalidChar/"가나" valid). 웹+백엔드 @Pattern 정합(AC-13)
  - NonceGeneratorTests: sha256Hex 벡터·소문자, randomNonce 길이/charset(BR-2, 이중해시 없음)
  - KeychainTokenStoreTests: save→read→복원(AC-5), clear→nil, refresh 실패→clear+logoutHandler, inFlight 직렬화(동시2회→performRefresh 1회)
  - AppConfigTests(CONSIDER): /$()/ 이스케이프 URL 정상 파싱, 잘못된 입력→localhost 폴백, KAKAO_APP_KEY_NOT_SET→isKakaoKeyConfigured false
  - ActiveGroupDecodingTests(MUST#1): ①data:null→throw없이 nil ②키부재→APIClient throw→GroupAPI 200→nil ③정상객체 디코딩 ④401 rethrow
  - RouteGuardTests: 플래그/그룹 조합별 resolveRoute/finishOnboarding 분기(AC-11/12/14/15/17/20/21). GroupAPIProtocol 목
- 주입성: GroupAPIProtocol/KakaoAuthServicing/AppleAuthServicing 프로토콜 목. KeychainTokenStore는 URLSession/URLProtocol 스텁
- 검증 불가(발급물 의존): 카카오 실로그인(AC-6실행/키), Apple 실로그인(AC-8실행/계정+capability), 폰트(AC-22/파일). **시뮬빌드+로직 단위테스트가 P3 검증 상한.**
