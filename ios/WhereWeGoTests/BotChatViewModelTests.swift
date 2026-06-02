import XCTest
@testable import WhereWeGo

// BotChatViewModel 단위 테스트(설계 §5, AC-2/4).
// - 전송 → PROCESSING 버블 추가(pendingProcessingIds FIFO).
// - BOT 결과(STOMP onFrame) 수신 → 가장 오래된 PROCESSING 교체 + 결과 append.
// - dedup: 동일 messageId 중복 프레임 차단.
// - BR-3/AC-4: 2000자 초과 전송 차단.
//
// 의존(ChatAPI/PinAPI/GroupAPI/Realtime/CurrentUser)은 in-file 프로토콜 목으로 주입(MapViewModelTests 패턴).
// VM 이 @MainActor 이므로 테스트 클래스도 @MainActor.
@MainActor
final class BotChatViewModelTests: XCTestCase {

    // MARK: - 전송 → PROCESSING

    func test_send_appendsProcessingBubble() async {
        let chatAPI = StubChatAPI()
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        let realtime = StubChatRealtime()
        let vm = makeViewModel(chatAPI: chatAPI, realtime: realtime)
        await vm.appear()

        vm.draft = "https://instagram.com/reel/abc"
        await vm.send()

        // PROCESSING 버블 1건 추가, 입력 초기화.
        XCTAssertEqual(vm.messages.count, 1)
        XCTAssertEqual(vm.messages.first?.kind, .PROCESSING)
        XCTAssertEqual(vm.messages.first?.messageId, 100)
        XCTAssertEqual(vm.draft, "")
    }

    // MARK: - 결과 수신 → PROCESSING 교체(AC-2)

    func test_resultFrame_replacesOldestProcessing() async {
        let chatAPI = StubChatAPI()
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        let realtime = StubChatRealtime()
        let vm = makeViewModel(chatAPI: chatAPI, realtime: realtime)
        await vm.appear()

        vm.draft = "릴스 링크"
        await vm.send()
        XCTAssertEqual(vm.messages.map(\.kind), [.PROCESSING])

        // BOT 결과(PLACE_CARDS) 도착 → PROCESSING 제거 + 결과 append.
        realtime.emit(makeFrame(messageId: 200, kind: .PLACE_CARDS))
        await drainFrames()

        XCTAssertEqual(vm.messages.count, 1)
        XCTAssertEqual(vm.messages.first?.kind, .PLACE_CARDS)
        XCTAssertEqual(vm.messages.first?.messageId, 200)
        XCTAssertFalse(vm.messages.contains { $0.kind == .PROCESSING })
    }

    func test_multipleSends_replaceProcessingFIFO() async {
        let chatAPI = StubChatAPI()
        let realtime = StubChatRealtime()
        let vm = makeViewModel(chatAPI: chatAPI, realtime: realtime)
        await vm.appear()

        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        vm.draft = "첫 번째"
        await vm.send()
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 101, kind: .PROCESSING))
        vm.draft = "두 번째"
        await vm.send()

        // PROCESSING 2건(100, 101) 순서 유지.
        XCTAssertEqual(vm.messages.map(\.messageId), [100, 101])

        // 첫 결과 도착 → 가장 오래된 PROCESSING(100) 제거.
        realtime.emit(makeFrame(messageId: 200, kind: .SYSTEM))
        await drainFrames()
        XCTAssertEqual(Set(vm.messages.map(\.messageId)), [101, 200])
    }

    // MARK: - dedup

    func test_duplicateFrame_ignored() async {
        let chatAPI = StubChatAPI()
        let realtime = StubChatRealtime()
        let vm = makeViewModel(chatAPI: chatAPI, realtime: realtime)
        await vm.appear()

        realtime.emit(makeFrame(messageId: 300, kind: .SYSTEM))
        await drainFrames()
        realtime.emit(makeFrame(messageId: 300, kind: .SYSTEM))
        await drainFrames()

        XCTAssertEqual(vm.messages.filter { $0.messageId == 300 }.count, 1)
    }

    // MARK: - BR-3/AC-4: 2000자 가드

    func test_send_overLimit_blocked() async {
        let chatAPI = StubChatAPI()
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        let realtime = StubChatRealtime()
        let vm = makeViewModel(chatAPI: chatAPI, realtime: realtime)
        await vm.appear()

        vm.draft = String(repeating: "가", count: BotChatViewModel.messageMaxLength + 1)
        await vm.send()

        // 전송 호출되지 않고 버블 미추가, 입력 유지.
        XCTAssertEqual(chatAPI.sendCallCount, 0)
        XCTAssertTrue(vm.messages.isEmpty)
        XCTAssertFalse(vm.draft.isEmpty)
    }

    func test_send_atLimit_allowed() async {
        let chatAPI = StubChatAPI()
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        let realtime = StubChatRealtime()
        let vm = makeViewModel(chatAPI: chatAPI, realtime: realtime)
        await vm.appear()

        vm.draft = String(repeating: "a", count: BotChatViewModel.messageMaxLength)
        await vm.send()

        XCTAssertEqual(chatAPI.sendCallCount, 1)
        XCTAssertEqual(vm.messages.count, 1)
    }

    // MARK: - 로드(오름차순 reverse)

    func test_load_reversesToAscending() async {
        let chatAPI = StubChatAPI()
        // 서버는 id DESC(최신순) 반환.
        chatAPI.messagesResult = .success(MessagesResponse(
            messages: [makeFrame(messageId: 3, kind: .TEXT), makeFrame(messageId: 2, kind: .TEXT), makeFrame(messageId: 1, kind: .TEXT)],
            hasMore: false,
            nextCursor: nil
        ))
        let realtime = StubChatRealtime()
        let vm = makeViewModel(chatAPI: chatAPI, realtime: realtime)
        await vm.appear()

        // 화면은 오름차순(1,2,3).
        XCTAssertEqual(vm.messages.map(\.messageId), [1, 2, 3])
    }

    // MARK: - 헬퍼

    private func makeViewModel(
        chatAPI: StubChatAPI,
        pinAPI: PinAPIProtocol? = nil,
        groupAPI: GroupAPIProtocol? = nil,
        realtime: StubChatRealtime
    ) -> BotChatViewModel {
        BotChatViewModel(
            chatAPI: chatAPI,
            pinAPI: pinAPI ?? StubBotPinAPI(),
            groupAPI: groupAPI ?? StubBotGroupAPI(group: ActiveGroup(groupId: 1, name: "팀", memberCount: 2)),
            realtime: realtime,
            currentUser: makeCurrentUser()
        )
    }
}

// MARK: - 공유 헬퍼(이 파일 + PlaceCardSaveTests)

/// onFrame 콜백의 Task{@MainActor} hop 이 완료되도록 양보한다(emit 후 동기 검증 전 호출).
/// 단일 hop 이지만 여러 emit 누적 대비 충분히 양보한다.
@MainActor
func drainFrames() async {
    for _ in 0..<3 {
        await Task.yield()
    }
}

@MainActor
func makeCurrentUser() -> CurrentUser {
    // CurrentUser 는 AuthAPI 의존이나 본 테스트는 id 미사용 경로만 검증 → 더미 주입.
    CurrentUser(authAPI: AuthAPI(client: APIClient(baseURL: URL(string: "https://example.com")!, tokens: DummyTokenStore())))
}

/// JSON 경유로 ChatFrame 구성(ChatFrame 은 커스텀 디코더만 보유 — 직접 init 불가).
func makeFrame(messageId: Int, kind: MessageKind, cards: [PlaceCard]? = nil, text: String? = nil) -> ChatFrame {
    let payload: String
    switch kind {
    case .PLACE_CARDS:
        let cardsJSON = (cards ?? []).map {
            "{\"kakaoPlaceId\":\($0.kakaoPlaceId.map { "\"\($0)\"" } ?? "null"),\"name\":\"\($0.name)\",\"address\":\($0.address.map { "\"\($0)\"" } ?? "null"),\"latitude\":\($0.latitude.map(String.init) ?? "null"),\"longitude\":\($0.longitude.map(String.init) ?? "null")}"
        }.joined(separator: ",")
        payload = "{\"cards\":[\(cardsJSON)]}"
    case .TEXT, .SYSTEM, .MEMO_PROMPT:
        payload = "{\"text\":\"\(text ?? "msg")\"}"
    case .PROCESSING:
        payload = "{}"
    }
    let senderType = kind == .TEXT ? "USER" : "BOT"
    let json = "{\"messageId\":\(messageId),\"roomId\":1,\"senderType\":\"\(senderType)\",\"kind\":\"\(kind.rawValue)\",\"payload\":\(payload),\"createdAt\":\"2026-01-01T00:00:00Z\"}"
    return try! JSONDecoder().decode(ChatFrame.self, from: Data(json.utf8))
}

// MARK: - In-file 목

final class StubChatAPI: ChatAPIProtocol, @unchecked Sendable {
    var messagesResult: Result<MessagesResponse, Error> = .success(MessagesResponse(messages: [], hasMore: false, nextCursor: nil))
    var sendResult: Result<SendMessageResponse, Error> = .success(SendMessageResponse(messageId: 0, kind: .PROCESSING))
    private(set) var sendCallCount = 0
    private(set) var lastSentText: String?

    func botMessages(cursor: Int?, limit: Int) async throws -> MessagesResponse {
        try messagesResult.get()
    }

    func sendBotMessage(text: String) async throws -> SendMessageResponse {
        sendCallCount += 1
        lastSentText = text
        return try sendResult.get()
    }

    func coupleMessages(groupId: Int, cursor: Int?, limit: Int) async throws -> MessagesResponse {
        try messagesResult.get()
    }

    func sendCoupleMessage(groupId: Int, text: String) async throws -> SendMessageResponse {
        try sendResult.get()
    }
}

// VM 은 상태/재연결을 ChatRealtimeServicing 의 id 키 옵저버로 연결한다(Critical-5 — 단일 var 덮어쓰기 방지).
// 본 목은 currentState 기본값(.connected)으로 두어 배너를 숨기고, reconcileLatest 직접 호출로 보완조회를 검증한다.
// 옵저버는 딕셔너리로 보관하고 emitState/emitReconnected 헬퍼로 수동 트리거한다(필요 테스트용).
@MainActor
final class StubChatRealtime: ChatRealtimeServicing, @unchecked Sendable {
    private var onFrame: (@Sendable (ChatFrame) -> Void)?
    private(set) var subscribeCallCount = 0
    private(set) var unsubscribeCallCount = 0
    private(set) var retryCallCount = 0

    var currentState: ConnectionState = .connected
    private var stateObservers: [String: @Sendable (ConnectionState) -> Void] = [:]
    private var reconnectedObservers: [String: @Sendable () -> Void] = [:]

    func addStateObserver(id: String, _ handler: @escaping @Sendable (ConnectionState) -> Void) {
        stateObservers[id] = handler
    }

    func removeStateObserver(id: String) {
        stateObservers.removeValue(forKey: id)
    }

    func addReconnectedObserver(id: String, _ handler: @escaping @Sendable () -> Void) {
        reconnectedObservers[id] = handler
    }

    func removeReconnectedObserver(id: String) {
        reconnectedObservers.removeValue(forKey: id)
    }

    func subscribe(topic: ChatTopic, id: String, onFrame: @escaping @Sendable (ChatFrame) -> Void) async {
        subscribeCallCount += 1
        self.onFrame = onFrame
    }

    func unsubscribe(id: String) async {
        unsubscribeCallCount += 1
    }

    func onForeground() async {}

    func retryManually() async {
        retryCallCount += 1
    }

    /// 테스트에서 STOMP MESSAGE 수신을 시뮬레이션한다(구독된 onFrame 직접 호출).
    /// VM 의 onFrame 은 Task{@MainActor} hop 으로 handleFrame 을 호출하므로,
    /// 호출부는 emit 후 await Task.yield() 로 hop 완료를 보장해야 한다.
    func emit(_ frame: ChatFrame) {
        onFrame?(frame)
    }

    /// 상태 변화 수동 트리거(등록된 옵저버 전체 통지).
    func emitState(_ state: ConnectionState) {
        for observer in stateObservers.values { observer(state) }
    }

    /// 재연결 성공 수동 트리거(등록된 옵저버 전체 통지).
    func emitReconnected() {
        for observer in reconnectedObservers.values { observer() }
    }
}

final class StubBotPinAPI: PinAPIProtocol, @unchecked Sendable {
    var createResult: Result<PinSummary, Error> = .failure(APIError(code: "UNSET", status: 0, message: ""))
    private(set) var createRequests: [CreatePinRequest] = []

    func list(groupId: Int) async throws -> [PinSummary] { [] }

    func create(groupId: Int, request: CreatePinRequest) async throws -> PinSummary {
        createRequests.append(request)
        return try createResult.get()
    }

    func update(groupId: Int, pinId: Int, request: UpdatePinRequest) async throws -> UpdatePinResponse {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }

    func delete(groupId: Int, pinId: Int) async throws {}

    func uploadPhoto(groupId: Int, pinId: Int, imageData: Data) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }

    func deletePhoto(groupId: Int, pinId: Int) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }
}

final class StubBotGroupAPI: GroupAPIProtocol, @unchecked Sendable {
    private let group: ActiveGroup?
    var error: Error?

    init(group: ActiveGroup?) {
        self.group = group
    }

    func myActiveGroup() async throws -> ActiveGroup? {
        if let error { throw error }
        return group
    }

    func acceptInvite(token: String) async throws -> InviteAccept { InviteAccept(groupId: 0) }
    func issueInviteLink(groupId: Int) async throws -> InviteLink { InviteLink(token: "stub", slug: nil, shareUrl: nil) }
}

final class DummyTokenStore: TokenStore, @unchecked Sendable {
    func accessToken() async -> String? { nil }
    func refresh() async throws {}
}
