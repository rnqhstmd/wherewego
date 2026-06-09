import Foundation

// 내정보 ViewModel(설계 §8 / IA 재설계 D단계 내정보 축소, FR-23~27, BR-5, AC-11).
// frontend/src/app/settings/SettingsClient.tsx 이식 — 단 "챗봇 연동" 섹션은 제외(FR-27, AC-11).
// IA 재설계: 그룹(활성그룹·탈퇴)은 지도 탭 ⋯ 그룹관리(GroupManageView)로 이전 → 내정보는 사용자(닉네임)+계정만.
//  따라서 activeGroup·shouldShowGroupSection·leaveGroup·groupAPI 의존을 제거한다.
// 닉네임·로그아웃·계정삭제를 주입 의존(authAPI/sessionStore/currentUser)에 위임한다.
@MainActor
final class MyInfoViewModel: ObservableObject {
    @Published var nickname: String?
    @Published var isBusy = false
    @Published var errorMessage: String?

    private let authAPI: AuthAPI
    private let sessionStore: SessionStore
    private let currentUser: CurrentUser
    /// 앱 표준 로그아웃 경로(설계 §11) — 디바이스 토큰 해제·CurrentUser.clear·SessionStore.logout 일괄.
    /// MainTabView 가 AppDependencies.logout 을 주입한다. 미주입 시 SessionStore.logout 단독 폴백(하위호환).
    private let logoutHandler: (@Sendable () async -> Void)?

    init(
        authAPI: AuthAPI,
        sessionStore: SessionStore,
        currentUser: CurrentUser,
        logoutHandler: (@Sendable () async -> Void)? = nil
    ) {
        self.authAPI = authAPI
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

    /// 진입 시 닉네임(GET /users/me) 로드. me 실패는 조용히 무시(CurrentUser 캐시 폴백).
    /// IA 재설계: 활성 그룹 로드는 제거됨(그룹은 지도 탭 ⋯ 그룹관리로 이전).
    func load() async {
        if let user = try? await authAPI.me() {
            nickname = user.nickname
        } else {
            nickname = currentUser.nickname
        }
    }

    /// 닉네임 수정 완료 후 표시 갱신(NicknameView onDone 콜백에서 호출).
    func refreshNickname() async {
        if let user = try? await authAPI.me() {
            nickname = user.nickname
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
