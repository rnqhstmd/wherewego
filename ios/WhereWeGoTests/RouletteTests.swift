import XCTest
@testable import WhereWeGo

// Roulette 순수 추첨 단위 테스트(설계 §5, AC-10/11).
// frontend/src/app/map/_lib/roulette.test.ts 동치 + SeededGenerator 결정성 검증.
final class RouletteTests: XCTestCase {

    private func makePin(_ id: Int, _ lat: Double, _ lng: Double, _ tag: PinTag = .REEL) -> RoulettePin {
        RoulettePin(pinId: id, coordinate: Coordinate(latitude: lat, longitude: lng), tag: tag)
    }

    // MARK: - pickRandomWithExpansion

    func test_pick_candidateInRadius_returnsPicked() {
        // Given ~0.7km, REEL
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        var rng: RandomNumberGenerator = SeededGenerator(seed: 1)
        // When
        let result = Roulette.pickRandomWithExpansion(center: center, pins: [makePin(1, 37.505, 127.005)], using: &rng)
        // Then 10km 반경에서 픽
        guard case let .picked(pin, radiusKm, candidates, count, distanceKm) = result else {
            return XCTFail("expected picked, got \(result)")
        }
        XCTAssertEqual(radiusKm, 10)
        XCTAssertEqual(pin.pinId, 1)
        XCTAssertGreaterThan(distanceKm, 0)
        XCTAssertLessThan(distanceKm, 1)
        XCTAssertEqual(candidates.count, 1)
        XCTAssertEqual(count, 1)
    }

    func test_pick_allOutsideRadius_returnsExhausted() {
        // Given 매우 먼 핀(AC-10 후보 0건)
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        var rng: RandomNumberGenerator = SeededGenerator(seed: 1)
        // When
        let result = Roulette.pickRandomWithExpansion(center: center, pins: [makePin(3, 35.0, 130.0)], using: &rng)
        // Then exhausted
        XCTAssertEqual(result, .exhausted)
    }

    func test_pick_emptyPins_returnsExhausted() {
        // Given 후보 0개(AC-10)
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        var rng: RandomNumberGenerator = SeededGenerator(seed: 7)
        // When / Then
        XCTAssertEqual(Roulette.pickRandomWithExpansion(center: center, pins: [], using: &rng), .exhausted)
    }

    func test_pick_defaultPool_excludesMemory() {
        // Given REEL/WISH/MEMORY 혼합 — 기본 풀은 REEL+WISH 만
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        let pins = [
            makePin(1, 37.501, 127.001, .REEL),
            makePin(2, 37.502, 127.002, .WISH),
            makePin(3, 37.503, 127.003, .MEMORY),
        ]
        var rng: RandomNumberGenerator = SeededGenerator(seed: 1)
        // When
        let result = Roulette.pickRandomWithExpansion(center: center, pins: pins, using: &rng)
        // Then 후보는 REEL+WISH(1,2)만, MEMORY 제외
        guard case let .picked(_, _, candidates, _, _) = result else {
            return XCTFail("expected picked, got \(result)")
        }
        XCTAssertEqual(candidates.map { $0.pinId }.sorted(), [1, 2])
        XCTAssertNil(candidates.first { $0.tag == .MEMORY })
    }

    func test_pick_wishOnly_passesDefaultPool() {
        // Given WISH 단독
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        var rng: RandomNumberGenerator = SeededGenerator(seed: 1)
        // When
        let result = Roulette.pickRandomWithExpansion(center: center, pins: [makePin(10, 37.505, 127.005, .WISH)], using: &rng)
        // Then WISH 핀 픽
        guard case let .picked(pin, _, _, _, _) = result else {
            return XCTFail("expected picked, got \(result)")
        }
        XCTAssertEqual(pin.pinId, 10)
        XCTAssertEqual(pin.tag, .WISH)
    }

    func test_pick_memoryAllowed_passesMemoryPin() {
        // Given tagsAllowed 에 MEMORY 추가(AC-11)
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        var rng: RandomNumberGenerator = SeededGenerator(seed: 1)
        // When
        let result = Roulette.pickRandomWithExpansion(
            center: center,
            pins: [makePin(5, 37.505, 127.005, .MEMORY)],
            tagsAllowed: [.REEL, .WISH, .MEMORY],
            using: &rng
        )
        // Then MEMORY 핀 통과
        guard case let .picked(pin, _, _, _, _) = result else {
            return XCTFail("expected picked, got \(result)")
        }
        XCTAssertEqual(pin.tag, .MEMORY)
    }

    func test_pick_memoryOnlyInDefaultPool_returnsExhausted() {
        // Given MEMORY 단독 + 기본 풀(REEL+WISH) → 후보 0건(AC-10)
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        var rng: RandomNumberGenerator = SeededGenerator(seed: 1)
        // When / Then
        let result = Roulette.pickRandomWithExpansion(center: center, pins: [makePin(6, 37.505, 127.005, .MEMORY)], using: &rng)
        XCTAssertEqual(result, .exhausted)
    }

    // MARK: - 결정성(SeededGenerator)

    func test_pick_sameSeed_sameResult() {
        // Given 후보 여러 개 + 동일 시드 두 RNG
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        let pins = [
            makePin(1, 37.501, 127.001),
            makePin(2, 37.502, 127.002),
            makePin(3, 37.503, 127.003),
            makePin(4, 37.504, 127.004),
        ]
        var rngA: RandomNumberGenerator = SeededGenerator(seed: 42)
        var rngB: RandomNumberGenerator = SeededGenerator(seed: 42)
        // When
        let a = Roulette.pickRandomWithExpansion(center: center, pins: pins, using: &rngA)
        let b = Roulette.pickRandomWithExpansion(center: center, pins: pins, using: &rngB)
        // Then 동일 시드 → 동일 결과
        XCTAssertEqual(a, b)
    }

    func test_pick_sameSeed_repeatedSequenceDeterministic() {
        // Given 동일 시드로 연속 추첨 시퀀스 두 번
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        let pins = [makePin(1, 37.501, 127.001), makePin(2, 37.502, 127.002), makePin(3, 37.503, 127.003)]
        func sequence() -> [Int] {
            var rng: RandomNumberGenerator = SeededGenerator(seed: 99)
            var ids: [Int] = []
            for _ in 0..<5 {
                if case let .picked(pin, _, _, _, _) = Roulette.pickRandomWithExpansion(center: center, pins: pins, using: &rng) {
                    ids.append(pin.pinId)
                }
            }
            return ids
        }
        // When / Then 두 시퀀스 동일
        XCTAssertEqual(sequence(), sequence())
    }

    // MARK: - reRollFromSamePool

    func test_reRoll_excludesPrevPin() {
        // Given 후보 2개, 직전 핀 1 제외 요청
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        let candidates = [makePin(1, 37.501, 127.001), makePin(2, 37.502, 127.002)]
        var rng: RandomNumberGenerator = SeededGenerator(seed: 3)
        // When
        let result = Roulette.reRollFromSamePool(center: center, candidates: candidates, radiusKm: 10, prevPinId: 1, using: &rng)
        // Then 직전 핀(1) 제외 → 반드시 2
        guard case let .picked(pin, _, _, _, _) = result else {
            return XCTFail("expected picked, got \(result)")
        }
        XCTAssertEqual(pin.pinId, 2)
    }

    func test_reRoll_singleCandidate_returnsSamePin() {
        // Given 후보 1개 + prevPinId 지정(선택지 없음)
        let center = Coordinate(latitude: 37.5, longitude: 127.0)
        var rng: RandomNumberGenerator = SeededGenerator(seed: 3)
        // When
        let result = Roulette.reRollFromSamePool(center: center, candidates: [makePin(10, 37.503, 127.003)], radiusKm: 10, prevPinId: 10, using: &rng)
        // Then 그대로 같은 핀
        guard case let .picked(pin, radiusKm, _, _, distanceKm) = result else {
            return XCTFail("expected picked, got \(result)")
        }
        XCTAssertEqual(pin.pinId, 10)
        XCTAssertEqual(radiusKm, 10)
        XCTAssertGreaterThan(distanceKm, 0)
    }

    func test_reRoll_emptyPool_returnsExhausted() {
        // Given 빈 풀
        var rng: RandomNumberGenerator = SeededGenerator(seed: 3)
        // When / Then
        let result = Roulette.reRollFromSamePool(center: Coordinate(latitude: 37.5, longitude: 127.0), candidates: [], radiusKm: 10, using: &rng)
        XCTAssertEqual(result, .exhausted)
    }

    // MARK: - computeTagsAllowed

    func test_computeTagsAllowed_intersectsVisibleTags() {
        // Given 화면 필터 = WISH 만, MEMORY 토글 OFF
        let allowed = Roulette.computeTagsAllowed(visibleTags: [.WISH], includeMemory: false)
        // Then 기본 풀[REEL,WISH] ∩ [WISH] = [WISH]
        XCTAssertEqual(allowed, [.WISH])
    }

    func test_computeTagsAllowed_includeMemoryWithFullVisible() {
        // Given 전체 표시 + MEMORY 토글 ON
        let allowed = Roulette.computeTagsAllowed(visibleTags: [.REEL, .WISH, .MEMORY], includeMemory: true)
        // Then [REEL,WISH,MEMORY]
        XCTAssertEqual(allowed, [.REEL, .WISH, .MEMORY])
    }
}
