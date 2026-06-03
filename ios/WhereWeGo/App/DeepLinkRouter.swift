import Foundation

// 푸시 탭/Universal Link → 앱 내 이동 대상 라우팅(설계 §9, AC-10/AC-11, FR-19).
// 입력원:
//  (a) 푸시 탭 userInfo: ApnsPushSender 가 직렬화한 custom property "type"(String)/"roomId"(Long).
//      - BOT_RESULT  → .chat  (채팅 탭. userId 기반 토픽이므로 roomId 무시)
//      - COUPLE_MESSAGE → .chat (커플챗 제거 후 채팅 탭으로 재매핑 — 하위호환 폴백, FR-28)
//      - PIN_SAVED   → .map      (roomId/pinId 없음 — 특정 핀 상세 아님, 지도/핀 목록으로)
//  (b) Universal Link:
//      - /invite/{slug} → .invite(slug)
//      - ?pinId=N       → .pin(pinId)
// 매핑 실패 시 false(파싱 실패) — handlePush 는 unknown type 무시, handleUniversalLink 는 false 반환.
// 소비: MainTabView 가 pending 을 읽어 탭 전환(.pin/.map → 지도+flyTo, .invite → 그룹 합류). 소비 후 nil.

/// 딥링크 이동 대상(설계 §9). MainTabView 가 destination 별로 탭 전환/네비게이션.
enum DeepLinkDestination: Equatable {
    case chat
    case pin(pinId: Int)
    case invite(slug: String)
    case map
}

@MainActor
final class DeepLinkRouter: ObservableObject {

    /// 소비 대기 중인 이동 대상. MainTabView 가 읽고 처리 후 nil 로 리셋.
    /// 인증 전 보류 → authenticated 후 소비(설계 §9 폴백).
    @Published var pending: DeepLinkDestination?

    /// APNs userInfo 의 custom property 키(ApnsPushSender 직렬화 기준).
    /// nonisolated — 콜백(AppNotificationDelegate) 의 nonisolated 컨텍스트에서 키만 읽어 type(String) 을 추출하기 위함.
    nonisolated static let typeKey = "type"

    /// 푸시 탭 → userInfo type 으로 destination 결정 후 pending 세팅.
    /// unknown/누락 type 은 무시(pending 미변경) — 의도치 않은 이동 방지.
    func handlePush(userInfo: [AnyHashable: Any]) {
        handlePush(type: userInfo[Self.typeKey] as? String)
    }

    /// 추출된 push type(Sendable String) 으로 destination 결정 후 pending 세팅.
    /// nonisolated 콜백이 non-Sendable userInfo 를 @MainActor 로 보내지 않도록, 키 추출은 호출부에서 하고
    /// Sendable 한 type 만 넘겨받는 경로(Swift 6 동시성).
    func handlePush(type: String?) {
        guard let type, let destination = Self.destination(forPushType: type) else {
            return
        }
        pending = destination
    }

    /// Universal Link URL → destination 파싱 후 pending 세팅.
    /// - `/invite/{slug}` → .invite(slug)
    /// - `?pinId=N` → .pin(pinId) (정수 파싱 성공 시)
    /// - 그 외(인식 불가) → false(파싱 실패, pending 미변경).
    /// - Returns: 처리 가능한 링크면 true, 아니면 false.
    @discardableResult
    func handleUniversalLink(_ url: URL) -> Bool {
        guard let destination = Self.destination(forUniversalLink: url) else {
            return false
        }
        pending = destination
        return true
    }

    // MARK: - 순수 매핑(테스트 대상)

    /// 푸시 type 문자열 → destination(순수). 백엔드 PushPayload TYPE_* 상수와 정합.
    /// PIN_SAVED 는 roomId/pinId 부재 → .map(특정 핀 상세 아님, AC-10 재해석).
    static func destination(forPushType type: String) -> DeepLinkDestination? {
        switch type {
        case "BOT_RESULT":
            return .chat
        case "COUPLE_MESSAGE":
            // 커플챗 제거(FR-11/BR-2) 후 채팅 탭으로 재매핑(FR-28 하위호환 폴백).
            return .chat
        case "PIN_SAVED":
            return .map
        default:
            return nil
        }
    }

    /// Universal Link URL → destination(순수). path `/invite/{slug}` 우선, 그다음 query `pinId`.
    static func destination(forUniversalLink url: URL) -> DeepLinkDestination? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return nil
        }
        // /invite/{slug} — path 세그먼트에서 "invite" 다음 비어있지 않은 값.
        let segments = components.path.split(separator: "/").map(String.init)
        if let inviteIndex = segments.firstIndex(of: "invite"),
           inviteIndex + 1 < segments.count {
            let slug = segments[inviteIndex + 1]
            if !slug.isEmpty {
                return .invite(slug: slug)
            }
        }
        // ?pinId=N — 양의 정수만 유효(음수/0/비정수는 무시: pending 미변경).
        if let pinIdValue = components.queryItems?.first(where: { $0.name == "pinId" })?.value,
           let pinId = Int(pinIdValue),
           pinId > 0 {
            return .pin(pinId: pinId)
        }
        return nil
    }
}
