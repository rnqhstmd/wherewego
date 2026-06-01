import Foundation

// 핀 태그·메모 출처 도메인 enum(설계 §2). 백엔드 PinTag/MemoSource record 직렬화와 1:1 정합.
// - 직렬화 값은 백엔드와 동일한 대문자 그대로 유지(REEL/WISH/MEMORY, AUTO/MANUAL).
// - 마커 색·심볼 매핑은 View 레이어(Theme.swift)가 담당. 여기서는 태그 의미만 보유(순수 유지).

/// 핀 태그. 백엔드 PinTag(REEL/WISH/MEMORY) 직렬화 값과 동일.
enum PinTag: String, Codable, CaseIterable {
    case REEL
    case WISH
    case MEMORY
}

/// 메모 출처. 백엔드 MemoSource(AUTO/MANUAL) 직렬화 값과 동일.
/// AUTO=인스타 추출/시스템 기록, MANUAL=사용자 직접 입력.
enum MemoSource: String, Codable {
    case AUTO
    case MANUAL
}
