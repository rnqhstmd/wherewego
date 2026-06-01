import Foundation
import CryptoKit
import Security

// Apple Sign In nonce 생성기(설계 §8, BR-2).
// 계약: Apple request.nonce = sha256Hex(rawNonce), 서버 body nonce = rawNonce(평문).
// 서버가 sha256Hex(받은 nonce) == identityToken claims.nonce 로 검증한다(이중해시 없음).
/// 안전한 난수 확보 실패(설계 §8, QE-4). fatalError(크래시) 대신 throw 하여 호출부에서 복구한다.
enum NonceError: Error {
    case randomGenerationFailed(OSStatus)
}

enum NonceGenerator {
    /// URL-safe charset(A-Za-z0-9-._) 으로 랜덤 nonce 생성.
    /// SecRandomCopyBytes 실패 시 NonceError.randomGenerationFailed throw(크래시 없음, QE-4).
    static func randomNonce(length: Int = 32) throws -> String {
        precondition(length > 0)
        let charset = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._")
        var result = ""
        var remaining = length

        while remaining > 0 {
            var randoms = [UInt8](repeating: 0, count: 16)
            let status = SecRandomCopyBytes(kSecRandomDefault, randoms.count, &randoms)
            guard status == errSecSuccess else {
                // 안전한 난수 확보 실패 시 더 진행하지 않는다(throw → 호출부에서 사용자 오류로 처리).
                throw NonceError.randomGenerationFailed(status)
            }
            for random in randoms where remaining > 0 {
                if random < UInt8(charset.count) {
                    result.append(charset[Int(random)])
                    remaining -= 1
                }
            }
        }
        return result
    }

    /// CryptoKit SHA256 → 소문자 hex 문자열.
    static func sha256Hex(_ input: String) -> String {
        let digest = SHA256.hash(data: Data(input.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
