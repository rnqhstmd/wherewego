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

    // MARK: - 커서 소유권(FR-GC2-3/BR-3, AC-3/4/9)

    /// AC-3/9: loadMore 로 커서를 확정한 뒤 reconcileLatest 를 반복해도 nextCursor/hasMore 가 유지된다.
    /// (merge 경로에서 커서를 최신 페이지 값으로 되돌리던 버그 ② 회귀 차단.)
    func test_reconcile_doesNotMutateCursorAfterLoadMore() async {
        let api = StubChatAPI()
        // 최신 페이지: hasMore=true, nextCursor=2(과거 더 있음).
        api.groupMessagesResult = .success(GroupMessagesResponse(
            groupId: 1, messages: [frame(3, sender: 5)], hasMore: true, nextCursor: 2))
        let vm = makeVM(chatAPI: api)
        await vm.load()

        // loadMore: 과거 페이지(2,1) prepend + 커서 확정(더 과거 없음).
        api.groupMessagesResult = .success(GroupMessagesResponse(
            groupId: 1, messages: [frame(2, sender: 5), frame(1, sender: 5)], hasMore: false, nextCursor: nil))
        await vm.loadMore()
        XCTAssertEqual(vm.messages.map(\.messageId), [1, 2, 3])

        // 이후 reconcile 이 최신 페이지(hasMore=true, nextCursor=2)를 다시 받아도 커서는 불변이어야 한다.
        api.groupMessagesResult = .success(GroupMessagesResponse(
            groupId: 1, messages: [frame(3, sender: 5)], hasMore: true, nextCursor: 2))
        await vm.reconcileLatest()
        await vm.reconcileLatest()

        // 커서가 되돌아갔다면 loadMore 가드(hasMore=false)가 풀려 다시 과거 로드를 시도하게 된다 →
        //  loadMore 호출 시 추가 조회가 발생하지 않아야(커서 유지) 함을 호출 횟수로 검증.
        let before = api.groupMessagesCallCount
        await vm.loadMore()
        XCTAssertEqual(api.groupMessagesCallCount, before, "커서 유지 시 loadMore 는 hasMore=false 가드로 no-op 이어야 한다.")
    }

    /// AC-4: load/loadMore 후에는 커서가 응답값으로 정상 갱신된다(replaceAll/loadMore 경로 소유권 유지).
    func test_loadAndLoadMore_updateCursorFromResponse() async {
        let api = StubChatAPI()
        api.groupMessagesResult = .success(GroupMessagesResponse(
            groupId: 1, messages: [frame(2, sender: 5)], hasMore: true, nextCursor: 1))
        let vm = makeVM(chatAPI: api)
        await vm.load()

        // hasMore=true·nextCursor=1 이므로 loadMore 가 실제 조회를 수행(커서가 정상 갱신됨을 입증).
        api.groupMessagesResult = .success(GroupMessagesResponse(
            groupId: 1, messages: [frame(1, sender: 5)], hasMore: false, nextCursor: nil))
        let before = api.groupMessagesCallCount
        await vm.loadMore()
        XCTAssertEqual(api.groupMessagesCallCount, before + 1, "load 가 커서를 갱신해 loadMore 가 동작해야 한다.")
        XCTAssertEqual(vm.messages.map(\.messageId), [1, 2])
    }

    // MARK: - send-poll 조기 종료(FR-GC2-9, AC-5/6)

    /// AC-5: 전송 후 폴링 중 타인의 새 메시지가 도착하면 루프가 즉시 종료된다(추가 reconcile 없음).
    func test_sendPolling_earlyExitOnOthersMessage() async {
        let api = StubChatAPI()
        api.sendGroupResult = .success(SendMessageResponse(messageId: 100, kind: .TEXT))
        // 큐 1번(load): 타인 메시지 없는 초기 페이지. 큐 2번(send-poll 1회차): 타인(7)의 새 메시지 200 첫 등장 → 즉시 종료.
        //  타인 메시지를 send-poll reconcile 에서 처음 append 하도록 분리해야 "조기 종료 = 1회차" 를 정확히 검증할 수 있다.
        api.groupMessagesQueue = [
            GroupMessagesResponse(
                groupId: 1, messages: [frame(50, sender: 5)],
                hasMore: false, nextCursor: nil),                                  // load
            GroupMessagesResponse(
                groupId: 1, messages: [frame(200, sender: 7), frame(50, sender: 5)],
                hasMore: false, nextCursor: nil)                                   // send-poll 1회차(타인 도착)
        ]
        let vm = makeVM(chatAPI: api)
        await vm.appear()       // currentUser.load → id=1 + load 1회(큐 1번 소비) + 라이브 폴링 시작.
        await vm.disappear()    // 라이브 폴링 취소(같은 reconcileLatest 경로 격리) — send-poll 은 아직 미시작.
        let afterAppear = api.groupMessagesCallCount

        vm.draft = "hello"
        await vm.send()         // 낙관 append + startSendPolling
        // 폴링 1회차 reconcile 에서 타인 메시지 200 이 append 될 때까지 대기(= 조기 종료 신호).
        await waitUntil { vm.messages.contains { $0.messageId == 200 } }
        // waitUntil 은 타임아웃 시에도 그냥 반환하므로, 도달 여부를 먼저 명시 검증해 false negative 를 차단한다.
        XCTAssertTrue(vm.messages.contains { $0.messageId == 200 },
                      "타임아웃: 타인 메시지(200)가 send-poll reconcile 로 도달하지 않았다.")

        // 조기 종료 → send-poll 의 reconcile 은 정확히 1회만(추가 폴링 회차 없음, AC-5).
        XCTAssertEqual(api.groupMessagesCallCount, afterAppear + 1, "타인 메시지 수신 시 1회차 reconcile 후 종료해야 한다.")
    }

    /// AC-6: 새 메시지가 없거나 내 메시지만 보이면 폴링은 최대 10회를 채운다(조기 종료 안 함).
    func test_sendPolling_runsMaxAttemptsWhenNoOthers() async {
        let api = StubChatAPI()
        api.sendGroupResult = .success(SendMessageResponse(messageId: 100, kind: .TEXT))
        // 매 회차 동일 최신 페이지(내 메시지 100 만) → 신규 타인 append 없음 → 10회 소진.
        api.groupMessagesResult = .success(GroupMessagesResponse(
            groupId: 1, messages: [frame(100, sender: 1, text: "mine")], hasMore: false, nextCursor: nil))
        let vm = makeVM(chatAPI: api)
        await vm.appear()       // currentUser.load → id=1 + load 1회 + 라이브 폴링 시작.
        await vm.disappear()    // 라이브 폴링 취소(같은 reconcileLatest 경로가 카운트를 오염시키므로 격리) — send-poll 은 아직 미시작.
        let afterAppear = api.groupMessagesCallCount

        vm.draft = "hello"
        await vm.send()         // 낙관 append + startSendPolling(라이브 폴링은 꺼진 상태이므로 send-poll 만 측정됨).
        // 폴링이 10회(maxSendPollAttempts)를 모두 소진할 때까지 대기.
        await waitUntil { api.groupMessagesCallCount >= afterAppear + GroupChatViewModel.maxSendPollAttempts }
        XCTAssertEqual(api.groupMessagesCallCount, afterAppear + GroupChatViewModel.maxSendPollAttempts,
                       "조기 종료 없이 정확히 10회 폴링해야 한다.")
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

    /// PIN_REPLY 핀 카드 탭 → 그 그룹/핀으로 .pinFocus 딥링크 세팅(openReel 미러).
    func test_openPin_setsPinFocusPending() {
        let router = DeepLinkRouter()
        let vm = makeVM(chatAPI: StubChatAPI(), router: router, groupId: 7)
        vm.openPin(pinId: 42)
        XCTAssertEqual(router.pending, .pinFocus(groupId: 7, pinIds: [42]))
    }

    // MARK: - PIN_REPLY pinSnapshot 디코딩(설계 §1)

    /// PIN_REPLY 프레임이 top-level pinSnapshot + payload.text 를 평탄화 디코딩하는지 검증.
    func test_decode_pinReplyFrame_parsesSnapshotAndText() throws {
        let json = """
        {"messageId":7,"roomId":10,"senderUserId":1,"senderNickname":"u1","kind":"PIN_REPLY",
         "payload":{"text":"여기 좋아요"},"createdAt":"2026-06-10T12:00:00+09:00",
         "pinSnapshot":{"pinId":42,"placeName":"성수 카페","tag":"WISH","memo":"분위기 굿",
                        "photoThumbnailUrl":"https://t","photoUrl":"https://f","deleted":false}}
        """
        let frame = try JSONDecoder().decode(GroupChatFrame.self, from: Data(json.utf8))
        XCTAssertEqual(frame.kind, .PIN_REPLY)
        XCTAssertEqual(frame.text, "여기 좋아요")
        XCTAssertEqual(frame.pinSnapshot?.pinId, 42)
        XCTAssertEqual(frame.pinSnapshot?.placeName, "성수 카페")
        XCTAssertEqual(frame.pinSnapshot?.tag, "WISH")
        XCTAssertEqual(frame.pinSnapshot?.deleted, false)
    }

    /// 핀 삭제 프레임: deleted=true + placeName 유지, 사진 nil.
    func test_decode_pinReplyFrame_deletedSnapshot() throws {
        let json = """
        {"messageId":8,"roomId":10,"senderUserId":1,"senderNickname":"u1","kind":"PIN_REPLY",
         "payload":{"text":"hi"},"createdAt":"2026-06-10T12:00:00+09:00",
         "pinSnapshot":{"pinId":43,"placeName":"옛 장소","tag":null,"memo":null,
                        "photoThumbnailUrl":null,"photoUrl":null,"deleted":true}}
        """
        let frame = try JSONDecoder().decode(GroupChatFrame.self, from: Data(json.utf8))
        XCTAssertEqual(frame.pinSnapshot?.deleted, true)
        XCTAssertEqual(frame.pinSnapshot?.placeName, "옛 장소")
        XCTAssertNil(frame.pinSnapshot?.photoThumbnailUrl)
    }

    /// 비-PIN_REPLY(TEXT) 프레임은 pinSnapshot 이 nil(top-level 키 부재 → decodeIfPresent).
    func test_decode_textFrame_pinSnapshotNil() throws {
        let json = """
        {"messageId":9,"roomId":10,"senderUserId":1,"senderNickname":"u1","kind":"TEXT",
         "payload":{"text":"안녕"},"createdAt":"2026-06-10T12:00:00+09:00"}
        """
        let frame = try JSONDecoder().decode(GroupChatFrame.self, from: Data(json.utf8))
        XCTAssertNil(frame.pinSnapshot)
        XCTAssertEqual(frame.text, "안녕")
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
