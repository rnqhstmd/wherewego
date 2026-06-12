import Foundation
import UIKit   // GP-1 FR-3: 프사 업로드 입력(UIImage) + ImageCropper.resizeAndCompress 전처리.

// 내정보 ViewModel(설계 §8 / IA 재설계 D단계 내정보 축소, FR-23~27, BR-5, AC-11).
// frontend/src/app/settings/SettingsClient.tsx 이식 — 단 "챗봇 연동" 섹션은 제외(FR-27, AC-11).
// IA 재설계: 그룹(활성그룹·탈퇴)은 지도 탭 ⋯ 그룹관리(GroupManageView)로 이전 → 내정보는 사용자(닉네임)+계정만.
//  따라서 activeGroup·shouldShowGroupSection·leaveGroup·groupAPI 의존을 제거한다.
// 닉네임·로그아웃·계정삭제를 주입 의존(authAPI/sessionStore/currentUser)에 위임한다.
@MainActor
final class MyInfoViewModel: ObservableObject {
    @Published var nickname: String?
    /// 내 유효 프사 URL(GP-1 FR-3). CurrentUser/me 응답 미러(닉네임과 동일 패턴). nil = 이니셜 폴백(AvatarView).
    @Published var profileImageUrl: String?
    /// 내가 등록한 핀 수(FR-3 프로필 통계). me() 응답 반영. 구서버는 nil → 화면에서 0 폴백.
    @Published private(set) var pinCount: Int?
    @Published var isBusy = false
    @Published var errorMessage: String?
    /// 프사 업로드/제거 진행 중(중복 호출 가드 + 아바타 로딩 오버레이). isBusy(로그아웃/계정삭제)와 분리해 상호 비간섭.
    @Published var isUploadingPhoto = false

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
        self.profileImageUrl = currentUser.profileImageUrl
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

    /// 진입 시 닉네임·프사(GET /users/me) 로드. me 실패는 조용히 무시(CurrentUser 캐시 폴백).
    /// IA 재설계: 활성 그룹 로드는 제거됨(그룹은 지도 탭 ⋯ 그룹관리로 이전).
    func load() async {
        if let user = try? await authAPI.me() {
            nickname = user.nickname
            profileImageUrl = user.profileImageUrl
            pinCount = user.pinCount
        } else {
            nickname = currentUser.nickname
            profileImageUrl = currentUser.profileImageUrl
        }
    }

    /// 닉네임 수정 완료 후 표시 갱신(프로필 편집 onDone 콜백에서 호출). 핀 수도 함께 최신화.
    func refreshNickname() async {
        if let user = try? await authAPI.me() {
            nickname = user.nickname
            pinCount = user.pinCount
        }
    }

    // MARK: - 프로필 사진(GP-1 FR-3)

    /// 프사 업로드(크롭된 1:1 이미지). resizeAndCompress(2MB 게이트) → POST → 응답·CurrentUser 갱신.
    /// 핀 업로드 선례(resizeAndCompress 전처리) 동일. 실패 시 기존 에러 표출 패턴(errorMessage).
    func uploadProfileImage(_ image: UIImage) async {
        guard !isUploadingPhoto else { return }
        isUploadingPhoto = true
        errorMessage = nil
        defer { isUploadingPhoto = false }
        guard let jpeg = ImageCropper.resizeAndCompress(image) else {
            errorMessage = "사진이 너무 커서 올리지 못했어요. 다른 사진을 선택해 주세요."
            return
        }
        do {
            let user = try await authAPI.uploadProfileImage(jpegData: jpeg)
            // 즉시 반영(응답 URL) + CurrentUser 동기화(다른 화면 — 채팅/그룹목록 — 정합). 적용 메서드 부재라 load() 재호출.
            profileImageUrl = user.profileImageUrl
            await currentUser.load()
        } catch {
            errorMessage = "사진을 올리지 못했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    /// 프사 제거(카카오 URL 포함 전부 null, GP-1 §2.2). DELETE → 응답·CurrentUser 갱신 → 이니셜 폴백.
    func removeProfileImage() async {
        guard !isUploadingPhoto else { return }
        isUploadingPhoto = true
        errorMessage = nil
        defer { isUploadingPhoto = false }
        do {
            let user = try await authAPI.deleteProfileImage()
            profileImageUrl = user.profileImageUrl
            await currentUser.load()
        } catch {
            errorMessage = "사진을 제거하지 못했어요. 잠시 후 다시 시도해 주세요."
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
