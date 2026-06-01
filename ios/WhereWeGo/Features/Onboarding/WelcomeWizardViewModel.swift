import Foundation

// 환영 위저드 ViewModel(설계 §11, FR-16, BR-9). 2스텝(챗봇 제거).
// 비동기 API(초대 링크 발급) + 에러 + 로딩 → VM 분리.
@MainActor
final class WelcomeWizardViewModel: ObservableObject {
    enum Step {
        case loading   // 그룹 유무 결정 전(깜빡임 방지, AC-19)
        case group     // 스텝 1/2
        case invite    // 스텝 2/2
    }

    @Published private(set) var step: Step = .loading
    @Published private(set) var isLoading = false
    @Published private(set) var shareText: String?
    @Published private(set) var errorMessage: String?
    @Published var copied = false

    private let groupAPI: GroupAPIProtocol
    /// 라우터가 이미 조회한 활성 그룹(중복 조회 제거). 라우터 전달이 없으면 nil → start 에서 재조회.
    private let initialGroup: ActiveGroup?
    private var inviteLoaded = false
    /// loadInviteLink 가 재사용할, 확보된 활성 그룹(start 에서 세팅).
    private var activeGroup: ActiveGroup?

    init(groupAPI: GroupAPIProtocol, initialGroup: ActiveGroup? = nil) {
        self.groupAPI = groupAPI
        self.initialGroup = initialGroup
        self.activeGroup = initialGroup
    }

    /// 위저드 진입 시 자동스킵 판단(AC-19): 그룹 있으면 스텝1 스킵하고 스텝2부터.
    /// 라우터가 initialGroup 을 전달하면 재조회 없이 즉시 결정(중복 호출 제거).
    func start() async {
        let group: ActiveGroup?
        if let initialGroup {
            group = initialGroup
        } else {
            group = try? await groupAPI.myActiveGroup()
            activeGroup = group
        }
        if group != nil {
            step = .invite
            await loadInviteLink()
        } else {
            step = .group
        }
    }

    /// 스텝1 → 스텝2 진행(그룹 단계 건너뛰기/다음).
    func goToInvite() async {
        step = .invite
        await loadInviteLink()
    }

    /// 스텝2 진입 시 1회만 초대 링크 자동 발급.
    func loadInviteLink() async {
        guard !inviteLoaded else { return }
        inviteLoaded = true
        isLoading = true
        errorMessage = nil
        do {
            // start 에서 확보한 그룹 우선 재사용. 없으면(스텝1→다음 경로) 재조회.
            let resolved: ActiveGroup?
            if let activeGroup {
                resolved = activeGroup
            } else {
                resolved = try await groupAPI.myActiveGroup()
            }
            guard let group = resolved else {
                errorMessage = "그룹이 없어요. 먼저 그룹을 만들어주세요."
                isLoading = false
                return
            }
            activeGroup = group
            let link = try await groupAPI.issueInviteLink(groupId: group.groupId)
            // shareUrl 우선, 없으면 token 폴백.
            shareText = link.shareUrl ?? link.token
            isLoading = false
        } catch {
            errorMessage = "초대 링크를 만들지 못했어요"
            isLoading = false
        }
    }

    /// 표시된 초대 텍스트를 클립보드로 복사.
    func copyLink(pasteboardSetter: (String) -> Void) {
        guard let text = shareText, !text.isEmpty else { return }
        pasteboardSetter(text)
        copied = true
    }
}
