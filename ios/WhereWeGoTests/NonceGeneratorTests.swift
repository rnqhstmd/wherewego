import XCTest
@testable import WhereWeGo

// BR-2: Apple Sign In nonce 생성기 단위 테스트.
// 계약: Apple request.nonce = sha256Hex(rawNonce), 서버 body nonce = rawNonce(평문).
// 클라이언트는 이중해시하지 않으며 sha256Hex 는 소문자 hex 64자를 반환한다.
final class NonceGeneratorTests: XCTestCase {

    // MARK: - sha256Hex: 알려진 벡터

    func test_sha256Hex_knownVector_abc() {
        // Given 표준 SHA-256("abc") 벡터
        // When
        let hex = NonceGenerator.sha256Hex("abc")
        // Then NIST 알려진 벡터와 일치
        XCTAssertEqual(
            hex,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
    }

    func test_sha256Hex_emptyString_knownVector() {
        // Given 빈 문자열의 SHA-256
        let hex = NonceGenerator.sha256Hex("")
        // Then 알려진 벡터
        XCTAssertEqual(
            hex,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
    }

    // MARK: - sha256Hex: 소문자 hex 형식

    func test_sha256Hex_outputIsLowercaseHex64() {
        // Given 임의 입력
        let hex = NonceGenerator.sha256Hex("WhereWeGo-nonce-123")
        // Then 소문자 hex 64자
        XCTAssertEqual(hex.count, 64)
        XCTAssertNotNil(
            hex.range(of: "^[0-9a-f]{64}$", options: .regularExpression),
            "출력이 소문자 hex 64자가 아님: \(hex)"
        )
    }

    // MARK: - randomNonce: 길이

    func test_randomNonce_defaultLengthIs32() throws {
        // Given 기본 길이
        // When
        let nonce = try NonceGenerator.randomNonce()
        // Then 32자
        XCTAssertEqual(nonce.count, 32)
    }

    func test_randomNonce_customLength() throws {
        // Given 임의 길이
        XCTAssertEqual(try NonceGenerator.randomNonce(length: 16).count, 16)
        XCTAssertEqual(try NonceGenerator.randomNonce(length: 64).count, 64)
        XCTAssertEqual(try NonceGenerator.randomNonce(length: 1).count, 1)
    }

    // MARK: - randomNonce: charset (A-Za-z0-9-._)

    func test_randomNonce_onlyUsesAllowedCharset() throws {
        // Given 충분히 긴 nonce 여러 개
        // When / Then 허용 charset(A-Za-z0-9-._) 외 문자 없음
        for _ in 0..<50 {
            let nonce = try NonceGenerator.randomNonce(length: 64)
            XCTAssertNotNil(
                nonce.range(of: "^[A-Za-z0-9._-]+$", options: .regularExpression),
                "허용 charset 외 문자 포함: \(nonce)"
            )
        }
    }

    // MARK: - randomNonce: 랜덤성

    func test_randomNonce_twoCallsDiffer() throws {
        // Given 두 번 호출
        let a = try NonceGenerator.randomNonce()
        let b = try NonceGenerator.randomNonce()
        // Then 서로 다른 값(난수성)
        XCTAssertNotEqual(a, b)
    }
}
