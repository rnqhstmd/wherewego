import Foundation

// 인증 관련 DTO 및 호출(설계 §9). 모든 호출은 APIClient.request 경유.
// APIClient.request 가 "api/v1" + path 를 붙이므로 path 는 "/auth/kakao/native" 형태로 전달한다.

// MARK: - 응답 DTO

/// 토큰 갱신/로그인 공통 응답. KeychainTokenStore/GroupAPI 등에서 공유(중복 정의 금지).
struct TokenResponse: Decodable {
    let accessToken: String
    let refreshToken: String
    let expiresIn: Int
}

/// 닉네임 저장 응답.
struct UserResponse: Decodable {
    let id: Int
    let nickname: String
    let profileImageUrl: String?
}

// MARK: - 요청 DTO

private struct KakaoNativeRequest: Encodable {
    let kakaoAccessToken: String
}

private struct AppleFullName: Encodable {
    let givenName: String?
    let familyName: String?
}

private struct AppleNativeRequest: Encodable {
    let identityToken: String
    let nonce: String
    let authorizationCode: String?
    let fullName: AppleFullName?
    let email: String?
}

private struct UpdateNicknameRequest: Encodable {
    let nickname: String
}

// MARK: - AuthAPI

final class AuthAPI: Sendable {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    /// POST /auth/kakao/native
    func kakaoNative(kakaoAccessToken: String) async throws -> TokenResponse {
        let body = try JSONEncoder().encode(KakaoNativeRequest(kakaoAccessToken: kakaoAccessToken))
        return try await client.request("/auth/kakao/native", method: "POST", body: body, type: TokenResponse.self)
    }

    /// POST /auth/apple/native. nonce 는 rawNonce(평문)을 전달한다(BR-2).
    func appleNative(
        identityToken: String,
        nonce: String,
        authorizationCode: String?,
        fullName: (givenName: String?, familyName: String?)?,
        email: String?
    ) async throws -> TokenResponse {
        let name = fullName.map { AppleFullName(givenName: $0.givenName, familyName: $0.familyName) }
        let request = AppleNativeRequest(
            identityToken: identityToken,
            nonce: nonce,
            authorizationCode: authorizationCode,
            fullName: name,
            email: email
        )
        let body = try JSONEncoder().encode(request)
        return try await client.request("/auth/apple/native", method: "POST", body: body, type: TokenResponse.self)
    }

    /// PUT /users/me
    func updateNickname(_ nickname: String) async throws -> UserResponse {
        let body = try JSONEncoder().encode(UpdateNicknameRequest(nickname: nickname))
        return try await client.request("/users/me", method: "PUT", body: body, type: UserResponse.self)
    }
}
