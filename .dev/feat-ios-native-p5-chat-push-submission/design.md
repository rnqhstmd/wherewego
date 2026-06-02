# 설계: iOS Phase 5 — 채팅(봇 방 + 1:1 커플방) + 푸시·딥링크 + 제출 자산 (확정본)

## 설계 규모
**대형** — 신규 Feature 모듈(Chat) 전체 + STOMP 실시간 계층(신규 인프라) + APNs/딥링크 배선 + 제출 자산 5종 + 백엔드 데모 시드. FR 27 / BR 9 / AC 21 다영역 변경.

## 핵심 제약 / 검증 결과 (코드 확인)
1. **봇 방 카드→핀 저장 우회**: 봇 방 REST에 카드 선택 actionPayload 엔드포인트 없음 → iOS가 PLACE_CARDS payload 좌표로 `PinAPI.create` 직접 호출. 중복 시 409(`PLC_DUPLICATE_PIN`) 흡수("이미 저장됨" 친화 표시). instagramUrl=nil, tag=REEL.
2. **AC-9 재연결 보완**: 백엔드 `findByRoomIdBefore`는 `id<cursor`(과거)만 → cursor=null 최신 N건 재조회 + 클라이언트 id dedup. 끊긴 동안 50건 초과분은 상단 스크롤 loadMore로만 보완.
3. **AC-10 PIN_SAVED 딥링크**: `PushPayload`상 PIN_SAVED는 roomId·pinId 미포함 → 지도/핀 목록으로 이동(특정 핀 상세 아님).
4. **데모 로그인(refresh 재사용)**: `AuthService.refreshTokens:212`가 매 호출 refreshToken 회전·이전 무효화(1회용) → 정적 시드 토큰 첫 사용 후 깨짐 → **데모 계정 한정 회전 예외**(백엔드 1건, 데모 식별자 매칭 시에만 회전 스킵, 운영 무변경).
5. **me userId**: `GET /api/v1/users/me`가 `{id, nickname, ...}` 반환 → `CurrentUser`에 캐시(봇 토픽 path·내 메시지 판별).

## 배경 및 목적
P1~P4 develop 머지 완료, 지도·핀 동작하나 채팅 미존재·제출 자산 미비. 목표: 봇방·커플방·푸시·딥링크 동작 + 리뷰어 데모 검증 → P6 즉시 착수. 백엔드 P2 완료(일반 API 변경 금지, 데모 시드+회전예외만 추가).

## 변경 범위

### iOS 신규 파일 (`ios/WhereWeGo/`)
채팅 모델·API:
- `Features/Chat/ChatMessageModels.swift` — 백엔드 1:1 Codable (`ChatFrame`/`MessageKind`/`SenderType`/`PlaceCardsPayload`/`PlaceCard`/`MessagesResponse`/`SendMessageResponse`)
- `Features/Chat/ChatAPI.swift` — `ChatAPIProtocol`+`ChatAPI`

채팅 UI:
- `Features/Chat/ChatMessageRow.swift` — 발신자/kind별 버블 분기
- `Features/Chat/PlaceCardsBubble.swift` — PLACE_CARDS 다중선택·저장
- `Features/Chat/ChatScrollContainer.swift` — 역순 로드·최신 스크롤·키보드 회피·연결상태 배너
- `Features/Chat/Bot/BotChatView.swift` + `Bot/BotChatViewModel.swift`
- `Features/Chat/Couple/CoupleChatView.swift` + `Couple/CoupleChatViewModel.swift`

실시간(STOMP) `Core/Realtime/`:
- `StompFrame.swift` — STOMP 1.2 프레임 인코딩/디코딩 순수 로직(테스트)
- `StompClient.swift` — `URLSessionWebSocketTask` 단일 연결 + subscribe/unsubscribe (actor)
- `ChatRealtimeService.swift` — 단일 연결·인증·구독관리·재연결(BR-8) (`ChatRealtimeServicing`)
- `ConnectionState.swift` — connecting/connected/reconnecting/disconnected (QE-2)

푸시·딥링크 `Core/Push/`, `App/`:
- `Core/Push/DeviceAPI.swift` — `DeviceAPIProtocol`+`DeviceAPI` (`POST/DELETE /api/v1/devices`)
- `Core/Push/PushRegistrationService.swift` — 권한·APNs 등록/해제 (`PushRegistrationServicing`)
- `Core/Push/AppNotificationDelegate.swift` — `UNUserNotificationCenterDelegate` (배너 FR-21, 탭 FR-19)
- `App/AppDelegate.swift` — `UIApplicationDelegate` APNs 토큰 콜백 (`@UIApplicationDelegateAdaptor`)
- `App/DeepLinkRouter.swift` — 푸시/Universal Link → `DeepLinkDestination`·폴백
- `Core/Session/CurrentUser.swift` — me userId/닉네임 캐시

데모: `Features/Auth/DemoLoginGate.swift` — 로고 5회 탭 게이트 순수 로직

테스트: `StompFrameTests`, `ChatRealtimeReconnectTests`, `ChatFrameDecodingTests`, `BotChatViewModelTests`, `CoupleChatViewModelTests`, `DeepLinkRouterTests`, `DemoLoginGateTests`, `DeviceAPITests`, `PlaceCardSaveTests`

### iOS 수정 파일
- `App/WhereWeGoApp.swift` — `@UIApplicationDelegateAdaptor`, `.onOpenURL` Universal Link 분기(카카오 보존), DeepLinkRouter 주입
- `App/AppDependencies.swift` — chatAPI/deviceAPI/pushRegistration/chatRealtime/deepLinkRouter/currentUser 조립 + 로그아웃 디바이스 해제
- `App/RootView.swift` / `App/OnboardingRouter.swift` — 종착을 `MainTabView`로 교체 + 딥링크 소비
- (신규) `App/MainTabView.swift` — 하단 TabView(지도/봇방/커플방), 딥링크 탭 전환
- `Features/Auth/LoginView.swift` — 로고 5회 탭 + "데모 로그인" 버튼
- `Features/Auth/LoginViewModel.swift` — `loginDemo()`
- `Features/Onboarding/NotificationView.swift` — 권한 허용 시 `PushRegistrationService` 호출(FR-17/Q4)
- `Core/Auth/SessionStore.swift` — `logout()` 디바이스 해제, `didLoginDemo`
- `Core/Config/AppConfig.swift` — 데모 refreshToken·AASA 도메인 접근자(placeholder 폴백)
- `project.yml` — Push capability·Associated Domains·`remote-notification` BackgroundModes·Privacy 리소스·entitlements
- `Config/Shared.xcconfig`·`Debug.xcconfig`·`Release.xcconfig` — `DEMO_REFRESH_TOKEN`·`APP_LINKS_DOMAIN`
- `Core/DesignSystem/Theme.swift` — 채팅 버블 색상 토큰(필요 시)

### iOS 신규 리소스
- `Resources/Assets.xcassets/AppIcon.appiconset/`(1024 포함 전 해상도, 임시)
- LaunchScreen 자산 + `UILaunchScreen`
- `WhereWeGo/PrivacyInfo.xcprivacy`
- `WhereWeGo/WhereWeGo.entitlements` (aps-environment, associated-domains)

### Backend 신규/수정
- (신규) `support/demo/DemoSeedRunner.java` — `ApplicationRunner`+`@ConditionalOnProperty(prefix="wherewego.demo-seed",name="enabled")` 멱등 시드
- (신규) `support/demo/DemoSeedProperties.java` — 데모 식별자·refreshToken 환경변수 바인딩(BR-7)
- (수정) `domain/auth/AuthService.java` `refreshTokens` — 데모 식별자 매칭 시 refreshTokenHash 회전 스킵(데모 한정 예외, 운영 무변경)

### 웹/인프라 (사용자, 비코드)
- AASA(`/.well-known/apple-app-site-association`) 웹 도메인 호스팅 (FR-20 전제)

## 적용 컨벤션
- 네이밍: `XXXView`+`XXXViewModel`(MVVM), `XXXAPI`+`XXXAPIProtocol`(Sendable), `XXXServicing`+`XXXService`. DTO 백엔드 record와 1:1.
- iOS: `@MainActor final class : ObservableObject`, async/await, 프로토콜 주입. APIClient actor — `request(path,method,body,type)`, `api/v1` 자동 부착(`/chat/...`). 204/빈본문 `EmptyResponse`+`NO_CONTENT` 흡수(PinAPI.delete:153).
- 에러: `APIError`(code/status/message)+도메인 `LocalizedError`. 특정 code(`PLC_DUPLICATE_PIN` 등) 친화 분기.
- DI: `AppDependencies` 2단계 조립, `logoutBox` 순환 차단(:24-50).
- 설정 주입: xcconfig→Info.plist→`AppConfig`, `*_NOT_SET` 폴백.
- Int 매핑: Long→Int(PinSummary). 
- Backend 시드: `ApplicationRunner`+멱등(InviteLinkBackfillRunner). `@ConditionalOnProperty` 게이트.

## 상세 설계

### 1. ChatMessageModels.swift
STOMP MESSAGE body와 REST `MessagesResponse.messages[]`가 동일 `ChatMessageFrame` → 단일 `ChatFrame` 통합. payload는 kind 분기 디코딩(PLACE_CARDS만 cards, TEXT/SYSTEM/MEMO_PROMPT는 text). createdAt String(PinSummary 동일).
```
enum MessageKind: String, Codable { case TEXT, PLACE_CARDS, MEMO_PROMPT, PROCESSING, SYSTEM }
enum SenderType: String, Codable { case USER, BOT, SYSTEM }
struct PlaceCard: Decodable, Identifiable { let kakaoPlaceId: String?; let name: String; let address: String?; let latitude: Double?; let longitude: Double? }
struct PlaceCardsPayload: Decodable { let cards: [PlaceCard] }
struct ChatFrame: Decodable, Identifiable { var id: Int { messageId }; let messageId: Int; let roomId: Int; let senderType: SenderType; let kind: MessageKind; let createdAt: String; let placeCards: [PlaceCard]?; let text: String? }
struct MessagesResponse: Decodable { let messages: [ChatFrame]; let hasMore: Bool; let nextCursor: Int? }
struct SendMessageResponse: Decodable { let messageId: Int; let kind: MessageKind }
```
TEXT/SYSTEM payload `text` 키명은 `ChatMessageAppender` Read로 정합(탐색 항목).

### 2. ChatAPI.swift
`PinAPI` 패턴, `APIClient.request` 경유. Q6 확정: cursor+limit는 path에 `?cursor=10&limit=20` 직접 조합(percentEncodedQuery 보존, APIClient 무변경). cursor nil이면 생략. limit 기본 20(BR-4).
```
protocol ChatAPIProtocol: Sendable {
  func botMessages(cursor: Int?, limit: Int) async throws -> MessagesResponse
  func sendBotMessage(text: String) async throws -> SendMessageResponse
  func coupleMessages(groupId: Int, cursor: Int?, limit: Int) async throws -> MessagesResponse
  func sendCoupleMessage(groupId: Int, text: String) async throws -> SendMessageResponse
}
```

### 3. StompFrame / StompClient (Q1: 직접 구현)
`/ws/chat` raw STOMP(SockJS 아님). CONNECT native header `Authorization: Bearer` 검증. **구현 프레임 한정: CONNECT/CONNECTED/SUBSCRIBE/MESSAGE/ERROR/DISCONNECT**. heart-beat·ACK·트랜잭션 미구현(단일 인스턴스·단방향). 외부 SPM 미추가.
- `StompFrame`: command+headers+body, NUL(`\0`) 종단. 순수 인코딩/디코딩(다중 프레임 NUL 분할).
- `StompClient`(actor): `connect(bearer:)`→CONNECT→CONNECTED 대기, `subscribe/unsubscribe`, `receive` 루프 MESSAGE→콜백·ERROR/close 감지. heart-beat `0,0`.
```
struct StompFrame { let command: String; let headers: [String:String]; let body: String
  func encode() -> URLSessionWebSocketTask.Message; static func decode(_ text: String) -> [StompFrame] }
actor StompClient { func connect(bearerToken:) async throws; func subscribe(destination:id:) async throws; func unsubscribe(id:) async throws; var onMessage: (@Sendable (StompFrame)->Void)?; var onClosed: (@Sendable (Error?)->Void)?; func disconnect() async }
```
CONNECT 헤더 `accept-version:1.2`,`host`,`Authorization:Bearer`. 엔드포인트 `wss://<host>/ws/chat`(API_BASE_URL scheme 변환). 토픽 문자열 서버 화이트리스트 정확 일치.

### 4. ChatRealtimeService (단일 연결 + 화면별 subscribe, BR-8 재연결)
**독립 연결 2개 ❌ → 단일 WebSocket + 화면별 SUBSCRIBE/UNSUBSCRIBE 2개**(critic SIMPLIFY). CONNECT 1회, QE-2 단일 `ConnectionState`.
- 구독: `subscribe(topic:onFrame:)` id(`sub-bot`/`sub-couple`) 부여·토픽별 콜백 라우팅. 진입 subscribe / 이탈 unsubscribe(두 방 빠른 전환 단일 연결 유지).
- 재연결(BR-8): scenePhase `.active` 복귀 또는 close 감지 시 즉시 1회 → 실패 시 5초×3회(총 4회). 4회 실패 → `.disconnected`("연결 끊김"+수동 재시도). 성공 시 활성 구독 re-subscribe + `onReconnected` 콜백.
- 보완조회(AC-9): 재연결 성공 시 ViewModel `reconcileLatest()`가 cursor=null 최신 N건 재조회 + id Set dedup/merge. 50건 초과분은 loadMore.
- 재연결 카운터·간격 순수 로직 분리(now/sleep 주입, 테스트).
```
enum ConnectionState: Equatable { case connecting, connected, reconnecting, disconnected }
protocol ChatRealtimeServicing: AnyObject, Sendable { func subscribe(topic:id:onFrame:) async; func unsubscribe(id:) async; func onForeground() async; func retryManually() async }
@MainActor final class ChatRealtimeService: ObservableObject, ChatRealtimeServicing { @Published private(set) var state: ConnectionState; var onReconnected: (@Sendable ()->Void)? }
```
단일 인스턴스(앱 수명). 봇 토픽 path userId → `CurrentUser` 의존.

### 5. BotChatViewModel / BotChatView (카드→핀 409 흡수, PROCESSING 교체)
- 로드(FR-2): `botMessages(cursor:nil,limit:20)` id DESC → View 오름차순 reverse. 상단 loadMore(nextCursor).
- 전송(FR-3): `sendBotMessage` → `SendMessageResponse.messageId`(PROCESSING) 즉시 버블. 입력 초기화·하단.
- PROCESSING 교체(critic): `pendingProcessingIds:[Int]` FIFO 추적, BOT 결과(PLACE_CARDS/SYSTEM/MEMO_PROMPT) 수신 시 해당 PROCESSING 제거+결과 append. dedup: 동일 messageId id Set 차단. 재조회 시 서버 진실 소스(PROCESSING+결과 공존 시 같은 turn PROCESSING은 결과 존재 시 숨김).
- 카드 저장(FR-5/AC-3): 다중선택→`pinAPI.create`(instagramUrl=nil, placeName/address/lat/lng=카드, tag=.REEL, groupId=myActiveGroup). **409(`PLC_DUPLICATE_PIN`) 흡수**("이미 저장된 장소예요"). 좌표 없는 카드 비활성. 성공 시 백엔드 pushPinSaved(파트너 푸시).
- BR-3: 2000자(AC-4). FR-6/BR-6/FR-27: URL만 전송·미디어 미저장 주석(AC-20). FR-7 MEMO_PROMPT.
```
@MainActor final class BotChatViewModel: ObservableObject {
  @Published private(set) var messages: [ChatFrame]; @Published private(set) var realtimeState: ConnectionState
  @Published var draft: String; @Published var saveInfoMessage: String?
  func appear() async; func disappear() async; func load() async; func loadMore() async; func send() async
  func savePlaceCards(_ selected: [PlaceCard], from messageId: Int) async; func reconcileLatest() async; func retryRealtime() async
}
```

### 6. CoupleChatViewModel / CoupleChatView
- 로드(FR-10): `coupleMessages(groupId:cursor:limit:)`, groupId=myActiveGroup.
- 전송(FR-11/AC-6): 낙관 버블(임시 음수 id)→`sendCoupleMessage`→응답/STOMP로 실제 id 치환·dedup. 실패 시 재시도.
- 실시간(FR-12): `subscribe("/topic/chat/couple/{groupId}")`, `senderType==USER` & 내 메시지(`CurrentUser.id` 비교)는 중복 제거, 파트너만 append.
- BR-3: 1000자(AC-5). 재연결 `reconcileLatest()`.
읽음 확인 제외. 낙관 dedup = messageId 일치 또는 내용+근접시각 휴리스틱.

### 7. 공통 UI
- `ChatMessageRow`: kind/sender 분기 — TEXT(좌우·색 FR-14), PROCESSING(로딩점·결과 시 숨김), PLACE_CARDS(`PlaceCardsBubble`), MEMO_PROMPT(안내), SYSTEM(중앙 FR-8). `WGColor`/`WGFont`.
- `PlaceCardsBubble`: 장소명(세리프)·주소(Mono, chat1.md)+선택 토글+저장 버튼. 좌표 없으면 비활성.
- `ChatScrollContainer`: ScrollViewReader 최신 스크롤(FR-15), 상단 loadMore, 키보드 회피(FR-16), 상단 `ConnectionState` 배너(QE-2/BR-8/AC-8). 빈 상태(FR-13/AC-19): 봇 "릴스 링크를 입력해보세요"/커플 "파트너에게 첫 메시지를 보내보세요".

### 8. DeviceAPI / PushRegistrationService / AppDelegate / AppNotificationDelegate
- `AppDelegate`(`@UIApplicationDelegateAdaptor`): 토큰 hex→`PushRegistrationService`. didFail 로그만(BR-9).
- `PushRegistrationService`: 권한 요청(Q4: 온보딩 `NotificationView`)→허용 시 `registerForRemoteNotifications()`. 토큰 수신→`deviceAPI.register`+토큰 보관(UserDefaults). 거부 스킵(BR-9). 재발급 upsert(서버 reassign).
- `DeviceAPI`: register/unregister(204 흡수).
- 로그아웃/삭제(FR-18/AC-12): `SessionStore.logout()`에서 보관 토큰 `unregister`(없으면 no-op).
- `AppNotificationDelegate`: 포그라운드 `.banner`(FR-21), 탭→`userInfo` type/roomId→`DeepLinkRouter.handlePush`.
```
protocol PushRegistrationServicing: Sendable { func requestAuthorizationAndRegister() async; func didReceiveAPNsToken(_ data: Data) async; func unregisterCurrentToken() async }
protocol DeviceAPIProtocol: Sendable { func register(deviceToken: String) async throws; func unregister(deviceToken: String) async throws }
```
aps-environment entitlement + BackgroundModes(remote-notification). 시뮬레이터 토큰 미발급 → 실기기 전제.

### 9. DeepLinkRouter (AC-10 재해석)
- 입력원: (a) 푸시 탭 payload type→destination, (b) Universal Link(`/invite/{slug}`, `?pinId=`).
- 매핑: `BOT_RESULT`→봇 방(userId, roomId 무시), `COUPLE_MESSAGE`→커플방(myActiveGroup, payload roomId 미사용), `PIN_SAVED`→지도/핀 목록(roomId·pinId 없음).
- 폴백(FR-19/AC-11): 대상 조회 실패 시 `.map`. 인증 전 보류→authenticated 후 소비.
- 소비: `MainTabView`가 destination→탭 전환(.pin/.map→지도+flyTo). `.invite`→그룹 합류.
```
enum DeepLinkDestination: Equatable { case botChat, coupleChat, pin(pinId: Int), invite(slug: String), map }
@MainActor final class DeepLinkRouter: ObservableObject { @Published var pending: DeepLinkDestination?; func handlePush(userInfo:); func handleUniversalLink(_ url:) -> Bool }
```
AASA 미호스팅 시 Universal Link 미작동(P5 전제).

### 10. 데모 로그인 (refresh 재사용 + 회전 예외)
`AuthService.refreshTokens:209-213`이 매 호출 refreshToken 회전·이전 무효화(1회용). `POST /api/v1/auth/refresh` body`{refreshToken}`→`{accessToken,refreshToken,expiresIn}`.
- 게이트(FR-26/AC-17): 워드마크 `onTapGesture` 5회 연속(`DemoLoginGateState` 순수 로직)→"데모 로그인" 버튼(비노출).
- 진입(BR-7/AC-21): `loginDemo()`→`AppConfig.demoRefreshToken`(xcconfig `DEMO_REFRESH_TOKEN`, 비하드코딩, `*_NOT_SET` 시 비활성)으로 `/auth/refresh`→토큰 Keychain→`didLoginDemo`.
- **refresh 회전 차선책(a) 채택**: 백엔드 `refreshTokens`에 데모 식별자(userId/oauthId, `DemoSeedProperties`) 매칭 시 `replaceRefreshTokenHash` 스킵 → 동일 시드 토큰 재사용. 데모 한정, 운영 흐름 무변경. (대안 b: iOS가 회전 토큰 저장 → 재시드/다중기기 깨짐; c: 데모 엔드포인트 → 사용자 배제. → a)
```
struct DemoLoginGateState { mutating func registerTap(now: Date) -> Bool }
// LoginViewModel.loginDemo() ; SessionStore.didLoginDemo(access,refresh) ; AppConfig.demoRefreshToken: String?
```
AC-21: 소스 grep 부재(xcconfig placeholder 커밋+빌드 주입). App Store Connect 안내문에 "로고 5회 탭→데모 로그인".

### 11. CurrentUser (Q3: GET /users/me)
`GET /api/v1/users/me`→`{id,nickname,profileImageUrl}`(`UserV1Dto.UserResponse`, id:Long). 로그인/부트스트랩 후 load→`CurrentUser`(MainActor) 보관. 봇 토픽 path·내 메시지 판별. 기존 `UserResponse`(AuthAPI.swift:16)에 id:Int 보유 → 재사용.
```
@MainActor final class CurrentUser: ObservableObject { @Published private(set) var id: Int?; @Published private(set) var nickname: String?; func load() async }
```
me 실패 시 채팅 진입 전 재시도(봇 토픽 id 필수).

### 12. 제출 자산 + Backend 데모
- `PrivacyInfo.xcprivacy`(FR-22/AC-14): 앱 타깃 — 수집(위치/사진/카메라)+목적, `NSPrivacyAccessedAPITypes`. Q7: SDK 자체 manifest 의존, 빌드 검증 후 누락 시 보강.
- Info.plist 권한 3종(FR-23/AC-15): project.yml L39-41 이미 존재 → 검증만. 푸시 런타임 문구 불요.
- AppIcon/LaunchScreen(FR-24/AC-16): 디자인 토큰(크림 #FAF8F5, Gowun Batang) 임시 1024+전 해상도. P6 최종 교체(비차단).
- entitlements: `aps-environment`, `applinks:$(APP_LINKS_DOMAIN)`. project.yml entitlements 경로·`UIBackgroundModes:[remote-notification]`.
- `DemoSeedRunner`(FR-25/QE-3/AC-18): `@ConditionalOnProperty` 멱등. 데모 유저 2(커플)·그룹 1·봇방 대화 3건(릴스→PLACE_CARDS 1건은 `ChatMessageAppender`/`BotPlaceCardsPayloadBuilder` 경유)·핀 3·커플방 3건. 식별자·refreshToken `DemoSeedProperties`(env, BR-7).
- `AuthService` 데모 회전 예외(§10 a): 데모 식별자 매칭 시 `replaceRefreshTokenHash` 스킵. 데모 한정·운영 무변경.

## 의존성 및 영향도
- 새 SPM 의존성: 없음(STOMP 직접). Backend 신규 의존성: 없음.
- 기존 영향: AppDependencies 확장(logoutBox 유지), WhereWeGoApp 델리게이트·onOpenURL 분기, SessionStore logout 디바이스 해제(멱등), OnboardingRouter 종착 MainTabView(지도 비파괴), NotificationView APNs 등록, project.yml capability(XcodeGen 재생성), **AuthService 데모 회전 예외 1건(운영 무변경)**.
- 하위 호환: 일반 API 무변경, 디바이스 등록·회전 예외 additive, 웹/기존 사용자 무영향, 데모 게이트(프로퍼티·식별자).

## 구현 순서 (배치)
```
A. 모델·계약·순수 로직 (병렬, 의존 없음)
1.ChatMessageModels+테스트 2.StompFrame+테스트 3.DemoLoginGate+테스트 4.DeepLinkRouter규칙+테스트 5.ConnectionState 6.DeviceAPI+테스트 7.CurrentUser+AuthAPI.me()
B. API·서비스
8.ChatAPI(←1) 9.StompClient(←2,5) 10.ChatRealtimeService 재연결(←7,8,9) 11.PushRegistrationService(←6)
C. UI·ViewModel
12.공통UI(←1,5) 13.BotChat VM/View 카드409·PROCESSING(←8,10,12,pinAPI/groupAPI,7) 14.CoupleChat VM/View(←8,10,12,7)
D. 배선·진입점
15.AppDelegate+NotificationDelegate(←11,4) 16.AppDependencies(←8,10,11,15,7) 17.MainTabView+종착교체+딥링크소비(←13,14,16,4) 18.WhereWeGoApp 델리게이트·UniversalLink(←15,16,4) 19.SessionStore 해제·didLoginDemo(←11,16) 20.NotificationView 권한등록(←11,16) 21.LoginView 데모게이트+loginDemo+AppConfig(←3,19)
E. 제출 자산 (병렬)
22.project.yml capability/entitlement/BackgroundModes/Privacy+entitlements(←15정합) 23.PrivacyInfo.xcprivacy 24.AppIcon/LaunchScreen(임시) 25.Info.plist 검증(←22)
F. 백엔드 (독립 병렬)
26.DemoSeedProperties+DemoSeedRunner 27.AuthService 데모 refresh 회전 예외(←26 동일 식별자)
```
핵심 경로: 1→8→10→13/14→17. 푸시: 6→11→15→16. 데모: 3·26→27→21. 제출(E) 독립 병렬.

## 위험·트레이드오프
- STOMP 직접 구현(프레임 한정). 시뮬레이터 APNs 미발급 → 실기기 테스트. AASA 미호스팅 시 Universal Link 미작동(사용자 작업). 데모 회전 예외는 데모 식별자 매칭 한정(운영 무변경).
