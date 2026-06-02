import Foundation

// 실시간(STOMP) 연결 상태(설계 §4, QE-2). 단일 WebSocket 연결의 상태를 표현한다.
// ChatRealtimeService(C5 담당)가 @Published 로 노출하고, ChatScrollContainer 상단 배너가 구독한다.
//
// - connecting   최초 연결 시도 중
// - connected    연결됨(구독 활성)
// - reconnecting 끊긴 후 재연결 시도 중(BR-8: 즉시 1회 → 5초×3회)
// - disconnected 재연결 실패(수동 재시도 안내)
enum ConnectionState: Equatable {
    case connecting
    case connected
    case reconnecting
    case disconnected
}
