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
    /// one-shot 대기 여부. 들어온 표본을 one-shot resume 전용으로 처리(onSample 비유발) 분기에 사용.
    private var pendingOneShot = false
    /// requestOneShot 타임아웃 Task(콜백 미수신 시 nil resume). resume 시 취소.
    private var oneShotTimeout: Task<Void, Never>?
    /// 5초 폴링 타이머(FR-32). granted 시 startUpdating 에서 가동.
    private var pollTimer: Timer?

    /// requestOneShot 타임아웃(초). 콜백이 끝내 안 와도 호출부 고착을 막는다.
    private let oneShotTimeoutSeconds: UInt64 = 10

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
    /// 콜백(didUpdateLocations/didFailWithError)이 끝내 안 와도 타임아웃으로 nil resume 하여
    /// 호출부(RouletteViewModel.spin) 고착을 막는다. resume 은 성공/실패/타임아웃 어느 경로든 1회만.
    func requestOneShot() async -> LocationSample? {
        guard isGranted else { return nil }
        // 이전 대기 중 continuation 이 있으면 정리(중복 호출 방어).
        resolveOneShot(with: nil)
        // L4(code-review) — 외부 Task 취소(예: requestOneShotWithTimeout 의 5초 타임아웃 후 cancelAll)에 반응해
        // 즉시 nil resume 한다. 없으면 CoreLocation 자체 타임아웃(10초)까지 블록되어 "5초 상한"이 깨진다.
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                oneShotContinuation = continuation
                pendingOneShot = true
                // 타임아웃 Task 와 delegate 콜백은 모두 @MainActor 로 격리되어 직렬화된다.
                oneShotTimeout = Task { @MainActor [weak self] in
                    try? await Task.sleep(nanoseconds: (self?.oneShotTimeoutSeconds ?? 10) * 1_000_000_000)
                    guard !Task.isCancelled else { return }
                    self?.resolveOneShot(with: nil)
                }
                manager.requestLocation()
            }
        } onCancel: {
            // double-resume 은 resolveOneShot 의 continuation nil 가드가 방지한다.
            Task { @MainActor [weak self] in self?.resolveOneShot(with: nil) }
        }
    }

    /// one-shot continuation 을 1회만 resume 한다(double-resume 방지).
    /// continuation 을 먼저 nil 로 비운 뒤 호출하고, 타임아웃 Task·pending 플래그도 함께 정리한다.
    private func resolveOneShot(with sample: LocationSample?) {
        oneShotTimeout?.cancel()
        oneShotTimeout = nil
        pendingOneShot = false
        guard let continuation = oneShotContinuation else { return }
        oneShotContinuation = nil
        continuation.resume(returning: sample)
    }

    // MARK: - Private

    private var isGranted: Bool {
        let status = manager.authorizationStatus
        return status == .authorizedWhenInUse || status == .authorizedAlways
    }

    /// 최신 위치를 처리한다. one-shot/연속구독 표본을 분리한다(한 manager 공유).
    /// - one-shot 대기 중이면: continuation resume 전용. onSample(방문감지 평가) 으로 흘리지 않는다.
    /// - 그 외(연속 구독 startUpdating): onSample 로만 전달.
    /// (CLLocation → LocationSample 매핑은 nonisolated delegate 콜백에서 Sendable 값만 추출해 수행)
    private func handleSample(_ sample: LocationSample) {
        if pendingOneShot {
            resolveOneShot(with: sample)
            return
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
        // one-shot 대기 중이면 nil 로 resume(룰렛 graceful 실패). resolveOneShot 이 1회 resume 보장.
        Task { @MainActor [weak self] in
            self?.resolveOneShot(with: nil)
        }
    }
}
