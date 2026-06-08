import Foundation

// 봇 채팅 ViewModel(설계 §5 → 이벤트 전환: STOMP 제거).
// 수신 경로(상시 WebSocket 구독 대신):
//  - 전송 직후 제한 폴링(FR-1/2): PROCESSING 대기 중에만 2초 간격·최대 10회 reconcileLatest.
//  - APNs 푸시(BR-1): 백그라운드 통지(발송·라우팅은 푸시 도메인이 담당, 본 VM 무관).
//  - scenePhase .active 재조회(FR-4): BotChatView 가 reconcileLatest 트리거.
//
//  - 로드(FR-2): botMessages(cursor:nil,limit:20) → id DESC 응답을 오름차순(오래된→최신)으로 유지.
//  - 전송(FR-3/BR-3/AC-4): 2000자 가드 후 sendBotMessage → 응답 messageId 로 PROCESSING 버블 추가(FIFO) + 폴링 시작.
//  - 결과 반영(AC-2): reconcileLatest 가 BOT 결과(PLACE_CARDS/SYSTEM/MEMO_PROMPT) 병합 시 가장 오래된 PROCESSING 교체. dedup=knownIds.
//  - 카드 저장(FR-I5/BR-2): 좌표 있는 카드 전부 pinAPI.create(체크=WISH/미체크=REEL) + 공통 메모 + 출처 instagramUrl. 409 흡수.
//    저장 완료 시 saveResult(결과 카드) 발행 + sourceInstagramUrl 있으면 "보러가기"(.reelFocus 딥링크).
//
// FR-6/BR-6/AC-20: 사용자는 릴스 URL 텍스트만 전송한다(클라이언트는 텍스트 송수신만).
@MainActor
final class BotChatViewModel: ObservableObject {

    /// 봇 방 메시지 전송 최대 길이(BR-3/AC-4). 백엔드 검증과 동치.
    static let messageMaxLength = 2000
    /// 최신 페이지 1건 로드 크기(BR-4, 설계 §5).
    static let pageLimit = 20
    /// 전송 후 결과 폴링 간격(초, FR-2).
    static let pollIntervalSeconds: Double = 2.0
    /// 폴링 최대 횟수(FR-2). 봇 처리 SLA(≈4.5초)의 약 4배 여유. 상한 초과는 scenePhase 복귀가 보완(BR-4).
    static let maxPollAttempts = 10

    // MARK: - 게시 상태

    /// 화면 표시 순서(오름차순: 오래된 → 최신). ChatScrollContainer 가 그대로 소비.
    @Published private(set) var messages: [ChatFrame] = []
    /// 입력 초안(입력바 바인딩). 전송 성공 시 초기화.
    @Published var draft: String = ""
    /// 카드 저장 안내/409 흡수 토스트(에러 아님). nil 이면 미표시. (그룹 미확보/일반 에러 등 결과 카드로 표현하기 어려운 안내)
    @Published var saveInfoMessage: String?
    /// 위저드 저장 완료 결과(FR-I8). 채팅 하단 결과 카드로 렌더. nil 이면 미표시.
    @Published var saveResult: ReelSaveResult?

    // MARK: - 의존성

    private let chatAPI: ChatAPIProtocol
    private let pinAPI: PinAPIProtocol
    private let groupAPI: GroupAPIProtocol
    private let currentUser: CurrentUser
    /// 딥링크 라우터(I5/I8). "보러가기" → .reelFocus(instagramUrl) 발행으로 지도 탭 전환·릴스 포커스를 트리거.
    private let deepLinkRouter: DeepLinkRouter
    /// 폴링 간격 대기(테스트 주입). 실제는 Task.sleep, 테스트는 즉시/제어로 결정성 확보.
    private let sleeper: @Sendable (Double) async -> Void

    // MARK: - 내부 상태

    /// 표시 중인 messageId 집합(dedup, AC-2). 로드/전송/폴링 공통 차단.
    private var knownIds: Set<Int> = []
    /// PROCESSING 버블 messageId FIFO(전송 순서). 결과 도착 시 가장 오래된 1건을 교체 대상으로.
    private var pendingProcessingIds: [Int] = []
    /// 과거 로드 커서(nextCursor). nil 이면 더 과거 없음(또는 미로드).
    private var nextCursor: Int?
    /// 더 과거 메시지 존재 여부(hasMore). loadMore no-op 분기.
    private var hasMore = false
    /// 중복 로드 방지(appear/loadMore 동시 진입).
    private var isLoading = false
    /// 전송 후 결과 폴링 루프(중복 생성 방지·이탈 취소, FR-9). nil 이면 미실행.
    private var pollingTask: Task<Void, Never>?

    init(
        chatAPI: ChatAPIProtocol,
        pinAPI: PinAPIProtocol,
        groupAPI: GroupAPIProtocol,
        currentUser: CurrentUser,
        deepLinkRouter: DeepLinkRouter,
        sleeper: @escaping @Sendable (Double) async -> Void = { seconds in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        }
    ) {
        self.chatAPI = chatAPI
        self.pinAPI = pinAPI
        self.groupAPI = groupAPI
        self.currentUser = currentUser
        self.deepLinkRouter = deepLinkRouter
        self.sleeper = sleeper
    }

    // MARK: - 라이프사이클(진입/이탈)

    /// 진입: 최신 메시지 로드(STOMP 구독 제거 — 이벤트 전환). 봇 결과는 전송 직후 폴링/포그라운드 재조회/푸시로 수신한다.
    /// userId 선행 확보(currentUser.load)는 기존 흐름 보존 차원에서 유지하되, 실패해도 로드는 진행한다.
    func appear() async {
        if currentUser.id == nil {
            await currentUser.load()
        }
        await load()
    }

    /// 이탈: 진행 중 결과 폴링 루프 중단(연결 개념 없음 — 폴링만 정리).
    func disappear() async {
        cancelPolling()
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

    /// 입력 텍스트 전송. 2000자 초과 시 차단(가드만 — 입력바가 카운터로 안내).
    /// 성공 시 응답 messageId 로 PROCESSING 버블 즉시 추가(FIFO) + 입력 초기화 + 결과 폴링 시작.
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
        // 봇 방 응답은 PROCESSING 플레이스홀더 messageId. 즉시 버블 추가 후 결과 폴링 시작.
        appendProcessing(messageId: response.messageId)
        startPollingIfNeeded()
    }

    // MARK: - 결과 폴링(FR-1/2/9, BR-3)

    /// 전송 직후 결과 대기 폴링 시작. 이미 실행 중이거나(FR-9 중복 방지) 대기 PROCESSING 이 없으면 무시한다.
    private func startPollingIfNeeded() {
        guard pollingTask == nil, !pendingProcessingIds.isEmpty else { return }
        pollingTask = Task { [weak self] in
            await self?.runPollingLoop()
        }
    }

    /// 2초 간격·최대 10회로 reconcileLatest. PROCESSING 소진/취소(이탈)/상한 도달 시 종료한다.
    /// 사용자 전송 직후로만 한정한 제한 폴링(BR-3 — 앱 상시 폴링 아님).
    private func runPollingLoop() async {
        defer { pollingTask = nil }
        var attempts = 0
        while attempts < Self.maxPollAttempts {
            guard !pendingProcessingIds.isEmpty else { return }
            await sleeper(Self.pollIntervalSeconds)
            if Task.isCancelled { return }
            guard !pendingProcessingIds.isEmpty else { return }
            await reconcileLatest()
            attempts += 1
        }
    }

    /// 진행 중 폴링 루프 취소(이탈 시). 다음 진입/푸시/scenePhase 복귀가 결과를 보완한다.
    private func cancelPolling() {
        pollingTask?.cancel()
        pollingTask = nil
    }

    // MARK: - 카드 저장(FR-I5, BR-1/2/3, AC-5/6/8/9/14)

    /// 위저드 제출 → PLACE_CARDS 를 핀으로 저장(groupId=활성그룹). 좌표 있는 카드 전부 저장(BR-2).
    /// - 체크된(wishIDs 포함) 카드는 tag=.WISH, 미체크 카드(좌표 있는 것)는 tag=.REEL(FR-I5/BR-2).
    /// - 메모는 있으면 저장되는 모든 핀에 동일 적용(BR-3). 각 핀의 instagramUrl 에 sourceInstagramUrl 기록(BR-7: nil 이면 미기록).
    /// - 좌표(lat/lng) 없는 카드는 스킵(BR-1).
    /// - 409(PLC_DUPLICATE_PIN)는 에러로 전파하지 않고 흡수(중복 카운트, AC-14). 그 외 에러는 안내 후 중단.
    /// - 저장 완료 시 saveResult(결과 카드) 발행(FR-I8). 위시/발견 신규 저장명, 중복 수, 메모, 출처 URL.
    func savePlaceCards(
        cards: [PlaceCard],
        wishIDs: Set<String>,
        memo: String?,
        sourceInstagramUrl: String?
    ) async {
        guard !cards.isEmpty else { return }
        // try? 가 throwing+ActiveGroup? 을 ActiveGroup? 로 평탄화 → guard let 후 group 은 비옵셔널.
        guard let group = try? await groupAPI.myActiveGroup() else {
            saveInfoMessage = "활성 그룹을 찾지 못했어요. 잠시 후 다시 시도해 주세요."
            return
        }
        let groupId = group.groupId
        let normalizedMemo = (memo?.isEmpty == false) ? memo : nil

        var wishNames: [String] = []
        var reelNames: [String] = []
        var duplicateCount = 0

        for card in cards {
            // 좌표 없는 카드는 핀 저장 불가 — 스킵(BR-1).
            guard let latitude = card.latitude, let longitude = card.longitude else {
                continue
            }
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
                if tag == .WISH {
                    wishNames.append(card.name)
                } else {
                    reelNames.append(card.name)
                }
            } catch let error as APIError where error.code == "PLC_DUPLICATE_PIN" {
                // 409 — 에러 아님(이미 저장된 장소). 흡수하고 계속(AC-14). 결과 목록에는 미포함.
                duplicateCount += 1
            } catch {
                // 그 외 실패는 안내 후 중단(부분 저장 상태 유지).
                saveInfoMessage = "장소를 저장하지 못했어요. 다시 시도해 주세요."
                return
            }
        }

        // 신규 저장 핀이 1개 이상일 때만 "보러가기" 노출 가능(sourceInstagramUrl 은 비-nil 일 때만, BR-7).
        let savedCount = wishNames.count + reelNames.count
        saveResult = ReelSaveResult(
            wishNames: wishNames,
            reelNames: reelNames,
            duplicateCount: duplicateCount,
            memo: normalizedMemo,
            // 저장 성공 핀이 0개면 포커스할 핀이 없으므로 출처 URL 미노출(BR-7 정합).
            sourceInstagramUrl: savedCount > 0 ? sourceInstagramUrl : nil
        )
    }

    /// 결과 카드 [지도에서 보기 →] 액션(FR-I10/I15). .reelFocus 딥링크 발행 → MainTabView 가 지도 탭 전환 + focusReel.
    func showOnMap(instagramUrl: String) {
        deepLinkRouter.pending = .reelFocus(instagramUrl: instagramUrl)
    }

    /// 결과 카드 닫기(✕). 결과 발행 해제(FR-I8).
    func dismissSaveResult() {
        saveResult = nil
    }

    // MARK: - 재조회(FR-4, 폴링·scenePhase 공용)

    /// 최신 N건 재조회 + id dedup 병합(서버 진실 소스). 폴링 틱과 포그라운드 복귀(scenePhase .active)에서 호출한다.
    /// 같은 turn 의 BOT 결과(PLACE_CARDS/SYSTEM/MEMO_PROMPT)가 들어오면 가장 오래된 PROCESSING 을 교체한다(AC-2 정합).
    func reconcileLatest() async {
        guard let response = try? await chatAPI.botMessages(cursor: nil, limit: Self.pageLimit) else { return }
        let ascending = response.messages.reversed()
        for frame in ascending where !knownIds.contains(frame.messageId) {
            switch frame.kind {
            case .PLACE_CARDS, .SYSTEM, .MEMO_PROMPT:
                removeOldestProcessing()
            case .TEXT, .PROCESSING:
                break
            }
            appendFrame(frame)
        }
        // 재조회 결과가 dedup 으로 모두 중복이어도 커서/hasMore 는 최신 page 값으로 갱신한다(이후 loadMore 일관).
        nextCursor = response.nextCursor
        hasMore = response.hasMore
    }

    // MARK: - Private 헬퍼

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

    /// PROCESSING 플레이스홀더 ChatFrame 생성(전송 응답/폴링 공통).
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

// MARK: - 저장 결과(FR-I8)

/// 위저드 저장 완료 결과(결과 카드 렌더 입력). 409 중복은 목록(wishNames/reelNames)에서 제외되고 duplicateCount 로만 집계.
/// sourceInstagramUrl 이 non-nil 일 때만 결과 카드에 [지도에서 보기 →] 노출(BR-7 — 저장 성공 핀 1개 이상 + URL 존재).
struct ReelSaveResult: Equatable {
    let wishNames: [String]
    let reelNames: [String]
    let duplicateCount: Int
    let memo: String?
    let sourceInstagramUrl: String?
}
