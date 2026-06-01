import Foundation

// 장소 방문 감지 순수 평가 엔진(설계 §4, FR-27~32, AC-12~14).
// frontend/src/app/map/_hooks/useVisitDetection.ts 1:1 이식 + now 주입(시계 결정성).
//
// 책임:
//  - LocationSample + WISH/REEL 후보 핀 + 세션 노출 Set 을 받아
//    "100m 이내 핀에 30초 머무름" 조건을 만족하는 핀 1개(최근접)를 검출.
//  - 후보 핀별 진입 시각(firstEnterAt) 누적/소거를 내부 Map 에 보관.
// 비책임: 토스트/시트 UI, PATCH, 위치 구독, 알림 — 호출처(MapViewModel)가 담당.
//
// 정확도 게이트는 설계 확정값 50m(PRD/AC-12). 웹은 운영 중 100m 로 완화했으나
// iOS 는 설계 확정대로 50m 를 사용한다(coder 보고에 불일치 명시).

/// 위치 표본. CoreLocationService 가 CLLocation → 변환하여 전달.
struct LocationSample {
    let latitude: Double
    let longitude: Double
    /// 수평 정확도(m). 50m 초과면 평가 스킵.
    let accuracyMeters: Double
    /// 속도(m/s). nil 인 디바이스는 속도 게이트 통과(안전 fallback).
    let speedMps: Double?
}

/// 방문 감지 후보 핀(호출자가 WISH/REEL 만 필터링하여 전달).
struct VisitCandidatePin: Equatable {
    let pinId: Int
    let latitude: Double
    let longitude: Double
}

/// 방문 감지 평가 엔진. firstEnterAt 상태를 보유하는 stateful 순수 평가기(I/O 없음).
final class VisitDetectionEngine {
    /// 감지 반경(km). 웹 PROXIMITY_KM = 0.1(100m).
    private static let proximityKm = 0.1
    /// 감지 반경(m). BBox 사전 필터용. 웹 PROXIMITY_METERS = 100.
    private static let proximityMeters = 100.0
    /// 정확도 상한(m). 설계 확정 50m(AC-12). 초과 시 평가 스킵 + firstEnterAt 보존.
    private static let accuracyMaxMeters = 50.0
    /// 머무름 임계(초). 웹 DWELL_MS = 30_000. now 가 초 단위라 30초.
    private static let dwellSeconds = 30.0
    /// 걷는 속도 상한(m/s). 웹 WALKING_SPEED_MAX_MS = 1.4(≈5km/h). 초과 시 이동 중으로 간주.
    private static let walkingSpeedMaxMps = 1.4

    /// pinId → 첫 진입 시각(초). 후보 set 에서 벗어나면 삭제.
    private var firstEnterAt: [Int: TimeInterval] = [:]

    /// 방문 후보 평가(웹 evaluate 1:1 이식 + now 주입).
    ///
    /// - Parameters:
    ///   - sample: 현재 위치 표본.
    ///   - wishReelPins: 호출자가 WISH/REEL 만 필터링한 후보 핀.
    ///   - shownPinIds: 세션 내 이미 토스트 표시한 pinId(중복 차단, AC-14).
    ///   - now: 평가 시각(초). 테스트는 고정값 주입.
    /// - Returns: 30초+ 머문 최근접 핀 pinId. 없으면 nil.
    func evaluate(
        sample: LocationSample,
        wishReelPins: [VisitCandidatePin],
        shownPinIds: Set<Int>,
        now: TimeInterval
    ) -> Int? {
        // 1) 정확도 미달 — 전체 평가 스킵. firstEnterAt 은 보존(불량 GPS 가 누적 진행을 무효화하지 않음, AC-12).
        if sample.accuracyMeters > Self.accuracyMaxMeters {
            return nil
        }

        // 2) 이동 중(차량/자전거) — 머무름으로 카운트하지 않고 모든 후보 firstEnterAt clear(AC-13).
        //    speed 가 nil 인 디바이스는 통과(안전 fallback).
        if let speed = sample.speedMps, speed > Self.walkingSpeedMaxMps {
            firstEnterAt.removeAll()
            return nil
        }

        let userPos = Coordinate(latitude: sample.latitude, longitude: sample.longitude)

        // 3) 후보 핀 수집: shownPinIds 제외 → BBox 100m 사전 필터 → haversine ≤ 100m 정밀.
        var candidates: [(pinId: Int, distanceKm: Double)] = []
        for pin in wishReelPins {
            if shownPinIds.contains(pin.pinId) { continue }
            let pinPos = Coordinate(latitude: pin.latitude, longitude: pin.longitude)
            guard GeoMath.bboxContains(center: userPos, point: pinPos, radiusMeters: Self.proximityMeters) else {
                continue
            }
            let distanceKm = GeoMath.haversineKm(userPos, pinPos)
            if distanceKm <= Self.proximityKm {
                candidates.append((pinId: pin.pinId, distanceKm: distanceKm))
            }
        }

        // 4) 후보에서 벗어난 핀의 firstEnterAt 제거(FR-VD-8).
        let candidateIds = Set(candidates.map { $0.pinId })
        for pinId in firstEnterAt.keys where !candidateIds.contains(pinId) {
            firstEnterAt.removeValue(forKey: pinId)
        }

        // 5) 모든 후보에 firstEnterAt 누적(없는 경우만). 차순위 핀도 함께 추적.
        for candidate in candidates where firstEnterAt[candidate.pinId] == nil {
            firstEnterAt[candidate.pinId] = now
        }

        // 6) 거리 오름차순 정렬 후 30초+ 누적된 첫(최근접) 후보 반환.
        candidates.sort { $0.distanceKm < $1.distanceKm }
        for candidate in candidates {
            if let enterAt = firstEnterAt[candidate.pinId], now - enterAt >= Self.dwellSeconds {
                return candidate.pinId
            }
        }

        return nil
    }

    /// 토스트 닫고 shownPinIds 에 추가한 직후 호출. 해당 핀 firstEnterAt 도 비운다.
    func clearFirstEnterAt(pinId: Int) {
        firstEnterAt.removeValue(forKey: pinId)
    }

    /// 전체 firstEnterAt clear. scenePhase background 진입/속도 게이트에서 호출.
    func clearAll() {
        firstEnterAt.removeAll()
    }
}
