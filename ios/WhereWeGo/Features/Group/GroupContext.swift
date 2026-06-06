import Foundation

// 활성 그룹 컨텍스트(FR-5, BR-1/2). MainTabView 가 @StateObject 로 소유한다.
//
// 책임:
//  - 현재 활성 그룹(activeGroupId/activeGroupName)을 단일 소스로 보유 → 상단 그룹 칩이 관찰.
//  - 그룹 전환 시트(GroupSwitcherSheet) 진입 시 listMyGroups 로 목록 로드(BR-6 — 진입 시 호출, 앱 시작 시 미리 X).
//  - 전환 선택 시 활성 그룹 갱신 + 지도/채팅 재로드 콜백 호출(전환 동기화는 MainTabView 가 연결).
//
// 백엔드에 "활성 그룹 전환" 엔드포인트는 없다 — 핀 API 가 groupId 파라미터화돼 있으므로
// 전환은 클라이언트 측 groupId 선택으로 처리한다(영속화는 범위 밖, 앱 재시작 시 myActiveGroup 기본값).
@MainActor
final class GroupContext: ObservableObject {

    /// 그룹 전환 시트 목록 로드 상태(BR-6 스피너).
    enum ListState: Equatable {
        case idle
        case loading
        case loaded
        case error(String)
    }

    // MARK: - 게시 상태

    /// 현재 활성 그룹 id. nil 이면 그룹 없음(칩 "그룹 없음", BR-2). 최초 진입은 bootstrap 이 myActiveGroup 으로 시드.
    @Published private(set) var activeGroupId: Int?
    /// 현재 활성 그룹명(칩 표시). 그룹 없으면 nil → 칩이 "그룹 없음" 표기.
    @Published private(set) var activeGroupName: String?
    /// 그룹 전환 시트 목록(listMyGroups 결과). 시트 진입 시 로드.
    @Published private(set) var groups: [GroupSummary] = []
    /// 시트 목록 로드 상태.
    @Published private(set) var listState: ListState = .idle

    // MARK: - 의존성

    private let groupAPI: GroupAPIProtocol

    init(groupAPI: GroupAPIProtocol) {
        self.groupAPI = groupAPI
    }

    // MARK: - 초기 활성 그룹 시드(최초 진입)

    /// 앱 진입 시 1회 — myActiveGroup 으로 활성 그룹을 시드한다(앱 재시작 기본값, 영속화 범위 밖).
    /// 이미 활성 그룹이 정해져 있으면(전환 후) 덮어쓰지 않고 현재 activeGroupId 를 그대로 반환한다.
    ///
    /// 반환값은 시드 후 확정된 활성 그룹 id(nil=그룹 0개 또는 네트워크 에러). MainTabView 가 이 결과를
    /// 지도 초기 로드(load(groupId:) / loadEmpty)로 주입해 활성 그룹 해석을 GroupContext 단일 소스로 모은다.
    ///
    /// nil(그룹 0개, BR-2)과 네트워크 에러를 do/catch 로 분리한다:
    ///  - nil → 그룹 없음(칩 "그룹 없음"). 시드 없이 반환.
    ///  - catch(네트워크 에러) → 조용히 무시하되 시드를 남기지 않아(activeGroupId 유지=nil)
    ///    다음 진입/전환에서 재시도 가능하게 둔다(현 동작과 기능 동일 — 의도 명시).
    @discardableResult
    func bootstrap() async -> Int? {
        guard activeGroupId == nil else { return activeGroupId }
        do {
            guard let group = try await groupAPI.myActiveGroup() else {
                // 그룹 0개(BR-2) — 칩 "그룹 없음". 시드 없음.
                return nil
            }
            activeGroupId = group.groupId
            activeGroupName = group.name
            return group.groupId
        } catch {
            // 네트워크 에러 — 시드를 남기지 않아 다음 진입/전환에서 재시도 가능(best-effort 시드).
            return nil
        }
    }

    // MARK: - 활성 그룹 롤백(FR-5 전환 실패 복원)

    /// 전환 실패 시 호출 — 이전 (id, name) 으로 활성 그룹을 복원한다(칩 복원, FR-5).
    /// MainTabView.switchActiveGroup 이 전환 전 값을 보관했다가 지도 재로드 실패 시 이 메서드로 롤백한다.
    func rollbackActiveGroup(toId id: Int?, name: String?) {
        activeGroupId = id
        activeGroupName = name
    }

    // MARK: - 시트 목록 로드(BR-6)

    /// 그룹 전환 시트 진입 시 호출 — listMyGroups 로 목록 로드(로딩 스피너 → 목록/에러).
    func loadGroups() async {
        listState = .loading
        do {
            let fetched = try await groupAPI.listMyGroups()
            groups = fetched
            listState = .loaded
            // 활성 그룹명이 목록과 어긋났으면(예: 이름 변경) 목록 기준으로 보정한다.
            if let id = activeGroupId, let match = fetched.first(where: { $0.groupId == id }) {
                activeGroupName = match.name
            }
        } catch {
            listState = .error("그룹 목록을 불러오지 못했어요. 다시 시도해 주세요.")
        }
    }

    // MARK: - 활성 그룹 전환(FR-5)

    /// 활성 그룹을 선택 그룹으로 갱신(칩 텍스트 갱신). 실제 지도/채팅 재로드는 호출부(MainTabView)가 콜백으로 연결한다.
    /// 동일 그룹 재선택은 변경 없음(no-op) — 호출부가 시트만 닫는다.
    func setActiveGroup(_ group: GroupSummary) {
        activeGroupId = group.groupId
        activeGroupName = group.name
    }

    /// 현재 활성 그룹과 동일한지(시트에서 체크 표시·재로드 스킵 판단, FR-11).
    func isActive(_ group: GroupSummary) -> Bool {
        activeGroupId == group.groupId
    }
}
