import Foundation

// 그룹 컨텍스트 전역 상태(설계 §1·§2, GM-2 iOS 그룹 다중화, FR-2/FR-5).
// 1인 N그룹 지원(GM-1 백엔드 제약 해제)에 맞춰, 단일 ActiveGroup 가정을 그룹 목록 + 현재/마지막 그룹으로 확장한다.
//
// 2레벨 지도 탭의 상태 소유자:
//  - currentGroupId == nil → 레벨0(GroupListView, 그룹 목록)
//  - currentGroupId != nil → 레벨1(MapView, 그 그룹 지도)
//  - lastGroupId(UserDefaults persist) — 마지막 본 그룹. 다른 탭 갔다 와도 그 그룹 지도로 직행(AC-3).
//
// 그룹 전환 시 지도 재로드는 onGroupChanged 콜백으로 약결합 배선한다(MainTabView 가 MapViewModel.switchTo 로 연결).
//  GroupContext 가 MapViewModel 을 직접 참조하지 않아 의존 순환/수명 결합을 피한다(MapViewModel 은 MainTabView 소유).
@MainActor
final class GroupContext: ObservableObject {

    /// 내가 속한 그룹 목록(레벨0 GroupListView 소스). bootstrap/refresh 로 갱신.
    @Published private(set) var groups: [GroupSummary] = []
    /// 현재 진입한 그룹 id. nil = 그룹 목록(레벨0), 값 = 그 그룹 지도(레벨1).
    @Published var currentGroupId: Int?

    // MARK: - 의존성

    private let groupAPI: GroupAPIProtocol
    /// 그룹 전환/진입 시 지도 재로드 트리거(MainTabView 가 MapViewModel.switchTo 로 배선). 미배선이면 no-op.
    private let onGroupChanged: (Int) -> Void
    /// lastGroupId 저장소(테스트에서 별도 suite 로 교체 가능). UserDefaults 는 thread-safe.
    private let store: UserDefaults

    init(
        groupAPI: GroupAPIProtocol,
        store: UserDefaults = .standard,
        onGroupChanged: @escaping (Int) -> Void = { _ in }
    ) {
        self.groupAPI = groupAPI
        self.store = store
        self.onGroupChanged = onGroupChanged
    }

    // MARK: - 마지막 본 그룹(UserDefaults persist, AC-3)

    /// UserDefaults 키(OnboardingFlags 패턴 동치 — 단순 키 래핑).
    private enum Key {
        static let lastGroupId = "lastGroupId"
    }

    /// 마지막 본 그룹 id. 미저장 시 nil(UserDefaults.integer 는 0 반환이므로 object 존재로 판별).
    private(set) var lastGroupId: Int? {
        get { store.object(forKey: Key.lastGroupId) as? Int }
        set {
            if let newValue {
                store.set(newValue, forKey: Key.lastGroupId)
            } else {
                store.removeObject(forKey: Key.lastGroupId)
            }
        }
    }

    // MARK: - 부트스트랩(진입 1회, 설계 §1)

    /// listMyGroups → groups. currentGroupId = lastGroupId(목록에 존재할 때만) else nil(레벨0 진입).
    /// 실패(네트워크/서버)는 무손상 — groups 빈 채로 두고 currentGroupId nil(그룹 목록 빈 상태로 폴백).
    func bootstrap() async {
        let fetched = (try? await groupAPI.listMyGroups()) ?? []
        groups = fetched
        if let last = lastGroupId, fetched.contains(where: { $0.groupId == last }) {
            currentGroupId = last   // 마지막 본 그룹이 여전히 유효 → 그 그룹 지도로 직행(AC-3)
        } else {
            currentGroupId = nil    // 무효(탈퇴/없음)면 그룹 목록(레벨0)
        }
    }

    /// 그룹 목록만 재조회(전환 시트 등에서 최신화). currentGroupId 는 유지.
    func refresh() async {
        groups = (try? await groupAPI.listMyGroups()) ?? []
    }

    // MARK: - 진입/전환(설계 §1)

    /// 그룹 목록(레벨0)에서 그룹 선택 → 레벨1 진입. lastGroupId 저장 + 지도 재로드 트리거.
    func enterGroup(_ id: Int) {
        currentGroupId = id
        lastGroupId = id
        onGroupChanged(id)
    }

    /// 그룹 지도(레벨1)에서 다른 그룹으로 전환. enterGroup 과 동치(현재 그룹 갱신 + 지도 재로드).
    func switchGroup(_ id: Int) {
        guard id != currentGroupId else { return }   // 동일 그룹 재선택은 no-op(불필요 재로드 방지)
        enterGroup(id)
    }

    /// 그룹 지도(레벨1) → 그룹 목록(레벨0). lastGroupId 는 유지(탭 복귀 시 그 그룹 직행 보장, AC-3/AC-4).
    func backToList() {
        currentGroupId = nil
    }
}
