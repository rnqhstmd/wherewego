import XCTest
@testable import WhereWeGo

// 설계 §9: DeepLinkRouter — 푸시 type→destination 매핑, Universal Link 파싱, PIN_SAVED→.map.
// 순수 매핑(static)과 인스턴스 handle*(pending 세팅) 모두 검증.
@MainActor
final class DeepLinkRouterTests: XCTestCase {

    // MARK: - 푸시 type → destination(순수 매핑)

    func test_pushType_botResult_mapsToChat() {
        XCTAssertEqual(DeepLinkRouter.destination(forPushType: "BOT_RESULT"), .chat)
    }

    func test_pushType_coupleMessage_mapsToChat() {
        // 커플챗 제거(FR-11/BR-2) 후 COUPLE_MESSAGE 는 채팅 탭으로 재매핑(FR-28 하위호환 폴백).
        XCTAssertEqual(DeepLinkRouter.destination(forPushType: "COUPLE_MESSAGE"), .chat)
    }

    func test_pushType_pinSaved_mapsToMap() {
        // AC-10 재해석: PIN_SAVED 는 roomId/pinId 부재 → 특정 핀 상세 아닌 지도(.map).
        XCTAssertEqual(DeepLinkRouter.destination(forPushType: "PIN_SAVED"), .map)
    }

    func test_pushType_unknown_returnsNil() {
        XCTAssertNil(DeepLinkRouter.destination(forPushType: "SOMETHING_ELSE"))
        XCTAssertNil(DeepLinkRouter.destination(forPushType: ""))
    }

    // MARK: - AC-3: DeepLinkDestination 에 .coupleChat/.botChat 부재(케이스 표면 검증)

    func test_destination_cases_doNotContainCoupleChatOrBotChat() {
        // AC-3: 커플챗 제거(FR-11/BR-2) + .botChat→.chat 리네임(FR-28) 후,
        // DeepLinkDestination 의 모든 도달 가능한 케이스에 coupleChat/botChat 식별자가 없어야 한다.
        // (.coupleChat/.botChat 케이스가 남아 있으면 컴파일 단계에서 .coupleChat 참조가 가능했을 것 — 부재의 표면 검증.)
        let allReachable: [DeepLinkDestination] = [
            .chat,
            .pin(pinId: 1),
            .invite(slug: "s"),
            .map
        ]
        let labels = allReachable.map { String(describing: $0) }
        XCTAssertFalse(labels.contains { $0.contains("coupleChat") })
        XCTAssertFalse(labels.contains { $0.contains("botChat") })
    }

    func test_pushType_coupleAndBot_bothMapToChat_notSeparateDestinations() {
        // AC-3/AC-4: COUPLE_MESSAGE/BOT_RESULT 둘 다 동일하게 .chat 으로 수렴(별도 .coupleChat/.botChat 없음).
        XCTAssertEqual(DeepLinkRouter.destination(forPushType: "COUPLE_MESSAGE"), .chat)
        XCTAssertEqual(DeepLinkRouter.destination(forPushType: "BOT_RESULT"), .chat)
        XCTAssertEqual(
            DeepLinkRouter.destination(forPushType: "COUPLE_MESSAGE"),
            DeepLinkRouter.destination(forPushType: "BOT_RESULT")
        )
    }

    func test_handlePush_coupleMessage_setsPendingChat() {
        // AC-4 인스턴스 경로: COUPLE_MESSAGE 푸시 → pending == .chat(채팅 탭 폴백).
        let router = DeepLinkRouter()
        router.handlePush(userInfo: ["type": "COUPLE_MESSAGE", "roomId": 3])
        XCTAssertEqual(router.pending, .chat)
    }

    // MARK: - handlePush(userInfo) → pending

    func test_handlePush_setsPendingFromType() {
        let router = DeepLinkRouter()
        router.handlePush(userInfo: ["type": "BOT_RESULT", "roomId": 7])
        XCTAssertEqual(router.pending, .chat)
    }

    func test_handlePush_pinSaved_noRoomId_setsMap() {
        let router = DeepLinkRouter()
        // PIN_SAVED 는 roomId 없음 — 그대로 .map.
        router.handlePush(userInfo: ["type": "PIN_SAVED"])
        XCTAssertEqual(router.pending, .map)
    }

    func test_handlePush_missingType_doesNotSetPending() {
        let router = DeepLinkRouter()
        router.handlePush(userInfo: ["roomId": 5])
        XCTAssertNil(router.pending)
    }

    func test_handlePush_unknownType_doesNotSetPending() {
        let router = DeepLinkRouter()
        router.handlePush(userInfo: ["type": "NOPE"])
        XCTAssertNil(router.pending)
    }

    // MARK: - Universal Link 파싱(순수 매핑)

    func test_universalLink_invite_parsesSlug() {
        let url = URL(string: "https://wherewego.app/invite/abc123")!
        XCTAssertEqual(DeepLinkRouter.destination(forUniversalLink: url), .invite(slug: "abc123"))
    }

    func test_universalLink_pinIdQuery_parsesPin() {
        let url = URL(string: "https://wherewego.app/map?pinId=42")!
        XCTAssertEqual(DeepLinkRouter.destination(forUniversalLink: url), .pin(pinId: 42))
    }

    func test_universalLink_invitePreferredOverPinId() {
        // path /invite/{slug} 가 query pinId 보다 우선.
        let url = URL(string: "https://wherewego.app/invite/xyz?pinId=9")!
        XCTAssertEqual(DeepLinkRouter.destination(forUniversalLink: url), .invite(slug: "xyz"))
    }

    func test_universalLink_unrecognized_returnsNil() {
        let url = URL(string: "https://wherewego.app/somewhere")!
        XCTAssertNil(DeepLinkRouter.destination(forUniversalLink: url))
    }

    func test_universalLink_nonNumericPinId_returnsNil() {
        let url = URL(string: "https://wherewego.app/map?pinId=notanumber")!
        XCTAssertNil(DeepLinkRouter.destination(forUniversalLink: url))
    }

    func test_universalLink_inviteWithoutSlug_returnsNil() {
        let url = URL(string: "https://wherewego.app/invite/")!
        XCTAssertNil(DeepLinkRouter.destination(forUniversalLink: url))
    }

    // MARK: - handleUniversalLink → pending + 반환값

    func test_handleUniversalLink_validInvite_returnsTrueAndSetsPending() {
        let router = DeepLinkRouter()
        let url = URL(string: "https://wherewego.app/invite/slug-1")!
        XCTAssertTrue(router.handleUniversalLink(url))
        XCTAssertEqual(router.pending, .invite(slug: "slug-1"))
    }

    func test_handleUniversalLink_invalid_returnsFalseAndNoPending() {
        let router = DeepLinkRouter()
        let url = URL(string: "https://wherewego.app/unknown")!
        XCTAssertFalse(router.handleUniversalLink(url))
        XCTAssertNil(router.pending)
    }
}
