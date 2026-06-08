# 설계: DM — 그룹별 봇방 목록 (IA 재설계 GM-2, #105 소비)

## 설계 규모
**중형** — iOS 전용. 신규 2(DMListView/DMListViewModel + 방 래퍼) · 수정 6(ChatAPI/Models/BotChatVM/BotChatView/MainTabView/FloatingTabBar) · 테스트 3. 백엔드 무변경(#105 머지 완료).

## §1 개요 / 아키텍처
- DM 탭: 단일 `BotChatView` → **2레벨**(목록 `DMListView` → 방 `BotChatView`). NavigationStack push.
- 데이터 소스: `GET /chat/bot/rooms`(활성 그룹별 1항목, 가상항목 포함) → `[BotRoomSummary]`.
- 방은 그룹별 봇 채팅(`/chat/bot/{groupId}/messages`). `BotChatViewModel`에 `groupId` 주입.
- 읽음: 백엔드가 방 GET 시 읽음처리 → 목록은 **진입·방 복귀·포그라운드 복귀** 시 재조회.
- 방별 VM 수명: 인스타식. 방 진입 시 생성(@StateObject 래퍼), pop 시 해제, 재진입 시 재로드(백엔드 읽음 시맨틱과 정합).
- 기존 봇 채팅 동작(PROCESSING/폴링/2000자/저장카드/지도이동)은 **그대로**, 경로만 그룹별로.

```
MainTabView (DM 탭)
└─ NavigationStack
   ├─ DMListView(viewModel: dmListVM, makeRoomViewModel:)   ← 레벨0 목록
   │    행 탭 → openedRoom = room
   └─ .navigationDestination(item: $openedRoom)
        └─ BotChatRoomView(room, makeRoomViewModel)          ← 레벨1 방(@StateObject VM)
             └─ BotChatView(viewModel: botVM(groupId), groupName)
```

## §2 API 레이어 — ChatAPI.swift (수정)
`ChatAPIProtocol` 봇 메서드를 그룹별로 전환. couple 메서드는 불변.

```swift
protocol ChatAPIProtocol: Sendable {
    /// GET /chat/bot/rooms — 내 활성 그룹별 봇 방 요약(가입 순, 가상항목 포함).
    func botRooms() async throws -> [BotRoomSummary]
    /// GET /chat/bot/{groupId}/messages — 그룹별 봇 방 메시지 페이지(최신순).
    func botMessages(groupId: Int, cursor: Int?, limit: Int) async throws -> MessagesResponse
    /// POST /chat/bot/{groupId}/messages — 그룹별 봇 방 전송(PROCESSING 플레이스홀더).
    func sendBotMessage(groupId: Int, text: String) async throws -> SendMessageResponse
    // couple 메서드 그대로 유지
    func coupleMessages(groupId: Int, cursor: Int?, limit: Int) async throws -> MessagesResponse
    func sendCoupleMessage(groupId: Int, text: String) async throws -> SendMessageResponse
}
```
구현:
- `botRooms()`: `client.request("/chat/bot/rooms", type: [BotRoomSummary].self)`. **그룹 0개(data null/204) 정규화** → `listMyGroups` 패턴 모방(`HTTP_200`/`NO_CONTENT` catch → `[]`, 401만 전파).
- `botMessages(groupId:...)`: 경로 `"/chat/bot/\(groupId)/messages" + pageQuery(...)`.
- `sendBotMessage(groupId:text:)`: 경로 `"/chat/bot/\(groupId)/messages"`, body `{"text": ...}`.
- 구버전 비그룹 `/chat/bot/messages` 호출 코드는 제거(백엔드 deprecated 엔드포인트는 잔존하나 iOS 미소비).
- 헤더 주석의 계약 설명을 그룹별로 갱신.

## §3 모델 — ChatMessageModels.swift (수정)
`BotRoomSummary` 추가(백엔드 `ChatV1Dto.BotRoomSummaryResponse`와 1:1):
```swift
/// DM 목록 항목(백엔드 BotRoomSummaryResponse 1:1). 봇 방 없는 활성 그룹은 가상항목(roomId=nil, unread=false).
/// roomId/lastPreview/lastSenderType/lastAt: 메시지 없음 → nil. groupId 가 안정 식별자(그룹당 방 1개).
struct BotRoomSummary: Decodable, Identifiable, Equatable {
    let roomId: Int?
    let groupId: Int
    let groupName: String
    let lastPreview: String?
    let lastSenderType: SenderType?
    let unread: Bool
    let lastAt: String?

    var id: Int { groupId }   // 그룹당 봇 방 1개 — groupId 가 안정 식별자(가상항목 roomId=nil 회피)
}
```
- `MessagesResponse`에 백엔드가 새로 넣은 `groupId`는 **디코딩 불필요**(VM이 주입 groupId 보유, 미지정 키는 무시). 변경 안 함.
- `Long → Int` 수용 리스크는 기존 선례(PinSummary/ChatFrame) 동일.

## §4 DMListViewModel.swift (신규)
```swift
@MainActor
final class DMListViewModel: ObservableObject {
    enum LoadState: Equatable { case idle, loading, loaded([BotRoomSummary]), error(String) }

    @Published private(set) var loadState: LoadState = .idle

    /// FR-10 배지 소스 — 안 읽은 방 1개 이상. loaded 상태의 rooms 기준.
    var hasUnread: Bool {
        if case let .loaded(rooms) = loadState { return rooms.contains { $0.unread } }
        return false
    }

    private let chatAPI: ChatAPIProtocol
    private var isFetching = false   // load/refresh 동시 진입 가드(NotificationInbox isLoading 패턴)

    init(chatAPI: ChatAPIProtocol) { self.chatAPI = chatAPI }

    /// 탭 진입(보이는 로드): 최초/에러 후엔 .loading 표시, 이미 .loaded 면 깜빡임 없이 갱신.
    func load() async { await fetch(showLoading: true) }

    /// 방 복귀·포그라운드·배지 갱신(무음): 스피너 없이 rooms 갱신, 실패는 기존 유지.
    func refresh() async { await fetch(showLoading: false) }

    private func fetch(showLoading: Bool) async {
        if isFetching { return }
        isFetching = true; defer { isFetching = false }
        if showLoading, case .loaded = loadState {} else if showLoading { loadState = .loading }
        do {
            let rooms = try await chatAPI.botRooms()
            loadState = .loaded(rooms)
        } catch {
            // 보이는 로드 + 아직 목록 없음 → 에러. 무음 갱신/기존 목록 보유 시 화면 유지.
            if showLoading, case .loaded = loadState {} else if showLoading {
                loadState = .error("대화 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.")
            }
        }
    }

    /// 상대 시각 문구(NotificationInboxViewModel.formatTime 미러 — 방금/N분/N시간/N일/날짜).
    static func formatTime(_ iso: String, now: Date = Date()) -> String { /* 동일 구현 */ }
}
```
- 빈 상태(그룹 0개)는 `.loaded([])` → View 가 빈 화면 분기(에러 아님).
- `formatTime`은 Notification 선례와 동일 로직(소수초 ISO 파서 2종 + 상대 표현). **중복 인지** — 후속 공용 유틸 추출 가능(범위 외).

## §5 DMListView.swift (신규) + 방 래퍼
NotificationInboxView 상태 분기(로딩/빈/에러+재시도/목록) 패턴 이식. NavigationStack 콘텐츠(스택은 MainTabView 제공).

```swift
struct DMListView: View {
    @ObservedObject var viewModel: DMListViewModel
    let makeRoomViewModel: (Int) -> BotChatViewModel   // groupId → 방 VM 팩토리(MainTabView가 deps 캡처)
    @State private var openedRoom: BotRoomSummary?

    var body: some View {
        content
            .background(WGColor.bg)
            .navigationTitle("DM")
            .navigationBarTitleDisplayMode(.inline)
            .task { await viewModel.load() }                       // 최초 진입 로드
            .navigationDestination(item: $openedRoom) { room in
                BotChatRoomView(groupId: room.groupId, groupName: room.groupName,
                                makeViewModel: makeRoomViewModel)
            }
            .onChange(of: openedRoom) { _, new in
                if new == nil { Task { await viewModel.refresh() } }  // 방 pop → 읽음 갱신(FR-6/AC-4)
            }
    }
    // content: switch loadState → loadingView / (loaded: empty ? emptyView : list) / errorView(재시도)
    // 행: DMRoomRow(room) 버튼 → openedRoom = room
}
```
- **DMRoomRow**: 좌측 그룹 아바타(SF `bubble.left.and.bubble.right` 원형 배경) + 그룹명 + 미리보기 + 우측 시각/미읽음 점.
  - unread → 그룹명·미리보기 `.fontWeight(.semibold)` + 우측 `Circle(WGColor.pinNew)` 점(NotificationRow 미읽음 패턴) + 옅은 `WGColor.cta.opacity(0.06)` 배경.
  - 미리보기 텍스트(FR-8): `lastPreview == nil` → "아직 대화가 없어요"(inkFaint). `lastSenderType == .USER` → "나: \(lastPreview)". else `lastPreview`.
  - 시각: `lastAt` 있으면 `DMListViewModel.formatTime(lastAt)`, 없으면 미표시.
- **빈 상태**(`.loaded([])`): `bubble.left.and.bubble.right` 아이콘 + "참여 중인 그룹이 없어요"(NotificationInboxView.emptyView 패턴).
- **에러**: 메시지 + "다시 시도"(`viewModel.load()`), NotificationInboxView.errorView 패턴.

```swift
/// 방 화면 래퍼 — 방별 BotChatViewModel 을 @StateObject 로 소유(push 수명, pop 시 해제).
struct BotChatRoomView: View {
    private let groupName: String
    @StateObject private var viewModel: BotChatViewModel
    init(groupId: Int, groupName: String, makeViewModel: (Int) -> BotChatViewModel) {
        self.groupName = groupName
        _viewModel = StateObject(wrappedValue: makeViewModel(groupId))  // StateObject: 최초 1회만 생성
    }
    var body: some View { BotChatView(viewModel: viewModel, groupName: groupName) }
}
```

## §6 BotChatViewModel.swift (수정) — groupId 주입
- `init`에 `groupId: Int` 추가. `groupAPI` 의존 **제거**(유일 소비처인 savePlaceCards가 주입 groupId 사용 → 미사용).
- `load()/loadMore()/reconcileLatest()`: `chatAPI.botMessages(groupId: groupId, cursor:..., limit:...)`.
- `send()`: `chatAPI.sendBotMessage(groupId: groupId, text:)`.
- `savePlaceCards(...)`: `groupAPI.myActiveGroup()` 제거 → `let groupId = self.groupId` 사용. "활성 그룹 못 찾음" 실패 경로 삭제(groupId 항상 확보). 나머지(좌표 가드/태그/메모/409 흡수/saveResult) 불변.
- `makeProcessingFrame`의 roomId=0 고정 등 내부 표시는 그대로.

## §7 BotChatView.swift (수정) — 그룹명 타이틀
- `let groupName: String` 추가(이니셜라이저 주입). `.navigationTitle("어디가지 봇")` → `.navigationTitle(groupName)`(FR-9). 본문 로직 불변.
- (보조 식별 "어디가지 봇"은 타이틀에서 생략 — 그룹명 우선. 필요 시 후속 보강.)

## §8 MainTabView.swift (수정) — DM 탭 배선
- `botViewModel: BotChatViewModel` @StateObject **제거** → `dmListViewModel: DMListViewModel` @StateObject 추가(`DMListViewModel(chatAPI: dependencies.chatAPI)`).
- DM 탭 콘텐츠:
```swift
NavigationStack {
    DMListView(
        viewModel: dmListViewModel,
        makeRoomViewModel: { groupId in
            BotChatViewModel(
                groupId: groupId,
                chatAPI: dependencies.chatAPI,
                pinAPI: dependencies.pinAPI,
                currentUser: dependencies.currentUser,
                deepLinkRouter: dependencies.deepLinkRouter
            )
        }
    )
}
.reserveFloatingTabBarSpace()
.tag(MainTab.chat)
```
- **배지(FR-10)**: `FloatingTabBar(... hasChatUnread: dmListViewModel.hasUnread ...)`.
- **배지 갱신 트리거**: 진입 `.task`와 scenePhase `.active`에 `dmListViewModel.refresh()` 추가(알림 배지 onForeground 옆):
  - `.task { await groupContext.bootstrap(); consumePending(); await notificationInboxViewModel.onForeground(); await dmListViewModel.refresh() }`
  - scenePhase active: `Task { await notificationInboxViewModel.onForeground(); await dmListViewModel.refresh() }`
- 딥링크 `.chat` → `selection = .chat`(DM 목록 진입) 유지. 그룹 특정 방 자동오픈은 범위 외.

## §9 FloatingTabBar.swift (수정) — DM 배지
- `init`에 `hasChatUnread: Bool = false` 추가. `.chat` 탭 버튼에 `showUnread: hasChatUnread` 전달(기존 `tabButton(... showUnread:)` 재사용 — 빨간 점 동일).
- 알림 탭 `hasUnread`는 그대로.

## §10 테스트
- **BotChatViewModelTests.swift**(수정): `StubChatAPI`에 `botRooms()` 추가 + `botMessages(groupId:...)`/`sendBotMessage(groupId:...)` 시그니처 변경(+`lastGroupId` 기록). `makeViewModel`에 `groupId: Int = 1` 추가, `groupAPI` 인자 제거(VM이 더 이상 안 받음). 기존 케이스 로직 불변(시그니처만).
- **PlaceCardSaveTests.swift**(수정): `makeViewModel`이 `group:` 대신 `groupId:` 주입. `StubBotPinAPI`에 `lastCreateGroupId`(또는 `createGroupIds`) 기록 추가 → **AC-5 검증**(저장이 주입 groupId 로). 기존 케이스의 groupId 기대값 정합.
- **DMListViewModelTests.swift**(신규): ① load 성공 → `.loaded(rooms)` ② 그룹0개 → `.loaded([])`(빈, 에러 아님) ③ load 실패(목록 없음) → `.error` ④ `hasUnread` 가 rooms.unread 반영 ⑤ refresh 가 무음 갱신(unread→false 반영) ⑥ refresh 실패 시 기존 목록 유지. StubChatAPI.botRooms 결과 주입.

## §11 변경 범위 (8 파일 + 테스트 3)
신규:
- `ios/WhereWeGo/Features/Chat/DMListView.swift` (DMListView + BotChatRoomView + DMRoomRow)
- `ios/WhereWeGo/Features/Chat/DMListViewModel.swift`
- `ios/WhereWeGoTests/DMListViewModelTests.swift`
수정:
- `ios/WhereWeGo/Features/Chat/ChatAPI.swift` (botRooms + groupId 인자화)
- `ios/WhereWeGo/Features/Chat/ChatMessageModels.swift` (BotRoomSummary)
- `ios/WhereWeGo/Features/Chat/Bot/BotChatViewModel.swift` (groupId 주입, groupAPI 제거)
- `ios/WhereWeGo/Features/Chat/Bot/BotChatView.swift` (groupName 타이틀)
- `ios/WhereWeGo/App/MainTabView.swift` (DM 탭=목록, 방 팩토리, 배지·refresh 배선)
- `ios/WhereWeGo/App/FloatingTabBar.swift` (hasChatUnread 배지)
- `ios/WhereWeGoTests/BotChatViewModelTests.swift` (StubChatAPI/makeViewModel)
- `ios/WhereWeGoTests/PlaceCardSaveTests.swift` (groupId 주입 + pin groupId 검증)

## §12 구현 순서
1. **모델·API**: ChatMessageModels(BotRoomSummary) → ChatAPI(botRooms + groupId 인자화). 컴파일 기준선.
2. **봇 VM**: BotChatViewModel groupId 주입 + savePlaceCards/groupAPI 제거 + BotChatView groupName.
3. **DM 목록**: DMListViewModel → DMListView/BotChatRoomView/DMRoomRow.
4. **배선**: MainTabView(DM 탭 교체·팩토리·배지·refresh) + FloatingTabBar(hasChatUnread).
5. **테스트**: StubChatAPI/BotChatViewModelTests/PlaceCardSaveTests 갱신 + DMListViewModelTests 신규.
> 각 단계 시그니처 정합을 직접 검토(iOS Windows 빌드 불가 — Mac DoD-B는 리뷰어).

## §13 확인이 필요한 사항
추가 확인 사항 없음. 설계가 완료되었습니다.
(주요 결정: 방별 VM=인스타식 재생성 / groupAPI 제거 / 목록 식별자=groupId / 읽음 갱신=방복귀+포그라운드 refresh / formatTime 미러(중복 인지·후속 통합).)

## 탐색 추가 항목 (코드맵 누적)
- ios/WhereWeGo/Features/Notification/NotificationInboxViewModel.swift:141-176 → formatTime(ISO→상대시각) + LoadState enum 선례. DM 목록 VM 미러 대상.
- ios/WhereWeGo/Features/Notification/NotificationInboxView.swift:38-113 → 로딩/빈/에러+재시도/목록 분기 UI 선례. DMListView 이식 기준.
