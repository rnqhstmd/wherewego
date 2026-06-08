import Foundation

// DM 목록 ViewModel(설계 §4, FR-1/2/6/7/10, BR-1/4). #105 그룹별 봇 API 소비.
//  - DM 탭 진입 시 GET /chat/bot/rooms → 내 활성 그룹별 봇 방 요약 목록(인스타그램 DM 스타일).
//  - 읽음 갱신(FR-6): 백엔드가 방 GET 시 읽음처리 → 목록은 진입·방 복귀·포그라운드 복귀 시 재조회.
//  - 미읽음 배지(FR-10): rooms 중 unread==true 존재 여부 → 하단 DM 탭 빨간 점(알림 배지 패턴 동치).
//  - 빈/로딩/에러 상태(FR-7): 그룹 0개는 .loaded([])(에러 아님). 조회 실패(목록 없음)는 .error(재시도).
//
// NotificationInboxViewModel.LoadState/formatTime 선례를 미러한다(상태 분기·상대시각 표현 동일).
@MainActor
final class DMListViewModel: ObservableObject {

    /// 목록 로드 상태(로딩/빈/에러+재시도/목록). View 가 분기 렌더.
    enum LoadState: Equatable {
        case idle
        case loading
        case loaded([BotRoomSummary])
        case error(String)
    }

    // MARK: - 게시 상태

    /// 목록 로드 상태. 진입(load)/방 복귀·포그라운드(refresh)/재시도로 갱신.
    @Published private(set) var loadState: LoadState = .idle

    // MARK: - 파생 상태

    /// FR-10 배지 소스 — 안 읽은 방이 1개 이상이면 true. .loaded 상태의 rooms 기준(그 외 false).
    /// 탭 진입 전에도 관찰 가능(알림 배지 unreadCount 패턴 동치) — 포그라운드 refresh 가 미리 채운다.
    var hasUnread: Bool {
        if case let .loaded(rooms) = loadState { return rooms.contains { $0.unread } }
        return false
    }

    // MARK: - 의존성

    private let chatAPI: ChatAPIProtocol

    // MARK: - 내부 상태

    /// load()/refresh() 동시 진입 가드(NotificationInbox isLoading 패턴). 중복 botRooms 차단.
    private var isFetching = false

    init(chatAPI: ChatAPIProtocol) {
        self.chatAPI = chatAPI
    }

    // MARK: - 로드(FR-1/7)

    /// 탭 진입(보이는 로드): 최초/에러 후엔 .loading 표시, 이미 .loaded 면 깜빡임 없이 갱신.
    func load() async { await fetch(showLoading: true) }

    /// 방 복귀·포그라운드·배지 갱신(무음): 스피너 없이 rooms 갱신, 실패 시 기존 목록 유지(FR-6).
    func refresh() async { await fetch(showLoading: false) }

    /// botRooms 조회 → loadState 갱신. showLoading 으로 보이는 로드/무음 갱신을 구분한다.
    /// - 보이는 로드 + 아직 목록 없음(.idle/.error) → .loading 표시 후 .loaded/.error.
    /// - 이미 .loaded → .loading 으로 덮지 않음(재진입 깜빡임 방지). 실패해도 화면 유지.
    /// - 무음 갱신(refresh) → 성공 시 .loaded 갱신, 실패는 조용히 무시(기존 유지).
    private func fetch(showLoading: Bool) async {
        // in-flight 가드: load()/refresh() 동시 진입 시 중복 botRooms 차단.
        if isFetching { return }
        isFetching = true
        defer { isFetching = false }

        // 이미 목록을 보여주는 중이면 .loading 으로 덮지 않는다 — 재진입/무음 갱신 시 빈 로딩 깜빡임 방지.
        // 최초 진입(.idle)·에러 후 재시도(.error)에서만 로딩 표시(보이는 로드 한정).
        if showLoading, case .loaded = loadState {} else if showLoading {
            loadState = .loading
        }
        do {
            let rooms = try await chatAPI.botRooms()
            loadState = .loaded(rooms)
        } catch {
            // 이미 목록을 보여주는 중(.loaded)이면 화면 유지(무음 갱신 실패는 무시 — 포그라운드/방복귀 refresh 부가동작).
            // 아직 목록이 없으면(.idle/.loading/.error) — refresh 무음이라도 — 에러를 노출해 재시도 경로를 연다.
            //  근거: .idle 가 로딩 스피너로 렌더되므로, load()가 in-flight 가드로 조기 반환된 직후 무음 refresh 가
            //  실패하면 .idle 가 잔존해 스피너가 무한 고정된다(소프트락). 미로드 상태면 실패를 에러로 surface 한다.
            if case .loaded = loadState {} else {
                loadState = .error("대화 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.")
            }
        }
    }

    // MARK: - 시간 포맷(NotificationInboxViewModel.formatTime 미러)

    /// ISO-8601 lastAt → 상대 시간 문구(방금 전/N분 전/N시간 전/N일 전/날짜).
    /// NotificationInboxViewModel.formatTime 과 동일 로직(소수초 ISO 파서 2종 + 상대 표현).
    /// 파싱 실패 시 "". (중복 인지 — 후속 공용 유틸 추출 가능, 범위 외.)
    static func formatTime(_ iso: String, now: Date = Date()) -> String {
        guard let date = isoFormatter.date(from: iso) ?? isoFormatterNoFraction.date(from: iso) else {
            return ""
        }
        let diffMin = Int((now.timeIntervalSince(date) / 60).rounded(.down))
        if diffMin < 1 { return "방금 전" }
        if diffMin < 60 { return "\(diffMin)분 전" }
        let diffHour = diffMin / 60
        if diffHour < 24 { return "\(diffHour)시간 전" }
        let diffDay = diffHour / 24
        if diffDay < 7 { return "\(diffDay)일 전" }
        return dateFormatter.string(from: date)
    }

    /// 소수 초(밀리초) 포함 ISO-8601 파서(Jackson 직렬화 대응).
    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    /// 소수 초 미포함 ISO-8601 파서(폴백).
    private static let isoFormatterNoFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    /// 7일 초과 시 표시할 날짜 포맷(웹 toLocaleDateString('ko-KR') 대응).
    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ko_KR")
        f.dateStyle = .medium
        f.timeStyle = .none
        return f
    }()
}
