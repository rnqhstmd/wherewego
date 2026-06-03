# 설계서: P7 — iOS 내비게이션 재설계 (5탭 통일 + ＋통합추가 + 알림함/내정보 이식)

> 기준: PRD `.dev/feat-ios-nav-redesign/prd.md`, 상위 설계 `docs/superpowers/specs/2026-06-02-ios-nav-redesign-design.md`
> 본 설계서는 design-critic 검토(MUST-ADDRESS 4건) + 사용자 결정(Q2/Q3/Q4/＋소유권)을 모두 반영한 최종본이다.

## 설계 규모

**대형** — 5탭 전면 재구성 + 신규 화면 4종(FloatingTabBar, AddPlaceSheet, NotificationInbox, MyInfo) + 신규 서비스/API 3종(ReverseGeocoder, NotificationAPI, GroupAPI/AuthAPI 확장) + 커플챗 삭제 + SearchPin/Crosshair 통합 흡수 + 딥링크 정리. 신규 약 11파일, 수정 약 13파일(프로토콜 1 + 스텁 6 + 테스트 1 포함), 삭제 4파일.

## 배경 및 목적

- 현재 하단 3탭(지도/봇/커플) + 지도 내 별도 액션바(검색·추가·룰렛)가 "가로 바 두 줄" 혼란 유발. 봇방이 커플방과 같은 채팅 급 배치로 성격 불일치. 알림함·내정보 화면 iOS 부재.
- 목표: 단일 5탭(`어디갈까·채팅·＋·알림·내정보`), 장소 추가 ＋ 통합(검색+콕찍기), 릴스 저장 채팅 탭 직행, 알림함·내정보 네이티브 이식, iOS 17~25 솔리드 폴백 / iOS 26+ Liquid Glass, 커플챗 제거.
- **백엔드 추가 0** — 알림(NotificationV1Controller 3엔드포인트)·닉네임 PUT·그룹탈퇴 DELETE `members/me`·계정삭제 DELETE `users/me` 전부 기존 재사용. 역지오 = 온디바이스 `CLGeocoder`.

## 요구사항 및 수용 기준

PRD FR-1~28, BR-1~6, QE-1~2 채택. 본 범위(DoD-A) = 정적/단위 테스트 검증 **AC-1~11**. 시각/제출 **AC-V1~V10**은 DoD-B(Mac/Xcode) 이연.

## 변경 범위

### 신규 생성

| 경로 | 역할 |
|------|------|
| `App/FloatingTabBar.swift` | 둥근 플로팅 필 바(4탭 + 센터 ＋ FAB + iOS26 `if #available` 분기 + 외곽선↔채움) |
| `Features/Map/AddPlaceSheet.swift` | ＋ 통합 추가 시트(독립 MapContainerView + 검색바 + 확정 카드) |
| `Features/Map/AddPlaceViewModel.swift` | 검색↔콕찍기 상태·역지오·태그·핀 생성 |
| `Core/Location/ReverseGeocoder.swift` | CLGeocoder 역지오 + Debouncer(300ms) + 좌표 폴백(순수) |
| `Features/Notification/NotificationAPI.swift` | 알림 3엔드포인트 + Codable 모델(좌표 Double?) |
| `Features/Notification/NotificationInboxView.swift` | 알림함 화면(목록/로딩/빈/에러+재시도) |
| `Features/Notification/NotificationInboxViewModel.swift` | list/read-all/unreadCount/flyTo·didReadAll 1회 |
| `Features/MyInfo/MyInfoView.swift` | 내정보(사용자/활성그룹 조건부/계정) |
| `Features/MyInfo/MyInfoViewModel.swift` | 닉네임·탈퇴·로그아웃·계정삭제 위임 |
| `WhereWeGoTests/` 신규 테스트 6종 | §단위 테스트 계획 |

### 수정

| 경로 | 변경 요지 |
|------|----------|
| `App/MainTabView.swift` | 5탭(`MainTab{map,chat,notification,myInfo}`), 센터 ＋ 액션(selection 불변), FloatingTabBar 적용, coupleViewModel·`onUnavailable`·`.task` 관련 제거, `consumePending()` `.coupleChat` 제거·`.chat` 매핑, NotificationInboxViewModel `@StateObject` 보유, AddPlaceSheet 공유 진입, scenePhase .active 시 onForeground |
| `App/DeepLinkRouter.swift` | `.botChat`→`.chat` 리네임, `.coupleChat` 제거, `COUPLE_MESSAGE`→`.chat` 재매핑 |
| `App/AppDependencies.swift` | coupleViewModel 제거, `notificationAPI` 조립, MyInfo 의존 노출 |
| `Features/Map/MapView.swift` | 하단 액션바 제거, 룰렛 우상단·내위치 우하단 플로팅, `.search`/`.crosshair`→`.addPlace`, EmptyMapCard→`.addPlace` |
| `Features/Map/MapViewModel.swift` | `ActiveSheet` `.search`/`.crosshair` 제거→`.addPlace`. `addPinAtCenter`/`validatePinInput`/`roundCoordinate`/`mapCenter` 유지(AddPlaceViewModel 재사용) |
| `Core/Auth/AuthAPI.swift` | `deleteAccount()` (DELETE `/users/me`) |
| `Core/Auth/AuthServiceProtocols.swift` | `GroupAPIProtocol`에 `leaveGroup(groupId:)` 추가 |
| `Features/Group/GroupAPI.swift` | `leaveGroup(groupId:)` (DELETE `/groups/{groupId}/members/me`) |
| `WhereWeGoTests/{BotChatViewModelTests, MapViewModelTests, MapCacheAndPollingTests, VisitOrchestrationTests, PinDetailViewModelTests, RouletteViewModelTests, RouteGuardTests}.swift` | GroupAPI 스텁 7곳에 `leaveGroup` 추가 |
| `WhereWeGoTests/DeepLinkRouterTests.swift` | `.botChat`→`.chat` 단언 교체(`botResult`·`setsPendingFromType`) + `coupleMessage`→`COUPLE_MESSAGE==.chat` 재작성 |

> **GroupAPIProtocol 스텁 파급 = 정확히 7곳**(Grep 확정). 8번째 `CoupleStubGroupAPI`(CoupleChatViewModelTests)는 동반 삭제 대상이라 수정 불요.

### 삭제

| 경로 | 비고 |
|------|------|
| `Features/Chat/Couple/CoupleChatView.swift` | 커플챗 제거(FR-11, BR-2) |
| `Features/Chat/Couple/CoupleChatViewModel.swift` | ChatRealtimeService/ChatAPI/CurrentUser는 BotChatViewModel이 계속 사용 → 잔존 |
| `Features/Map/SearchPinSheet.swift` + `SearchPinViewModel.swift` | AddPlaceSheet 검색측 흡수(Q3) |
| `Features/Map/CrosshairAddView.swift` | AddPlaceSheet 콕찍기측 흡수(Q3) |
| `WhereWeGoTests/CoupleChatViewModelTests.swift` | 대상 VM 삭제로 컴파일 불가 → 동반 삭제 |

> **SearchPin/Crosshair 잔존 참조(Grep 확정)**: 코드 참조는 `MapView.swift`(시트 바인딩·EmptyMapCard·actionBar) + `MapViewModel.swift`(주석)뿐 → 둘 다 §수정에 포함. **전용 단위 테스트 부재**(`PlaceCardSaveTests`는 BotChatViewModel.savePlaceCards 테스트로 무관, `PlaceAPITests`는 PlaceAPI 테스트). 테스트 삭제 불요. `addPinAtCenter`/`validatePinInput`/`roundCoordinate`는 AddPlaceViewModel 재사용으로 유지.

## 적용 컨벤션

- 레이어: `Features/{도메인}/{View}.swift` + `{ViewModel}.swift` + `{도메인}API.swift`. View `@StateObject`로 VM 보유, VM `@MainActor final class ... ObservableObject`.
- API: `{Domain}APIProtocol: Sendable` + `final class {Domain}API`. `client.request(path, method:, body:, type:)` 경유. path `api/v1` 자동(`/notifications`). 204/빈본문 = `EmptyResponse`+`NO_CONTENT`/`HTTP_200` catch 흡수(PinAPI.delete·GroupAPI.myActiveGroup 패턴).
- DTO: 백엔드 record 1:1. `Long→Int`, `Instant→String`, `BigDecimal→Double`(JacksonConfig `WRITE_BIGDECIMAL_AS_PLAIN`=number 직렬화).
- 에러: `APIError{code,status,message}` + 도메인 `enum ...Error: LocalizedError`(한국어).
- 디자인 토큰: `WGColor.*`(cta=주황 #C4622D, panel/bg/ink/inkSoft/inkFaint/hairline/shadow/shadowMd, pinNew), `WGFont.sans/serif/mono/emo(_:)`. 신규 색 금지.
- 시트: VM `activeSheet` enum + `Binding<Bool>` 계산 프로퍼티, 닫힘 시 `.none`.
- 테스트: `XCTest @testable import WhereWeGo`, `@MainActor`. API는 `StubURLProtocol.makeSession()`+handler. 순수 매핑 static 직접. 프로토콜 mock으로 VM 검증.
- DI: `AppDependencies.init` 동기 조립.

## 상세 설계

### 1. `App/FloatingTabBar.swift` (신규) — 둥근 플로팅 필 바
- 시스템 탭바 숨김(`.toolbar(.hidden, for:.tabBar)`) + ZStack 하단 커스텀 바. 4탭 버튼 + 가운데 ＋ FAB(주황 원, flush). Capsule + 그림자 + 바닥 패딩.
```
enum MainTab: Hashable, CaseIterable { case map, chat, notification, myInfo }   // ＋ 미포함(액션)
struct FloatingTabBar: View { init(selection: Binding<MainTab>, hasUnread: Bool, onPlusTap: () -> Void) }
// 아이콘 쌍: map/map.fill · bubble.left.and.bubble.right/.fill · bell/bell.fill · person/person.fill
// 선택=fill+WGColor.cta, 미선택=outline+WGColor.inkSoft. 알약 배경 없음.
// notification 탭: hasUnread 시 bell 우상단 빨간 점(WGColor.pinNew).
// if #available(iOS 26.0,*){ Liquid Glass — DoD-B 보정 } else { background(WGColor.panel) 솔리드 둥근 필 }
```
- BR-1/AC-2: ＋는 MainTab 미포함, `onPlusTap`만 호출(selection 불변). FR-3 아이콘 쌍·FR-22 빨간 점.

### 2. `App/MainTabView.swift` (수정) — 5탭 재구성 + ＋ 단일 소유권
- `Tab`→`MainTab{map,chat,notification,myInfo}`(AC-1). coupleViewModel·`onUnavailable`·`.task` 관련 제거(QE-1 — map/bot VM 수명 유지).
- `notificationInboxViewModel` `@StateObject` 신규 보유(미읽음 점 항상 노출).
- TabView 4콘텐츠: map=MapView, chat=NavigationStack{BotChatView}(라벨 "채팅"), notification=NavigationStack{NotificationInboxView}, myInfo=NavigationStack{MyInfoView}.
- 시스템 탭바 숨김 + `FloatingTabBar(selection:$selection, hasUnread: vm.unreadCount>0, onPlusTap:{showAddPlace=true})`.
- `@State showAddPlace` + `.sheet(isPresented:$showAddPlace){ AddPlaceSheet(mapViewModel: mapViewModel) }`.
- `consumePending()`: `.chat`→.chat, `.pin`/`.map`→.map+flyTo, `.invite`→inviteSlug. `.coupleChat` 제거.
- scenePhase `.active` → `await vm.onForeground()`(unreadCount 갱신, 폴링 없음).
- **＋ 시트 2진입점·1컴포넌트(확정)**: ① ＋ = `showAddPlace`. ② EmptyMapCard(FR-8) = `MapViewModel.activeSheet=.addPlace`(MapView가 `.sheet`로 동일 `AddPlaceSheet` 표시). 중복 구현 금지. MapView init 시그니처 불변.

### 3. `App/DeepLinkRouter.swift` (수정)
```
enum DeepLinkDestination: Equatable { case chat, pin(pinId:Int), invite(slug:String), map }
static func destination(forPushType:) // "BOT_RESULT"->.chat, "COUPLE_MESSAGE"->.chat, "PIN_SAVED"->.map
```
- `.coupleChat` 제거, `.botChat`→`.chat`(Q4, AC-3/4). 향후 알림 푸시용 `.notification`은 도입 안 함(YAGNI — 푸시 type 없음). 참조부: DeepLinkRouter·MainTabView·DeepLinkRouterTests(2). AppNotificationDelegate 무수정(handlePush 위임).

### 4. `Features/Map/AddPlaceSheet.swift` + `AddPlaceViewModel.swift` (신규)
- **시트 내부 독립 `MapContainerView` 인스턴스**(MUST-ADDRESS #2) — 그 인스턴스 cameraIdle을 AddPlaceViewModel이 직접 구독. 메인 mapViewModel과 카메라/콕찍기 분리. mapViewModel 공유는 핀 생성 결과 반영(appendPin/flyTo)에만.
- 레이아웃: 상단 검색바 + 중앙 독립맵(+중앙 고정 핀) + 하단 카드(장소/주소 + 태그3 + "여기 등록"). 토글 없음.
- 검색(FR-13): `placeAPI.search` → 결과 → 선택 시 selectedPlace + 독립맵 flyTo + 카드 채움. 무결과 "검색 결과가 없어요"(AC-V10).
- 콕찍기(FR-14): 독립맵 드래그 → `searchText=""`(AC-8) + `.pinpoint` + 중앙핀. cameraIdle → ReverseGeocoder 디바운스 300ms → resolvedAddress. 실패 시 `coordinateFallback`(AC-9).
- 확정(FR-15): 태그 → "여기 등록" → `validatePinInput` → `pinAPI.create` → `mapViewModel.appendPin`+`flyTo` → didCreate.
- 릴스 미포함(FR-16). 토큰 미설정 시 PlaceholderMapView 폴백(콕찍기 center=placeholder center, 실렌더=DoD-B).
```
enum InputMode { case search, pinpoint }
@Published var query; results; inputMode; selectedPlace; resolvedAddress; didSearch; isSearching; isCreating; errorMessage; didCreate
func search() async / selectResult(_) / onMapMoved(center:) // query="" + .pinpoint + 디바운스 / createPin(tag:) async
```

### 5. `Core/Location/ReverseGeocoder.swift` (신규)
```
@MainActor final class ReverseGeocoder {
  func reverseGeocode(_ c: Coordinate) async -> String?         // CLGeocoder, 실패 nil
  static func coordinateFallback(lat:Double,lng:Double) -> String  // "위도 37.1235, 경도 127.5679"(순수, AC-9)
}
@MainActor final class Debouncer { init(interval:, scheduler:); func call(_ action:) }  // 300ms 마지막 1회(AC-5)
```
- AC-5: Debouncer를 주입 클로저(카운트)로 검증(CLGeocoder는 실디바이스 의존 → 테스트 제외). AC-9: coordinateFallback 순수 함수 직접. 포맷 소수 4자리.

### 6. `Features/Notification/NotificationAPI.swift` (신규)
- **좌표 number 직렬화**(MUST-ADDRESS #1): `JacksonConfig.WRITE_BIGDECIMAL_AS_PLAIN`이 BigDecimal을 number(`37.5`)로 → `latitude/longitude: Double?`. 구현자는 `NotificationV1ControllerIntegrationTest` 응답 1건으로 확인.
```
enum NotificationType: String, Decodable { case MANUAL_PIN, CHATBOT_PINS, VISIT_DETECTED }
struct NotificationItem: Decodable, Identifiable { id; type; registeredBy:Int?; registeredByNickname:String?;
  firstPlaceName:String; totalPinCount:Int; wishCount:Int?; reelCount:Int?; createdAt:String; readAt:String? }
struct NotificationListResponse: Decodable { items:[NotificationItem]; unreadCount:Int }
struct NotificationPinItem: Decodable { pinId:Int; placeName:String; address:String?;
  latitude:Double?; longitude:Double?; deleted:Bool; instagramUrl:String?; memo:String?; tag:String? }
struct NotificationDetail: Decodable { id; type; registeredByNickname:String?; createdAt:String; pins:[NotificationPinItem] }
struct ReadAllResponse: Decodable { updatedCount:Int }
protocol NotificationAPIProtocol: Sendable {
  func list() async throws -> NotificationListResponse        // GET  /notifications
  func readAll() async throws -> ReadAllResponse              // POST /notifications/read-all
  func detail(id:Int) async throws -> NotificationDetail      // GET  /notifications/{id}
}
```
- AC-7: 3엔드포인트 경로 + `latitude/longitude` Double?(number) 디코딩 검증. flyTo는 Double 직접.

### 7. `Features/Notification/NotificationInboxView.swift` + `ViewModel.swift` (신규)
- VM: 진입 `load()` = `list`(FR-19) + **`didReadAll==false`일 때만 `readAll`**(FR-21,BR-4). readAll 성공 시 `didReadAll=true` + 로컬 `unreadCount=0` 낙관. 실패 조용히 무시(BR-4). 미읽음 = 서버 `unreadCount`(>0 → 빨간 점 FR-22). `onForeground()` = `list`만(readAll 미반복, FR-19). 행 탭 → `detail` → 핀 선택 시 `deepLinkRouter.pending=.pin`(FR-20). soft delete → flyTo 비활성 + "삭제된 장소: {이름}". 목록 실패 → 에러+재시도(BR-6).
- View: 상태별(로딩/빈 "아직 알림이 없어요"/에러+재시도/목록). 행 = 종류 아이콘(mappin/bubble.left/location) + 문구(FR-18) + 시간(웹 formatTime 이식) + 미읽음 강조(readAt==nil → 옅은 cta).
```
enum LoadState { idle, loading, loaded([NotificationItem]), error(String) }
@Published loadState; unreadCount; private var didReadAll=false
func load() / onForeground() / selectItem(_) / flyToPin(_)
```

### 8. `Features/MyInfo/MyInfoView.swift` + `ViewModel.swift` (신규)
- 섹션(FR-23): 사용자 / 활성 그룹(보유 시) / 계정. 챗봇 연동 제외(FR-27,AC-11).
- 사용자(FR-24): nickname 표시 + 닉네임 수정(NicknameViewModel 재사용). 활성 그룹(FR-25): `myActiveGroup()` name·memberCount, **nil → 섹션 미렌더(AC-10)**, 탈퇴=확인다이얼로그(BR-5)→`leaveGroup`. 계정(FR-26): 로그아웃=`SessionStore.logout`, 계정삭제=확인다이얼로그(BR-5)→`AuthAPI.deleteAccount`→로그아웃 전환.
```
@Published nickname:String?; activeGroup:ActiveGroup?; isBusy; errorMessage
var shouldShowGroupSection: Bool { activeGroup != nil }   // AC-10
func load() / leaveGroup() / deleteAccount() / logout()
```

### 9. `Core/Auth/AuthAPI.swift` (수정)
```
func deleteAccount() async throws    // DELETE /users/me, 204/빈본문 EmptyResponse+NO_CONTENT 흡수
```

### 10. `AuthServiceProtocols.swift` + `GroupAPI.swift` (수정)
```
// GroupAPIProtocol
func leaveGroup(groupId: Int) async throws
// GroupAPI: DELETE /groups/{groupId}/members/me (200 {data:null} → EmptyResponse+HTTP_200/NO_CONTENT)
```
- 프로토콜 변경 → 7스텁 컴파일 영향 → B1 일괄.

### 11. `Features/Map/MapView.swift` + `MapViewModel.swift` (수정)
- actionBar 제거(FR-5). 룰렛 우상단 플로팅(FR-6)→`activeSheet=.roulette`. 내위치 우하단 플로팅(FR-7)→`locationService.requestOneShot`+`flyTo`. `ActiveSheet` `.search`/`.crosshair` 제거→`.addPlace`. EmptyMapCard `onAddPin:{activeSheet=.addPlace}`(FR-8). `addPinAtCenter`/`validatePinInput`/`roundCoordinate`/`mapCenter` 유지.

## 의존성 / 영향도

- 새 라이브러리 없음(CoreLocation 기존). 백엔드 추가 0.
- GroupAPIProtocol → 7스텁 + 구현 동시. DeepLinkRouterTests 3테스트 수정. ChatRealtimeService/ChatAPI/CurrentUser 잔존(BotChatViewModel 사용). QE-1 유지.
- 하위 호환: `COUPLE_MESSAGE`→`.chat` 자동 폴백(FR-28). 커플챗 상실(BR-2). 백엔드 커플방 잔존.
- **XcodeGen**: `ios/project.yml` 폴더 글롭 → 신규 자동 포함·삭제 자동. 신규 폴더(Features/Notification·MyInfo)·삭제 반영 위해 `cd ios && xcodegen generate` 재실행 필수. project.yml 수정 불요.

## 구현 순서 (배치 — 파일 배타성)

**B1 — 삭제 + 프로토콜/스텁 + 딥링크 + API/서비스 신규**
1. Couple{View,ViewModel,ViewModelTests} 삭제 (AC-6)
2. DeepLinkRouter `.botChat`→`.chat`·`.coupleChat` 제거·재매핑 (AC-3)
3. GroupAPIProtocol.leaveGroup + GroupAPI 구현 + 7스텁 동시
4. AuthAPI.deleteAccount
5. NotificationAPI 신규(Double? 좌표)
6. ReverseGeocoder 신규

**B2 — 신규 뷰/VM (병렬 안전, B1 의존)**
7. FloatingTabBar / 8. AddPlaceSheet+VM(독립맵) / 9. NotificationInbox+VM / 10. MyInfo+VM

**B3 — 통합 (동일 파일군, 순차)**
11. AppDependencies / 12. MapViewModel(.addPlace) / 13. MapView(액션바 제거·플로팅·시트) / 14. MainTabView(5탭·＋·VM 보유·딥링크) / 15. SearchPin/Crosshair 삭제(참조 제거 후) / 16. `cd ios && xcodegen generate`

**B4 — 단위 테스트**
17. DeepLinkRouterTests 수정 / 18. AddPlaceViewModelTests·ReverseGeocoderTests·NotificationAPITests·NotificationInboxViewModelTests·MyInfoViewModelTests·MainTabTests

## 단위 테스트 계획 (AC-1~11)

> 실행은 DoD-B(Mac). 더블: StubURLProtocol/StubTokenStore + 프로토콜 mock.

| AC | 파일 | 케이스 |
|----|------|--------|
| AC-1 | MainTabTests | `MainTab.allCases==[.map,.chat,.notification,.myInfo]`, couple 부재 |
| AC-2 | MainTabTests | onPlusTap 시 selection 불변 + showAddPlace=true |
| AC-3 | DeepLinkRouterTests | `.coupleChat`/`.botChat` 부재(컴파일+케이스) |
| AC-4 | DeepLinkRouterTests | `COUPLE_MESSAGE==.chat`, `BOT_RESULT==.chat`(기존 2 교체) |
| AC-5 | ReverseGeocoderTests | Debouncer 300ms 내 3회 → 1회 실행 |
| AC-6 | (정적) | CoupleChat*/SearchPin*/Crosshair 미존재(Glob/빌드) |
| AC-7 | NotificationAPITests | 3경로 캡처 + latitude/longitude Double?(number) 디코딩 |
| AC-8 | AddPlaceViewModelTests | query 비어있지 않음 + onMapMoved → query=="" + .pinpoint |
| AC-9 | ReverseGeocoderTests | coordinateFallback(37.12345,127.56789)=="위도 37.1235, 경도 127.5679" |
| AC-10 | MyInfoViewModelTests | activeGroup==nil → shouldShowGroupSection==false; 보유 시 true |
| AC-11 | MyInfoViewModelTests | 챗봇 연동 상태/메서드 부재 |
| (CONSIDER) | NotificationInboxViewModelTests | load 2회 → readAll 1회(didReadAll); 성공 후 unreadCount==0; onForeground는 list만 |

## 위험·미결정

- **iOS 26 Liquid Glass API 시그니처**: SDK 미확정. `if #available(iOS 26.0,*)` 분기 골격만, 실제 modifier는 DoD-B(Mac/Xcode 26) 보정. 본 범위 기본 경로 = 폴백(솔리드 둥근 필), AC-1~11 무영향.

(해소: 좌표 Double?, 콕찍기 독립맵, GroupAPIProtocol 7스텁, ＋ 2진입점·1컴포넌트, 미읽음 서버 unreadCount, read-all 1회 didReadAll — 전부 반영.)
