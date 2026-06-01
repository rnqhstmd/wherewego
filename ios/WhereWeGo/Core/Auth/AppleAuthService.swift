import Foundation
import AuthenticationServices
import UIKit

// Apple 네이티브 로그인(설계 §8, BR-2, QE-4).
// nonce 계약: Apple request.nonce = sha256Hex(rawNonce), 서버 body nonce = rawNonce(평문).
@MainActor
final class AppleAuthService: NSObject, AppleAuthServicing {
    private let authAPI: AuthAPI

    /// 진행 중인 로그인의 continuation 과 rawNonce.
    private var continuation: CheckedContinuation<TokenResponse, Error>?
    private var rawNonce: String?

    init(authAPI: AuthAPI) {
        self.authAPI = authAPI
        super.init()
    }

    func login() async throws -> TokenResponse {
        // 안전한 난수 확보 실패 시 크래시 대신 사용자 오류로 매핑(QE-4).
        let nonce: String
        do {
            nonce = try NonceGenerator.randomNonce()
        } catch {
            throw AuthError.appleUnavailable
        }
        self.rawNonce = nonce

        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.fullName, .email]
        request.nonce = NonceGenerator.sha256Hex(nonce) // Apple 엔 해시(BR-2).

        return try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            let controller = ASAuthorizationController(authorizationRequests: [request])
            controller.delegate = self
            controller.presentationContextProvider = self
            controller.performRequests()
        }
    }

    private func finish(_ result: Result<TokenResponse, Error>) {
        guard let continuation else { return }
        self.continuation = nil
        self.rawNonce = nil
        continuation.resume(with: result)
    }
}

// MARK: - ASAuthorizationControllerDelegate

extension AppleAuthService: ASAuthorizationControllerDelegate {
    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        guard
            let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
            let identityTokenData = credential.identityToken,
            let identityToken = String(data: identityTokenData, encoding: .utf8),
            let rawNonce
        else {
            finish(.failure(AuthError.appleUnavailable))
            return
        }

        let authorizationCode = credential.authorizationCode.flatMap { String(data: $0, encoding: .utf8) }
        let givenName = credential.fullName?.givenName
        let familyName = credential.fullName?.familyName
        let hasName = givenName != nil || familyName != nil
        let email = credential.email

        Task { [authAPI] in
            do {
                let response = try await authAPI.appleNative(
                    identityToken: identityToken,
                    nonce: rawNonce, // 서버엔 평문(BR-2).
                    authorizationCode: authorizationCode,
                    fullName: hasName ? (givenName: givenName, familyName: familyName) : nil,
                    email: email
                )
                self.finish(.success(response))
            } catch let error as APIError {
                self.finish(.failure(AuthError.server(error)))
            } catch {
                self.finish(.failure(error))
            }
        }
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        if let authError = error as? ASAuthorizationError {
            switch authError.code {
            case .canceled:
                finish(.failure(AuthError.cancelled))
            default:
                finish(.failure(AuthError.appleUnavailable)) // capability 미설정 등(QE-4).
            }
        } else {
            finish(.failure(AuthError.appleUnavailable))
        }
    }
}

// MARK: - ASAuthorizationControllerPresentationContextProviding

extension AppleAuthService: ASAuthorizationControllerPresentationContextProviding {
    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first

        let keyWindow = scene?.windows.first { $0.isKeyWindow } ?? scene?.windows.first
        return keyWindow ?? ASPresentationAnchor()
    }
}
