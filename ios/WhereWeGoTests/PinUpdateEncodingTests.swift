import XCTest
@testable import WhereWeGo

// MUST-2: UpdatePinRequest 는 설정된 필드 키만 직렬화하고 미설정 키는 생략해야 한다.
// (Swift JSONEncoder 가 nil 옵셔널을 {"memo":null} 로 내보내면 백엔드가 "미변경"을 "null 변경"으로 오인.)
// 검증: JSONEncoder().encode → JSONSerialization.jsonObject → dict 의 키 집합을 단언.
final class PinUpdateEncodingTests: XCTestCase {

    /// UpdatePinRequest 를 인코딩하여 최상위 JSON 객체의 키 집합을 반환.
    private func encodeKeys(_ request: UpdatePinRequest) throws -> Set<String> {
        let data = try JSONEncoder().encode(request)
        let object = try JSONSerialization.jsonObject(with: data)
        let dict = try XCTUnwrap(object as? [String: Any], "최상위가 JSON 객체여야 함")
        return Set(dict.keys)
    }

    func test_encode_tagOnly_keysAreTagOnly() throws {
        // Given tag 만 설정(memo/placeName 미설정)
        let request = UpdatePinRequest(tag: .set(.MEMORY))

        // When
        let keys = try encodeKeys(request)

        // Then 키 집합 == ["tag"] (memo/placeName 키 부재 — MUST-2 핵심)
        XCTAssertEqual(keys, ["tag"])
    }

    func test_encode_memoOnly_keysAreMemoOnly() throws {
        // Given memo 만 설정
        let request = UpdatePinRequest(memo: .set("새 메모"))

        // When
        let keys = try encodeKeys(request)

        // Then
        XCTAssertEqual(keys, ["memo"])
    }

    func test_encode_tagAndMemo_keysAreBoth() throws {
        // Given tag + memo 설정(placeName 미설정)
        let request = UpdatePinRequest(memo: .set("메모"), tag: .set(.WISH))

        // When
        let keys = try encodeKeys(request)

        // Then 설정된 두 키만, placeName 부재
        XCTAssertEqual(keys, ["memo", "tag"])
    }

    func test_encode_placeNameOnly_keysArePlaceNameOnly() throws {
        // Given placeName 만 설정
        let request = UpdatePinRequest(placeName: .set("새 장소"))

        // When
        let keys = try encodeKeys(request)

        // Then
        XCTAssertEqual(keys, ["placeName"])
    }

    func test_encode_allUnset_emptyObject() throws {
        // Given 아무 필드도 설정 안 함
        let request = UpdatePinRequest()

        // When
        let keys = try encodeKeys(request)

        // Then 빈 객체(키 없음)
        XCTAssertTrue(keys.isEmpty)
    }

    func test_encode_tag_serializesEnumRawValue() throws {
        // Given tag 설정 — enum 이 rawValue("MEMORY") 문자열로 직렬화되는지 확인.
        let request = UpdatePinRequest(tag: .set(.MEMORY))

        // When
        let data = try JSONEncoder().encode(request)
        let dict = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )

        // Then tag 값이 "MEMORY" 문자열(백엔드 PinTag.valueOf 정합)
        XCTAssertEqual(dict["tag"] as? String, "MEMORY")
    }
}
