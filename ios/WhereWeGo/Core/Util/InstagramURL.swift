import Foundation

// 인스타그램 릴스/게시물 URL 판정(GC-2 FR-GC2-8).
// 백엔드 ReelPlaceExtractor.INSTAGRAM_URL(`^https?://(www\.)?(instagram\.com|instagr\.am)/(p|reel|reels)/[A-Za-z0-9_-]+/?.*`)
// + GroupChatService.validateReelUrl(https:// 강제 + 2000자 상한)과 동치.
// 용도: 채팅 입력창 전송 시 REEL_LINK/TEXT 분기(메시지 전체가 URL 단독일 때만 REEL_LINK), ShareExtension 전송.
// 정규식 대신 URLComponents 파싱으로 Swift6 동시성 안전(전역 가변 NSRegularExpression 회피).
enum InstagramURL {

    /// REEL_LINK URL 상한(백엔드 GroupChatService.MAX 대칭, payload 비대 차단).
    static let maxLength = 2000

    /// 메시지 전체(trim)가 인스타 릴스/게시물 URL 1개인지(FR-GC2-8).
    /// 공백/줄바꿈이 섞이면(텍스트 혼합) false → TEXT 로 전송한다(URL 단독만 REEL_LINK).
    static func isReelURL(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= maxLength else { return false }
        // URL 단독 판정 — 내부 공백이 있으면 문장 혼합으로 보고 TEXT 처리.
        guard !trimmed.contains(where: { $0.isWhitespace }) else { return false }
        guard let comps = URLComponents(string: trimmed),
              comps.scheme == "https",                       // 백엔드 https:// 강제
              let host = comps.host?.lowercased() else { return false }
        let validHost = host == "instagram.com" || host == "www.instagram.com"
            || host == "instagr.am" || host == "www.instagr.am"
        guard validHost else { return false }
        // path: /(p|reel|reels)/{code}[/...]. 첫 두 세그먼트만 검증(백엔드 정규식의 `.*` suffix 허용).
        let segments = comps.path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        guard segments.count >= 2 else { return false }
        guard segments[0] == "p" || segments[0] == "reel" || segments[0] == "reels" else { return false }
        let code = segments[1]
        // 백엔드 [A-Za-z0-9_-]+ 와 정확히 동치(유니코드 isLetter 회피 — ASCII 영숫자/_/- 만).
        guard !code.isEmpty, code.allSatisfy(Self.isCodeCharacter) else { return false }
        return true
    }

    /// 백엔드 정규식 문자 클래스 [A-Za-z0-9_-] 판정(ASCII 한정).
    private static func isCodeCharacter(_ ch: Character) -> Bool {
        ("A"..."Z").contains(ch) || ("a"..."z").contains(ch)
            || ("0"..."9").contains(ch) || ch == "_" || ch == "-"
    }
}
