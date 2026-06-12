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
//  - selectItem(_): 상세 화면 폐기(IG-2 FR-4). detail(id:) 로 유효 핀을 추려 .pinFocus 딥링크 → 지도 탭 직행.
//      접근 불가 그룹/삭제된 장소/조회 실패는 infoMessage 토스트로 안내(딥링크 미발화).
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
    /// 행 탭 안내 토스트 문구(IG-2 FR-4). 접근 불가 그룹/삭제된 장소/조회 실패 시 세팅. View 가 하단 캡슐로 표시 후 nil 리셋.
    @Published var infoMessage: String?
    /// 행 탭 라우팅 진행 중(IG-2 FR-4). 중복 탭 가드 겸 인디케이터(detail 조회 동안 true).
    @Published private(set) var isRouting = false

    // MARK: - 의존성

    private let api: NotificationAPIProtocol
    private let deepLinkRouter: DeepLinkRouter
    /// 그룹 접근 가능 여부 판정(IG-2 FR-4). GroupContext 직접 주입 대신 클로저 — 단위 테스트 구성 부담 제거.
    private let isGroupAccessible: (Int) -> Bool

    // MARK: - 내부 상태

    /// read-all 1회 보장 플래그(BR-4). load() 진입 시 false 로 리셋 → 탭 진입(load)당 read-all 1회.
    private var didReadAll = false
    /// list 조회 in-flight 가드(cross-review #1). load()/onForeground() 동시 진입 시 중복 list 차단.
    private var isLoading = false

    init(
        api: NotificationAPIProtocol,
        deepLinkRouter: DeepLinkRouter,
        isGroupAccessible: @escaping (Int) -> Bool = { _ in true }
    ) {
        self.api = api
        self.deepLinkRouter = deepLinkRouter
        self.isGroupAccessible = isGroupAccessible
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

    // MARK: - 행 탭 라우팅(IG-2 FR-4)

    /// 알림 행 탭: 그룹 접근 가드 → detail(id:) 로 유효 핀 추출 → .pinFocus 딥링크(지도 탭 직행).
    ///  접근 불가 그룹/유효 핀 0개/조회 실패는 infoMessage 토스트로 안내(딥링크 미발화). 상세 화면 폐기.
    func selectItem(_ item: NotificationItem) async {
        // 중복 탭 가드(라우팅 인디케이터 겸용).
        guard !isRouting else { return }
        isRouting = true
        defer { isRouting = false }

        // 1) 그룹 접근 가드: groupId 부재(구서버) 또는 떠난 그룹 → 안내.
        guard let groupId = item.groupId, isGroupAccessible(groupId) else {
            infoMessage = "더 이상 함께하지 않는 그룹이에요"
            return
        }

        // 2) detail 조회 → 유효(미삭제·좌표 보유) 핀만 추출.
        let pinIds: [Int]
        do {
            let detail = try await api.detail(id: item.id)
            pinIds = detail.pins
                .filter { !$0.deleted && $0.latitude != nil && $0.longitude != nil }
                .map { $0.pinId }
        } catch {
            infoMessage = "알림을 여는 데 실패했어요. 다시 시도해 주세요"
            return
        }

        // 3) 유효 핀 0개 → 안내. ≥1 → .pinFocus 딥링크(MainTabView 가 지도 탭 전환 + 그룹 + 포커스).
        guard !pinIds.isEmpty else {
            infoMessage = "삭제된 장소예요"
            return
        }
        deepLinkRouter.pending = .pinFocus(groupId: groupId, pinIds: pinIds)
    }

    // MARK: - 섹션 그룹핑(IG-2 FR-4)

    /// 피드 섹션 구분(오늘/이번 주/이전). createdAt 파싱 실패 시 .earlier(맨 아래)로 폴백.
    enum Section: Int {
        case today
        case thisWeek
        case earlier

        /// 섹션 헤더 표기.
        var title: String {
            switch self {
            case .today: return "오늘"
            case .thisWeek: return "이번 주"
            case .earlier: return "이전"
            }
        }
    }

    /// ISO-8601 createdAt → 섹션 키. 오늘(같은 달력일) / 이번 주(7일 이내) / 이전.
    /// 파싱 실패 시 .earlier(맨 아래). View 가 ForEach 그룹핑 + 빈 섹션 헤더 생략에 사용.
    static func sectionKey(_ iso: String, now: Date = Date()) -> Section {
        guard let date = isoFormatter.date(from: iso) ?? isoFormatterNoFraction.date(from: iso) else {
            return .earlier
        }
        let calendar = Calendar.current
        if calendar.isDate(date, inSameDayAs: now) {
            return .today
        }
        if now.timeIntervalSince(date) < 7 * 24 * 60 * 60 {
            return .thisWeek
        }
        return .earlier
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
