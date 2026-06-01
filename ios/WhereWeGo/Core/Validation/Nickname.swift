import Foundation

// 닉네임 유효성 검증. frontend/src/lib/validation/nickname.ts 1:1 이식.
// 한글/영문/숫자만 허용, 길이 2~12자.
// 백엔드 UserV1Dto @Pattern("^[가-힣a-zA-Z0-9]+$") + @Size(2,12) 와 정합(설계 §4, BR-1).

enum NicknameValidationResult: Equatable {
    case valid
    case tooShort
    case tooLong
    case invalidChar
}

enum Nickname {
    /// 허용 문자: 한글/영문/숫자.
    private static let allowed = "^[가-힣a-zA-Z0-9]+$"

    /// 길이는 Character(grapheme) 단위로 센다(이모지/결합문자 안전).
    static func validate(_ value: String) -> NicknameValidationResult {
        let count = value.count
        if count < 2 { return .tooShort }
        if count > 12 { return .tooLong }
        if value.range(of: allowed, options: .regularExpression) == nil {
            return .invalidChar
        }
        return .valid
    }

    /// 허용외 문자 제거 + 12자 절단(Character 단위).
    static func sanitize(_ value: String) -> String {
        let filtered = value.filter { ch in
            String(ch).range(of: "^[가-힣a-zA-Z0-9]+$", options: .regularExpression) != nil
        }
        return String(filtered.prefix(12))
    }
}
