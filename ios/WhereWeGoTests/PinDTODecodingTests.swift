import XCTest
@testable import WhereWeGo

// AC-3: 백엔드 PinV1Dto 응답 JSON → DTO 전 필드 디코딩 검증.
// 백엔드 계약(PinSummaryResponse/UpdatePinResponse/PinListResponse) 과 1:1 정합 확인.
final class PinDTODecodingTests: XCTestCase {

    // MARK: - PinSummary 전 필드(AC-3)

    func test_decode_pinSummary_allFields() throws {
        // Given 백엔드 PinSummaryResponse 전 필드(ZonedDateTime → ISO-8601 String).
        let json = """
        {
          "id": 101,
          "groupId": 7,
          "createdBy": 42,
          "createdByNickname": "보승",
          "placeName": "성수 카페",
          "address": "서울 성동구 성수동",
          "latitude": 37.5446281,
          "longitude": 127.0557234,
          "instagramUrl": "https://instagram.com/reel/abc",
          "memo": "분위기 좋음",
          "memoSource": "MANUAL",
          "tag": "WISH",
          "createdAt": "2026-05-24T12:34:56.789+09:00",
          "visitedAt": "2026-05-25T09:00:00+09:00",
          "memoUpdatedBy": 43,
          "memoUpdatedByNickname": "지은",
          "photoUrl": "https://cdn.example.com/p/101.jpg",
          "photoThumbnailUrl": "https://cdn.example.com/p/101_thumb.jpg"
        }
        """

        // When
        let pin = try JSONDecoder().decode(PinSummary.self, from: Data(json.utf8))

        // Then 전 필드 정확 디코딩
        XCTAssertEqual(pin.id, 101)
        XCTAssertEqual(pin.groupId, 7)
        XCTAssertEqual(pin.createdBy, 42)
        XCTAssertEqual(pin.createdByNickname, "보승")
        XCTAssertEqual(pin.placeName, "성수 카페")
        XCTAssertEqual(pin.address, "서울 성동구 성수동")
        XCTAssertEqual(pin.latitude, 37.5446281, accuracy: 1e-7)
        XCTAssertEqual(pin.longitude, 127.0557234, accuracy: 1e-7)
        XCTAssertEqual(pin.instagramUrl, "https://instagram.com/reel/abc")
        XCTAssertEqual(pin.memo, "분위기 좋음")
        XCTAssertEqual(pin.memoSource, .MANUAL)
        XCTAssertEqual(pin.tag, .WISH)
        XCTAssertEqual(pin.createdAt, "2026-05-24T12:34:56.789+09:00")
        XCTAssertEqual(pin.visitedAt, "2026-05-25T09:00:00+09:00")
        XCTAssertEqual(pin.memoUpdatedBy, 43)
        XCTAssertEqual(pin.memoUpdatedByNickname, "지은")
        XCTAssertEqual(pin.photoUrl, "https://cdn.example.com/p/101.jpg")
        XCTAssertEqual(pin.photoThumbnailUrl, "https://cdn.example.com/p/101_thumb.jpg")
    }

    func test_decode_pinSummary_optionalsAbsentAsNil() throws {
        // Given 옵셔널 필드 null/부재(신규 핀: 방문 전·메모 없음·사진 없음).
        let json = """
        {
          "id": 1,
          "groupId": 2,
          "createdBy": 3,
          "createdByNickname": null,
          "placeName": "장소",
          "address": null,
          "latitude": 37.0,
          "longitude": 127.0,
          "instagramUrl": null,
          "memo": null,
          "memoSource": null,
          "tag": "REEL",
          "createdAt": "2026-01-01T00:00:00+09:00",
          "visitedAt": null,
          "memoUpdatedBy": null,
          "memoUpdatedByNickname": null,
          "photoUrl": null,
          "photoThumbnailUrl": null
        }
        """

        // When
        let pin = try JSONDecoder().decode(PinSummary.self, from: Data(json.utf8))

        // Then 옵셔널은 nil, 필수는 정상
        XCTAssertEqual(pin.id, 1)
        XCTAssertEqual(pin.tag, .REEL)
        XCTAssertNil(pin.createdByNickname)
        XCTAssertNil(pin.address)
        XCTAssertNil(pin.instagramUrl)
        XCTAssertNil(pin.memo)
        XCTAssertNil(pin.memoSource)
        XCTAssertNil(pin.visitedAt)
        XCTAssertNil(pin.memoUpdatedBy)
        XCTAssertNil(pin.photoUrl)
        XCTAssertNil(pin.photoThumbnailUrl)
    }

    // MARK: - visitors[] 추가형 계약(정책 v2 FR-B4)

    func test_decode_pinSummary_visitorsPresent() throws {
        // Given visitors[] 포함 응답(방문자 2명). 정책 v2 추가형 필드.
        let json = """
        {
          "id": 9, "groupId": 2, "createdBy": 3, "createdByNickname": null, "placeName": "추억",
          "address": null, "latitude": 37.0, "longitude": 127.0, "instagramUrl": null, "memo": null,
          "memoSource": null, "tag": "MEMORY", "createdAt": "2026-01-01T00:00:00+09:00",
          "visitedAt": null, "memoUpdatedBy": null, "memoUpdatedByNickname": null,
          "photoUrl": null, "photoThumbnailUrl": null,
          "visitors": [
            {"userId": 3, "nickname": "보승", "profileImageUrl": "https://cdn/u3.jpg", "source": "SELF"},
            {"userId": 4, "nickname": "지은", "profileImageUrl": null, "source": "TAGGED"}
          ]
        }
        """

        // When
        let pin = try JSONDecoder().decode(PinSummary.self, from: Data(json.utf8))

        // Then visitors 정확 디코딩(프사 없음은 nil → 이니셜 폴백)
        XCTAssertEqual(pin.visitors?.count, 2)
        XCTAssertEqual(pin.visitors?.first?.userId, 3)
        XCTAssertEqual(pin.visitors?.first?.nickname, "보승")
        XCTAssertEqual(pin.visitors?.first?.profileImageUrl, "https://cdn/u3.jpg")
        XCTAssertNil(pin.visitors?.last?.profileImageUrl)
    }

    func test_decode_pinSummary_visitorsAbsentAsNil() throws {
        // Given visitors 키 부재(구서버 호환). decodeIfPresent → nil.
        let json = """
        {
          "id": 1, "groupId": 2, "createdBy": 3, "createdByNickname": null, "placeName": "장소",
          "address": null, "latitude": 37.0, "longitude": 127.0, "instagramUrl": null, "memo": null,
          "memoSource": null, "tag": "REEL", "createdAt": "2026-01-01T00:00:00+09:00",
          "visitedAt": null, "memoUpdatedBy": null, "memoUpdatedByNickname": null,
          "photoUrl": null, "photoThumbnailUrl": null
        }
        """

        // When
        let pin = try JSONDecoder().decode(PinSummary.self, from: Data(json.utf8))

        // Then 키 부재 → visitors nil(구 클라/구서버 무시 가능)
        XCTAssertNil(pin.visitors)
    }

    // MARK: - DeclareVisitResponse(정책 v2 FR-B2/B3)

    func test_decode_declareVisitResponse() throws {
        // Given 방문 선언 응답(converted=true + visitors 2명).
        let json = """
        {
          "converted": true, "alreadyConverted": false,
          "visitors": [
            {"userId": 3, "nickname": "보승", "profileImageUrl": null, "source": "SELF"},
            {"userId": 4, "nickname": "지은", "profileImageUrl": "https://cdn/u4.jpg", "source": "TAGGED"}
          ]
        }
        """

        // When
        let response = try JSONDecoder().decode(DeclareVisitResponse.self, from: Data(json.utf8))

        // Then 분기 플래그 + visitors 디코딩
        XCTAssertTrue(response.converted)
        XCTAssertFalse(response.alreadyConverted)
        XCTAssertEqual(response.visitors.count, 2)
        XCTAssertEqual(response.visitors.last?.source, "TAGGED")
    }

    // MARK: - PinListResponse (legacy {items} vs paged)

    func test_decode_pinListResponse_legacy_itemsOnly() throws {
        // Given legacy 모드 — totalCount/hasNext null.
        let json = """
        {
          "items": [
            {"id":1,"groupId":2,"createdBy":3,"createdByNickname":null,"placeName":"A","address":null,
             "latitude":37.0,"longitude":127.0,"instagramUrl":null,"memo":null,"memoSource":null,
             "tag":"WISH","createdAt":"2026-01-01T00:00:00+09:00","visitedAt":null,
             "memoUpdatedBy":null,"memoUpdatedByNickname":null,"photoUrl":null,"photoThumbnailUrl":null}
          ],
          "totalCount": null,
          "hasNext": null
        }
        """

        // When
        let response = try JSONDecoder().decode(PinListResponse.self, from: Data(json.utf8))

        // Then items 만 채워지고 페이지 메타는 nil
        XCTAssertEqual(response.items.count, 1)
        XCTAssertEqual(response.items.first?.placeName, "A")
        XCTAssertNil(response.totalCount)
        XCTAssertNil(response.hasNext)
    }

    func test_decode_pinListResponse_paged() throws {
        // Given paged 모드 — totalCount/hasNext 존재.
        let json = """
        {"items":[],"totalCount":25,"hasNext":true}
        """

        // When
        let response = try JSONDecoder().decode(PinListResponse.self, from: Data(json.utf8))

        // Then 메타 디코딩
        XCTAssertEqual(response.totalCount, 25)
        XCTAssertEqual(response.hasNext, true)
    }

    // MARK: - UpdatePinResponse (transitionedToMemoryNow, AC-15 분기 근거)

    func test_decode_updatePinResponse_transitionedTrue() throws {
        // Given PATCH 응답 — summary 중첩 + transitionedToMemoryNow.
        let json = """
        {
          "summary": {
            "id":5,"groupId":2,"createdBy":3,"createdByNickname":null,"placeName":"추억",
            "address":null,"latitude":37.0,"longitude":127.0,"instagramUrl":null,"memo":null,
            "memoSource":null,"tag":"MEMORY","createdAt":"2026-01-01T00:00:00+09:00",
            "visitedAt":"2026-05-25T09:00:00+09:00","memoUpdatedBy":null,"memoUpdatedByNickname":null,
            "photoUrl":null,"photoThumbnailUrl":null
          },
          "transitionedToMemoryNow": true
        }
        """

        // When
        let response = try JSONDecoder().decode(UpdatePinResponse.self, from: Data(json.utf8))

        // Then summary 중첩 + 플래그 디코딩
        XCTAssertEqual(response.summary.id, 5)
        XCTAssertEqual(response.summary.tag, .MEMORY)
        XCTAssertEqual(response.summary.visitedAt, "2026-05-25T09:00:00+09:00")
        XCTAssertTrue(response.transitionedToMemoryNow)
    }

    func test_decode_updatePinResponse_transitionedFalse() throws {
        // Given 두 번째 동시 전환 — transitionedToMemoryNow false(confetti/메모시트 스킵).
        let json = """
        {
          "summary": {
            "id":5,"groupId":2,"createdBy":3,"createdByNickname":null,"placeName":"추억",
            "address":null,"latitude":37.0,"longitude":127.0,"instagramUrl":null,"memo":null,
            "memoSource":null,"tag":"MEMORY","createdAt":"2026-01-01T00:00:00+09:00",
            "visitedAt":null,"memoUpdatedBy":null,"memoUpdatedByNickname":null,
            "photoUrl":null,"photoThumbnailUrl":null
          },
          "transitionedToMemoryNow": false
        }
        """

        // When
        let response = try JSONDecoder().decode(UpdatePinResponse.self, from: Data(json.utf8))

        // Then
        XCTAssertFalse(response.transitionedToMemoryNow)
    }
}
