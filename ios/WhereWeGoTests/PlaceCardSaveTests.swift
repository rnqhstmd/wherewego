import XCTest
@testable import WhereWeGo

// 카드 → 핀 저장 위저드 테스트(FR-I5, BR-1/2/3, AC-5/6/8/9/14).
// - 좌표 있는 카드 전부 저장(체크=WISH/미체크=REEL), 메모/instagramUrl 공통 기록.
// - 409(PLC_DUPLICATE_PIN) → 흡수(결과 목록 제외, duplicateCount 집계).
// - 좌표 없는 카드 스킵(BR-1).
//
// Stub(StubChatAPI/StubBotPinAPI)·makeFrame·makeCurrentUser 는
// BotChatViewModelTests.swift 의 공유 정의를 재사용한다(동일 테스트 타깃).
// DM 그룹별 전환으로 그룹 API 목(StubBotGroupAPI)은 불필요 — groupId 를 직접 주입한다.
@MainActor
final class PlaceCardSaveTests: XCTestCase {

    // MARK: - 저장 성공(체크=WISH/미체크=REEL, AC-5/8)

    func test_savePlaceCards_success_savesWishAndReelWithUrlAndMemo() async {
        let pinAPI = StubBotPinAPI()
        pinAPI.createResult = .success(makePinSummary(id: 1))
        let vm = makeViewModel(pinAPI: pinAPI, groupId: 7)

        let wishCard = makePlaceCard(name: "성수동 카페", latitude: 37.5, longitude: 127.05)
        let reelCard = makePlaceCard(name: "발견 식당", latitude: 37.6, longitude: 127.06)
        let url = "https://instagram.com/reel/abc"
        await vm.savePlaceCards(
            cards: [wishCard, reelCard],
            wishIDs: [wishCard.id],
            memo: "데이트 코스",
            sourceInstagramUrl: url
        )

        // 좌표 있는 2건 모두 저장.
        XCTAssertEqual(pinAPI.createRequests.count, 2)
        // AC-5: 릴스 저장 핀이 모두 방의 groupId(=7)로 귀속(myActiveGroup 추정 아님).
        XCTAssertEqual(pinAPI.createGroupIds, [7, 7])
        let wishReq = pinAPI.createRequests.first { $0.placeName == "성수동 카페" }
        let reelReq = pinAPI.createRequests.first { $0.placeName == "발견 식당" }
        XCTAssertEqual(wishReq?.tag, .WISH)
        XCTAssertEqual(reelReq?.tag, .REEL)
        // 메모/instagramUrl 공통 기록(BR-3/AC-8).
        XCTAssertEqual(wishReq?.memo, "데이트 코스")
        XCTAssertEqual(reelReq?.memo, "데이트 코스")
        XCTAssertEqual(wishReq?.instagramUrl, url)
        XCTAssertEqual(reelReq?.instagramUrl, url)

        // 결과 카드(FR-I8): 위시 1·발견 1, 출처 URL 노출 가능.
        let result = vm.saveResult
        XCTAssertEqual(result?.wishNames, ["성수동 카페"])
        XCTAssertEqual(result?.reelNames, ["발견 식당"])
        XCTAssertEqual(result?.duplicateCount, 0)
        XCTAssertEqual(result?.sourceInstagramUrl, url)
    }

    // MARK: - 메모 없이 저장(건너뛰기, AC-7)

    func test_savePlaceCards_noMemo_savesNilMemo() async {
        let pinAPI = StubBotPinAPI()
        pinAPI.createResult = .success(makePinSummary(id: 1))
        let vm = makeViewModel(pinAPI: pinAPI, groupId: 1)

        let card = makePlaceCard(name: "장소", latitude: 37.5, longitude: 127.0)
        await vm.savePlaceCards(cards: [card], wishIDs: [], memo: nil, sourceInstagramUrl: nil)

        XCTAssertEqual(pinAPI.createRequests.count, 1)
        XCTAssertNil(pinAPI.createRequests.first?.memo)
        XCTAssertNil(pinAPI.createRequests.first?.instagramUrl)
        XCTAssertEqual(vm.saveResult?.reelNames, ["장소"])
    }

    // MARK: - 409 흡수(AC-14)

    func test_savePlaceCards_duplicate409_absorbedNotInList() async {
        let pinAPI = StubBotPinAPI()
        pinAPI.createResult = .failure(APIError(code: "PLC_DUPLICATE_PIN", status: 409, message: "duplicate"))
        let vm = makeViewModel(pinAPI: pinAPI, groupId: 1)

        let card = makePlaceCard(name: "중복 장소", latitude: 37.5, longitude: 127.0)
        // throw 하지 않아야 한다(흡수).
        await vm.savePlaceCards(cards: [card], wishIDs: [card.id], memo: nil, sourceInstagramUrl: "https://insta/reel/x")

        XCTAssertEqual(pinAPI.createRequests.count, 1)
        // 결과 목록에는 미포함(N+M=0), duplicateCount=1(AC-9/14).
        XCTAssertEqual(vm.saveResult?.wishNames, [])
        XCTAssertEqual(vm.saveResult?.reelNames, [])
        XCTAssertEqual(vm.saveResult?.duplicateCount, 1)
        // 저장 성공 핀 0개 → 출처 URL 미노출(BR-7).
        XCTAssertNil(vm.saveResult?.sourceInstagramUrl)
    }

    // MARK: - 좌표 없는 카드 스킵(BR-1)

    func test_savePlaceCards_noCoordinate_skipped() async {
        let pinAPI = StubBotPinAPI()
        pinAPI.createResult = .success(makePinSummary(id: 1))
        let vm = makeViewModel(pinAPI: pinAPI, groupId: 1)

        let card = makePlaceCard(name: "좌표 없음", latitude: nil, longitude: nil)
        await vm.savePlaceCards(cards: [card], wishIDs: [card.id], memo: nil, sourceInstagramUrl: nil)

        // create 미호출(스킵).
        XCTAssertTrue(pinAPI.createRequests.isEmpty)
        XCTAssertEqual(vm.saveResult?.wishNames, [])
        XCTAssertEqual(vm.saveResult?.reelNames, [])
    }

    func test_savePlaceCards_mixed_savesOnlyCoordinated() async {
        let pinAPI = StubBotPinAPI()
        pinAPI.createResult = .success(makePinSummary(id: 1))
        let vm = makeViewModel(pinAPI: pinAPI, groupId: 1)

        let cards = [
            makePlaceCard(name: "좌표 있음", latitude: 37.5, longitude: 127.0),
            makePlaceCard(name: "좌표 없음", latitude: nil, longitude: nil)
        ]
        await vm.savePlaceCards(cards: cards, wishIDs: [], memo: nil, sourceInstagramUrl: nil)

        // 좌표 있는 1건만 create.
        XCTAssertEqual(pinAPI.createRequests.count, 1)
        XCTAssertEqual(pinAPI.createRequests.first?.placeName, "좌표 있음")
        XCTAssertEqual(vm.saveResult?.reelNames, ["좌표 있음"])
    }

    // MARK: - "보러가기" 딥링크(FR-I10/I15)

    func test_showOnMap_setsReelFocusPending() async {
        let router = DeepLinkRouter()
        let vm = BotChatViewModel(
            groupId: 1,
            chatAPI: StubChatAPI(),
            pinAPI: StubBotPinAPI(),
            currentUser: makeCurrentUser(),
            deepLinkRouter: router
        )

        vm.showOnMap(instagramUrl: "https://instagram.com/reel/abc")

        XCTAssertEqual(router.pending, .reelFocus(groupId: 1, instagramUrl: "https://instagram.com/reel/abc"))
    }

    // MARK: - 헬퍼

    private func makeViewModel(pinAPI: PinAPIProtocol, groupId: Int) -> BotChatViewModel {
        // DM 그룹별 전환: 활성 그룹 추정(groupAPI) 제거 → 방의 groupId 주입. savePlaceCards 가 이 groupId 로 핀 저장(AC-5).
        BotChatViewModel(
            groupId: groupId,
            chatAPI: StubChatAPI(),
            pinAPI: pinAPI,
            currentUser: makeCurrentUser(),
            deepLinkRouter: DeepLinkRouter()
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
