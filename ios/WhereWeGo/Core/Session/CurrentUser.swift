import Foundation

// 현재 로그인 사용자 식별자/닉네임 캐시(설계 §11, Q3).
// GET /api/v1/users/me → { id, nickname, profileImageUrl }(UserV1Dto.UserResponse, id:Long → Int).
// 용도: 봇 토픽 path(/topic/chat/bot/{userId}) 구성, 커플방 내 메시지 판별(senderType==USER & id 비교).
// 로그인/부트스트랩 후 load() 로 채운다. me 실패 시 채팅 진입 전 재시도(봇 토픽 id 필수).
@MainActor
final class CurrentUser: ObservableObject {

    @Published private(set) var id: Int?
    @Published private(set) var nickname: String?
    /// 내 유효 프사 URL(GP-1 §2.2). 없음/카카오만 → nil/카카오 URL. me() 응답으로 갱신, clear 시 비움.
    @Published private(set) var profileImageUrl: String?

    private let authAPI: AuthAPI

    init(authAPI: AuthAPI) {
        self.authAPI = authAPI
    }

    /// GET /users/me 호출로 id/nickname/프사 채움. 실패 시 기존 값 유지(조용히 무시).
    func load() async {
        guard let user = try? await authAPI.me() else { return }
        id = user.id
        nickname = user.nickname
        profileImageUrl = user.profileImageUrl
    }

    /// 로그아웃 시 캐시 비움(다음 사용자 정보 오염 방지).
    func clear() {
        id = nil
        nickname = nil
        profileImageUrl = nil
    }
}
