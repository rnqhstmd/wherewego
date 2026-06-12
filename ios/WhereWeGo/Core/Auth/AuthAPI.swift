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

/// 토큰 갱신 요청(설계 §10 데모 로그인). 백엔드 RefreshTokenRequest({"refreshToken": ...}) 대칭.
private struct RefreshTokenRequest: Encodable {
    let refreshToken: String
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

    /// GET /users/me. 현재 사용자 식별자/닉네임 조회(설계 §11, CurrentUser 캐시 소스).
    func me() async throws -> UserResponse {
        try await client.request("/users/me", type: UserResponse.self)
    }

    /// POST /users/me/profile-image (multipart, image/jpeg) — 내 프로필 사진 업로드(GP-1 §2.2).
    /// 핀 uploadPhoto 패턴(client.upload, fieldName "file"). 응답 UserResponse.profileImageUrl 로 CurrentUser 갱신은 B4 배선.
    func uploadProfileImage(jpegData: Data) async throws -> UserResponse {
        try await client.upload(
            "/users/me/profile-image",
            fileData: jpegData,
            fileName: "profile.jpg",
            fieldName: "file",
            mimeType: "image/jpeg",
            type: UserResponse.self
        )
    }

    /// DELETE /users/me/profile-image — 내 프로필 사진 제거(GP-1 §2.2, 카카오 URL 포함 전부 null).
    /// 응답 UserResponse(profileImageUrl=null) 디코드.
    func deleteProfileImage() async throws -> UserResponse {
        try await client.request("/users/me/profile-image", method: "DELETE", type: UserResponse.self)
    }

    /// DELETE /users/me. 계정 삭제(FR-26).
    // 204(빈 본문) 정상 성공 — APIClient.decodeEnvelope 는 data 키 부재로 NO_CONTENT 를 throw.
    // 204 자체는 성공이므로 NO_CONTENT 만 정상 흡수하고 나머지는 전파(PinAPI.delete 패턴).
    func deleteAccount() async throws {
        do {
            _ = try await client.request("/users/me", method: "DELETE", type: EmptyResponse.self)
        } catch let error as APIError where error.code == "NO_CONTENT" {
            return
        }
    }

    /// POST /auth/refresh. 데모 로그인(설계 §10) — 시드 refreshToken 으로 새 토큰 쌍 발급.
    /// 데모 식별자 매칭 시 백엔드가 회전을 스킵해 동일 시드 토큰을 재사용한다(§10 (a)).
    /// (KeychainTokenStore.refresh 는 보관 토큰 전용이라 임의 시드 토큰 흐름에 부적합 → AuthAPI 경유.)
    func refresh(refreshToken: String) async throws -> TokenResponse {
        let body = try JSONEncoder().encode(RefreshTokenRequest(refreshToken: refreshToken))
        return try await client.request("/auth/refresh", method: "POST", body: body, type: TokenResponse.self)
    }
}
