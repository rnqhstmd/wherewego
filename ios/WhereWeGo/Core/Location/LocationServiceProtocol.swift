import Foundation
import CoreLocation

// 위치 서비스 추상 인터페이스(설계 §4, FR-27~32).
// 룰렛(one-shot)·방문감지(연속 구독)가 공통으로 사용한다. ViewModel 은 이 프로토콜에만 의존하여
// 테스트에서 위치를 주입할 수 있게 한다(DI, AppDependencies 등록).
//
// LocationSample 은 VisitDetectionEngine.swift 에 정의된 타입을 재사용한다(중복 정의 금지).

/// 위치 서비스 프로토콜. CoreLocationService(실구현)와 테스트 더블이 채택.
/// @MainActor 격리: 실구현 CoreLocationService 가 @MainActor 이고, 소비자(MapViewModel/
/// RouletteViewModel)도 모두 @MainActor ObservableObject 이므로 채택이 actor 경계를 넘지 않게 한다
/// (Swift 6 strict concurrency). requestOneShot()·onSample 콜백도 MainActor 컨텍스트에서 안전.
@MainActor
protocol LocationServiceProtocol: AnyObject {
    /// 현재 위치 권한 상태.
    var authorizationStatus: CLAuthorizationStatus { get }
    /// when-in-use 권한 요청(LocationPermView 패턴 재사용).
    func requestWhenInUsePermission()
    /// 연속 위치 업데이트 시작(방문감지, FR-32).
    func startUpdating()
    /// 연속 위치 업데이트 중지.
    func stopUpdating()
    /// 단발 현재 위치 1회 획득(룰렛). 실패/거부 시 nil.
    func requestOneShot() async -> LocationSample?
    /// 위치 표본 콜백(startUpdating 동안 호출).
    var onSample: ((LocationSample) -> Void)? { get set }
}
