import Foundation

// Share Extension 전용 최소 DTO(설계 §0 — 앱 모델 미공유, 의도적 디커플).
// 백엔드 ApiResponse 래퍼 + GET /chat/bot/rooms 항목 + POST /auth/refresh 응답.

/// API 공통 래퍼(메인 앱 APIEnvelope 동치 최소판).
struct ShareEnvelope<T: Decodable>: Decodable {
    struct Meta: Decodable {
        let result: String?
        let errorCode: String?
        let message: String?
    }
    let meta: Meta?
    let data: T?
}

/// 전송 대상 그룹(= 봇 DM 방). GET /chat/bot/rooms 항목에서 groupId/groupName 만 사용(나머지 키 무시).
struct ShareGroup: Decodable, Identifiable, Equatable, Sendable {
    let groupId: Int
    let groupName: String
    var id: Int { groupId }
}

/// 토큰 갱신 응답(POST /api/v1/auth/refresh).
struct ShareTokenResponse: Decodable {
    let accessToken: String
    let refreshToken: String
}

/// 공유 도메인 에러(코드 + HTTP 상태).
struct ShareAPIError: Error, Equatable {
    let code: String
    let status: Int
    let message: String
}
