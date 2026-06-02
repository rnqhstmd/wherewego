import Foundation

// 채팅 실시간 계층(설계 §4). **단일 WebSocket 연결 + 화면별 SUBSCRIBE/UNSUBSCRIBE 2개**(봇/커플).
// CONNECT 인증은 1회(Bearer = KeychainTokenStore.accessToken). QE-2 단일 ConnectionState.
//
// 책임:
//  - 진입 시 subscribe(topic:id:onFrame:) / 이탈 시 unsubscribe(id:). 두 방 빠른 전환에도 단일 연결 유지.
//  - STOMP MESSAGE 프레임을 subscription id 로 라우팅해 토픽별 onFrame 콜백(ChatFrame)으로 전달.
//  - 재연결(BR-8): 소켓 close 감지 또는 scenePhase .active 복귀(onForeground) 시 → 즉시 1회 재연결 →
//    실패 시 5초 간격 최대 3회(총 4회). 4회 실패 → state=.disconnected. 성공 시 활성 구독 re-subscribe + onReconnected 콜백.
//  - 봇 토픽 path 의 userId 는 CurrentUser.id 의존(nil 이면 연결 보류·재시도).
//
// 비책임: 보완조회(AC-9)는 onReconnected 콜백을 받은 ViewModel(C7/C8)의 reconcileLatest() 가 수행한다.
//         프레임 디코딩 실패·메시지 dedup·낙관 버블도 ViewModel 책임.
//
// actor 격리: StompClient 는 actor, 본 서비스는 @MainActor → client 호출은 await 경유.
// 콜백은 setOnMessage(_:)/setOnClosed(_:) actor 메서드로 주입(프로퍼티 직접 대입 불가).

// MARK: - 토픽

/// 구독 대상 토픽(설계 §4). 봇 path 는 userId, 커플 path 는 groupId 가 필요하다.
/// destination 문자열은 서버 화이트리스트(StompAuthChannelInterceptor)와 정확히 일치해야 한다.
enum ChatTopic: Equatable {
    /// 봇 방 — `/topic/chat/bot/{userId}`. userId 는 CurrentUser.id.
    case bot(userId: Int)
    /// 커플 방 — `/topic/chat/couple/{groupId}`.
    case couple(groupId: Int)

    /// STOMP SUBSCRIBE destination 헤더 값.
    var destination: String {
        switch self {
        case .bot(let userId):
            return "/topic/chat/bot/\(userId)"
        case .couple(let groupId):
            return "/topic/chat/couple/\(groupId)"
        }
    }
}

// MARK: - 재연결 정책(순수 로직, BR-8)

/// 재연결 시도 카운터·간격 스케줄(설계 §4, 순수 로직 분리 — now/sleep 주입으로 결정적 테스트).
/// 시퀀스(BR-8): 즉시 1회(delay 0) → 5초 × 3회 → 4회 모두 실패 시 nil(중단).
///
/// 사용: 매 시도 직전 `nextDelay()` 로 대기 시간을 얻는다. nil 이면 더 시도하지 않는다(.disconnected).
/// 연결 성공 시 `reset()` 으로 카운터 초기화.
struct ReconnectPolicy: Equatable {
    /// 총 시도 횟수(BR-8: 즉시 1 + 5초 간격 3 = 4회).
    static let maxAttempts = 4
    /// 즉시 재시도 이후 간격(초). BR-8 = 5초.
    static let retryInterval: TimeInterval = 5

    /// 지금까지 소비한(반환한) 시도 수. 0 이면 아직 시도 전.
    private(set) var attempt = 0

    /// 다음 재연결 시도까지의 대기 시간을 반환한다. 더 시도할 수 없으면 nil.
    /// - 첫 시도(attempt 0): 0초(즉시).
    /// - 2~4번째 시도: 5초.
    /// - maxAttempts 초과: nil(중단).
    mutating func nextDelay() -> TimeInterval? {
        guard attempt < Self.maxAttempts else { return nil }
        let delay: TimeInterval = attempt == 0 ? 0 : Self.retryInterval
        attempt += 1
        return delay
    }

    /// 연결 성공/새 재연결 사이클 시작 시 카운터를 초기화한다.
    mutating func reset() {
        attempt = 0
    }
}

// MARK: - 프로토콜

/// 채팅 실시간 서비스 추상화(설계 §4). ViewModel(C7/C8)이 진입/이탈에서 subscribe/unsubscribe 한다.
/// AnyObject + Sendable: 단일 인스턴스(앱 수명)를 @MainActor 클래스로 구현한다.
///
/// 상태 관찰·재연결 통지를 **id 키 옵저버**로 노출해 봇/커플 두 ViewModel 이 동시에 구독해도
/// 서로 덮어쓰지 않도록 한다(AC-9, Critical-5 수정). 단일 var 콜백은 마지막 등록 VM 만 통지받아
/// 탭 전환 시 한 방만 상태배너·재연결 보완을 수신하는 문제가 있었다.
@MainActor
protocol ChatRealtimeServicing: AnyObject, Sendable {
    /// 현재 QE-2 연결 상태(상단 배너 초기값). 구독 등록 직후 즉시 1회 미러링하는 데 사용.
    var currentState: ConnectionState { get }
    /// 상태 변화 옵저버 등록(id 키). @MainActor 에서 호출한다. 동일 id 재등록 시 교체된다.
    /// ViewModel 이 진입 시 등록하고 매 상태 전환마다 realtimeState 로 반영한다.
    func addStateObserver(id: String, _ handler: @escaping @Sendable (ConnectionState) -> Void)
    /// 상태 변화 옵저버 제거(이탈). 없는 id 는 no-op.
    func removeStateObserver(id: String)
    /// 재연결 성공 옵저버 등록(id 키, AC-9 보완조회 트리거). ViewModel 이 reconcileLatest() 를 연결한다.
    func addReconnectedObserver(id: String, _ handler: @escaping @Sendable () -> Void)
    /// 재연결 성공 옵저버 제거(이탈). 없는 id 는 no-op.
    func removeReconnectedObserver(id: String)
    /// 토픽 구독(진입). id 별 onFrame 콜백으로 MESSAGE(ChatFrame)를 라우팅한다.
    /// 미연결이면 연결을 시작한 뒤 구독을 등록한다.
    func subscribe(topic: ChatTopic, id: String, onFrame: @escaping @Sendable (ChatFrame) -> Void) async
    /// 구독 해제(이탈). 연결은 유지한다(빠른 방 전환 대비).
    func unsubscribe(id: String) async
    /// scenePhase .active 복귀 — 즉시 1회 재연결을 시도한다(BR-8).
    func onForeground() async
    /// 수동 재시도(.disconnected 배너의 "다시 시도"). 재연결 사이클을 새로 시작한다.
    func retryManually() async
}

// MARK: - 서비스

@MainActor
final class ChatRealtimeService: ObservableObject, ChatRealtimeServicing {

    /// QE-2 연결 상태. 상단 배너(ChatScrollContainer)가 구독한다.
    /// 변화 시 등록된 모든 stateObservers 에 통지해 봇/커플 두 ViewModel 이 동시에 미러링하도록 한다(Critical-5).
    @Published private(set) var state: ConnectionState = .connecting {
        didSet {
            guard oldValue != state else { return }
            notifyStateObservers(state)
        }
    }

    /// ChatRealtimeServicing.currentState — 현재 연결 상태(구독 등록 직후 즉시 미러용).
    var currentState: ConnectionState { state }

    /// 상태 변화 옵저버(id 키). 봇/커플 두 ViewModel 이 동시에 등록·통지받는다(Critical-5 — 단일 var 덮어쓰기 방지).
    private var stateObservers: [String: @Sendable (ConnectionState) -> Void] = [:]

    /// 재연결 성공 옵저버(id 키, AC-9). 재연결 성공 시 전체 통지 → 각 ViewModel 이 reconcileLatest() 로 보완조회한다.
    private var reconnectedObservers: [String: @Sendable () -> Void] = [:]

    // MARK: - 의존성

    private let client: StompClient
    private let tokens: TokenStore
    private let currentUser: CurrentUser

    /// 재연결 대기용 sleep 주입(테스트 결정성). 기본 Task.sleep.
    private let sleep: @Sendable (TimeInterval) async -> Void

    // MARK: - 구독 상태

    /// 활성 구독: id → (토픽, onFrame). 재연결 시 re-subscribe 의 소스.
    private var subscriptions: [String: Subscription] = [:]

    /// 단일 재연결 사이클 Task(중복 사이클 방지). nil 이면 진행 중 아님.
    private var reconnectTask: Task<Void, Never>?
    /// 현재 연결이 수립되어 있는지(중복 connect 방지). state 와 별개로 소켓 핸드셰이크 성공 여부를 추적.
    private var isConnected = false

    private struct Subscription {
        let topic: ChatTopic
        let onFrame: @Sendable (ChatFrame) -> Void
    }

    /// - Parameters:
    ///   - client: 단일 STOMP 연결(actor). AppDependencies 가 baseURL 로 생성·주입.
    ///   - tokens: Bearer 출처(KeychainTokenStore). CONNECT accessToken.
    ///   - currentUser: 봇 토픽 path 의 userId 출처.
    ///   - sleep: 재연결 간격 대기(테스트 주입). 기본 Task.sleep.
    init(
        client: StompClient,
        tokens: TokenStore,
        currentUser: CurrentUser,
        sleep: @escaping @Sendable (TimeInterval) async -> Void = { seconds in
            try? await Task.sleep(nanoseconds: UInt64(max(0, seconds) * 1_000_000_000))
        }
    ) {
        self.client = client
        self.tokens = tokens
        self.currentUser = currentUser
        self.sleep = sleep
    }

    // MARK: - 옵저버(ChatRealtimeServicing — 상태/재연결, id 키)

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

    /// 상태 전환 시 등록된 모든 옵저버에 통지(@MainActor).
    private func notifyStateObservers(_ state: ConnectionState) {
        for observer in stateObservers.values {
            observer(state)
        }
    }

    /// 재연결 성공 시 등록된 모든 옵저버에 통지(@MainActor, AC-9).
    private func notifyReconnectedObservers() {
        for observer in reconnectedObservers.values {
            observer()
        }
    }

    // MARK: - 구독(ChatRealtimeServicing)

    func subscribe(topic: ChatTopic, id: String, onFrame: @escaping @Sendable (ChatFrame) -> Void) async {
        subscriptions[id] = Subscription(topic: topic, onFrame: onFrame)
        // 미연결이면 연결부터(연결 성공 후 활성 구독을 일괄 SUBSCRIBE). 연결돼 있으면 이 구독만 SUBSCRIBE.
        if isConnected {
            await sendSubscribe(id: id, topic: topic)
        } else {
            await ensureConnected()
        }
    }

    func unsubscribe(id: String) async {
        subscriptions.removeValue(forKey: id)
        guard isConnected else { return }
        try? await client.unsubscribe(id: id)
    }

    func onForeground() async {
        // scenePhase .active 복귀(BR-8). 끊겨 있으면 즉시 재연결 사이클 시작. 연결돼 있으면 무시.
        guard !isConnected else { return }
        startReconnectCycle()
    }

    func retryManually() async {
        // 수동 재시도(.disconnected 배너). 새 재연결 사이클을 시작한다(기존 사이클 취소 후).
        reconnectTask?.cancel()
        reconnectTask = nil
        startReconnectCycle()
    }

    // MARK: - 연결 수립

    /// 미연결 시 최초 연결을 수행한다. 성공하면 활성 구독을 일괄 SUBSCRIBE.
    /// 실패하면 재연결 사이클로 위임한다(BR-8).
    private func ensureConnected() async {
        guard !isConnected, reconnectTask == nil else { return }
        state = .connecting
        let ok = await attemptConnect()
        if ok {
            await onConnectSucceeded(notifyReconnected: false)
        } else {
            startReconnectCycle()
        }
    }

    /// 1회 연결 시도. 토큰·userId 확보 → 콜백 주입 → connect. 성공 시 true.
    /// userId 미확보(봇 토픽 path 필수) 시 CurrentUser.load() 로 1회 보강 후 재확인.
    private func attemptConnect() async -> Bool {
        // 봇 구독이 있으면 userId 가 반드시 필요하다(토픽 path). 미확보면 load 로 보강.
        if needsUserId, currentUser.id == nil {
            await currentUser.load()
            if currentUser.id == nil { return false }
        }
        guard let token = await tokens.accessToken() else { return false }

        await installClientCallbacks()
        do {
            try await client.connect(bearerToken: token)
            return true
        } catch {
            return false
        }
    }

    /// 활성 구독 중 봇 토픽이 있어 userId 가 필요한지.
    private var needsUserId: Bool {
        subscriptions.values.contains {
            if case .bot = $0.topic { return true }
            return false
        }
    }

    /// 연결 성공 처리: 상태 전환 + 활성 구독 re-subscribe + (재연결이면) onReconnected 통지.
    private func onConnectSucceeded(notifyReconnected: Bool) async {
        isConnected = true
        state = .connected
        await resubscribeAll()
        if notifyReconnected {
            notifyReconnectedObservers()
        }
    }

    /// 활성 구독 전체를 SUBSCRIBE 한다(최초 연결·재연결 공통). 봇 토픽은 최신 userId 로 destination 재구성.
    private func resubscribeAll() async {
        for (id, sub) in subscriptions {
            await sendSubscribe(id: id, topic: sub.topic)
        }
    }

    /// 단일 구독을 SUBSCRIBE 한다. 봇 토픽은 CurrentUser.id 로 destination 을 확정(보류된 nil 방지).
    private func sendSubscribe(id: String, topic: ChatTopic) async {
        let destination: String
        switch topic {
        case .bot:
            guard let userId = currentUser.id else { return }
            destination = ChatTopic.bot(userId: userId).destination
        case .couple:
            destination = topic.destination
        }
        try? await client.subscribe(destination: destination, id: id)
    }

    // MARK: - STOMP 콜백 주입

    /// StompClient 콜백을 actor 메서드로 주입한다(프로퍼티 직접 대입 불가).
    /// onMessage: subscription id 로 라우팅 → ChatFrame 디코딩 → 토픽별 onFrame.
    /// onClosed: 소켓 종료 → 재연결 사이클 시작(BR-8).
    private func installClientCallbacks() async {
        await client.setOnMessage { [weak self] frame in
            // actor → MainActor 경계: 라우팅은 MainActor 에서(subscriptions 접근).
            Task { @MainActor [weak self] in
                self?.routeMessage(frame)
            }
        }
        await client.setOnClosed { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.handleClosed()
            }
        }
    }

    /// MESSAGE 프레임을 subscription 헤더(구독 id)로 라우팅한다. body(JSON)를 ChatFrame 으로 디코딩 후 콜백.
    /// 디코딩 실패·미등록 구독은 무시(방어).
    private func routeMessage(_ frame: StompFrame) {
        guard frame.command == "MESSAGE" else { return }
        // 서버 MESSAGE 는 subscription 헤더에 구독 id 를 싣는다(SUBSCRIBE 시 부여한 id).
        guard let subscriptionId = frame.headers["subscription"],
              let sub = subscriptions[subscriptionId] else { return }
        guard let data = frame.body.data(using: .utf8),
              let chatFrame = try? JSONDecoder().decode(ChatFrame.self, from: data) else { return }
        sub.onFrame(chatFrame)
    }

    /// 소켓 종료 처리(StompClient.onClosed). 이미 끊김 처리 중이면 무시, 아니면 재연결 사이클 시작.
    private func handleClosed() {
        isConnected = false
        guard reconnectTask == nil else { return }
        startReconnectCycle()
    }

    // MARK: - 재연결 사이클(BR-8)

    /// 재연결 사이클을 시작한다(중복 사이클 방지). 즉시 1회 → 5초×3회.
    /// 매 시도 전 ReconnectPolicy.nextDelay() 만큼 sleep. 성공 시 onConnectSucceeded(통지 true), 4회 실패 시 .disconnected.
    private func startReconnectCycle() {
        guard reconnectTask == nil else { return }
        isConnected = false
        state = .reconnecting

        reconnectTask = Task { [weak self] in
            guard let self else { return }
            var policy = ReconnectPolicy()
            while let delay = policy.nextDelay() {
                if Task.isCancelled { return }
                await self.sleep(delay)
                if Task.isCancelled { return }
                let ok = await self.attemptConnect()
                if ok {
                    await self.finishReconnect(success: true)
                    return
                }
            }
            // 4회 모두 실패 → 수동 재시도 안내.
            await self.finishReconnect(success: false)
        }
    }

    /// 재연결 사이클 종료 처리. 성공이면 connected + 재구독 + onReconnected, 실패면 disconnected.
    private func finishReconnect(success: Bool) async {
        reconnectTask = nil
        if success {
            await onConnectSucceeded(notifyReconnected: true)
        } else {
            isConnected = false
            state = .disconnected
        }
    }
}
