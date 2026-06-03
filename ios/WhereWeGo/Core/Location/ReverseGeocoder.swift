import Foundation
import CoreLocation

// 온디바이스 역지오코딩 + 디바운서 + 좌표 폴백(설계 §5, FR-14/BR-3/AC-5/AC-9).
// 콕찍기(AddPlaceSheet) cameraIdle → Debouncer(300ms) → ReverseGeocoder → resolvedAddress.
// 실패/무결과 시 coordinateFallback("위도 ..., 경도 ...") 로 대체(AC-9).
// CLGeocoder 는 실디바이스/네트워크 의존이라 단위 테스트 제외. 순수 로직(coordinateFallback)·
// Debouncer 동작(주입 스케줄러)만 검증 가능하게 분리한다.

/// CLGeocoder 기반 역지오코딩. 실패 시 nil 반환(호출부가 coordinateFallback 으로 대체).
@MainActor
final class ReverseGeocoder {

    private let geocoder = CLGeocoder()

    /// 좌표 → 사람이 읽는 주소 문자열. 실패/무결과 시 nil.
    func reverseGeocode(_ c: Coordinate) async -> String? {
        let location = CLLocation(latitude: c.latitude, longitude: c.longitude)
        let placemarks = try? await geocoder.reverseGeocodeLocation(location)
        guard let placemark = placemarks?.first else {
            return nil
        }
        return Self.format(placemark)
    }

    /// 좌표 폴백 문자열(순수, AC-9). 소수 4자리 고정 포맷(%.4f).
    /// 예: coordinateFallback(lat: 37.12345, lng: 127.56789) == "위도 37.1234, 경도 127.5679".
    static func coordinateFallback(lat: Double, lng: Double) -> String {
        String(format: "위도 %.4f, 경도 %.4f", lat, lng)
    }

    // MARK: - 내부

    /// CLPlacemark → 주소 문자열(국내 주소 관례: 시/구 + 동/도로 + 번지).
    /// 비어 있으면 좌표 폴백을 쓰도록 nil 반환.
    private static func format(_ p: CLPlacemark) -> String? {
        let parts = [
            p.administrativeArea,   // 시/도
            p.locality,             // 시/군/구
            p.subLocality,          // 동/읍/면
            p.thoroughfare,         // 도로/지번
            p.subThoroughfare       // 상세 번지
        ].compactMap { $0 }.filter { !$0.isEmpty }
        let joined = parts.joined(separator: " ")
        return joined.isEmpty ? nil : joined
    }
}

/// 연속 호출을 묶어 interval 내 마지막 1회만 실행(설계 §5, 300ms, AC-5).
/// 콕찍기 드래그(cameraIdle 연발) 동안 과도한 역지오 호출을 막는다.
/// scheduler 를 주입 가능하게 하여 테스트에서 즉시/제어 실행으로 검증한다(CLGeocoder 비의존).
@MainActor
final class Debouncer {

    /// 지연 실행 스케줄러: (지연 초, 실행 클로저) → 예약. 기본은 main asyncAfter.
    /// 테스트는 즉시 실행/수동 트리거 스케줄러를 주입해 "마지막 1회만 실행"을 카운트로 검증.
    private let interval: TimeInterval
    private let scheduler: (TimeInterval, @escaping () -> Void) -> Void

    /// 현재 예약된 작업의 세대 토큰. call 마다 증가시켜 이전 예약 실행을 무효화한다.
    private var generation = 0

    init(
        interval: TimeInterval = 0.3,
        scheduler: @escaping (TimeInterval, @escaping () -> Void) -> Void = { delay, work in
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
        }
    ) {
        self.interval = interval
        self.scheduler = scheduler
    }

    /// action 을 interval 후 실행하되, interval 내 재호출 시 직전 예약을 취소하고 마지막 1회만 실행.
    func call(_ action: @escaping () -> Void) {
        generation += 1
        let token = generation
        scheduler(interval) { [weak self] in
            guard let self, token == self.generation else { return }
            action()
        }
    }
}
