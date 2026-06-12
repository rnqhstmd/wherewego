import XCTest
@testable import WhereWeGo

// AC-13 / BR-1: 닉네임 규칙(한글/영문/숫자, 2~5자) 단위 테스트.
// 웹 nickname.ts + 백엔드 @Pattern("^[가-힣a-zA-Z0-9]+$")+@Size(2,5) 와 정합 검증.
final class NicknameTests: XCTestCase {

    // MARK: - validate: 길이 경계값

    func test_validate_oneChar_returnsTooShort() {
        // Given 1자 입력 (경계: 최소 미만)
        // When / Then
        XCTAssertEqual(Nickname.validate("가"), .tooShort)
    }

    func test_validate_twoChars_returnsValid() {
        // Given 2자 입력 (경계: 최소)
        XCTAssertEqual(Nickname.validate("가나"), .valid)
    }

    func test_validate_fiveChars_returnsValid() {
        // Given 5자 입력 (경계: 최대)
        XCTAssertEqual(Nickname.validate("12345"), .valid)
    }

    func test_validate_sixChars_returnsTooLong() {
        // Given 6자 입력 (경계: 최대 초과)
        XCTAssertEqual(Nickname.validate("123456"), .tooLong)
    }

    // MARK: - validate: 허용 문자

    func test_validate_koreanAndEnglishAndDigits_returnsValid() {
        // Given 한글/영문/숫자 조합
        XCTAssertEqual(Nickname.validate("abc12"), .valid)
        XCTAssertEqual(Nickname.validate("가나다라"), .valid)
        XCTAssertEqual(Nickname.validate("길동H2"), .valid)
    }

    func test_validate_specialChar_returnsInvalidChar() {
        // Given 특수문자 포함 (길이는 유효 범위)
        XCTAssertEqual(Nickname.validate("hi!!"), .invalidChar)
        XCTAssertEqual(Nickname.validate("가 나"), .invalidChar) // 공백
    }

    func test_validate_emoji_returnsInvalidChar() {
        // Given 이모지 포함
        XCTAssertEqual(Nickname.validate("가나😀"), .invalidChar)
    }

    // MARK: - sanitize: 특수문자/이모지 제거

    func test_sanitize_removesSpecialChars() {
        // Given 허용외 문자 혼합
        // When
        let result = Nickname.sanitize("길동!@# Hong")
        // Then 허용 문자만 남음(공백/특수문자 제거)
        XCTAssertEqual(result, "길동Hong")
    }

    func test_sanitize_removesEmoji() {
        // Given 이모지 포함
        let result = Nickname.sanitize("가😀나🎉다")
        // Then 이모지 제거
        XCTAssertEqual(result, "가나다")
    }

    // MARK: - sanitize: 5자 절단(Character 단위)

    func test_sanitize_truncatesToFiveChars() {
        // Given 5자 초과 입력
        let result = Nickname.sanitize("abcdefghij") // 10자
        // Then 5자로 절단
        XCTAssertEqual(result, "abcde")
        XCTAssertEqual(result.count, 5)
    }

    func test_sanitize_truncatesKoreanToFiveChars() {
        // Given 한글 5자 초과(Character 단위 절단 확인)
        let result = Nickname.sanitize("가나다라마바사") // 7자
        // Then 5자(grapheme)로 절단
        XCTAssertEqual(result.count, 5)
        XCTAssertEqual(result, "가나다라마")
    }

    func test_sanitize_emptyAfterRemoval_returnsEmpty() {
        // Given 허용 문자가 전혀 없는 입력
        let result = Nickname.sanitize("!@#$ 😀")
        // Then 빈 문자열
        XCTAssertEqual(result, "")
    }
}
