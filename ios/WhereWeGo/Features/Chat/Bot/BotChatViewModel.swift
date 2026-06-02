import Foundation

// 봇 채팅 ViewModel(설계 §5, FR-2/3/5/6/7, BR-3/5/6, AC-2/3/4/9/20).
//  - 로드(FR-2): botMessages(cursor:nil,limit:20) → id DESC 응답을 오름차순(오래된→최신)으로 유지(View 직접 소비).
//  - 전송(FR-3/BR-3/AC-4): 2000자 가드 후 sendBotMessage → 응답 messageId 로 PROCESSING 버블 즉시 추가(pendingProcessingIds FIFO).
//  - PROCESSING 교체(AC-2): STOMP onFrame 으로 BOT 결과(PLACE_CARDS/SYSTEM/MEMO_PROMPT) 도착 시
//    가장 오래된 pendingProcessing 1건 제거 + 결과 append. dedup 은 messageId Set.
//  - 카드 저장(FR-5/AC-3): 좌표 있는 카드만 pinAPI.create(tag:.REEL, groupId:활성그룹).
//    409(PLC_DUPLICATE_PIN) 흡수 — saveInfoMessage 안내(에러 미전파). 좌표 없는 카드 스킵 + 안내.
//  - 재연결 보완(AC-9): onReconnected → reconcileLatest() cursor=null 최신 재조회 + id dedup 병합.
//
// FR-6/BR-6/FR-27/AC-20: 사용자는 릴스 URL 텍스트만 전송한다. 미디어는 단말에 저장하지 않으며
// 서버가 URL 로부터 장소를 추출한다(클라이언트는 텍스트 송수신만 담당).
@MainActor
final class BotChatViewModel: ObservableObject {

    /// 봇 방 메시지 전송 최대 길이(BR-3/AC-4). 백엔드 검증과 동치.
    static let messageMaxLength = 2000
    /// 최신 페이지 1건 로드 크기(BR-4, 설계 §5).
    static let pageLimit = 20
    /// 봇 구독 식별자(설계 §4 — `sub-bot`).
    static let subscriptionId = "sub-bot"

    // MARK: - 게시 상태

    /// 화면 표시 순서(오름차순: 오래된 → 최신). ChatScrollContainer 가 그대로 소비.
    @Published private(set) var messages: [ChatFrame] = []
    /// 실시간 연결 상태(상단 배너). ChatRealtimeService.state 미러.
    @Published private(set) var realtimeState: ConnectionState = .connecting
    /// 입력 초안(입력바 바인딩). 전송 성공 시 초기화.
    @Published var draft: String = ""
    /// 카드 저장 안내/409 흡수 토스트(에러 아님). nil 이면 미표시.
    @Published var saveInfoMessage: String?

    // MARK: - 의존성

    private let chatAPI: ChatAPIProtocol
    private let pinAPI: PinAPIProtocol
    private let groupAPI: GroupAPIProtocol
    private let realtime: ChatRealtimeServicing
    private let currentUser: CurrentUser

    // MARK: - 내부 상태

    /// 표시 중인 messageId 집합(dedup, AC-2). 로드/전송/STOMP 공통 차단.
    private var knownIds: Set<Int> = []
    /// PROCESSING 버블 messageId FIFO(전송 순서). 결과 도착 시 가장 오래된 1건을 교체 대상으로.
    private var pendingProcessingIds: [Int] = []
    /// 과거 로드 커서(nextCursor). nil 이면 더 과거 없음(또는 미로드).
    private var nextCursor: Int?
    /// 더 과거 메시지 존재 여부(hasMore). loadMore no-op 분기.
    private var hasMore = false
    /// 중복 로드 방지(appear/loadMore 동시 진입).
    private var isLoading = false

    init(
        chatAPI: ChatAPIProtocol,
        pinAPI: PinAPIProtocol,
        groupAPI: GroupAPIProtocol,
        realtime: ChatRealtimeServicing,
        currentUser: CurrentUser
    ) {
        self.chatAPI = chatAPI
        self.pinAPI = pinAPI
        self.groupAPI = groupAPI
        self.realtime = realtime
        self.currentUser = currentUser
    }

    // MARK: - 라이프사이클(진입/이탈)

    /// 진입: 봇 토픽 구독 + 재연결 보완 콜백 연결 → 최신 메시지 로드.
    /// subscribe 를 load 보다 먼저 호출한다(Q2 레이스): load(수백 ms) 사이 도착한 STOMP 프레임 유실 방지.
    /// 구독 등록 직후 load 하므로, 도착 프레임과 load 결과가 겹쳐도 messageId dedup(knownIds)로 중복 제거된다.
    func appear() async {
        observeRealtimeState()
        // 재연결 성공 통지(AC-9) — id 키 옵저버로 등록(봇/커플 동시 구독 시 덮어쓰기 방지, Critical-5).
        realtime.addReconnectedObserver(id: Self.subscriptionId) { [weak self] in
            // 옵저버는 @MainActor 컨텍스트에서 호출되지만 Sendable 계약 유지 위해 Task 로 진입.
            Task { @MainActor [weak self] in
                await self?.reconcileLatest()
            }
        }
        await realtime.subscribe(topic: botTopic, id: Self.subscriptionId) { [weak self] frame in
            // STOMP 콜백은 @Sendable — MainActor 로 hop 하여 상태 접근.
            Task { @MainActor [weak self] in
                self?.handleFrame(frame)
            }
        }
        await load()
    }

    /// 이탈: 구독 해제(연결은 유지 — 빠른 방 전환 대비). 상태/재연결 옵저버 정리(id 키).
    func disappear() async {
        realtime.removeStateObserver(id: Self.subscriptionId)
        realtime.removeReconnectedObserver(id: Self.subscriptionId)
        await realtime.unsubscribe(id: Self.subscriptionId)
    }

    // MARK: - 로드(FR-2)

    /// 최신 메시지 1페이지 로드(cursor=nil). id DESC 응답을 오름차순으로 뒤집어 표시.
    func load() async {
        guard !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        guard let response = try? await chatAPI.botMessages(cursor: nil, limit: Self.pageLimit) else { return }
        // 최신 페이지로 초기화 — 기존 표시분도 재구성(중복 진입 방어).
        let ascending = response.messages.reversed()
        var rebuilt: [ChatFrame] = []
        var ids: Set<Int> = []
        for frame in ascending where !ids.contains(frame.messageId) {
            ids.insert(frame.messageId)
            rebuilt.append(frame)
        }
        messages = rebuilt
        knownIds = ids
        pendingProcessingIds = pendingProcessingIds.filter { ids.contains($0) }
        nextCursor = response.nextCursor
        hasMore = response.hasMore
    }

    /// 상단 도달 시 과거 메시지 추가 로드(FR-2). nextCursor 로 더 과거 페이지를 prepend.
    func loadMore() async {
        guard !isLoading, hasMore, let cursor = nextCursor else { return }
        isLoading = true
        defer { isLoading = false }
        guard let response = try? await chatAPI.botMessages(cursor: cursor, limit: Self.pageLimit) else { return }
        // 응답은 id DESC(더 과거 페이지) → 오름차순으로 뒤집어 기존 앞쪽에 삽입. dedup.
        let ascending = response.messages.reversed()
        var older: [ChatFrame] = []
        for frame in ascending where !knownIds.contains(frame.messageId) {
            knownIds.insert(frame.messageId)
            older.append(frame)
        }
        messages.insert(contentsOf: older, at: 0)
        nextCursor = response.nextCursor
        hasMore = response.hasMore
    }

    // MARK: - 전송(FR-3/BR-3/AC-4)

    /// 입력 텍스트 전송. 2000자 초과 시 차단(앞 2000자 절단 후 진행하지 않고 가드만 — 입력바가 카운터로 안내).
    /// 성공 시 응답 messageId 로 PROCESSING 버블 즉시 추가(pendingProcessingIds FIFO) + 입력 초기화.
    func send() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        // BR-3/AC-4: 2000자 가드. 초과면 전송하지 않는다(입력바 카운터가 비활성 안내).
        guard text.count <= Self.messageMaxLength else { return }
        draft = ""
        guard let response = try? await chatAPI.sendBotMessage(text: text) else {
            // 전송 실패 시 입력 복원(사용자가 재시도).
            draft = text
            return
        }
        // 봇 방 응답은 PROCESSING 플레이스홀더 messageId. 즉시 버블 추가(kind 는 PROCESSING 고정).
        appendProcessing(messageId: response.messageId)
    }

    // MARK: - STOMP 수신(AC-2)

    /// MESSAGE 프레임 1건 처리. dedup 후, BOT 결과면 가장 오래된 PROCESSING 제거 + 결과 append.
    private func handleFrame(_ frame: ChatFrame) {
        guard !knownIds.contains(frame.messageId) else { return }

        switch frame.kind {
        case .PLACE_CARDS, .SYSTEM, .MEMO_PROMPT:
            // BOT 결과(같은 turn) — 가장 오래된 PROCESSING 1건을 교체한다(AC-2).
            removeOldestProcessing()
            appendFrame(frame)
        case .TEXT:
            // USER 에코/기타 텍스트 — 그대로 append(dedup 으로 자기 전송 중복은 없음).
            appendFrame(frame)
        case .PROCESSING:
            // 서버발 PROCESSING(전송 응답이 아닌 푸시) — 추적 + 표시.
            appendProcessing(messageId: frame.messageId)
        }
    }

    // MARK: - 카드 저장(FR-5/AC-3)

    /// 선택된 PLACE_CARDS 를 핀으로 저장(tag=.REEL, groupId=활성그룹).
    /// - 좌표(lat/lng) 없는 카드는 스킵 + 안내(설계 §5 — 핀 저장 불가).
    /// - 409(PLC_DUPLICATE_PIN)는 에러로 전파하지 않고 saveInfoMessage 로 흡수("이미 저장된 장소예요", AC-3).
    func savePlaceCards(_ selected: [PlaceCard], from messageId: Int) async {
        guard !selected.isEmpty else { return }
        // try? await myActiveGroup()?.groupId 는 Int?? 이중 옵셔널이 되어 타입 불일치 → 단계 분리.
        guard let group = try? await groupAPI.myActiveGroup(), let groupId = group?.groupId else {
            saveInfoMessage = "활성 그룹을 찾지 못했어요. 잠시 후 다시 시도해 주세요."
            return
        }

        var savedCount = 0
        var duplicateCount = 0
        var skippedNoCoordinate = 0

        for card in selected {
            // 좌표 없는 카드는 핀 저장 불가 — 스킵(설계 §5).
            guard let latitude = card.latitude, let longitude = card.longitude else {
                skippedNoCoordinate += 1
                continue
            }
            let request = CreatePinRequest(
                placeName: card.name,
                address: card.address,
                latitude: latitude,
                longitude: longitude,
                instagramUrl: nil,
                memo: nil,
                tag: .REEL
            )
            do {
                _ = try await pinAPI.create(groupId: groupId, request: request)
                savedCount += 1
            } catch let error as APIError where error.code == "PLC_DUPLICATE_PIN" {
                // 409 — 에러 아님(이미 저장된 장소). 흡수하고 계속(AC-3).
                duplicateCount += 1
            } catch {
                // 그 외 실패는 안내 후 중단(부분 저장 상태 유지).
                saveInfoMessage = "장소를 저장하지 못했어요. 다시 시도해 주세요."
                return
            }
        }

        saveInfoMessage = saveResultMessage(
            savedCount: savedCount,
            duplicateCount: duplicateCount,
            skippedNoCoordinate: skippedNoCoordinate
        )
    }

    // MARK: - 재연결 보완(AC-9)

    /// 재연결 성공 시 최신 N건 재조회 + id dedup 병합(서버 진실 소스). 끊김 동안 누락된 결과를 보완한다.
    func reconcileLatest() async {
        guard let response = try? await chatAPI.botMessages(cursor: nil, limit: Self.pageLimit) else { return }
        let ascending = response.messages.reversed()
        var appended = false
        for frame in ascending where !knownIds.contains(frame.messageId) {
            // 같은 turn 의 결과(BOT 결과 kind)가 들어오면 PROCESSING 을 교체(AC-2 정합).
            switch frame.kind {
            case .PLACE_CARDS, .SYSTEM, .MEMO_PROMPT:
                removeOldestProcessing()
            case .TEXT, .PROCESSING:
                break
            }
            appendFrame(frame)
            appended = true
        }
        // 최신 페이지 갱신 시 커서/hasMore 도 최신화(이후 loadMore 일관).
        if appended {
            nextCursor = response.nextCursor
            hasMore = response.hasMore
        }
    }

    /// 수동 재시도(.disconnected 배너의 "다시 연결").
    func retryRealtime() async {
        await realtime.retryManually()
    }

    // MARK: - Private 헬퍼

    /// 봇 토픽(CurrentUser.id 기반). id 미확보면 0 placeholder — 서비스가 sendSubscribe 단계에서 최신 id 로 재구성한다.
    private var botTopic: ChatTopic {
        .bot(userId: currentUser.id ?? 0)
    }

    /// 서비스의 연결 상태를 realtimeState 로 미러링한다(상단 배너 구독원, C9 통합 보강).
    /// ChatRealtimeServicing.currentState 로 즉시 1회 동기화 후, onStateChange 콜백으로 후속 전환을 반영한다.
    /// (구체 타입 캐스팅·$state.values 관찰 제거 — 프로토콜 경유.)
    private func observeRealtimeState() {
        realtimeState = realtime.currentState
        realtime.addStateObserver(id: Self.subscriptionId) { [weak self] state in
            // 옵저버는 @MainActor 에서 호출되지만 Sendable 계약 유지 위해 Task 로 진입.
            Task { @MainActor [weak self] in
                self?.realtimeState = state
            }
        }
    }

    /// PROCESSING 버블 추가(dedup + FIFO 추적). 표시는 ChatFrame(PROCESSING).
    private func appendProcessing(messageId: Int) {
        guard !knownIds.contains(messageId) else { return }
        guard let frame = Self.makeProcessingFrame(messageId: messageId) else { return }
        knownIds.insert(messageId)
        pendingProcessingIds.append(messageId)
        messages.append(frame)
    }

    /// 결과 프레임 append(dedup + 표시). knownIds 갱신.
    private func appendFrame(_ frame: ChatFrame) {
        guard !knownIds.contains(frame.messageId) else { return }
        knownIds.insert(frame.messageId)
        messages.append(frame)
    }

    /// 가장 오래된 PROCESSING 버블 1건 제거(결과 도착 교체, AC-2). 없으면 no-op.
    private func removeOldestProcessing() {
        guard !pendingProcessingIds.isEmpty else { return }
        let oldest = pendingProcessingIds.removeFirst()
        messages.removeAll { $0.messageId == oldest }
        knownIds.remove(oldest)
    }

    /// 카드 저장 결과 안내 문구(저장/중복/좌표없음 조합). 모두 0 이면 nil(무표시).
    private func saveResultMessage(savedCount: Int, duplicateCount: Int, skippedNoCoordinate: Int) -> String? {
        var parts: [String] = []
        if savedCount > 0 { parts.append("\(savedCount)곳을 저장했어요") }
        if duplicateCount > 0 { parts.append("이미 저장된 장소예요") }
        if skippedNoCoordinate > 0 { parts.append("좌표가 없는 장소는 저장하지 못했어요") }
        guard !parts.isEmpty else { return nil }
        return parts.joined(separator: ". ")
    }

    /// PROCESSING 플레이스홀더 ChatFrame 생성(전송 응답/서버 푸시 공통).
    /// ChatFrame 은 커스텀 디코더만 있어 직접 메모리 init 이 없으므로 고정 형식 JSON 경유로 구성한다(PROCESSING payload={}).
    /// 형식이 고정이라 디코딩은 항상 성공하지만, 방어적으로 옵셔널 반환(실패 시 호출부 no-op).
    private static func makeProcessingFrame(messageId: Int) -> ChatFrame? {
        // PROCESSING 플레이스홀더 — roomId 미사용(서버 결과 프레임이 교체). 의미 없는 값이므로 0 고정.
        let json = """
        {"messageId":\(messageId),"roomId":0,"senderType":"BOT","kind":"PROCESSING","payload":{},"createdAt":""}
        """
        guard let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(ChatFrame.self, from: data)
    }
}
