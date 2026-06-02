import XCTest
@testable import WhereWeGo

// CoupleChatViewModel 단위 테스트(설계 §6, FR-11/12, BR-3, AC-5/6/9).
//  - 낙관 전송 → 응답 messageId 로 실제 치환(임시 음수 id 제거).
//  - 파트너 메시지 STOMP 수신 → append.
//  - 내 메시지 STOMP echo → messageId 일치 dedup(중복 미표시).
//  - 1000자 가드(BR-3/AC-5): 초과 입력 전송 차단(절단 안 함).
//  - 로드 오름차순, 재연결 보완(AC-9) 신규만 merge.
//
// in-file 목은 BotChatViewModelTests(C7) 의 전역 StubChatAPI/StubChatRealtime 와 이름 충돌을 피하려
// Couple 접두 private 타입으로 분리한다. makeCurrentUser() 는 C7 의 전역 공유 헬퍼를 재사용한다.
// CoupleChatViewModel·CurrentUser 가 @MainActor 이므로 테스트도 @MainActor.
@MainActor
final class CoupleChatViewModelTests: XCTestCase {

    // MARK: - 낙관 전송 → 실제 id 치환(AC-6)

    func test_send_optimisticBubble_replacedWithRealId() async {
        let api = CoupleStubChatAPI(sendResult: SendMessageResponse(messageId: 100, kind: .TEXT))
        let vm = makeVM(api: api)
        await vm.appear()

        vm.draft = "안녕"
        await vm.send()

        // 낙관 버블(음수 id)이 실제 id 100 으로 치환 — 메시지 1건, id 양수, 음수 부재.
        XCTAssertEqual(vm.messages.count, 1)
        XCTAssertEqual(vm.messages.first?.messageId, 100)
        XCTAssertFalse(vm.messages.contains { $0.messageId < 0 })
        XCTAssertEqual(vm.draft, "")
        XCTAssertNil(vm.sendErrorMessage)
    }

    func test_send_failure_rollsBackOptimisticAndRestoresDraft() async {
        let api = CoupleStubChatAPI(sendError: APIError(code: "BOOM", status: 500, message: "fail"))
        let vm = makeVM(api: api)
        await vm.appear()

        vm.draft = "보낼 메시지"
        await vm.send()

        // 전송 실패 → 낙관 버블 제거, draft 복원, 안내 노출.
        XCTAssertTrue(vm.messages.isEmpty)
        XCTAssertEqual(vm.draft, "보낼 메시지")
        XCTAssertNotNil(vm.sendErrorMessage)
    }

    // MARK: - 파트너 메시지 수신 append(FR-12)

    func test_incoming_partnerMessage_appended() async {
        let vm = makeVM(api: CoupleStubChatAPI())
        await vm.appear()

        let partner = makeCoupleFrame(messageId: 50, senderType: .SYSTEM, text: "파트너 메시지")
        vm.handleIncoming(partner)

        XCTAssertEqual(vm.messages.count, 1)
        XCTAssertEqual(vm.messages.first?.messageId, 50)
    }

    // MARK: - 내 메시지 STOMP 중복 제거(AC-6)

    func test_incoming_myEchoAfterReplace_dedupedByMessageId() async {
        let api = CoupleStubChatAPI(sendResult: SendMessageResponse(messageId: 200, kind: .TEXT))
        let vm = makeVM(api: api)
        await vm.appear()

        // 전송 → 낙관 버블이 id 200 으로 치환됨.
        vm.draft = "내 메시지"
        await vm.send()
        XCTAssertEqual(vm.messages.count, 1)

        // 같은 id 200 STOMP echo 도착 → messageId 일치 dedup.
        let echo = makeCoupleFrame(messageId: 200, senderType: .USER, text: "내 메시지")
        vm.handleIncoming(echo)

        XCTAssertEqual(vm.messages.count, 1)
        XCTAssertEqual(vm.messages.first?.messageId, 200)
    }

    func test_incoming_duplicateMessageId_deduped() async {
        let vm = makeVM(api: CoupleStubChatAPI())
        await vm.appear()

        let first = makeCoupleFrame(messageId: 77, senderType: .SYSTEM, text: "한 번")
        vm.handleIncoming(first)
        vm.handleIncoming(first)

        XCTAssertEqual(vm.messages.count, 1)
    }

    // MARK: - 1000자 가드(BR-3/AC-5)

    func test_send_overLimit_blocked() async {
        let api = CoupleStubChatAPI(sendResult: SendMessageResponse(messageId: 1, kind: .TEXT))
        let vm = makeVM(api: api)
        await vm.appear()

        vm.draft = String(repeating: "가", count: CoupleChatViewModel.textMaxLength + 1)
        await vm.send()

        // BR-3/AC-5: 1000자 초과는 절단하지 않고 전송 차단(입력과 다른 내용 전송 방지).
        // 서버 호출 없음, 버블 미추가, 입력 유지.
        XCTAssertNil(api.lastSentText)
        XCTAssertTrue(vm.messages.isEmpty)
        XCTAssertFalse(vm.draft.isEmpty)
    }

    func test_send_emptyOrWhitespace_noop() async {
        let api = CoupleStubChatAPI(sendResult: SendMessageResponse(messageId: 1, kind: .TEXT))
        let vm = makeVM(api: api)
        await vm.appear()

        vm.draft = "   \n  "
        await vm.send()

        XCTAssertTrue(vm.messages.isEmpty)
        XCTAssertNil(api.lastSentText)
    }

    // MARK: - 로드(FR-10)

    func test_appear_loadsLatestAscending() async {
        // 서버 id DESC([3,2,1]) → 화면 오름차순([1,2,3]).
        let api = CoupleStubChatAPI(messagesResult: MessagesResponse(
            messages: [
                makeCoupleFrame(messageId: 3, senderType: .USER, text: "c"),
                makeCoupleFrame(messageId: 2, senderType: .SYSTEM, text: "b"),
                makeCoupleFrame(messageId: 1, senderType: .USER, text: "a")
            ],
            hasMore: false,
            nextCursor: nil
        ))
        let vm = makeVM(api: api)
        await vm.appear()

        XCTAssertEqual(vm.messages.map(\.messageId), [1, 2, 3])
    }

    // MARK: - 재연결 보완(AC-9)

    func test_reconcileLatest_mergesNewOnly() async {
        let api = CoupleStubChatAPI(messagesResult: MessagesResponse(
            messages: [makeCoupleFrame(messageId: 1, senderType: .USER, text: "a")],
            hasMore: false,
            nextCursor: nil
        ))
        let vm = makeVM(api: api)
        await vm.appear()
        XCTAssertEqual(vm.messages.count, 1)

        // 재조회 결과에 신규(id 2) + 기존(id 1) → 신규만 추가.
        api.messagesResult = MessagesResponse(
            messages: [
                makeCoupleFrame(messageId: 2, senderType: .SYSTEM, text: "b"),
                makeCoupleFrame(messageId: 1, senderType: .USER, text: "a")
            ],
            hasMore: false,
            nextCursor: nil
        )
        await vm.reconcileLatest()

        XCTAssertEqual(vm.messages.map(\.messageId), [1, 2])
    }

    // MARK: - 헬퍼

    private func makeVM(api: CoupleStubChatAPI) -> CoupleChatViewModel {
        CoupleChatViewModel(
            chatAPI: api,
            groupAPI: CoupleStubGroupAPI(group: ActiveGroup(groupId: 7, name: "커플", memberCount: 2)),
            realtime: CoupleStubRealtime(),
            currentUser: makeCurrentUser()  // BotChatViewModelTests(C7) 전역 공유 헬퍼
        )
    }

    /// 테스트용 TEXT ChatFrame 생성(JSON 디코딩 — 프로덕션 낙관 생성과 동일 경로).
    /// C7 전역 makeFrame(시그니처 다름)과 구분되도록 Couple 전용 이름 사용.
    private func makeCoupleFrame(messageId: Int, senderType: SenderType, text: String) -> ChatFrame {
        let json: [String: Any] = [
            "messageId": messageId,
            "roomId": 7,
            "senderType": senderType.rawValue,
            "kind": MessageKind.TEXT.rawValue,
            "createdAt": "2026-01-01T00:00:00Z",
            "payload": ["text": text]
        ]
        let data = try! JSONSerialization.data(withJSONObject: json)
        return try! JSONDecoder().decode(ChatFrame.self, from: data)
    }
}

// MARK: - In-file 목(Couple 전용, C7 전역 심볼과 충돌 방지 위해 private)

private final class CoupleStubChatAPI: ChatAPIProtocol, @unchecked Sendable {
    var messagesResult: MessagesResponse
    private let sendResult: SendMessageResponse?
    private let sendError: Error?
    private(set) var lastSentText: String?

    init(
        messagesResult: MessagesResponse = MessagesResponse(messages: [], hasMore: false, nextCursor: nil),
        sendResult: SendMessageResponse? = nil,
        sendError: Error? = nil
    ) {
        self.messagesResult = messagesResult
        self.sendResult = sendResult
        self.sendError = sendError
    }

    func botMessages(cursor: Int?, limit: Int) async throws -> MessagesResponse { messagesResult }
    func sendBotMessage(text: String) async throws -> SendMessageResponse {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }
    func coupleMessages(groupId: Int, cursor: Int?, limit: Int) async throws -> MessagesResponse {
        messagesResult
    }
    func sendCoupleMessage(groupId: Int, text: String) async throws -> SendMessageResponse {
        lastSentText = text
        if let sendError { throw sendError }
        guard let sendResult else { throw APIError(code: "UNSUPPORTED", status: 0, message: "stub") }
        return sendResult
    }
}

@MainActor
private final class CoupleStubRealtime: ChatRealtimeServicing, @unchecked Sendable {
    private(set) var subscribedTopic: ChatTopic?
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
        subscribedTopic = topic
    }
    func unsubscribe(id: String) async {}
    func onForeground() async {}
    func retryManually() async {}

    /// 재연결 성공 수동 트리거(등록된 옵저버 전체 통지).
    func emitReconnected() {
        for observer in reconnectedObservers.values { observer() }
    }
}

private final class CoupleStubGroupAPI: GroupAPIProtocol, @unchecked Sendable {
    private let group: ActiveGroup?

    init(group: ActiveGroup?) { self.group = group }

    func myActiveGroup() async throws -> ActiveGroup? { group }
    func acceptInvite(token: String) async throws -> InviteAccept { InviteAccept(groupId: 0) }
    func issueInviteLink(groupId: Int) async throws -> InviteLink { InviteLink(token: "stub", slug: nil, shareUrl: nil) }
}
