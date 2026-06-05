import Foundation

// 알림함 ViewModel(설계 §7, FR-17~22, BR-4/BR-6).
// frontend/src/app/map/_components/notifications/NotificationPanel.tsx 의 목록/상세 흐름 이식.
//
// 책임:
//  - 진입 load(): didReadAll=false 리셋 → list 조회(FR-19) + read-all 1회(FR-21/BR-4).
//    탭 진입마다 read-all 1회 보장(재진입·새 알림 수신 시 읽음 누락 방지). read-all 성공 시 didReadAll=true + 로컬 unreadCount=0 낙관 갱신. 실패는 조용히 무시(에러 미노출·무재시도, BR-4).
//  - 미읽음 수는 서버 unreadCount 사용(>0 → 빨간 점, FR-22).
//  - onForeground(): list 만 재조회(read-all 미호출, FR-19).
//  - in-flight 가드(isLoading): load()/onForeground() 동시 호출 시 list 중복 차단(cross-review #1).
//  - selectItem(_): detail(id:) 로드 → 핀 목록 표시.
//  - flyToPin(_): deepLinkRouter.pending=.pin(pinId)(FR-20). soft delete 핀은 flyTo 비활성(호출 안 함).
//  - 목록 조회 실패 → loadState=.error(재시도 버튼, BR-6).
@MainActor
final class NotificationInboxViewModel: ObservableObject {

    /// 목록 로드 상태(로딩/빈/에러+재시도/목록). View 가 분기 렌더.
    enum LoadState: Equatable {
        case idle
        case loading
        case loaded([NotificationItem])
        case error(String)
    }

    // MARK: - 게시 상태

    /// 목록 로드 상태. 진입/포그라운드/재시도로 갱신.
    @Published private(set) var loadState: LoadState = .idle
    /// 서버 미읽음 수(FR-22). >0 → 탭 빨간 점. read-all 성공 시 0 낙관 갱신.
    @Published private(set) var unreadCount = 0
    /// 선택된 알림 상세(연결 핀 목록). nil 이면 목록 표시.
    @Published private(set) var activeDetail: NotificationDetail?
    /// 상세 로드 진행 중(인디케이터). 진입 직후 true.
    @Published private(set) var isDetailLoading = false

    // MARK: - 의존성

    private let api: NotificationAPIProtocol
    private let deepLinkRouter: DeepLinkRouter

    // MARK: - 내부 상태

    /// read-all 1회 보장 플래그(BR-4). load() 진입 시 false 로 리셋 → 탭 진입(load)당 read-all 1회.
    private var didReadAll = false
    /// list 조회 in-flight 가드(cross-review #1). load()/onForeground() 동시 진입 시 중복 list 차단.
    private var isLoading = false

    init(api: NotificationAPIProtocol, deepLinkRouter: DeepLinkRouter) {
        self.api = api
        self.deepLinkRouter = deepLinkRouter
    }

    // MARK: - 진입 로드(FR-19/FR-21, BR-4)

    /// 진입: didReadAll 리셋 → list 조회(FR-19) + read-all 1회(FR-21/BR-4).
    /// 탭 진입마다 read-all 1회(재진입 읽음 누락 방지). 목록 실패 → .error(BR-6). read-all 실패는 조용히 무시(에러 미노출·무재시도).
    func load() async {
        // in-flight 가드: load()/onForeground() 동시 진입 시 중복 list 차단(cross-review #1).
        if isLoading { return }
        isLoading = true
        defer { isLoading = false }
        // 탭 진입마다 read-all 1회 보장 — 재진입·새 알림 수신 시 읽음 누락 방지(Gemini HIGH).
        didReadAll = false
        // 이미 목록을 보여주는 중(.loaded)이면 .loading 으로 덮지 않는다 — 재시도/재진입 시 빈 로딩 깜빡임 방지.
        // 최초 진입(.idle)·에러 후 재시도(.error)에서만 로딩 표시.
        if case .loaded = loadState {} else {
            loadState = .loading
        }
        do {
            let response = try await api.list()
            unreadCount = response.unreadCount
            loadState = .loaded(response.items)
        } catch {
            loadState = .error("알림을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.")
            return
        }

        // read-all 은 목록을 성공적으로 보여준 뒤 1회만 시도. 실패는 무시.
        if !didReadAll {
            do {
                _ = try await api.readAll()
                didReadAll = true
                unreadCount = 0
            } catch {
                // BR-4: read-all 실패는 조용히 무시(에러 미노출·무재시도).
            }
        }
    }

    // MARK: - 포그라운드 갱신(FR-19)

    /// 포그라운드 복귀: list 만 재조회(read-all 미호출). unreadCount 갱신.
    /// 실패 시 기존 목록 유지(조용히 무시) — 포그라운드 갱신은 부가 동작이므로 .error 로 덮지 않음.
    func onForeground() async {
        // in-flight 가드: load() 와 동시 호출 시 중복 list 차단(cross-review #1).
        if isLoading { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let response = try await api.list()
            unreadCount = response.unreadCount
            loadState = .loaded(response.items)
        } catch {
            // 포그라운드 갱신 실패는 기존 화면 유지(무시).
        }
    }

    // MARK: - 상세 선택(FR-20)

    /// 알림 행 탭: detail(id:) 로드 → activeDetail 세팅(핀 목록 표시).
    /// 실패 시 activeDetail 미세팅(목록 유지).
    func selectItem(_ item: NotificationItem) async {
        isDetailLoading = true
        defer { isDetailLoading = false }
        do {
            activeDetail = try await api.detail(id: item.id)
        } catch {
            activeDetail = nil
        }
    }

    /// 상세 → 목록 복귀.
    func clearDetail() {
        activeDetail = nil
    }

    // MARK: - 핀 이동(FR-20)

    /// 핀 선택 → 딥링크 pending 세팅(MainTabView 가 지도 탭 전환 + flyTo).
    /// soft delete 핀은 좌표가 유효하지 않으므로 flyTo 비활성(호출 안 함).
    func flyToPin(_ pin: NotificationPinItem) {
        guard !pin.deleted else { return }
        deepLinkRouter.pending = .pin(pinId: pin.pinId)
    }

    // MARK: - 시간 포맷(웹 formatTime 이식)

    /// ISO-8601 createdAt → 상대 시간 문구(방금 전/N분 전/N시간 전/N일 전/날짜).
    /// frontend NotificationItem.tsx formatTime 이식. 파싱 실패 시 "".
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

    // MARK: - 날짜 그룹(웹 NotificationPanel.groupByDate 이식)

    /// 날짜 버킷 1개(라벨 + 해당 알림들). 섹션 헤더 렌더에 사용.
    struct NotificationDateGroup: Identifiable {
        let label: String
        let items: [NotificationItem]
        var id: String { label }
    }

    /// createdAt 을 오늘/어제/이번 주/이전 4개 버킷으로 그룹화.
    /// frontend NotificationPanel.tsx groupByDate 이식(순서 보존, 빈 버킷 제외).
    /// 파싱 실패 항목은 "이전" 버킷으로 폴백(웹은 NaN → '이전' 분기와 동치).
    static func groupByDate(_ items: [NotificationItem], now: Date = Date()) -> [NotificationDateGroup] {
        let calendar = Calendar.current
        let todayStart = calendar.startOfDay(for: now)
        let yesterdayStart = calendar.date(byAdding: .day, value: -1, to: todayStart) ?? todayStart
        let weekStart = calendar.date(byAdding: .day, value: -7, to: todayStart) ?? todayStart

        let order = ["오늘", "어제", "이번 주", "이전"]
        var buckets: [String: [NotificationItem]] = [:]

        for item in items {
            let date = isoFormatter.date(from: item.createdAt)
                ?? isoFormatterNoFraction.date(from: item.createdAt)
            let label: String
            if let date {
                if date >= todayStart { label = "오늘" }
                else if date >= yesterdayStart { label = "어제" }
                else if date >= weekStart { label = "이번 주" }
                else { label = "이전" }
            } else {
                label = "이전"
            }
            buckets[label, default: []].append(item)
        }

        return order.compactMap { label in
            guard let group = buckets[label] else { return nil }
            return NotificationDateGroup(label: label, items: group)
        }
    }

    // MARK: - 본문 카피(웹 NotificationItem.tsx actorLabel/buildPlaceSummary 이식)

    /// Row1 행위자 카피. VISIT_DETECTED 는 본인/짝꿍 분기 없이 통일 문구(웹 Phase 10 정책).
    /// 그 외는 "{닉네임}님이 장소를 저장했어요."(닉네임 미전달 시 "짝꿍" 폴백).
    static func actorLabel(for item: NotificationItem) -> String {
        switch item.type {
        case .VISIT_DETECTED:
            return "추억이 한 곳 더 쌓였어요"
        case .MANUAL_PIN, .CHATBOT_PINS:
            let nickname = item.registeredByNickname ?? "짝꿍"
            return "\(nickname)님이 장소를 저장했어요."
        }
    }

    /// Row2 장소 요약 카피. CHATBOT_PINS 는 wishCount/reelCount 가 모두 채워졌을 때
    /// "위시 N곳, 발견 M곳" 분리 표기, 누락 시 totalPinCount 기반 기본 요약으로 폴백.
    /// frontend NotificationItem.tsx buildPlaceSummary 이식.
    static func placeSummary(for item: NotificationItem) -> String {
        if item.type == .CHATBOT_PINS,
           let wish = item.wishCount,
           let reel = item.reelCount,
           wish + reel > 0 {
            var parts: [String] = []
            if wish > 0 { parts.append("위시 \(wish)곳") }
            if reel > 0 { parts.append("발견 \(reel)곳") }
            return parts.joined(separator: ", ")
        }
        return item.totalPinCount <= 1
            ? item.firstPlaceName
            : "\(item.firstPlaceName) 외 \(item.totalPinCount - 1)곳"
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
