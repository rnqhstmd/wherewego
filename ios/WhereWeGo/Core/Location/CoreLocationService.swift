import Foundation
import CoreLocation

// CoreLocation 래퍼(설계 §4, FR-27~32). LocationPermView 권한 패턴 재사용.
// CLLocation → LocationSample 매핑(accuracy=horizontalAccuracy, speed=speed≥0 ? speed : nil).
// 룰렛 one-shot(continuation) + 방문감지 연속 구독 + 5초 폴링(FR-32, 선택적)을 제공한다.
//
// Swift 6 동시성(메모리 ios-xcodebuild-env): 클래스를 @MainActor 로 격리해 상태 접근을 직렬화한다.
// CLLocationManagerDelegate 콜백은 nonisolated 로 받고, Sendable 값(Double 등)만 추출하여
// MainActor 경계로 넘긴다(continuation/onSample 호출은 모두 MainActor).
@MainActor
final class CoreLocationService: NSObject, LocationServiceProtocol {

    /// 위치 표본 콜백(startUpdating 동안).
    var onSample: ((LocationSample) -> Void)?

    private let manager = CLLocationManager()
    /// requestOneShot 대기 continuation(1회 resume 보장).
    private var oneShotContinuation: CheckedContinuation<LocationSample?, Never>?
    /// 5초 폴링 타이머(FR-32). granted 시 startUpdating 에서 가동.
    private var pollTimer: Timer?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    var authorizationStatus: CLAuthorizationStatus {
        manager.authorizationStatus
    }

    func requestWhenInUsePermission() {
        manager.requestWhenInUseAuthorization()
    }

    func startUpdating() {
        guard isGranted else { return }
        manager.startUpdatingLocation()
        startPolling()
    }

    func stopUpdating() {
        manager.stopUpdatingLocation()
        stopPolling()
    }

    /// 단발 현재 위치 1회 획득(룰렛). 권한 없으면 즉시 nil.
    func requestOneShot() async -> LocationSample? {
        guard isGranted else { return nil }
        // 이전 대기 중 continuation 이 있으면 정리(중복 호출 방어).
        if let pending = oneShotContinuation {
            oneShotContinuation = nil
            pending.resume(returning: nil)
        }
        return await withCheckedContinuation { continuation in
            oneShotContinuation = continuation
            manager.requestLocation()
        }
    }

    // MARK: - Private

    private var isGranted: Bool {
        let status = manager.authorizationStatus
        return status == .authorizedWhenInUse || status == .authorizedAlways
    }

    /// 최신 위치를 onSample 로 전달하고, one-shot 대기 중이면 resume.
    /// (CLLocation → LocationSample 매핑은 nonisolated delegate 콜백에서 Sendable 값만 추출해 수행)
    private func handleSample(_ sample: LocationSample) {
        if let continuation = oneShotContinuation {
            oneShotContinuation = nil
            continuation.resume(returning: sample)
        }
        onSample?(sample)
    }

    /// 5초 폴링 시작(FR-32). 정기적으로 단발 요청을 보내 표본 갱신을 보조.
    private func startPolling() {
        stopPolling()
        let timer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.isGranted else { return }
                self.manager.requestLocation()
            }
        }
        pollTimer = timer
    }

    private func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }
}

// MARK: - CLLocationManagerDelegate
// delegate 콜백은 nonisolated 로 받고, Sendable 값만 추출해 MainActor 경계로 넘긴다(Swift 6).
extension CoreLocationService: CLLocationManagerDelegate {

    nonisolated func locationManager(
        _ manager: CLLocationManager,
        didUpdateLocations locations: [CLLocation]
    ) {
        guard let location = locations.last else { return }
        // CLLocation 은 Sendable. MainActor 로 넘겨 상태 접근/콜백을 직렬화한다.
        let coordinate = location.coordinate
        let horizontalAccuracy = location.horizontalAccuracy
        let speed = location.speed
        Task { @MainActor [weak self] in
            guard let self else { return }
            let sample = LocationSample(
                latitude: coordinate.latitude,
                longitude: coordinate.longitude,
                accuracyMeters: horizontalAccuracy,
                speedMps: speed >= 0 ? speed : nil
            )
            self.handleSample(sample)
        }
    }

    nonisolated func locationManager(
        _ manager: CLLocationManager,
        didFailWithError error: Error
    ) {
        // one-shot 대기 중이면 nil 로 resume(룰렛 graceful 실패).
        Task { @MainActor [weak self] in
            guard let self, let continuation = self.oneShotContinuation else { return }
            self.oneShotContinuation = nil
            continuation.resume(returning: nil)
        }
    }
}
