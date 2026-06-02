import Foundation

// 커플 1:1 채팅 ViewModel(설계 §6, FR-9~12, BR-3, AC-5/6/9).
// 책임:
//  - 활성 그룹 확보(groupAPI.myActiveGroup) → groupId 로 최신 메시지 로드(coupleMessages cursor=nil) + STOMP 구독.
//  - 전송(FR-11/AC-6): 낙관 버블(임시 음수 id) 즉시 추가 → sendCoupleMessage → 응답 messageId 로 실제 치환·dedup.
//    실패 시 전송 실패 상태 + 재시도.
//  - 실시간(FR-12): 파트너 메시지만 append. 내 메시지(senderType==USER & CurrentUser.id 일치 또는 낙관 매칭)는 dedup.
//  - 재연결 보완(AC-9): onReconnected → reconcileLatest(cursor=null 최신 N건 재조회 + id Set dedup/merge).
//  - 과거 로드(FR-2): 상단 도달 시 loadMore(nextCursor).
//
// messages 는 화면 표시 순서(오름차순: 오래된 → 최신)로 보유한다(ChatScrollContainer 계약).
// 봇 방과 달리 카드/PROCESSING 없음 — TEXT/SYSTEM 만 다룬다.
@MainActor
final class CoupleChatViewModel: ObservableObject {

    /// 커플 방 텍스트 길이 제한(BR-3/AC-5). 백엔드 검증(≤1000자)과 동치.
    static let textMaxLength = 1000
    /// 페이지 크기(BR-4). 서버가 1~50 클램프.
    static let pageLimit = 20
    /// STOMP 구독 id(설계 §4 — 커플 방 단일 구독).
    static let subscriptionId = "sub-couple"

    // MARK: - 게시 상태

    /// 표시 메시지(오름차순). ChatScrollContainer 가 그대로 렌더.
    @Published private(set) var messages: [ChatFrame] = []
    /// QE-2 연결 상태(상단 배너). realtime.state 미러.
    @Published private(set) var realtimeState: ConnectionState = .connecting
    /// 입력 초안(1000자 카운터 표시는 View).
    @Published var draft: String = ""
    /// 전송 실패 안내(인라인 토스트). nil 이면 미표시.
    @Published var sendErrorMessage: String?
    /// 로드 실패 안내(활성 그룹 없음/네트워크). nil 이면 미표시.
    @Published var loadErrorMessage: String?

    // MARK: - 의존성

    private let chatAPI: ChatAPIProtocol
    private let groupAPI: GroupAPIProtocol
    private let realtime: ChatRealtimeServicing
    private let currentUser: CurrentUser

    // MARK: - 내부 상태

    /// 확보한 활성 그룹 id(커플 토픽 path·전송·로드의 groupId 출처). 확보 전 nil.
    private(set) var groupId: Int?
    /// 더 과거 메시지 페이지 커서(nextCursor). nil 이면 더 없음.
    private var nextCursor: Int?
    /// 더 과거 메시지가 있는지(hasMore). loadMore 가드.
    private var hasMore = false
    /// 표시 중 messageId Set(dedup). 음수(낙관) id 포함.
    private var knownIds: Set<Int> = []
    /// 다음 낙관 버블에 부여할 임시 음수 id(점점 감소). -1, -2, ...
    private var nextOptimisticId = -1

    init(
        chatAPI: ChatAPIProtocol,
        groupAPI: GroupAPIProtocol,
        realtime: ChatRealtimeServicing,
        currentUser: CurrentUser
    ) {
        self.chatAPI = chatAPI
        self.groupAPI = groupAPI
        self.realtime = realtime
        self.currentUser = currentUser
    }

    // MARK: - 진입/이탈(설계 §6)

    /// 진입(FR-10/FR-12): 활성 그룹 확보 → 커플 토픽 구독 + 재연결 보완 등록 → 최신 메시지 로드.
    /// subscribe 를 load 보다 먼저 호출한다(Q2 레이스): load(수백 ms) 사이 도착한 STOMP 프레임 유실 방지.
    /// 도착 프레임과 load 결과는 messageId dedup(knownIds)로 중복 제거되므로 안전하다.
    func appear() async {
        observeRealtimeState()
        guard let groupId = await ensureGroupId() else { return }
        await realtime.subscribe(topic: .couple(groupId: groupId), id: Self.subscriptionId) { [weak self] frame in
            Task { @MainActor [weak self] in
                self?.handleIncoming(frame)
            }
        }
        // 재연결 성공 통지(AC-9) — id 키 옵저버로 등록(봇/커플 동시 구독 시 덮어쓰기 방지, Critical-5).
        realtime.addReconnectedObserver(id: Self.subscriptionId) { [weak self] in
            Task { @MainActor [weak self] in
                await self?.reconcileLatest()
            }
        }
        await load()
    }

    /// 이탈: 구독 해제(연결은 유지 — 빠른 방 전환). state 미러/재연결 옵저버 정리(id 키).
    func disappear() async {
        realtime.removeStateObserver(id: Self.subscriptionId)
        realtime.removeReconnectedObserver(id: Self.subscriptionId)
        await realtime.unsubscribe(id: Self.subscriptionId)
    }

    // MARK: - 로드(FR-10)

    /// 최신 메시지 N건 로드(cursor=nil). 서버 id DESC → 화면 오름차순으로 reverse.
    func load() async {
        guard let groupId else { return }
        do {
            let page = try await chatAPI.coupleMessages(groupId: groupId, cursor: nil, limit: Self.pageLimit)
            messages = page.messages.reversed()
            knownIds = Set(messages.map(\.messageId))
            hasMore = page.hasMore
            nextCursor = page.nextCursor
            loadErrorMessage = nil
        } catch {
            loadErrorMessage = "메시지를 불러오지 못했어요. 다시 시도해 주세요."
        }
    }

    /// 상단 도달 시 과거 메시지 추가 로드(FR-2). hasMore/nextCursor 가드 — 더 없으면 no-op.
    func loadMore() async {
        guard let groupId, hasMore, let cursor = nextCursor else { return }
        do {
            let page = try await chatAPI.coupleMessages(groupId: groupId, cursor: cursor, limit: Self.pageLimit)
            // 서버 id DESC(과거 페이지) → 화면 오름차순으로 reverse 후 기존 앞에 prepend.
            let older = page.messages.reversed().filter { !knownIds.contains($0.messageId) }
            messages.insert(contentsOf: older, at: 0)
            for frame in older { knownIds.insert(frame.messageId) }
            hasMore = page.hasMore
            nextCursor = page.nextCursor
        } catch {
            // 과거 로드 실패는 조용히 무시(best-effort, 상단 스크롤 재시도 가능).
        }
    }

    // MARK: - 전송(FR-11/AC-6)

    /// 텍스트 전송. 1000자 가드(BR-3/AC-5) → 낙관 버블 즉시 추가 → sendCoupleMessage →
    /// 응답 messageId 로 실제 치환·dedup. 실패 시 낙관 버블 제거 + 전송 실패 안내.
    func send() async {
        guard let groupId else {
            sendErrorMessage = "활성 그룹을 찾지 못했어요. 잠시 후 다시 시도해 주세요."
            return
        }
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        // BR-3/AC-5: 1000자 초과 차단(절단하지 않음 — 입력바 카운터가 비활성 안내, 서버 재검증과 정합).
        guard text.count <= Self.textMaxLength else { return }

        // 낙관 버블(임시 음수 id) 즉시 추가.
        let optimisticId = nextOptimisticId
        nextOptimisticId -= 1
        guard let optimistic = makeLocalTextFrame(messageId: optimisticId, roomId: groupId, text: text) else {
            sendErrorMessage = "메시지를 보내지 못했어요. 다시 시도해 주세요."
            return
        }
        appendUnique(optimistic)
        draft = ""
        sendErrorMessage = nil

        do {
            let response = try await chatAPI.sendCoupleMessage(groupId: groupId, text: text)
            replaceOptimistic(optimisticId: optimisticId, with: response.messageId)
        } catch {
            // 전송 실패: 낙관 버블 제거 + 재시도 안내(draft 복원으로 재시도 가능).
            removeMessage(id: optimisticId)
            draft = text
            sendErrorMessage = "메시지를 보내지 못했어요. 다시 시도해 주세요."
        }
    }

    // MARK: - 실시간 수신(FR-12/AC-6)

    /// STOMP MESSAGE 라우팅 결과. **messageId 일치(knownIds)로만 dedup** 후 append(설계 §6 "messageId 일치 우선").
    /// ChatFrame 에 senderUserId 가 없어(백엔드 ChatMessageFrame 비포함) 발신자 판별이 불가하므로,
    /// 내용 휴리스틱 승격은 파트너 메시지를 삼킬 위험이 있어 제거한다(Critical-1). 결과:
    ///  - 파트너 USER/SYSTEM 메시지는 항상 표시(유실 없음, FR-12).
    ///  - 내 낙관 버블은 send() 응답 messageId 로 이미 실제 id 치환됨 → 뒤이은 STOMP echo 는 knownIds 일치로 dedup.
    func handleIncoming(_ frame: ChatFrame) {
        appendUnique(frame)
    }

    // MARK: - 재연결 보완(AC-9)

    /// 재연결 성공 시 cursor=null 최신 N건 재조회 + id Set dedup/merge(AC-9).
    /// 끊긴 동안 누락분을 보완한다. 50건 초과분은 상단 loadMore 로만 보완(설계 §6).
    func reconcileLatest() async {
        guard let groupId else { return }
        guard let page = try? await chatAPI.coupleMessages(groupId: groupId, cursor: nil, limit: Self.pageLimit) else { return }
        let fresh = page.messages.reversed().filter { !knownIds.contains($0.messageId) }
        guard !fresh.isEmpty else { return }
        for frame in fresh {
            appendUnique(frame)
        }
        // 최신 페이지 재조회 시 커서/hasMore 도 최신화(이후 loadMore 정합 — BotChatViewModel.reconcileLatest 와 동일).
        nextCursor = page.nextCursor
        hasMore = page.hasMore
    }

    // MARK: - 수동 재연결(AC-8/BR-8)

    /// .disconnected 배너 "다시 연결" → 재연결 사이클 새로 시작.
    func retryRealtime() async {
        await realtime.retryManually()
    }

    // MARK: - 활성 그룹 확보

    /// 활성 그룹 id 확보(커플 토픽·로드·전송 공통). 실패/없음 시 안내 후 nil.
    private func ensureGroupId() async -> Int? {
        if let groupId { return groupId }
        do {
            guard let group = try await groupAPI.myActiveGroup() else {
                loadErrorMessage = "활성 그룹이 없어요. 짝꿍과 그룹을 먼저 만들어 주세요."
                return nil
            }
            groupId = group.groupId
            return group.groupId
        } catch {
            loadErrorMessage = "그룹 정보를 불러오지 못했어요. 다시 시도해 주세요."
            return nil
        }
    }

    // MARK: - dedup/치환 헬퍼

    /// id 미표시일 때만 append + knownIds 갱신. 오름차순 유지(append = 최신).
    private func appendUnique(_ frame: ChatFrame) {
        guard !knownIds.contains(frame.messageId) else { return }
        messages.append(frame)
        knownIds.insert(frame.messageId)
    }

    /// 낙관 버블(음수 id)을 서버 실제 messageId 로 치환. 실제 id 가 이미 표시 중(STOMP echo 선도착)이면
    /// 낙관 버블만 제거(중복 방지). 그 외엔 동일 위치에서 messageId 만 교체한다.
    private func replaceOptimistic(optimisticId: Int, with realId: Int) {
        guard let index = messages.firstIndex(where: { $0.messageId == optimisticId }) else { return }
        // STOMP echo 가 먼저 실제 id 로 append 됐다면 낙관 버블은 제거(중복 차단).
        if knownIds.contains(realId) {
            messages.remove(at: index)
            knownIds.remove(optimisticId)
            return
        }
        let old = messages[index]
        guard let promoted = makeLocalTextFrame(
            messageId: realId,
            roomId: old.roomId,
            text: old.text ?? "",
            senderType: old.senderType,
            createdAt: old.createdAt
        ) else { return }
        messages[index] = promoted
        knownIds.remove(optimisticId)
        knownIds.insert(realId)
    }

    /// id 일치 메시지 제거(전송 실패 시 낙관 버블 롤백).
    private func removeMessage(id: Int) {
        messages.removeAll { $0.messageId == id }
        knownIds.remove(id)
    }

    // MARK: - 낙관/치환용 ChatFrame 생성(공통 모델 무수정 우회)

    /// 메모리에서 TEXT ChatFrame 을 생성한다(낙관 버블·치환용).
    /// ChatFrame 은 커스텀 init(from:) 만 가져 memberwise init 이 없고 B2 담당(수정 불가) →
    /// JSON 인코딩→디코딩으로 정규 디코딩 경로를 재사용해 생성한다(payload {"text":...} 구조 정합).
    private func makeLocalTextFrame(
        messageId: Int,
        roomId: Int,
        text: String,
        senderType: SenderType = .USER,
        createdAt: String? = nil
    ) -> ChatFrame? {
        let createdAtValue = createdAt ?? Self.isoFormatter.string(from: Date())
        let json: [String: Any] = [
            "messageId": messageId,
            "roomId": roomId,
            "senderType": senderType.rawValue,
            "kind": MessageKind.TEXT.rawValue,
            "createdAt": createdAtValue,
            "payload": ["text": text]
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: json),
              let frame = try? JSONDecoder().decode(ChatFrame.self, from: data) else {
            return nil
        }
        return frame
    }

    /// 낙관 버블 createdAt(ISO-8601 offset, PinSummary/ChatFrame 동일 포맷).
    private static let isoFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    // MARK: - 연결 상태 미러

    /// realtime 연결 상태를 realtimeState 로 미러한다(상단 배너, C9 통합 보강).
    /// currentState 로 즉시 1회 동기화 후 onStateChange 콜백으로 후속 전환 반영(구체 타입 캐스팅 제거).
    private func observeRealtimeState() {
        realtimeState = realtime.currentState
        realtime.addStateObserver(id: Self.subscriptionId) { [weak self] state in
            Task { @MainActor [weak self] in
                self?.realtimeState = state
            }
        }
    }
}
