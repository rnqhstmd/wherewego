import XCTest
@testable import WhereWeGo

// BotChatViewModel 단위 테스트(설계 §5 → 이벤트 전환: STOMP 제거, 폴링 기반).
// - 전송 → PROCESSING 버블(FIFO).
// - 전송 직후 폴링(2초·최대 10회) → reconcileLatest 가 BOT 결과 도착 시 가장 오래된 PROCESSING 교체(AC-6).
// - 폴링 상한 종료(AC-7), 이탈 중단(AC-8), 다회 전송 단일 루프(AC-9, FR-9).
// - dedup, 2000자 가드(BR-3/AC-4), 로드 오름차순.
//
// 폴링 간격(sleeper)을 주입해 결정성을 확보한다(실제 Task.sleep 대신 즉시/지연 제어).
// 의존(ChatAPI/PinAPI/GroupAPI/CurrentUser)은 in-file 프로토콜 목으로 주입(MapViewModelTests 패턴).
@MainActor
final class BotChatViewModelTests: XCTestCase {

    // MARK: - 전송 → PROCESSING

    func test_send_appendsProcessingBubble() async {
        let chatAPI = StubChatAPI()
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        let vm = makeViewModel(chatAPI: chatAPI)
        await vm.appear()

        vm.draft = "https://instagram.com/reel/abc"
        await vm.send()

        // PROCESSING 버블 1건 추가, 입력 초기화.
        XCTAssertEqual(vm.messages.count, 1)
        XCTAssertEqual(vm.messages.first?.kind, .PROCESSING)
        XCTAssertEqual(vm.messages.first?.messageId, 100)
        XCTAssertEqual(vm.draft, "")
        await vm.disappear()
    }

    // MARK: - AC-6: 전송 → 폴링 자동 반영 → PROCESSING 교체

    func test_send_pollingAutoReflectsResult() async {
        let chatAPI = StubChatAPI()
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        let sleeper = DelayingSleeper(delaySeconds: 0.02)
        let vm = makeViewModel(chatAPI: chatAPI, sleeper: { _ in await sleeper.sleep() })
        await vm.appear() // 초기 로드(빈)

        // 폴링이 가져올 봇 결과 설정(서버 id DESC: 결과 200 + 원 PROCESSING 100).
        chatAPI.messagesResult = .success(MessagesResponse(
            messages: [makeFrame(messageId: 200, kind: .PLACE_CARDS), makeFrame(messageId: 100, kind: .PROCESSING)],
            hasMore: false, nextCursor: nil))

        vm.draft = "릴스 링크"
        await vm.send()

        await waitUntil { vm.messages.contains { $0.messageId == 200 } }

        XCTAssertTrue(vm.messages.contains { $0.messageId == 200 && $0.kind == .PLACE_CARDS })
        XCTAssertFalse(vm.messages.contains { $0.kind == .PROCESSING }, "결과 도착 시 PROCESSING 교체(AC-2/6).")
        await vm.disappear()
    }

    // MARK: - AC-2: reconcileLatest 결과 교체(폴링이 호출하는 단위 동작)

    func test_reconcileLatest_replacesOldestProcessing() async {
        let chatAPI = StubChatAPI()
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        let vm = makeViewModel(chatAPI: chatAPI)
        await vm.appear()
        vm.draft = "릴스"; await vm.send()
        await vm.disappear() // 자동 폴링 중단 후 직접 검증
        XCTAssertEqual(vm.messages.map(\.kind), [.PROCESSING])

        chatAPI.messagesResult = .success(MessagesResponse(
            messages: [makeFrame(messageId: 200, kind: .PLACE_CARDS), makeFrame(messageId: 100, kind: .PROCESSING)],
            hasMore: false, nextCursor: nil))
        await vm.reconcileLatest()

        XCTAssertEqual(vm.messages.count, 1)
        XCTAssertEqual(vm.messages.first?.kind, .PLACE_CARDS)
        XCTAssertEqual(vm.messages.first?.messageId, 200)
        XCTAssertFalse(vm.messages.contains { $0.kind == .PROCESSING })
    }

    func test_reconcileLatest_multiplePending_replacesFIFO() async {
        let chatAPI = StubChatAPI()
        let vm = makeViewModel(chatAPI: chatAPI)
        await vm.appear()

        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        vm.draft = "첫"; await vm.send()
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 101, kind: .PROCESSING))
        vm.draft = "둘"; await vm.send()
        await vm.disappear()
        XCTAssertEqual(vm.messages.map(\.messageId), [100, 101])

        // 첫 결과(SYSTEM 200) → 가장 오래된 PROCESSING(100) 교체.
        chatAPI.messagesResult = .success(MessagesResponse(
            messages: [
                makeFrame(messageId: 200, kind: .SYSTEM),
                makeFrame(messageId: 101, kind: .PROCESSING),
                makeFrame(messageId: 100, kind: .PROCESSING)
            ], hasMore: false, nextCursor: nil))
        await vm.reconcileLatest()

        XCTAssertEqual(Set(vm.messages.map(\.messageId)), [101, 200])
    }

    // MARK: - AC-7: 폴링 상한(10회) 종료

    func test_polling_stopsAtMaxAttempts() async {
        let chatAPI = StubChatAPI() // messagesResult 빈(기본) → 결과 안 옴
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        let sleeper = DelayingSleeper(delaySeconds: 0.01)
        let vm = makeViewModel(chatAPI: chatAPI, sleeper: { _ in await sleeper.sleep() })
        await vm.appear()
        vm.draft = "릴스"; await vm.send()

        await waitUntil { sleeper.count >= BotChatViewModel.maxPollAttempts }
        // 상한 도달 후 추가로 돌지 않는다.
        try? await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertEqual(sleeper.count, BotChatViewModel.maxPollAttempts, "폴링은 상한(10회)에서 종료한다.")
        XCTAssertTrue(vm.messages.contains { $0.kind == .PROCESSING }, "결과 미수신 시 PROCESSING 유지.")
    }

    // MARK: - AC-8: 이탈(disappear) 시 폴링 중단

    func test_disappear_stopsPolling() async {
        let chatAPI = StubChatAPI()
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        let sleeper = DelayingSleeper(delaySeconds: 0.1)
        let vm = makeViewModel(chatAPI: chatAPI, sleeper: { _ in await sleeper.sleep() })
        await vm.appear()
        vm.draft = "릴스"; await vm.send()

        await waitUntil { sleeper.count >= 1 } // 첫 폴링 sleep 진입
        let countAtDisappear = sleeper.count
        await vm.disappear() // cancel

        // 취소 후 폴링이 더 진행되지 않는다(취소 시점 진행 중 1틱 외 증가 없음).
        try? await Task.sleep(nanoseconds: 400_000_000)
        XCTAssertLessThanOrEqual(sleeper.count, countAtDisappear + 1, "disappear 후 폴링 중단(AC-8).")
    }

    // MARK: - AC-9: 다회 연속 전송 단일 폴링 루프(FR-9)

    func test_multipleSends_singlePollingLoop() async {
        let chatAPI = StubChatAPI() // 빈 → 단일 루프 10회까지 진행
        let sleeper = DelayingSleeper(delaySeconds: 0.02)
        let vm = makeViewModel(chatAPI: chatAPI, sleeper: { _ in await sleeper.sleep() })
        await vm.appear()

        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        vm.draft = "첫"; await vm.send()
        await waitUntil { sleeper.count >= 1 } // 첫 폴링이 sleep 진입(루프 점유)
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 101, kind: .PROCESSING))
        vm.draft = "둘"; await vm.send() // 실행 중 루프 재사용(중복 생성 금지)

        await waitUntil { sleeper.count >= BotChatViewModel.maxPollAttempts }
        try? await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertEqual(sleeper.count, BotChatViewModel.maxPollAttempts, "단일 폴링 루프만 실행한다(중복 생성 시 초과).")
        await vm.disappear()
    }

    // MARK: - BR-3/AC-4: 2000자 가드

    func test_send_overLimit_blocked() async {
        let chatAPI = StubChatAPI()
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        let vm = makeViewModel(chatAPI: chatAPI)
        await vm.appear()

        vm.draft = String(repeating: "가", count: BotChatViewModel.messageMaxLength + 1)
        await vm.send()

        XCTAssertEqual(chatAPI.sendCallCount, 0)
        XCTAssertTrue(vm.messages.isEmpty)
        XCTAssertFalse(vm.draft.isEmpty)
    }

    func test_send_atLimit_allowed() async {
        let chatAPI = StubChatAPI()
        chatAPI.sendResult = .success(SendMessageResponse(messageId: 100, kind: .PROCESSING))
        let vm = makeViewModel(chatAPI: chatAPI)
        await vm.appear()

        vm.draft = String(repeating: "a", count: BotChatViewModel.messageMaxLength)
        await vm.send()

        XCTAssertEqual(chatAPI.sendCallCount, 1)
        XCTAssertEqual(vm.messages.count, 1)
        await vm.disappear()
    }

    // MARK: - 로드(오름차순 reverse)

    func test_load_reversesToAscending() async {
        let chatAPI = StubChatAPI()
        // 서버는 id DESC(최신순) 반환.
        chatAPI.messagesResult = .success(MessagesResponse(
            messages: [makeFrame(messageId: 3, kind: .TEXT), makeFrame(messageId: 2, kind: .TEXT), makeFrame(messageId: 1, kind: .TEXT)],
            hasMore: false, nextCursor: nil))
        let vm = makeViewModel(chatAPI: chatAPI)
        await vm.appear()

        // 화면은 오름차순(1,2,3).
        XCTAssertEqual(vm.messages.map(\.messageId), [1, 2, 3])
    }

    // MARK: - dedup(reconcile 재호출 시 동일 id 차단)

    func test_reconcileLatest_dedupesKnownIds() async {
        let chatAPI = StubChatAPI()
        let vm = makeViewModel(chatAPI: chatAPI)
        await vm.appear()

        chatAPI.messagesResult = .success(MessagesResponse(
            messages: [makeFrame(messageId: 300, kind: .SYSTEM)], hasMore: false, nextCursor: nil))
        await vm.reconcileLatest()
        await vm.reconcileLatest() // 동일 id 재조회

        XCTAssertEqual(vm.messages.filter { $0.messageId == 300 }.count, 1)
    }

    // MARK: - 헬퍼

    private func makeViewModel(
        chatAPI: StubChatAPI,
        pinAPI: PinAPIProtocol? = nil,
        groupAPI: GroupAPIProtocol? = nil,
        sleeper: @escaping @Sendable (Double) async -> Void = { _ in }
    ) -> BotChatViewModel {
        BotChatViewModel(
            chatAPI: chatAPI,
            pinAPI: pinAPI ?? StubBotPinAPI(),
            groupAPI: groupAPI ?? StubBotGroupAPI(group: ActiveGroup(groupId: 1, name: "팀", memberCount: 2)),
            currentUser: makeCurrentUser(),
            sleeper: sleeper
        )
    }
}

// MARK: - 폴링 테스트 지원

/// 주입 가능한 폴링 sleeper. 호출 횟수를 기록하고, delaySeconds>0 이면 실제로 그만큼 대기한다(취소 가능).
/// 즉시(0)는 빠른 루프 검증, 지연(>0)은 이탈/중복 타이밍 검증에 쓴다.
final class DelayingSleeper: @unchecked Sendable {
    private let lock = NSLock()
    private var _count = 0
    private let delaySeconds: Double

    init(delaySeconds: Double = 0) {
        self.delaySeconds = delaySeconds
    }

    var count: Int {
        lock.lock(); defer { lock.unlock() }
        return _count
    }

    func sleep() async {
        // Swift 6: NSLock.lock()/unlock() 은 async 컨텍스트에서 noasync. 동기 스코프 withLock 으로 카운트한다.
        lock.withLock { _count += 1 }
        if delaySeconds > 0 {
            try? await Task.sleep(nanoseconds: UInt64(delaySeconds * 1_000_000_000))
        }
    }
}

/// 조건이 참이 될 때까지(또는 상한까지) 짧게 대기하며 폴링 비동기 완료를 기다린다.
@MainActor
func waitUntil(_ condition: () -> Bool, maxIterations: Int = 300) async {
    var i = 0
    while !condition() && i < maxIterations {
        try? await Task.sleep(nanoseconds: 10_000_000) // 10ms
        i += 1
    }
}

// MARK: - 공유 헬퍼(이 파일 + PlaceCardSaveTests)

@MainActor
func makeCurrentUser() -> CurrentUser {
    // appear() 가 userId 선행 로드(currentUser.load() → GET /users/me)를 수행하므로(cross-review),
    // 실제 외부 네트워크 호출 대신 StubURLProtocol 로 me() 응답을 가로채 결정적으로 id 를 채운다.
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [StubMeURLProtocol.self]
    let session = URLSession(configuration: config)
    let client = APIClient(baseURL: URL(string: "https://example.com")!, tokens: DummyTokenStore(), session: session)
    return CurrentUser(authAPI: AuthAPI(client: client))
}

/// GET /users/me 를 가로채 고정 UserResponse(id:1) envelope 를 반환하는 테스트용 URLProtocol.
final class StubMeURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.path.hasSuffix("/users/me") ?? false
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let body = Data(#"{"data":{"id":1,"nickname":"tester","profileImageUrl":null}}"#.utf8)
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: 200,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// JSON 경유로 ChatFrame 구성(ChatFrame 은 커스텀 디코더만 보유 — 직접 init 불가).
func makeFrame(messageId: Int, kind: MessageKind, cards: [PlaceCard]? = nil, text: String? = nil) -> ChatFrame {
    let payload: String
    switch kind {
    case .PLACE_CARDS:
        // 변수 분리 + 보간으로 타입 추론을 단순화한다(Swift 6: map{...}.joined / String.init 오버로드가
        //  중첩 보간 안에서 'ambiguous without type annotation' 을 유발 — parts:[String] 명시로 해소).
        let parts: [String] = (cards ?? []).map { card in
            let kakao = card.kakaoPlaceId.map { "\"\($0)\"" } ?? "null"
            let addr = card.address.map { "\"\($0)\"" } ?? "null"
            let lat = card.latitude.map { "\($0)" } ?? "null"
            let lng = card.longitude.map { "\($0)" } ?? "null"
            return "{\"kakaoPlaceId\":\(kakao),\"name\":\"\(card.name)\",\"address\":\(addr),\"latitude\":\(lat),\"longitude\":\(lng)}"
        }
        let cardsJSON = parts.joined(separator: ",")
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

    func createGroup(name: String) async throws -> GroupCreated { GroupCreated(groupId: 0, name: name) }
    func previewBySlug(slug: String) async throws -> InvitePreview { InvitePreview(token: "stub", groupName: "stub", inviterNickname: nil, expiresAt: nil) }
    func acceptInvite(token: String) async throws -> InviteAccept { InviteAccept(groupId: 0) }
    func issueInviteLink(groupId: Int) async throws -> InviteLink { InviteLink(token: "stub", slug: nil, shareUrl: nil) }
    func leaveGroup(groupId: Int) async throws {}
}

final class DummyTokenStore: TokenStore, @unchecked Sendable {
    func accessToken() async -> String? { nil }
    func refresh() async throws {}
}
