import Foundation

// 채팅(그룹 방) 목록 ViewModel(GC-2 FR-GC2-1). #118 그룹 채팅 API(GET /chat/groups) 소비.
//  - 탭 진입 시 groupRooms → 내 활성 그룹별 그룹 채팅방 요약(인스타그램 DM 스타일).
//  - 멤버별 unread(FR-GC2-1): 백엔드 hasUnread 그대로 신뢰. 하단 탭 빨간 점(배지) 소스.
//  - 빈/로딩/에러: 그룹 0개는 .loaded([])(에러 아님). 조회 실패(목록 없음)는 .error(재시도).
//  - currentUser: 방 진입(isMine 판정)·탭바 프사(IG-1) 대비 워밍업 로드(미리보기 "나:" 프리픽스는 IG-1 에서 제거).
//
// NotificationInboxViewModel.LoadState/formatTime 선례를 미러한다(상태 분기·상대시각 표현 동일).
@MainActor
final class DMListViewModel: ObservableObject {

    /// 목록 로드 상태(로딩/빈/에러+재시도/목록). View 가 분기 렌더.
    enum LoadState: Equatable {
        case idle
        case loading
        case loaded([GroupRoomSummary])
        case error(String)
    }

    // MARK: - 게시 상태

    @Published private(set) var loadState: LoadState = .idle

    // MARK: - 파생 상태

    /// 배지 소스(FR-GC2-1) — 안 읽은 방이 1개 이상이면 true. .loaded 상태의 rooms 기준.
    var hasUnread: Bool {
        if case let .loaded(rooms) = loadState { return rooms.contains { $0.hasUnread } }
        return false
    }

    // MARK: - 의존성

    private let chatAPI: ChatAPIProtocol
    private let currentUser: CurrentUser

    // MARK: - 내부 상태

    private var isFetching = false

    init(chatAPI: ChatAPIProtocol, currentUser: CurrentUser) {
        self.chatAPI = chatAPI
        self.currentUser = currentUser
    }

    // MARK: - 로드

    /// 탭 진입(보이는 로드): 최초/에러 후엔 .loading 표시, 이미 .loaded 면 깜빡임 없이 갱신.
    func load() async { await fetch(showLoading: true) }

    /// 방 복귀·포그라운드·배지 갱신(무음): 스피너 없이 rooms 갱신, 실패 시 기존 목록 유지.
    func refresh() async { await fetch(showLoading: false) }

    /// groupRooms 조회 → loadState 갱신. 방 진입(isMine 판정)·탭바 프사 대비 currentUser 선행 확보.
    private func fetch(showLoading: Bool) async {
        if isFetching { return }
        isFetching = true
        defer { isFetching = false }

        // 방 화면(GroupChatViewModel)의 isMine 판정과 탭바 내 프사(IG-1)에 내 id/프사가 필요 —
        // 미확보면 1회 로드. 목록 조회와 무관한 워밍업이라 비동기 분리(PR#124 리뷰 — 목록 렌더 병목 방지).
        // currentUser 는 앱 수명 싱글톤이라 fire-and-forget Task 가 VM 수명과 무관하게 안전하다.
        if currentUser.id == nil {
            Task {
                await currentUser.load()
            }
        }

        // 이미 목록을 보여주는 중이면 .loading 으로 덮지 않는다(재진입/무음 갱신 깜빡임 방지).
        if showLoading, case .loaded = loadState {} else if showLoading {
            loadState = .loading
        }
        do {
            let rooms = try await chatAPI.groupRooms()
            loadState = .loaded(rooms)
        } catch {
            // 목록 표시 중(.loaded)이면 화면 유지(무음 갱신 실패 무시). 미로드면 에러 노출(스피너 고정 방지).
            if case .loaded = loadState {} else {
                loadState = .error("대화 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.")
            }
        }
    }

    // MARK: - 시간 포맷(NotificationInboxViewModel.formatTime 미러)

    /// ISO-8601 lastAt → 상대 시간 문구(방금 전/N분 전/N시간 전/N일 전/날짜).
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

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static let isoFormatterNoFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ko_KR")
        f.dateStyle = .medium
        f.timeStyle = .none
        return f
    }()
}
