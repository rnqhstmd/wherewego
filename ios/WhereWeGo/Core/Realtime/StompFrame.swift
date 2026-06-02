import Foundation

// STOMP 1.2 프레임 순수 인코딩/디코딩(설계 §3, Q1: 직접 구현 — 외부 SPM 미추가).
// 토큰/네트워크 무관한 순수 로직이라 단위 테스트(StompFrameTests) 대상.
//
// 프레임 와이어 포맷(STOMP 1.2):
//   COMMAND\n
//   header-1:value-1\n
//   header-2:value-2\n
//   \n                      ← 빈 줄로 헤더 종료
//   body                    ← 본문(없을 수 있음)
//   \0                      ← NUL 종단
//
// 구현 프레임 한정(설계 §3): CONNECT / CONNECTED / SUBSCRIBE / MESSAGE / ERROR / DISCONNECT.
// heart-beat·ACK·트랜잭션은 미구현(단일 인스턴스·단방향 push 전제).
struct StompFrame: Equatable {
    let command: String
    let headers: [String: String]
    let body: String

    init(command: String, headers: [String: String] = [:], body: String = "") {
        self.command = command
        self.headers = headers
        self.body = body
    }

    // MARK: - 인코딩

    /// STOMP 와이어 텍스트(COMMAND\n headers\n\n body\0)로 직렬화한다.
    func encodedText() -> String {
        var lines = command + "\n"
        // 헤더 순서는 STOMP 상 의미 없으나, 결정성(테스트 안정성)을 위해 키 정렬.
        for key in headers.keys.sorted() {
            lines += "\(key):\(headers[key] ?? "")\n"
        }
        lines += "\n"            // 헤더 종료(빈 줄)
        lines += body
        lines += "\u{00}"        // NUL 종단
        return lines
    }

    /// URLSessionWebSocketTask 전송용 텍스트 메시지로 인코딩한다.
    func encode() -> URLSessionWebSocketTask.Message {
        .string(encodedText())
    }

    // MARK: - 디코딩

    /// 수신 텍스트를 NUL(`\0`)로 분할해 0개 이상의 프레임으로 파싱한다.
    /// 다중 프레임이 한 메시지로 합쳐 들어올 수 있으므로 NUL 분할로 모두 추출한다.
    /// NUL 사이 공백/개행만 있는 조각(heart-beat·trailing)은 무시한다.
    static func decode(_ text: String) -> [StompFrame] {
        // CRLF 브로커 방어: 파싱 전 `\r\n`을 `\n`으로 정규화한다(헤더/본문 구분 `\n\n`·다중 프레임 분리 안전).
        // 인코딩(encode)은 STOMP 송신 규약대로 `\n`만 사용하므로 변경하지 않는다.
        let normalized = text.replacingOccurrences(of: "\r\n", with: "\n")
        return normalized.split(separator: "\u{00}", omittingEmptySubsequences: false)
            .compactMap { parseSingle(String($0)) }
    }

    /// NUL 로 분리된 단일 프레임 텍스트를 파싱한다. command 가 없으면 nil(무시).
    private static func parseSingle(_ raw: String) -> StompFrame? {
        // 선행 개행(이전 프레임 NUL 뒤 \n 등) 제거 후 command 추출.
        let trimmedLeading = trimLeadingNewlines(raw)
        guard !trimmedLeading.isEmpty else { return nil }

        // 헤더 블록과 본문은 첫 빈 줄("\n\n")로 구분된다.
        let (headerBlock, body) = splitHeaderAndBody(trimmedLeading)

        var lines = headerBlock.components(separatedBy: "\n")
        guard let command = lines.first, !command.isEmpty else { return nil }
        lines.removeFirst()

        var headers: [String: String] = [:]
        for line in lines where !line.isEmpty {
            // 헤더는 첫 ':' 기준 key:value. value 에 ':' 포함 가능하므로 첫 콜론만 분리.
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = String(line[line.startIndex..<colon])
            let value = String(line[line.index(after: colon)...])
            // STOMP: 중복 헤더는 첫 값 우선.
            if headers[key] == nil {
                headers[key] = value
            }
        }
        return StompFrame(command: command, headers: headers, body: body)
    }

    /// 첫 빈 줄("\n\n")로 헤더 블록과 본문을 분리한다. 빈 줄이 없으면 전체가 헤더 블록(본문 없음).
    private static func splitHeaderAndBody(_ text: String) -> (header: String, body: String) {
        guard let range = text.range(of: "\n\n") else {
            return (text, "")
        }
        let header = String(text[text.startIndex..<range.lowerBound])
        let body = String(text[range.upperBound...])
        return (header, body)
    }

    /// 선행 개행/캐리지리턴을 제거한다(이전 프레임 종단 뒤 잔여 개행 방어).
    private static func trimLeadingNewlines(_ text: String) -> String {
        var index = text.startIndex
        while index < text.endIndex, text[index] == "\n" || text[index] == "\r" {
            index = text.index(after: index)
        }
        return String(text[index...])
    }
}
