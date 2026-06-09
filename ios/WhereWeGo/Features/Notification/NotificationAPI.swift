import Foundation

// 알림 도메인 API 및 DTO(설계 §6, FR-17~21/AC-7). 모든 호출은 APIClient 경유.
// 백엔드 계약(NotificationV1Controller/NotificationV1Dto)과 1:1 정합:
// - id/registeredBy/pinId: Long → Int.
// - unreadCount: long → Int (totalPinCount/wishCount/reelCount: int → Int).
// - createdAt/readAt: Instant → String (Jackson ISO-8601 직렬화).
// - latitude/longitude: BigDecimal → Double? (JacksonConfig WRITE_BIGDECIMAL_AS_PLAIN = number 직렬화 확인.
//   웹 types.ts 주석은 "문자열" 이라 적혀 있으나 실제 응답은 number 이므로 Double? 가 정확. flyTo 는 Double 직접).

// MARK: - 알림 종류

/// 백엔드 NotificationType 과 1:1. 행 아이콘/문구 분기에 사용.
enum NotificationType: String, Decodable {
    case MANUAL_PIN
    case CHATBOT_PINS
    case VISIT_DETECTED
}

// MARK: - 응답 DTO

/// 백엔드 NotificationV1Dto.NotificationItem 과 필드명·옵셔널 1:1.
/// wishCount/reelCount 는 CHATBOT_PINS 외에는 0 또는 미전달 → Int? 로 안전 디코딩.
struct NotificationItem: Decodable, Identifiable, Equatable {
    let id: Int
    let type: NotificationType
    let registeredBy: Int?
    let registeredByNickname: String?
    let firstPlaceName: String
    let totalPinCount: Int
    let wishCount: Int?
    let reelCount: Int?
    let createdAt: String
    let readAt: String?
    /// 알림이 속한 그룹명(D단계). 백엔드 groupName(nullable) 과 1:1. 미존재/삭제 그룹은 null → 표시 생략.
    let groupName: String?
}

/// 백엔드 NotificationV1Dto.NotificationListResponse.
struct NotificationListResponse: Decodable {
    let items: [NotificationItem]
    let unreadCount: Int
}

/// 백엔드 NotificationV1Dto.PinItem(상세 응답 pins 배열 원소)과 1:1.
/// 좌표는 number 직렬화 → Double?(soft-delete/미존재 핀은 null 가능).
struct NotificationPinItem: Decodable {
    let pinId: Int
    let placeName: String
    let address: String?
    let latitude: Double?
    let longitude: Double?
    let deleted: Bool
    let instagramUrl: String?
    let memo: String?
    let tag: String?
}

/// 백엔드 NotificationV1Dto.NotificationDetailResponse.
struct NotificationDetail: Decodable {
    let id: Int
    let type: NotificationType
    let registeredByNickname: String?
    let createdAt: String
    let pins: [NotificationPinItem]
    /// 알림이 속한 그룹명(D단계). 백엔드 groupName(nullable) 과 1:1. 미존재/삭제 그룹은 null → 표시 생략.
    let groupName: String?
}

/// 백엔드 NotificationV1Dto.ReadAllResponse.
struct ReadAllResponse: Decodable {
    let updatedCount: Int
}

// MARK: - NotificationAPIProtocol

protocol NotificationAPIProtocol: Sendable {
    /// GET /notifications — 최근 알림 목록 + 미읽음 수.
    func list() async throws -> NotificationListResponse
    /// POST /notifications/read-all — 전체 읽음 처리.
    func readAll() async throws -> ReadAllResponse
    /// GET /notifications/{id} — 알림 상세(연결 핀 목록).
    func detail(id: Int) async throws -> NotificationDetail
}

// MARK: - NotificationAPI

final class NotificationAPI: NotificationAPIProtocol {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    func list() async throws -> NotificationListResponse {
        try await client.request("/notifications", type: NotificationListResponse.self)
    }

    func readAll() async throws -> ReadAllResponse {
        try await client.request(
            "/notifications/read-all",
            method: "POST",
            type: ReadAllResponse.self
        )
    }

    func detail(id: Int) async throws -> NotificationDetail {
        try await client.request("/notifications/\(id)", type: NotificationDetail.self)
    }
}
