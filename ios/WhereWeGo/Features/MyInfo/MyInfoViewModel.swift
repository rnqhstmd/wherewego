import Foundation

// 내정보 ViewModel(설계 §8, FR-23~27, BR-5, AC-10/AC-11).
// frontend/src/app/settings/SettingsClient.tsx 이식 — 단 "챗봇 연동" 섹션은 제외(FR-27, AC-11).
// 닉네임·탈퇴·로그아웃·계정삭제를 주입 의존(authAPI/groupAPI/sessionStore/currentUser)에 위임한다.
@MainActor
final class MyInfoViewModel: ObservableObject {
    @Published var nickname: String?
    @Published var activeGroup: ActiveGroup?
    @Published var isBusy = false
    @Published var errorMessage: String?

    /// 활성 그룹 보유 시에만 그룹 섹션 노출(AC-10).
    var shouldShowGroupSection: Bool { activeGroup != nil }

    private let authAPI: AuthAPI
    private let groupAPI: GroupAPIProtocol
    private let sessionStore: SessionStore
    private let currentUser: CurrentUser
    /// 앱 표준 로그아웃 경로(설계 §11) — 디바이스 토큰 해제·CurrentUser.clear·SessionStore.logout 일괄.
    /// MainTabView 가 AppDependencies.logout 을 주입한다. 미주입 시 SessionStore.logout 단독 폴백(하위호환).
    private let logoutHandler: (@Sendable () async -> Void)?

    init(
        authAPI: AuthAPI,
        groupAPI: GroupAPIProtocol,
        sessionStore: SessionStore,
        currentUser: CurrentUser,
        logoutHandler: (@Sendable () async -> Void)? = nil
    ) {
        self.authAPI = authAPI
        self.groupAPI = groupAPI
        self.sessionStore = sessionStore
        self.currentUser = currentUser
        self.logoutHandler = logoutHandler
        self.nickname = currentUser.nickname
    }

    /// 표준 로그아웃 정리(설계 §11). 주입된 핸들러(디바이스 토큰 해제·CurrentUser.clear·SessionStore.logout)를
    /// 우선 사용하고, 미주입 시 SessionStore.logout 단독으로 폴백한다.
    private func performLogout() async {
        if let logoutHandler {
            await logoutHandler()
        } else {
            // 핸들러 미주입 폴백(prod 는 항상 주입). 핸들러와 동일하게 CurrentUser 캐시도 비워
            // 다음 사용자 정보 오염을 방지한다.
            currentUser.clear()
            await sessionStore.logout()
        }
    }

    /// 진입 시 닉네임(GET /users/me) + 활성 그룹(GET /groups/me) 로드.
    /// me 실패는 조용히 무시(CurrentUser 캐시 유지), 그룹 실패는 에러 노출.
    func load() async {
        if let user = try? await authAPI.me() {
            nickname = user.nickname
        } else {
            nickname = currentUser.nickname
        }
        do {
            activeGroup = try await groupAPI.myActiveGroup()
        } catch {
            errorMessage = "그룹 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    /// 닉네임 수정 완료 후 표시 갱신(NicknameView onDone 콜백에서 호출).
    func refreshNickname() async {
        if let user = try? await authAPI.me() {
            nickname = user.nickname
        }
    }

    /// 그룹 탈퇴(BR-5). 확인 다이얼로그는 View 책임 — VM 은 호출만 한다.
    /// 성공 시 activeGroup=nil → 그룹 섹션 미렌더(AC-10).
    func leaveGroup() async {
        guard let group = activeGroup, !isBusy else { return }
        isBusy = true
        errorMessage = nil
        // 성공/실패 양 경로 모두 isBusy 해제(@MainActor 보장). defer 로 고착 방지.
        defer { isBusy = false }
        do {
            try await groupAPI.leaveGroup(groupId: group.groupId)
            activeGroup = nil
        } catch {
            errorMessage = "그룹 탈퇴에 실패했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    /// 계정 삭제(FR-26, BR-5) → 로그아웃 전환(SessionStore 위임).
    /// 확인 다이얼로그는 View 책임. 삭제 성공 후 logout 으로 인증 상태 해제.
    func deleteAccount() async {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        // 성공 경로에서 performLogout 후 화면 전환이 실패해도 isBusy 가 고착되지 않도록
        // defer 로 양 경로 모두 해제(@MainActor 보장 — 전환으로 VM 이 해제되기 전 실행).
        defer { isBusy = false }
        do {
            try await authAPI.deleteAccount()
            await performLogout()
        } catch {
            errorMessage = "계정 삭제에 실패했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    /// 로그아웃(FR-26) — 표준 로그아웃 경로 위임(설계 §11, 디바이스 토큰 해제·CurrentUser.clear 포함).
    func logout() async {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        // performLogout 후 화면 전환이 실패해도 isBusy 고착되지 않도록 defer 해제(@MainActor 보장).
        defer { isBusy = false }
        await performLogout()
    }
}
