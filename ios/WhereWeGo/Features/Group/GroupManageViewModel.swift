import Foundation

// 그룹 관리 ViewModel(D단계, IA 재설계 §3.4). MapView 상단 ⋯ → 그룹관리 시트의 상태/액션 소유.
// 그룹원 목록 조회 · 그룹명 수정(모든 멤버) · 그룹 탈퇴(모든 멤버) · 그룹 삭제(방장만)를 주입 의존(groupAPI)에 위임한다.
// busy 가드 + errorMessage + defer 해제 패턴은 MyInfoViewModel 을 미러한다(@MainActor 보장).
//
// 방장 판정은 서버가 멤버 목록에 isOwner 를 마킹(가입 순 첫 항목)하므로, 내 userId(currentUser.id) 가
// isOwner 인 멤버인지로 판단한다. 멤버 미로드/내 정보 미확인 시 false(삭제 버튼 미노출 — 안전 측).
//
// 삭제/탈퇴 성공 후 화면 전환(시트 닫기 + 레벨0 복귀)은 호출측(MapView)이 onExit 콜백으로,
// 이름 수정 성공 후 상단 그룹명 갱신은 onRenamed 콜백으로 처리한다(VM 은 콜백 호출만 — 화면 결합 회피).
@MainActor
final class GroupManageViewModel: ObservableObject {

    /// 멤버 목록 로드 상태(로딩/완료/에러). members 자체는 별도 @Published 로 보관하고,
    /// 본 enum 은 View 의 로딩 인디케이터/에러 분기 렌더에만 쓴다.
    enum LoadState: Equatable {
        case idle
        case loading
        case loaded
        case error(String)
    }

    @Published private(set) var members: [GroupMemberItem] = []
    /// 그룹명 편집 초안(TextField 바인딩). 초기값은 주입된 현재 그룹명.
    @Published var groupNameDraft: String
    @Published private(set) var loadState: LoadState = .idle
    /// 이름수정/삭제/탈퇴 진행 중. 중복 호출 가드 + 버튼 비활성에 사용.
    @Published private(set) var isBusy = false
    @Published var errorMessage: String?

    private let groupAPI: GroupAPIProtocol
    private let currentUser: CurrentUser
    let groupId: Int

    /// 이름 수정 성공 시 호출(MapView 가 groupContext.refresh 로 상단 그룹명 갱신).
    private let onRenamed: () -> Void
    /// 그룹 삭제/탈퇴 성공 시 호출(MapView 가 시트 닫기 + groupContext.exitGroup).
    private let onExit: () -> Void

    init(
        groupAPI: GroupAPIProtocol,
        currentUser: CurrentUser,
        groupId: Int,
        groupName: String,
        onRenamed: @escaping () -> Void = {},
        onExit: @escaping () -> Void = {}
    ) {
        self.groupAPI = groupAPI
        self.currentUser = currentUser
        self.groupId = groupId
        self.groupNameDraft = groupName
        self.onRenamed = onRenamed
        self.onExit = onExit
    }

    /// 내가 방장인지(삭제 버튼 노출 분기). 멤버 목록에서 내 userId 가 isOwner 인지로 판단.
    /// 미로드/내 정보 미확인 시 false(안전 측 — 삭제 버튼 숨김).
    var isOwner: Bool {
        members.first(where: { $0.userId == currentUser.id })?.isOwner ?? false
    }

    /// 진입 시 그룹원 목록 로드(GET /groups/{id}/members). 실패 시 에러 상태(재시도 가능).
    func load() async {
        loadState = .loading
        errorMessage = nil
        do {
            members = try await groupAPI.listMembers(groupId: groupId)
            loadState = .loaded
        } catch {
            loadState = .error("그룹원 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.")
        }
        // 현재 활성 초대 코드 자동 조회(있으면 바로 표시 — 발급 아님이라 기존 코드 만료 없음).
        await loadCurrentInvite()
    }

    /// 현재 활성(미만료) 초대 코드 조회(GET, 발급 아님). 없음/실패 시 nil 유지 → '초대 코드 만들기' 노출.
    private func loadCurrentInvite() async {
        do {
            if let link = try await groupAPI.currentInviteLink(groupId: groupId), let slug = link.slug {
                inviteCode = slug
                inviteShareUrl = link.shareUrl
            }
        } catch {
            // 무시 — 코드 없음으로 간주(사용자가 '초대 코드 만들기' 로 발급 가능).
        }
    }

    /// 그룹명 수정(모든 멤버 가능). 공백 트림 후 빈 값이면 무시. 성공 시 onRenamed 콜백.
    func rename(_ newName: String) async {
        let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isBusy else { return }
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            try await groupAPI.updateGroupName(groupId: groupId, name: trimmed)
            groupNameDraft = trimmed
            onRenamed()
        } catch {
            errorMessage = "그룹 이름을 바꾸지 못했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    /// 그룹 삭제(방장만). 성공 시 onExit 콜백(시트 닫기 + 레벨0 복귀). 비방장 403 등은 에러 노출.
    func delete() async {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            try await groupAPI.deleteGroup(groupId: groupId)
            onExit()
        } catch {
            errorMessage = "그룹을 삭제하지 못했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    /// 그룹 탈퇴(모든 멤버). 성공 시 onExit 콜백(시트 닫기 + 레벨0 복귀).
    func leave() async {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            try await groupAPI.leaveGroup(groupId: groupId)
            onExit()
        } catch {
            errorMessage = "그룹 탈퇴에 실패했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    // MARK: - 초대 코드 발급/공유(IC-2)

    /// 발급된 초대 코드(slug). nil = 미발급("초대 코드 만들기" 노출).
    @Published private(set) var inviteCode: String?
    /// 공유용 링크(shareUrl). ShareLink 대상(절대 URL이면 url, 아니면 코드 텍스트 폴백).
    @Published private(set) var inviteShareUrl: String?
    /// 발급 진행 중(중복 호출 가드 + 버튼 라벨).
    @Published private(set) var isIssuing = false
    /// 코드 복사 완료 표시(버튼 라벨 토글).
    @Published var inviteCopied = false

    /// 초대 코드 발급(POST /groups/{id}/invite-links). slug·shareUrl 확보.
    /// 재호출 시 백엔드가 이전 미수락 토큰을 만료(BR-3)하므로 load 자동 발급 금지 — 명시 호출만 한다.
    func issueInvite() async {
        guard !isIssuing else { return }
        isIssuing = true
        errorMessage = nil
        defer { isIssuing = false }
        do {
            let link = try await groupAPI.issueInviteLink(groupId: groupId)
            inviteCode = link.slug
            inviteShareUrl = link.shareUrl
            inviteCopied = false
        } catch {
            errorMessage = "초대 코드를 만들지 못했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    /// 발급된 코드(slug)를 클립보드로 복사. pasteboardSetter 주입(테스트 가능, WelcomeWizardVM 패턴).
    /// 복사 후 "복사됨" 표시를 2초간 노출하고 자동 원복(토스트형 알림).
    func copyInviteCode(_ pasteboardSetter: (String) -> Void) {
        guard let code = inviteCode, !code.isEmpty else { return }
        pasteboardSetter(code)
        inviteCopied = true
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            self?.inviteCopied = false
        }
    }
}
