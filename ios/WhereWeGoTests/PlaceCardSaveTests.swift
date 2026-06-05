import XCTest
@testable import WhereWeGo

// 카드 → 핀 저장 테스트(설계 §5, FR-5/AC-3).
// - 좌표 있는 카드 저장 성공(tag=.REEL, groupId=활성그룹).
// - 409(PLC_DUPLICATE_PIN) → saveInfoMessage 흡수(에러 미전파).
// - 좌표 없는 카드 스킵 + 안내.
//
// Stub(StubChatAPI/StubBotPinAPI/StubBotGroupAPI/StubChatRealtime)·makeFrame·makeCurrentUser 는
// BotChatViewModelTests.swift 의 공유 정의를 재사용한다(동일 테스트 타깃).
@MainActor
final class PlaceCardSaveTests: XCTestCase {

    // MARK: - 저장 성공

    func test_savePlaceCards_success_callsCreateWithReelTagAndGroupId() async {
        let pinAPI = StubBotPinAPI()
        pinAPI.createResult = .success(makePinSummary(id: 1))
        let vm = makeViewModel(pinAPI: pinAPI, group: ActiveGroup(groupId: 7, name: "팀", memberCount: 2))

        let cards = [makePlaceCard(name: "성수동 카페", latitude: 37.5, longitude: 127.05)]
        await vm.savePlaceCards(cards, from: 100)

        XCTAssertEqual(pinAPI.createRequests.count, 1)
        let request = pinAPI.createRequests.first
        XCTAssertEqual(request?.placeName, "성수동 카페")
        XCTAssertEqual(request?.latitude, 37.5)
        XCTAssertEqual(request?.longitude, 127.05)
        XCTAssertEqual(request?.tag, .REEL)
        XCTAssertNil(request?.instagramUrl)
        // 성공 안내(에러 아님).
        XCTAssertNotNil(vm.saveInfoMessage)
    }

    // MARK: - 409 흡수(AC-3)

    func test_savePlaceCards_duplicate409_absorbedAsInfo() async {
        let pinAPI = StubBotPinAPI()
        pinAPI.createResult = .failure(APIError(code: "PLC_DUPLICATE_PIN", status: 409, message: "duplicate"))
        let vm = makeViewModel(pinAPI: pinAPI, group: ActiveGroup(groupId: 1, name: "팀", memberCount: 2))

        let cards = [makePlaceCard(name: "중복 장소", latitude: 37.5, longitude: 127.0)]
        // throw 하지 않아야 한다(흡수). saveInfoMessage 로 안내.
        await vm.savePlaceCards(cards, from: 100)

        XCTAssertEqual(pinAPI.createRequests.count, 1)
        XCTAssertEqual(vm.saveInfoMessage, "이미 저장된 장소예요")
    }

    // MARK: - 좌표 없는 카드 스킵

    func test_savePlaceCards_noCoordinate_skipped() async {
        let pinAPI = StubBotPinAPI()
        pinAPI.createResult = .success(makePinSummary(id: 1))
        let vm = makeViewModel(pinAPI: pinAPI, group: ActiveGroup(groupId: 1, name: "팀", memberCount: 2))

        let cards = [makePlaceCard(name: "좌표 없음", latitude: nil, longitude: nil)]
        await vm.savePlaceCards(cards, from: 100)

        // create 미호출(스킵), 안내 표시.
        XCTAssertTrue(pinAPI.createRequests.isEmpty)
        XCTAssertNotNil(vm.saveInfoMessage)
        XCTAssertTrue(vm.saveInfoMessage?.contains("좌표") ?? false)
    }

    func test_savePlaceCards_mixed_savesOnlyCoordinated() async {
        let pinAPI = StubBotPinAPI()
        pinAPI.createResult = .success(makePinSummary(id: 1))
        let vm = makeViewModel(pinAPI: pinAPI, group: ActiveGroup(groupId: 1, name: "팀", memberCount: 2))

        let cards = [
            makePlaceCard(name: "좌표 있음", latitude: 37.5, longitude: 127.0),
            makePlaceCard(name: "좌표 없음", latitude: nil, longitude: nil)
        ]
        await vm.savePlaceCards(cards, from: 100)

        // 좌표 있는 1건만 create.
        XCTAssertEqual(pinAPI.createRequests.count, 1)
        XCTAssertEqual(pinAPI.createRequests.first?.placeName, "좌표 있음")
    }

    // MARK: - 헬퍼

    private func makeViewModel(pinAPI: PinAPIProtocol, group: ActiveGroup?) -> BotChatViewModel {
        BotChatViewModel(
            chatAPI: StubChatAPI(),
            pinAPI: pinAPI,
            groupAPI: StubBotGroupAPI(group: group),
            currentUser: makeCurrentUser()
        )
    }

    private func makePinSummary(id: Int) -> PinSummary {
        PinSummary(
            id: id, groupId: 1, createdBy: 1, createdByNickname: "tester",
            placeName: "장소\(id)", address: nil, latitude: 37.5, longitude: 127.0,
            instagramUrl: nil, memo: nil, memoSource: nil, tag: .REEL,
            createdAt: "2026-01-01T00:00:00Z", visitedAt: nil,
            memoUpdatedBy: nil, memoUpdatedByNickname: nil,
            photoUrl: nil, photoThumbnailUrl: nil
        )
    }
}

/// PlaceCard 는 Decodable 만 보유(메모리 init 없음) → JSON 경유 생성.
@MainActor
func makePlaceCard(
    name: String,
    address: String? = "서울특별시",
    latitude: Double?,
    longitude: Double?,
    kakaoPlaceId: String? = nil
) -> PlaceCard {
    // String.init 오버로드가 보간 안에서 ambiguous → 보간 클로저로 명시(Swift 6).
    let kakaoStr = kakaoPlaceId.map { "\"\($0)\"" } ?? "null"
    let addrStr = address.map { "\"\($0)\"" } ?? "null"
    let latStr = latitude.map { "\($0)" } ?? "null"
    let lngStr = longitude.map { "\($0)" } ?? "null"
    let json = "{\"kakaoPlaceId\":\(kakaoStr),\"name\":\"\(name)\",\"address\":\(addrStr),\"latitude\":\(latStr),\"longitude\":\(lngStr)}"
    return try! JSONDecoder().decode(PlaceCard.self, from: Data(json.utf8))
}
