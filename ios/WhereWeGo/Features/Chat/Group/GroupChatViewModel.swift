import Foundation

// 그룹 채팅 ViewModel(GC-2 설계 §3). 멤버 간 단체 채팅 + REEL_LINK 공유 + 발신자 온디맨드 장소 등록.
// 봇 BotChatViewModel 구조 차용(이벤트 전환·폴링)하되 봇 PROCESSING/결과 교체를 제거하고:
//  - 발신자 구분: senderUserId == currentUser.id (내/남 정렬·닉네임).
//  - registered 파생: reconcile 이 최신 페이지로 동일 messageId 프레임을 교체-병합해 false→true 를 반영(자기치유).
//  - 수신 경로(FR-GC2-6): 전송 직후 제한 폴링(2초×10) + 방 표시 중 8초 주기 폴링 + scenePhase 재조회(View) +
//    포그라운드 willPresent 현재 방 신호(ChatPushSignal.tick → View 가 reconcile).
//  - 전송 분기(FR-GC2-8): 입력 전체가 인스타 URL 단독이면 REEL_LINK, 그 외 TEXT.
//  - 추출 등록(FR-GC2-4): 발신자만 「장소 등록하기」 → extract → ReelSaveWizard → savePlaceCards(409 흡수) → reconcile.
@MainActor
final class GroupChatViewModel: ObservableObject {

    /// 전송 최대 길이(TEXT, 백엔드 검증과 동치). REEL_LINK 는 URL 이라 InstagramURL.maxLength 가드.
    static let messageMaxLength = 2000
    /// 최신 페이지 로드 크기(백엔드 limit 기본 20).
    static let pageLimit = 20
    /// 전송 직후 결과 폴링 간격/횟수(빠른 왕복 대화 보완).
    static let sendPollIntervalSeconds: Double = 2.0
    static let maxSendPollAttempts = 10
    /// 방 표시 중 상시 폴링 간격(멤버 대화 준실시간 반영).
    static let livePollIntervalSeconds: Double = 8.0

    // MARK: - 추출 등록 팝업 상태(FR-GC2-4)

    /// 「장소 등록하기」 흐름 상태. View 가 .sheet(item:) 또는 분기 렌더.
    enum RegisterState: Equatable {
        case idle
        /// "장소 추출 중…" — extract API 대기.
        case extracting(messageId: Int, url: String)
        /// 추출 성공 — ReelSaveWizard 표시(전체 카드, 좌표 없는 카드는 위저드가 비활성).
        case wizard(messageId: Int, url: String, cards: [PlaceCard])
        /// 추출 0곳/좌표 없음 — 안내 후 닫기(재시도 가능).
        case empty(String)
        /// 추출 실패(권한/502 등) — 안내 후 재시도/닫기.
        case failed(String)

        var isActive: Bool { self != .idle }
    }

    // MARK: - 게시 상태

    /// 화면 표시 순서(오름차순: 오래된 → 최신). GroupChatView 가 그대로 소비.
    @Published private(set) var messages: [GroupChatFrame] = []
    /// 입력 초안(입력바 바인딩). 전송 성공 시 초기화.
    @Published var draft: String = ""
    /// 「장소 등록하기」 팝업 상태(FR-GC2-4).
    @Published var registerState: RegisterState = .idle
    /// 일반 안내/저장 실패 토스트(에러 아님). nil 이면 미표시.
    @Published var saveInfoMessage: String?

    // MARK: - 의존성

    /// 이 방의 그룹 식별자(메시지 조회/전송 경로·릴스 저장 핀 귀속·딥링크 그룹 전환).
    let groupId: Int
    /// 방 진입 시점의 미읽음 수(목록 응답 unreadCount) — "안 읽은 위치부터 진입" 앵커 계산용.
    let initialUnreadCount: Int
    /// 이 방의 roomId(willPresent 현재 방 매칭). 가상 방(미생성)이면 nil → 첫 프레임에서 보강.
    private(set) var roomId: Int?
    private let chatAPI: ChatAPIProtocol
    private let pinAPI: PinAPIProtocol
    private let currentUser: CurrentUser
    /// 딥링크 라우터 — 「구경하실래요?」 → .reelFocus(groupId, url).
    private let deepLinkRouter: DeepLinkRouter
    /// 포그라운드 수신 신호(현재 방 등록 + willPresent tick).
    private let chatPushSignal: ChatPushSignal
    /// 폴링 간격 대기(테스트 주입). 실제는 Task.sleep.
    private let sleeper: @Sendable (Double) async -> Void

    // MARK: - 내부 상태

    /// 표시 중인 messageId 집합(dedup).
    private var knownIds: Set<Int> = []
    /// 과거 로드 커서(nextCursor). nil 이면 더 과거 없음(또는 미로드).
    private var nextCursor: Int?
    /// 더 과거 메시지 존재 여부.
    private var hasMore = false
    /// 중복 로드 방지(appear/loadMore/reconcile 동시 진입).
    private var isLoading = false
    /// 전송 직후 결과 폴링 루프(중복 생성 방지·이탈 취소).
    private var sendPollingTask: Task<Void, Never>?
    /// 방 표시 중 상시 폴링 루프(appear 시작·disappear 취소).
    private var livePollingTask: Task<Void, Never>?
    /// 추출 진행 중복 방지(extract 중 재탭 차단).
    private var isExtracting = false

    /// 내 userId(발신자 구분). currentUser 캐시.
    var currentUserId: Int? { currentUser.id }

    /// 첫 로드 응답의 "전진 직전" 읽음 포인터(서버 진실). load()가 세팅.
    private(set) var serverLastReadId: Int?

    /// 첫 진입 스크롤 앵커: 첫 미읽음(타인) 메시지 id. 미읽음 없으면 nil → 맨 아래로.
    /// 서버 포인터(전진 직전 스냅샷) 우선 — 목록 unreadCount 는 목록 로드 시점 값이라 재진입 시
    /// 이미 읽은 위치로 반복 앵커되는 스테일 문제가 있다(폴백으로만 사용).
    var initialUnreadAnchorId: Int? {
        guard !messages.isEmpty else { return nil }
        if let lastRead = serverLastReadId {
            return messages.first(where: { $0.messageId > lastRead && !isMine($0) })?.messageId
        }
        guard initialUnreadCount > 0 else { return nil }
        let index = max(0, messages.count - initialUnreadCount)
        return messages[index].messageId
    }

    private func isMine(_ frame: GroupChatFrame) -> Bool {
        guard let me = currentUser.id, let sender = frame.senderUserId else { return false }
        return me == sender
    }

    // MARK: - 메시지 묶음(카톡식: 같은 발신자 + 같은 분)

    /// index 메시지가 직전 메시지와 같은 묶음인지 — 묶음이면 간격을 좁히고 닉네임/아바타 생략.
    func isChainedWithPrevious(at index: Int) -> Bool {
        guard index > 0, index < messages.count else { return false }
        return Self.isSameChain(messages[index - 1], messages[index])
    }

    /// index 메시지가 다음 메시지와 같은 묶음인지 — 묶음이면 시간 생략(마지막 것에만 표시).
    func isChainedWithNext(at index: Int) -> Bool {
        guard index >= 0, index + 1 < messages.count else { return false }
        return Self.isSameChain(messages[index], messages[index + 1])
    }

    /// 같은 묶음 판정: 동일 발신자 + 같은 '분'(SYSTEM 제외). 시각 파싱 실패(낙관 직후 등)면 묶지 않음.
    static func isSameChain(_ a: GroupChatFrame, _ b: GroupChatFrame) -> Bool {
        guard a.kind != .SYSTEM, b.kind != .SYSTEM else { return false }
        guard a.senderUserId != nil, a.senderUserId == b.senderUserId else { return false }
        guard let ma = minuteKey(a.createdAt), let mb = minuteKey(b.createdAt) else { return false }
        return ma == mb
    }

    private static func minuteKey(_ iso: String) -> Int? {
        guard !iso.isEmpty,
              let date = chainIsoFraction.date(from: iso) ?? chainIsoPlain.date(from: iso) else { return nil }
        return Int(date.timeIntervalSince1970 / 60)
    }

    private static let chainIsoFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let chainIsoPlain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    init(
        groupId: Int,
        roomId: Int?,
        initialUnreadCount: Int = 0,
        chatAPI: ChatAPIProtocol,
        pinAPI: PinAPIProtocol,
        currentUser: CurrentUser,
        deepLinkRouter: DeepLinkRouter,
        chatPushSignal: ChatPushSignal,
        sleeper: @escaping @Sendable (Double) async -> Void = { seconds in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        }
    ) {
        self.groupId = groupId
        self.initialUnreadCount = initialUnreadCount
        self.roomId = roomId
        self.chatAPI = chatAPI
        self.pinAPI = pinAPI
        self.currentUser = currentUser
        self.deepLinkRouter = deepLinkRouter
        self.chatPushSignal = chatPushSignal
        self.sleeper = sleeper
    }

    // MARK: - 라이프사이클

    /// 진입: userId 선행 확보(발신자 구분) → 현재 방 등록 → 최신 로드 → 상시 폴링 시작.
    func appear() async {
        if currentUser.id == nil {
            await currentUser.load()
        }
        chatPushSignal.register(roomId: roomId)
        await load()
        startLivePolling()
    }

    /// 이탈: 현재 방 해제 + 폴링 정리.
    func disappear() async {
        chatPushSignal.clear(roomId: roomId)
        cancelSendPolling()
        cancelLivePolling()
    }

    // MARK: - 로드/페이징

    /// 최신 1페이지 로드(cursor=nil). id DESC 응답을 오름차순으로 재구성.
    func load() async {
        guard !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        guard let response = try? await chatAPI.groupMessages(groupId: groupId, cursor: nil, limit: Self.pageLimit) else { return }
        // 첫 로드 응답의 "전진 직전" 포인터(서버 진실) — 미읽음 진입 앵커 기준. reconcile 응답으로는 덮지 않는다
        // (그 시점엔 이미 이번 진입으로 포인터가 전진해 있어 앵커 의미가 사라짐).
        serverLastReadId = response.lastReadMessageId
        applyLatestPage(response, replaceAll: true)
    }

    /// 상단 도달 시 과거 메시지 추가 로드. nextCursor 로 더 과거 페이지 prepend.
    func loadMore() async {
        guard !isLoading, hasMore, let cursor = nextCursor else { return }
        isLoading = true
        defer { isLoading = false }
        guard let response = try? await chatAPI.groupMessages(groupId: groupId, cursor: cursor, limit: Self.pageLimit) else { return }
        let ascending = response.messages.reversed()
        var older: [GroupChatFrame] = []
        for frame in ascending where !knownIds.contains(frame.messageId) {
            knownIds.insert(frame.messageId)
            older.append(frame)
        }
        messages.insert(contentsOf: older, at: 0)
        nextCursor = response.nextCursor
        hasMore = response.hasMore
    }

    /// 최신 N건 재조회 + 교체-병합(registered 갱신, FR-GC2-3). 폴링·scenePhase·willPresent 신호 공용 단일 경로.
    /// 반환값: 이번 병합에서 새로 append 된 프레임 목록(send-poll 조기 종료 판정용, FR-GC2-9). 대부분 호출부는 무시.
    @discardableResult
    func reconcileLatest() async -> [GroupChatFrame] {
        guard let response = try? await chatAPI.groupMessages(groupId: groupId, cursor: nil, limit: Self.pageLimit) else { return [] }
        return applyLatestPage(response, replaceAll: false)
    }

    /// 최신 페이지를 표시 모델에 반영.
    /// - replaceAll(load): 전체 재구성.
    /// - 병합(reconcile): 페이지에 포함된 기존 messageId 프레임을 **교체**(registered false→true 등 갱신),
    ///   신규 messageId 는 오름차순 끝에 append(채팅 append-only — 새 메시지는 항상 최신).
    /// 반환값: 이번에 새로 append 된 프레임 목록(merge 경로). replaceAll 경로는 [] 반환.
    @discardableResult
    private func applyLatestPage(_ response: GroupMessagesResponse, replaceAll: Bool) -> [GroupChatFrame] {
        let ascending = Array(response.messages.reversed())
        var appended: [GroupChatFrame] = []
        if replaceAll {
            messages = ascending
            knownIds = Set(ascending.map(\.messageId))
            // 커서 소유권은 load/loadMore 에만 있다(FR-GC2-3, BR-3). merge(reconcile) 경로는 커서를 건드리지 않는다 —
            // loadMore 로 확정한 과거 페이지 커서를 reconcile 이 최신 페이지 값으로 되돌리는 회귀(버그 ②)를 차단.
            nextCursor = response.nextCursor
            hasMore = response.hasMore
        } else {
            var byId: [Int: GroupChatFrame] = [:]
            for f in ascending { byId[f.messageId] = f }
            // 기존 표시 프레임 갱신(registered 등 서버 진실 반영).
            for i in messages.indices {
                if let updated = byId[messages[i].messageId] {
                    messages[i] = updated
                }
            }
            // 신규만 append(오름차순 유지).
            for f in ascending where !knownIds.contains(f.messageId) {
                knownIds.insert(f.messageId)
                messages.append(f)
                appended.append(f)
            }
        }
        // 가상 방(roomId nil) 진입 후 첫 프레임에서 roomId 보강 → willPresent 현재 방 매칭 활성화.
        if roomId == nil, let firstRoomId = ascending.first?.roomId {
            roomId = firstRoomId
            chatPushSignal.register(roomId: roomId)
        }
        return appended
    }

    // MARK: - 전송(FR-GC2-2/8)

    /// 입력 텍스트 전송. 전체(trim)가 인스타 URL 단독이면 REEL_LINK, 그 외 TEXT(FR-GC2-8).
    /// 낙관 append(내 프레임) 후 결과 폴링. 실패 시 입력 복원.
    func send() async {
        let raw = draft
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let isReel = InstagramURL.isReelURL(trimmed)
        // TEXT 만 2000자 가드(REEL_LINK 는 URL 이라 InstagramURL 이 길이 검증).
        if !isReel { guard trimmed.count <= Self.messageMaxLength else { return } }
        draft = ""
        let kind: MessageKind = isReel ? .REEL_LINK : .TEXT
        do {
            let response = try await chatAPI.sendGroupMessage(
                groupId: groupId,
                kind: kind,
                text: isReel ? nil : trimmed,
                url: isReel ? trimmed : nil
            )
            appendOptimistic(messageId: response.messageId, kind: kind, text: isReel ? nil : trimmed, url: isReel ? trimmed : nil)
            startSendPolling()
        } catch {
            draft = raw   // 전송 실패 — 입력 복원(재시도).
        }
    }

    /// 내 메시지 즉시 표시(낙관). 서버 응답 messageId 사용 — reconcile 이 같은 id 로 교정/registered 갱신.
    private func appendOptimistic(messageId: Int, kind: MessageKind, text: String?, url: String?) {
        guard !knownIds.contains(messageId) else { return }
        let frame = GroupChatFrame(
            messageId: messageId,
            roomId: roomId ?? 0,
            senderUserId: currentUser.id,
            senderNickname: currentUser.nickname,
            kind: kind,
            createdAt: Self.isoNow(),                       // 즉시 시각 표시(클라 시계) — reconcile 이 서버 값으로 교체.
            reelUrl: url,
            registered: kind == .REEL_LINK ? false : nil,   // 방금 보낸 릴스는 미등록 — 발신자에게 「등록하기」 노출.
            text: text
        )
        knownIds.insert(messageId)
        messages.append(frame)
    }

    /// 낙관 프레임용 현재 시각(ISO-8601). 전송 직후 시간이 1~2초 늦게 뜨던 공백 제거(서버 교체 시 ±오차 자가치유).
    private static let isoNowFormatter = ISO8601DateFormatter()
    private static func isoNow() -> String { isoNowFormatter.string(from: Date()) }

    // MARK: - 수신 폴링(FR-GC2-6)

    /// 전송 직후 빠른 결과 폴링(2초×10). 이미 실행 중이면 무시(단일 루프).
    private func startSendPolling() {
        guard sendPollingTask == nil else { return }
        sendPollingTask = Task { [weak self] in
            await self?.runSendPolling()
        }
    }

    /// 전송 직후 빠른 결과 폴링(2초×10). 목적: 상대의 빠른 답장을 8초 라이브 폴링보다 빨리 수신(BR-4).
    /// 조기 종료(FR-GC2-9): 어떤 회차의 reconcile 에서 "타인"의 새 메시지가 1건 이상 append 되면 즉시 종료하고
    /// 이후 수신은 8초 라이브 폴링에 위임한다. 10회 소진/취소(이탈)는 기존대로 종료.
    private func runSendPolling() async {
        defer { sendPollingTask = nil }
        var attempts = 0
        while attempts < Self.maxSendPollAttempts {
            await sleeper(Self.sendPollIntervalSeconds)
            if Task.isCancelled { return }
            let appended = await reconcileLatest()
            // reconcile 진행 중 이탈(disappear)했으면 조기 종료 판정 없이 즉시 종료(AC-7 엄밀 보장).
            if Task.isCancelled { return }
            // "타인"의 새 메시지 기준(BR-5): senderUserId 가 non-nil 이고 내 id 와 다른 프레임의 append.
            // currentUserId 미확보(nil)면 판정 자체를 건너뛴다(보수적 비-종료) — reconcile 이 낙관 프레임을
            // 서버 진실(내 senderUserId non-nil)로 교체한 것을 타인 메시지로 오판해 조기 종료하는 경로 차단.
            if let myId = currentUserId,
               appended.contains(where: { $0.senderUserId != nil && $0.senderUserId != myId }) {
                return
            }
            attempts += 1
        }
    }

    private func cancelSendPolling() {
        sendPollingTask?.cancel()
        sendPollingTask = nil
    }

    /// 방 표시 중 상시 폴링(8초). sleeper 를 캡처해 대기 동안 self 를 잡지 않는다(누수 방지).
    private func startLivePolling() {
        guard livePollingTask == nil else { return }
        let sleeper = self.sleeper
        livePollingTask = Task { [weak self] in
            while !Task.isCancelled {
                await sleeper(Self.livePollIntervalSeconds)
                if Task.isCancelled { return }
                guard let self else { return }
                await self.reconcileLatest()
            }
        }
    }

    private func cancelLivePolling() {
        livePollingTask?.cancel()
        livePollingTask = nil
    }

    // MARK: - 장소 등록(FR-GC2-4)

    /// 「장소 등록하기」(내 미등록 REEL_LINK) → 추출 시작. 중복 진입 차단.
    func register(messageId: Int, url: String) {
        guard !isExtracting else { return }
        isExtracting = true
        registerState = .extracting(messageId: messageId, url: url)
        Task { [weak self] in
            await self?.runExtract(messageId: messageId, url: url)
            self?.isExtracting = false
        }
    }

    private func runExtract(messageId: Int, url: String) async {
        // 팝업이 취소(idle)됐으면 결과를 덮지 않는다(전체 취소 — 핀 0·상태 불변).
        func stillExtracting() -> Bool {
            if case .extracting(let m, _) = registerState, m == messageId { return true }
            return false
        }
        do {
            let payload = try await chatAPI.extractGroupReelPlaces(groupId: groupId, messageId: messageId)
            guard stillExtracting() else { return }
            let savable = payload.cards.filter { $0.latitude != nil && $0.longitude != nil }
            if payload.cards.isEmpty {
                registerState = .empty("이 릴스에서 장소를 찾지 못했어요. 잠시 후 다시 시도해 주세요.")
            } else if savable.isEmpty {
                registerState = .empty("저장할 수 있는 장소가 없어요. (좌표 정보가 없어요)")
            } else {
                registerState = .wizard(messageId: messageId, url: url, cards: payload.cards)
            }
        } catch let error as APIError where error.code == "CHAT_EXTRACT_FORBIDDEN" {
            guard stillExtracting() else { return }
            registerState = .failed("내가 공유한 릴스만 장소로 등록할 수 있어요.")
        } catch {
            guard stillExtracting() else { return }
            registerState = .failed("장소를 불러오지 못했어요. 다시 시도해 주세요.")
        }
    }

    /// 위저드 제출 → 핀 저장(409 흡수) → 채팅 하단 안내 배너 + 즉시 reconcile(registered ③상태 전환, AC-4).
    /// ReelSaveWizard 는 제출과 동시에 onClose 를 호출(봇 공용 무변경)하므로 결과는 시트 밖(saveInfoMessage)으로 안내한다.
    func saveFromWizard(url: String, cards: [PlaceCard], wishIDs: Set<String>, memo: String?) async {
        let result = await savePlaceCards(cards: cards, wishIDs: wishIDs, memo: memo, sourceInstagramUrl: url)
        registerState = .idle
        guard let result else { return }   // 저장 실패는 saveInfoMessage 로 이미 안내됨.
        let savedCount = result.wishNames.count + result.reelNames.count
        saveInfoMessage = savedCount > 0
            ? "✨ \(savedCount)곳을 저장했어요! 지도에서 확인해보세요."
            : "이미 저장된 장소예요."
        await reconcileLatest()   // 등록됨 파생(registered) 갱신 → 버블 ③상태.
    }

    /// 팝업 닫기(취소/완료/안내 공통). idle 복귀(취소 = 핀 0·상태 불변).
    func dismissRegister() {
        registerState = .idle
    }

    /// 위저드 제출 → PLACE_CARDS 핀 저장(groupId=이 방 그룹). 좌표 있는 카드 전부(체크=WISH/미체크=REEL).
    /// 메모는 저장 핀 공통 적용. instagramUrl 에 sourceInstagramUrl 기록. 409(PLC_DUPLICATE_PIN)는 흡수.
    private func savePlaceCards(
        cards: [PlaceCard],
        wishIDs: Set<String>,
        memo: String?,
        sourceInstagramUrl: String?
    ) async -> ReelSaveResult? {
        guard !cards.isEmpty else { return nil }
        let normalizedMemo = (memo?.isEmpty == false) ? memo : nil
        var wishNames: [String] = []
        var reelNames: [String] = []
        var duplicateCount = 0
        for card in cards {
            guard let latitude = card.latitude, let longitude = card.longitude else { continue }
            let tag: PinTag = wishIDs.contains(card.id) ? .WISH : .REEL
            let request = CreatePinRequest(
                placeName: card.name,
                address: card.address,
                latitude: latitude,
                longitude: longitude,
                instagramUrl: sourceInstagramUrl,
                memo: normalizedMemo,
                tag: tag
            )
            do {
                _ = try await pinAPI.create(groupId: groupId, request: request)
                if tag == .WISH { wishNames.append(card.name) } else { reelNames.append(card.name) }
            } catch let error as APIError where error.code == "PLC_DUPLICATE_PIN" {
                duplicateCount += 1   // 409 — 이미 저장된 장소(흡수).
            } catch {
                saveInfoMessage = "장소를 저장하지 못했어요. 다시 시도해 주세요."
                return nil
            }
        }
        let savedCount = wishNames.count + reelNames.count
        return ReelSaveResult(
            wishNames: wishNames,
            reelNames: reelNames,
            duplicateCount: duplicateCount,
            memo: normalizedMemo,
            sourceInstagramUrl: savedCount > 0 ? sourceInstagramUrl : nil
        )
    }

    // MARK: - 딥링크(FR-GC2-5)

    /// 「구경하실래요?」(등록됨 REEL_LINK) → 지도 탭 + 해당 그룹 전환 + 릴스 핀 포커스.
    func openReel(url: String) {
        deepLinkRouter.pending = .reelFocus(groupId: groupId, instagramUrl: url)
    }
}
