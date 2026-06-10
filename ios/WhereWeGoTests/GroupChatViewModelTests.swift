import XCTest
@testable import WhereWeGo

// GroupChatViewModel 단위 테스트(GC-2 설계 §3). 발신자 구분·reconcile registered 갱신·URL 분기 전송·
// 추출 상태머신·409 흡수·딥링크·willPresent 신호.
// StubChatAPI/StubBotPinAPI/makeCurrentUser/waitUntil 은 BotChatViewModelTests.swift 공유 정의.
@MainActor
final class GroupChatViewModelTests: XCTestCase {

    // MARK: - 헬퍼

    private func makeVM(
        chatAPI: StubChatAPI,
        pinAPI: PinAPIProtocol? = nil,
        router: DeepLinkRouter = DeepLinkRouter(),
        signal: ChatPushSignal = ChatPushSignal(),
        groupId: Int = 1,
        roomId: Int? = 10,
        sleeper: @escaping @Sendable (Double) async -> Void = { _ in }
    ) -> GroupChatViewModel {
        GroupChatViewModel(
            groupId: groupId,
            roomId: roomId,
            chatAPI: chatAPI,
            pinAPI: pinAPI ?? StubBotPinAPI(),
            currentUser: makeCurrentUser(),   // id=1
            deepLinkRouter: router,
            chatPushSignal: signal,
            sleeper: sleeper
        )
    }

    private func frame(
        _ id: Int, sender: Int?, kind: MessageKind = .TEXT,
        url: String? = nil, registered: Bool? = nil, text: String? = "hi"
    ) -> GroupChatFrame {
        GroupChatFrame(
            messageId: id, roomId: 10, senderUserId: sender,
            senderNickname: sender.map { "u\($0)" }, kind: kind,
            createdAt: "2026-06-10T12:00:00+09:00",
            reelUrl: url, registered: registered, text: kind == .TEXT ? text : nil
        )
    }

    private func card(_ name: String, lat: Double?, lng: Double?) -> PlaceCard {
        PlaceCard(kakaoPlaceId: nil, name: name, address: nil, latitude: lat, longitude: lng)
    }

    // MARK: - 로드(오름차순)

    func test_load_reversesToAscending() async {
        let api = StubChatAPI()
        api.groupMessagesResult = .success(GroupMessagesResponse(
            groupId: 1, messages: [frame(3, sender: 5), frame(2, sender: 5), frame(1, sender: 5)],
            hasMore: false, nextCursor: nil))
        let vm = makeVM(chatAPI: api)
        await vm.load()
        XCTAssertEqual(vm.messages.map(\.messageId), [1, 2, 3])
    }

    // MARK: - 발신자 구분

    func test_currentUserId_reflectsCurrentUser() async {
        let vm = makeVM(chatAPI: StubChatAPI())
        await vm.appear()   // currentUser.load → id=1
        XCTAssertEqual(vm.currentUserId, 1)
        await vm.disappear()
    }

    // MARK: - 전송 분기(FR-GC2-8)

    func test_send_reelURLAlone_sendsReelLink() async {
        let api = StubChatAPI()
        api.sendGroupResult = .success(SendMessageResponse(messageId: 100, kind: .REEL_LINK))
        let vm = makeVM(chatAPI: api)
        await vm.appear()
        vm.draft = "https://instagram.com/reel/ABC"
        await vm.send()
        XCTAssertEqual(api.lastSentKind, .REEL_LINK)
        XCTAssertEqual(api.lastSentURL, "https://instagram.com/reel/ABC")
        XCTAssertNil(api.lastSentText)
        XCTAssertTrue(vm.messages.contains { $0.messageId == 100 && $0.kind == .REEL_LINK && $0.registered == false })
        await vm.disappear()
    }

    func test_send_mixedText_sendsText() async {
        let api = StubChatAPI()
        api.sendGroupResult = .success(SendMessageResponse(messageId: 101, kind: .TEXT))
        let vm = makeVM(chatAPI: api)
        await vm.appear()
        vm.draft = "여기 가보자 https://instagram.com/reel/ABC"
        await vm.send()
        XCTAssertEqual(api.lastSentKind, .TEXT)
        XCTAssertEqual(api.lastSentText, "여기 가보자 https://instagram.com/reel/ABC")
        XCTAssertNil(api.lastSentURL)
        await vm.disappear()
    }

    // MARK: - reconcile registered 교체-병합(FR-GC2-3)

    func test_reconcile_updatesRegisteredOnSameMessage() async {
        let api = StubChatAPI()
        api.groupMessagesResult = .success(GroupMessagesResponse(
            groupId: 1, messages: [frame(5, sender: 1, kind: .REEL_LINK, url: "u", registered: false, text: nil)],
            hasMore: false, nextCursor: nil))
        let vm = makeVM(chatAPI: api)
        await vm.load()
        XCTAssertEqual(vm.messages.first?.registered, false)

        // 저장 후 같은 messageId 가 registered:true 로 내려옴.
        api.groupMessagesResult = .success(GroupMessagesResponse(
            groupId: 1, messages: [frame(5, sender: 1, kind: .REEL_LINK, url: "u", registered: true, text: nil)],
            hasMore: false, nextCursor: nil))
        await vm.reconcileLatest()

        XCTAssertEqual(vm.messages.count, 1)
        XCTAssertEqual(vm.messages.first?.registered, true, "동일 messageId 의 registered 를 교체-병합해야 한다.")
    }

    func test_reconcile_appendsNewMessage() async {
        let api = StubChatAPI()
        api.groupMessagesResult = .success(GroupMessagesResponse(
            groupId: 1, messages: [frame(1, sender: 5)], hasMore: false, nextCursor: nil))
        let vm = makeVM(chatAPI: api)
        await vm.load()
        api.groupMessagesResult = .success(GroupMessagesResponse(
            groupId: 1, messages: [frame(2, sender: 7), frame(1, sender: 5)], hasMore: false, nextCursor: nil))
        await vm.reconcileLatest()
        XCTAssertEqual(vm.messages.map(\.messageId), [1, 2])
    }

    // MARK: - 추출 상태머신(FR-GC2-4)

    func test_register_success_showsWizard() async {
        let api = StubChatAPI()
        api.extractResult = .success(PlaceCardsPayload(
            cards: [card("장소", lat: 37.5, lng: 127.0)], sourceInstagramUrl: "u"))
        let vm = makeVM(chatAPI: api)
        vm.register(messageId: 5, url: "u")
        await waitUntil { if case .wizard = vm.registerState { return true }; return false }
        guard case let .wizard(_, _, cards) = vm.registerState else { return XCTFail("wizard 여야 한다") }
        XCTAssertEqual(cards.count, 1)
        XCTAssertEqual(api.extractedMessageId, 5)
    }

    func test_register_emptyCards_showsEmpty() async {
        let api = StubChatAPI()
        api.extractResult = .success(PlaceCardsPayload(cards: [], sourceInstagramUrl: "u"))
        let vm = makeVM(chatAPI: api)
        vm.register(messageId: 5, url: "u")
        await waitUntil { if case .empty = vm.registerState { return true }; return false }
        if case .empty = vm.registerState {} else { XCTFail("0곳은 empty 안내") }
    }

    func test_register_forbidden_showsFailed() async {
        let api = StubChatAPI()
        api.extractResult = .failure(APIError(code: "CHAT_EXTRACT_FORBIDDEN", status: 403, message: "x"))
        let vm = makeVM(chatAPI: api)
        vm.register(messageId: 5, url: "u")
        await waitUntil { if case .failed = vm.registerState { return true }; return false }
        if case .failed = vm.registerState {} else { XCTFail("권한 거부는 failed") }
    }

    // MARK: - 저장 409 흡수(FR-GC2-4)

    func test_save_duplicateAbsorbed() async {
        let pinAPI = StubBotPinAPI()
        pinAPI.createResult = .failure(APIError(code: "PLC_DUPLICATE_PIN", status: 409, message: "dup"))
        let vm = makeVM(chatAPI: StubChatAPI(), pinAPI: pinAPI)
        await vm.saveFromWizard(url: "u", cards: [card("A", lat: 37.5, lng: 127.0)], wishIDs: [], memo: nil)
        // 409 흡수 → 저장 0곳, 시트 닫힘(idle) + 안내 배너("이미 저장된 장소").
        XCTAssertEqual(vm.registerState, .idle)
        XCTAssertEqual(vm.saveInfoMessage, "이미 저장된 장소예요.")
    }

    // MARK: - 딥링크(FR-GC2-5)

    func test_openReel_setsReelFocusPending() {
        let router = DeepLinkRouter()
        let vm = makeVM(chatAPI: StubChatAPI(), router: router, groupId: 7)
        vm.openReel(url: "https://instagram.com/reel/abc")
        XCTAssertEqual(router.pending, .reelFocus(groupId: 7, instagramUrl: "https://instagram.com/reel/abc"))
    }

    // MARK: - willPresent 신호(FR-GC2-6)

    func test_pushSignal_currentRoomMatching() async {
        let signal = ChatPushSignal()
        let vm = makeVM(chatAPI: StubChatAPI(), signal: signal, roomId: 10)
        await vm.appear()                                  // register roomId 10
        XCTAssertTrue(signal.notifyIfCurrent(roomId: 10))  // 현재 방 → 배너 억제 + tick
        XCTAssertFalse(signal.notifyIfCurrent(roomId: 99)) // 다른 방 → 배너 표시
        await vm.disappear()                               // clear
        XCTAssertFalse(signal.notifyIfCurrent(roomId: 10)) // 해제됨
    }
}
