import Foundation

// 단일 WebSocket STOMP 연결(설계 §3, Q1: 직접 구현). URLSessionWebSocketTask 기반.
// 구현 프레임 한정: CONNECT/CONNECTED/SUBSCRIBE/MESSAGE/ERROR/DISCONNECT.
// heart-beat(0,0)·ACK·트랜잭션은 미구현(단일 인스턴스·단방향 server push 전제, 설계 §3).
//
// 책임: 연결/구독/수신 루프/해제 — 인증 토큰은 호출자(ChatRealtimeService, C5)가 주입(connect bearerToken).
// 재연결 정책(BR-8)·구독 라우팅은 상위 ChatRealtimeService 가 소유한다(여기서는 단일 연결 메커니즘만).

/// STOMP 클라이언트 에러. connect/handshake 실패 분기에 사용.
enum StompClientError: Error, LocalizedError {
    case invalidEndpoint
    case notConnected
    case connectTimeout
    case connectRejected(String)      // ERROR 프레임 또는 CONNECTED 외 응답
    case closed

    var errorDescription: String? {
        switch self {
        case .invalidEndpoint: return "실시간 서버 주소가 올바르지 않습니다."
        case .notConnected: return "실시간 연결이 없습니다."
        case .connectTimeout: return "실시간 연결 시간이 초과되었습니다."
        case .connectRejected(let reason): return "실시간 연결이 거부되었습니다: \(reason)"
        case .closed: return "실시간 연결이 종료되었습니다."
        }
    }
}

actor StompClient {

    /// MESSAGE 프레임 수신 콜백. 수신 루프에서 호출.
    var onMessage: (@Sendable (StompFrame) -> Void)?
    /// 연결 종료/에러 콜백(ERROR 프레임 또는 소켓 close). 재연결 트리거(ChatRealtimeService).
    var onClosed: (@Sendable (Error?) -> Void)?

    private let baseURL: URL
    private let session: URLSession
    private let connectTimeout: TimeInterval

    private var task: URLSessionWebSocketTask?
    /// CONNECTED 프레임 도착을 기다리는 connect() 의 continuation(handshake 1회용).
    private var connectContinuation: CheckedContinuation<Void, Error>?
    /// connect handshake 타임아웃 Task. connect 성공/실패 시 반드시 취소한다(잔존 Task 재연결 루프 방지).
    private var timeoutTask: Task<Void, Never>?
    private var isConnected = false

    /// - Parameters:
    ///   - baseURL: REST API base URL(AppConfig.apiBaseURL). scheme 을 ws/wss 로 변환해 사용.
    ///   - session: 주입(테스트). 기본 .shared.
    ///   - connectTimeout: CONNECTED 대기 타임아웃(초).
    init(baseURL: URL, session: URLSession = .shared, connectTimeout: TimeInterval = 10) {
        self.baseURL = baseURL
        self.session = session
        self.connectTimeout = connectTimeout
    }

    func setOnMessage(_ handler: @escaping @Sendable (StompFrame) -> Void) {
        self.onMessage = handler
    }

    func setOnClosed(_ handler: @escaping @Sendable (Error?) -> Void) {
        self.onClosed = handler
    }

    // MARK: - 연결

    /// WebSocket 연결 후 CONNECT 프레임 전송 → CONNECTED 수신까지 대기한다.
    /// CONNECT 헤더: accept-version:1.2, host, Authorization:Bearer, heart-beat:0,0.
    func connect(bearerToken: String) async throws {
        guard let endpoint = Self.websocketURL(from: baseURL) else {
            throw StompClientError.invalidEndpoint
        }

        let task = session.webSocketTask(with: endpoint)
        self.task = task
        self.isConnected = false
        task.resume()

        // 수신 루프 시작(CONNECTED·MESSAGE·ERROR·close 처리).
        receiveLoop()

        let host = endpoint.host ?? baseURL.host ?? ""
        let connectFrame = StompFrame(
            command: "CONNECT",
            headers: [
                "accept-version": "1.2",
                "host": host,
                "Authorization": "Bearer \(bearerToken)",
                "heart-beat": "0,0"
            ]
        )

        // CONNECTED 수신을 receiveLoop 가 connectContinuation 으로 resume 한다.
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            self.connectContinuation = continuation
            Task {
                do {
                    try await self.send(frame: connectFrame)
                    await self.scheduleConnectTimeout()
                } catch {
                    self.resumeConnect(throwing: error)
                }
            }
        }
    }

    /// connect() handshake 타임아웃. connectTimeout 후에도 CONNECTED 미수신이면 실패 처리.
    /// Task 를 timeoutTask 에 보관해 connect 성공/실패 시 resumeConnect 에서 취소한다(잔존 방지).
    private func scheduleConnectTimeout() {
        timeoutTask?.cancel()
        // 이미 연결 완료/실패로 continuation 이 소비되었으면 타임아웃 태스크를 예약하지 않는다(잔존 Task 방지).
        guard connectContinuation != nil else {
            timeoutTask = nil
            return
        }
        let timeout = connectTimeout
        timeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            if Task.isCancelled { return }
            guard let self else { return }
            if await self.connectContinuation != nil {
                await self.resumeConnect(throwing: StompClientError.connectTimeout)
                await self.disconnect()
            }
        }
    }

    // MARK: - 구독

    /// destination 토픽 구독(SUBSCRIBE). id 는 구독 식별자(sub-bot/sub-couple).
    func subscribe(destination: String, id: String) async throws {
        guard isConnected else { throw StompClientError.notConnected }
        let frame = StompFrame(
            command: "SUBSCRIBE",
            headers: ["id": id, "destination": destination]
        )
        try await send(frame: frame)
    }

    /// 구독 해제(UNSUBSCRIBE). id 는 subscribe 시 부여한 식별자.
    func unsubscribe(id: String) async throws {
        guard isConnected else { return }
        let frame = StompFrame(command: "UNSUBSCRIBE", headers: ["id": id])
        try await send(frame: frame)
    }

    // MARK: - 해제

    /// DISCONNECT 프레임 전송 후 소켓 종료. 재연결 가능하도록 상태만 초기화한다.
    func disconnect() async {
        if isConnected {
            let frame = StompFrame(command: "DISCONNECT")
            try? await send(frame: frame)
        }
        isConnected = false
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
    }

    // MARK: - 전송

    private func send(frame: StompFrame) async throws {
        guard let task else { throw StompClientError.notConnected }
        try await task.send(frame.encode())
    }

    // MARK: - 수신 루프

    /// 단일 수신 재귀 루프. 텍스트/바이너리 메시지를 STOMP 프레임으로 파싱해 분류한다.
    /// 실패(소켓 close/에러)는 onClosed 로 통지하고 루프를 종료한다.
    private func receiveLoop() {
        guard let task else { return }
        task.receive { [weak self] result in
            guard let self else { return }
            Task {
                switch result {
                case .success(let message):
                    await self.handle(message: message)
                    await self.receiveLoopIfActive()
                case .failure(let error):
                    await self.handleClose(error: error)
                }
            }
        }
    }

    /// 연결이 유효할 때만 다음 수신을 예약한다(disconnect 후 잔여 콜백 방지).
    private func receiveLoopIfActive() {
        guard task != nil else { return }
        receiveLoop()
    }

    /// 수신 메시지를 STOMP 프레임으로 파싱하여 command 별 처리.
    private func handle(message: URLSessionWebSocketTask.Message) {
        let text: String
        switch message {
        case .string(let value):
            text = value
        case .data(let data):
            text = String(decoding: data, as: UTF8.self)
        @unknown default:
            return
        }

        for frame in StompFrame.decode(text) {
            switch frame.command {
            case "CONNECTED":
                isConnected = true
                resumeConnect(throwing: nil)
            case "MESSAGE":
                onMessage?(frame)
            case "ERROR":
                // 서버 거부(CONNECT 인가 실패 등). handshake 중이면 connect 실패로, 아니면 close 로.
                let reason = frame.headers["message"] ?? frame.body
                if connectContinuation != nil {
                    resumeConnect(throwing: StompClientError.connectRejected(reason))
                } else {
                    onClosed?(StompClientError.connectRejected(reason))
                }
                isConnected = false
                task?.cancel(with: .normalClosure, reason: nil)
                task = nil
            default:
                // RECEIPT 등 미사용 command 는 무시.
                break
            }
        }
    }

    /// 소켓 종료/수신 에러 처리. handshake 중이면 connect 실패로, 아니면 onClosed 통지.
    private func handleClose(error: Error) {
        isConnected = false
        task = nil
        if connectContinuation != nil {
            resumeConnect(throwing: error)
        } else {
            onClosed?(error)
        }
    }

    /// connect() continuation 을 1회만 resume 한다(중복 resume 크래시 방지).
    /// continuation 소비 지점이므로 handshake 타임아웃 Task 도 함께 취소한다(connect 성공/실패 양쪽 잔존 방지).
    private func resumeConnect(throwing error: Error?) {
        timeoutTask?.cancel()
        timeoutTask = nil
        guard let continuation = connectContinuation else { return }
        connectContinuation = nil
        if let error {
            continuation.resume(throwing: error)
        } else {
            continuation.resume(returning: ())
        }
    }

    // MARK: - 순수 헬퍼

    /// REST base URL 의 scheme 을 WebSocket(ws/wss)으로 변환하고 `/ws/chat` 경로를 부착한다.
    /// http→ws, https→wss. 이미 ws/wss 면 그대로. 변환 불가 시 nil.
    static func websocketURL(from baseURL: URL) -> URL? {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            return nil
        }
        switch components.scheme?.lowercased() {
        case "https", "wss":
            components.scheme = "wss"
        case "http", "ws":
            components.scheme = "ws"
        default:
            return nil
        }
        // base path 가 비어 있으면 "/ws/chat", 있으면 뒤에 결합(중복 슬래시 방지).
        let trimmed = components.path.hasSuffix("/")
            ? String(components.path.dropLast())
            : components.path
        components.path = trimmed + "/ws/chat"
        components.query = nil
        return components.url
    }
}
