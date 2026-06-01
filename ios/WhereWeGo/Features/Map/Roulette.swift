import Foundation

// 위치 기반 룰렛 추첨 순수 로직(설계 §5).
// frontend/src/app/map/_lib/roulette.ts(pickRandomWithExpansion/reRollFromSamePool/withinRadius) 1:1 이식.
//
// - 10km 단일 반경 추첨(ROULETTE_RADIUS_STEPS_KM = [10]). 함수명/순회 구조는
//   미래 다단계 확장(1km → 5km → 10km) 가능성을 위해 유지하되 현재는 한 단계만 순회.
// - withinRadius = GeoMath.bboxContains(1차 컷) → GeoMath.haversineKm(정밀 원 필터).
// - RNG 주입(inout RandomNumberGenerator)으로 결정적 테스트 가능(웹 Math.random 대응).

/// 룰렛 후보 핀 최소 구조. 호출자가 PinSummary → RoulettePin 으로 사상.
struct RoulettePin: Equatable {
    let pinId: Int
    let coordinate: Coordinate
    let tag: PinTag
}

/// 룰렛 추첨 결과(설계 §5).
enum RouletteOutcome: Equatable {
    /// 후보에서 1개 선택. candidates 는 "다시"(reRoll)용 동일 풀.
    case picked(pin: RoulettePin, radiusKm: Double, candidates: [RoulettePin], candidateCount: Int, distanceKm: Double)
    /// 허용 태그 범위에서 반경 내 후보 0건.
    case exhausted
}

enum Roulette {
    /// 추첨 반경 단계(km). 웹 ROULETTE_RADIUS_STEPS_KM = [10] 와 동일.
    static let radiusStepsKm: [Double] = [10]

    /// 반경 단계 추첨(웹 pickRandomWithExpansion 1:1 이식).
    ///
    /// 1) tagsAllowed 로 후보 풀 필터(기본 [REEL, WISH] — MEMORY 제외).
    /// 2) radiusStepsKm 순회: 반경 내 후보가 1건이라도 있으면 그 안에서 무작위 선택.
    /// 3) 모든 단계에서 0건이면 exhausted.
    ///
    /// - Parameter rng: 테스트 시드 고정용 주입 RNG(웹 Math.random 대응).
    static func pickRandomWithExpansion(
        center: Coordinate,
        pins: [RoulettePin],
        tagsAllowed: Set<PinTag> = [.REEL, .WISH],
        using rng: inout RandomNumberGenerator
    ) -> RouletteOutcome {
        let eligible = pins.filter { tagsAllowed.contains($0.tag) }
        for radiusKm in radiusStepsKm {
            let candidates = withinRadius(center: center, pins: eligible, radiusKm: radiusKm)
            if !candidates.isEmpty {
                let index = Int.random(in: 0..<candidates.count, using: &rng)
                let picked = candidates[index]
                let distanceKm = GeoMath.haversineKm(center, picked.coordinate)
                return .picked(
                    pin: picked,
                    radiusKm: radiusKm,
                    candidates: candidates,
                    candidateCount: candidates.count,
                    distanceKm: distanceKm
                )
            }
        }
        return .exhausted
    }

    /// "다시" 재추첨(웹 reRollFromSamePool 1:1 이식).
    ///
    /// 마지막 성공 후보 풀에서 같은 radius 로 무작위 재선택.
    /// prevPinId 가 주어지고 후보가 2개 이상이면 직전 핀을 제외(같은 곳 반복 방지).
    /// 후보가 1개면 그대로 같은 핀 반환(선택지 없음).
    static func reRollFromSamePool(
        center: Coordinate,
        candidates: [RoulettePin],
        radiusKm: Double,
        prevPinId: Int? = nil,
        using rng: inout RandomNumberGenerator
    ) -> RouletteOutcome {
        if candidates.isEmpty { return .exhausted }
        let pool: [RoulettePin]
        if let prevPinId, candidates.count > 1 {
            pool = candidates.filter { $0.pinId != prevPinId }
        } else {
            pool = candidates
        }
        let index = Int.random(in: 0..<pool.count, using: &rng)
        let picked = pool[index]
        let distanceKm = GeoMath.haversineKm(center, picked.coordinate)
        return .picked(
            pin: picked,
            radiusKm: radiusKm,
            candidates: candidates,
            candidateCount: candidates.count,
            distanceKm: distanceKm
        )
    }

    /// 반경 내 핀(웹 withinRadius 이식): GeoMath.bboxContains 1차 컷 → haversine ≤ radiusKm.
    static func withinRadius(center: Coordinate, pins: [RoulettePin], radiusKm: Double) -> [RoulettePin] {
        let radiusMeters = radiusKm * 1000
        return pins.filter { pin in
            guard GeoMath.bboxContains(center: center, point: pin.coordinate, radiusMeters: radiusMeters) else {
                return false
            }
            return GeoMath.haversineKm(center, pin.coordinate) <= radiusKm
        }
    }

    /// 룰렛 허용 태그 교집합 계산(웹 MapClient computeTagsAllowed 이식, 설계 §5).
    /// 기본 풀 [REEL, WISH] 와 현재 화면 필터(visibleTags) 의 교집합.
    /// MEMORY 토글 ON 이면 includeMemory=true 로 MEMORY 도 후보 허용.
    static func computeTagsAllowed(visibleTags: Set<PinTag>, includeMemory: Bool) -> Set<PinTag> {
        var base: Set<PinTag> = [.REEL, .WISH]
        if includeMemory { base.insert(.MEMORY) }
        return base.intersection(visibleTags)
    }
}

/// 결정적 테스트용 시드 고정 RNG(웹 Math.random 대응). 선형 합동 생성기(SplitMix64).
/// 동일 시드 → 동일 추첨 시퀀스 보장(RouletteTests 결정성 검증).
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        self.state = seed
    }

    mutating func next() -> UInt64 {
        // SplitMix64: 빠르고 분포 양호한 결정적 생성기.
        state = state &+ 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
