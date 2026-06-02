import Foundation

// 데모 로그인 진입 게이트 순수 로직(설계 §10, FR-26/AC-17).
// 워드마크를 5회 "연속" 탭하면 데모 로그인 버튼을 노출한다. 탭 간격이 임계(2초)를 넘으면
// 카운트를 리셋해 우연한 누적을 막는다. View(LoginView)는 onTapGesture 에서 registerTap 을 호출하고
// 반환값(true)일 때 데모 버튼을 노출한다(C9 담당). 토큰/네트워크 무관 — 결정적 테스트 대상.
struct DemoLoginGateState {

    /// 게이트 해제에 필요한 연속 탭 횟수(AC-17).
    static let requiredTaps = 5
    /// 연속 탭으로 인정하는 최대 간격(초). 초과 시 카운트 리셋.
    static let maxInterval: TimeInterval = 2

    /// 현재까지 누적된 연속 탭 수.
    private(set) var tapCount = 0
    /// 마지막 탭 시각(연속성 판정용). 첫 탭 전에는 nil.
    private(set) var lastTapAt: Date?

    /// 탭 1회 등록. 직전 탭과의 간격이 임계 초과면 1부터 다시 센다.
    /// 누적이 requiredTaps 에 도달하면 true(게이트 해제) 반환 후 카운트 리셋.
    /// - Parameter now: 현재 시각(테스트 결정성 위해 주입).
    /// - Returns: 이번 탭으로 게이트가 해제되면 true, 아니면 false.
    mutating func registerTap(now: Date) -> Bool {
        if let last = lastTapAt, now.timeIntervalSince(last) > Self.maxInterval {
            // 간격 초과 — 연속성 끊김. 이번 탭부터 새로 카운트.
            tapCount = 1
        } else {
            tapCount += 1
        }
        lastTapAt = now

        guard tapCount >= Self.requiredTaps else { return false }
        // 해제 — 다음 게이트를 위해 카운트 초기화(중복 노출 방지).
        tapCount = 0
        lastTapAt = nil
        return true
    }
}
