import Foundation
import KakaoSDKAuth
import KakaoSDKUser
import KakaoSDKCommon

// 카카오 네이티브 로그인(설계 §7, BR-7, QE-3, Q3).
// SDK init 은 WhereWeGoApp.init 에서 isKakaoKeyConfigured 일 때만 수행한다.
@MainActor
final class KakaoAuthService: KakaoAuthServicing {
    private let authAPI: AuthAPI

    init(authAPI: AuthAPI) {
        self.authAPI = authAPI
    }

    func login() async throws -> TokenResponse {
        // ① 키 미설정 → 즉시 차단(QE-3).
        guard AppConfig.isKakaoKeyConfigured else {
            throw AuthError.kakaoNotConfigured
        }

        // ② 카카오톡 앱 우선, 미설치 시 계정 로그인 폴백(Q3).
        let accessToken = try await requestKakaoAccessToken()

        // ③ 발급된 accessToken 으로 서버 네이티브 로그인.
        do {
            return try await authAPI.kakaoNative(kakaoAccessToken: accessToken)
        } catch let error as APIError {
            throw AuthError.server(error)
        }
    }

    /// SDK completion 콜백을 async 로 래핑.
    /// OAuthToken 은 Sendable 이 아니므로 콜백 안에서 accessToken(String)만 추출해 경계 너머로 보낸다.
    private func requestKakaoAccessToken() async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            let handler: (OAuthToken?, Error?) -> Void = { token, error in
                if let error {
                    continuation.resume(throwing: Self.mapKakaoError(error))
                } else if let token {
                    continuation.resume(returning: token.accessToken)
                } else {
                    continuation.resume(throwing: AuthError.server(
                        APIError(code: "KAKAO_NO_TOKEN", status: 0, message: "로그인에 실패했어요. 다시 시도해 주세요.")
                    ))
                }
            }

            if UserApi.isKakaoTalkLoginAvailable() {
                UserApi.shared.loginWithKakaoTalk(completion: handler)
            } else {
                UserApi.shared.loginWithKakaoAccount(completion: handler)
            }
        }
    }

    /// SDK 에러 → AuthError 매핑. 사용자 취소는 .cancelled.
    private static func mapKakaoError(_ error: Error) -> AuthError {
        if let sdkError = error as? SdkError,
           case .ClientFailed(let reason, _) = sdkError,
           reason == .Cancelled {
            return .cancelled
        }
        return .server(APIError(code: "KAKAO_LOGIN_FAILED", status: 0, message: error.localizedDescription))
    }
}
