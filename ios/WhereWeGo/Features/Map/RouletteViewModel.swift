import Foundation

// 위치 기반 룰렛 ViewModel(설계 §5, FR-20~24, AC-10/11).
// frontend/src/app/map/MapClient.tsx 룰렛 오케스트레이션 + roulette.ts 호출부 이식.
//
// 흐름:
//  1) 진입 시 위치 권한 미허용이면 requestWhenInUsePermission 선행.
//  2) locationService.requestOneShot() 로 현재 위치 획득.
//  3) MapViewModel.pins → RoulettePin 사상, tagsAllowed(기본 [REEL,WISH], MEMORY 토글 시 확장)로 추첨.
//  4) 결과: 장소명·거리(km)·태그. 후보 0개 → exhausted("추첨할 핀이 없어요", AC-10).
//  5) "지도에서 보기" → MapViewModel.flyTo(pinId:) + selectedPinId(정보창)(AC-11) → 시트 닫기.
//  6) "다시" → reRollFromSamePool(직전 핀 제외).
//
// RNG 는 프로덕션 SystemRandomNumberGenerator, 테스트는 SeededGenerator 를 주입한다(makeRNG 클로저).
@MainActor
final class RouletteViewModel: ObservableObject {

    /// 룰렛 진행 상태(설계 §5).
    enum State: Equatable {
        case idle
        /// 위치 획득/추첨 진행 중.
        case spinning
        /// 추첨 성공 — 핀/반경/거리/풀.
        case result(pin: RoulettePin, placeName: String, address: String?, memo: String?, distanceKm: Double, radiusKm: Double, candidates: [RoulettePin])
        /// 허용 태그 범위 반경 내 후보 0건(AC-10).
        case exhausted
        /// 위치 권한 거부/획득 실패.
        case locationError(String)
    }

    // MARK: - 게시 상태

    @Published private(set) var state: State = .idle
    /// MEMORY 포함 여부. 웹 정합으로 토글 UI 는 제거됨 — 항상 false(추첨 풀 = REEL/WISH).
    /// 속성 자체는 유지한다(웹도 includeMemory state 를 보존하며, 추첨 로직/테스트가 참조).
    @Published var includeMemory: Bool = false

    /// 결과의 거리 라벨("여기서 800m" / "여기서 3.2km", 웹 RouletteResultContent 동치).
    var distanceLabel: String? {
        guard case let .result(_, _, _, _, distanceKm, _, _) = state else { return nil }
        if distanceKm < 1 {
            return "여기서 \(Int((distanceKm * 1000).rounded()))m"
        }
        return "여기서 \(String(format: "%.1f", distanceKm))km"
    }

    // MARK: - 의존성

    private weak var mapViewModel: MapViewModel?
    private let locationService: LocationServiceProtocol
    /// RNG 팩토리(테스트 결정성 주입). 기본 SystemRandomNumberGenerator.
    private let makeRNG: () -> RandomNumberGenerator

    init(
        mapViewModel: MapViewModel,
        locationService: LocationServiceProtocol,
        makeRNG: @escaping () -> RandomNumberGenerator = { SystemRandomNumberGenerator() }
    ) {
        self.mapViewModel = mapViewModel
        self.locationService = locationService
        self.makeRNG = makeRNG
    }

    // MARK: - 추첨(FR-20~22, AC-10)

    /// 권한 선행 → 현재 위치 → 추첨. 후보 0개면 exhausted.
    func spin() async {
        state = .spinning
        // mapViewModel 해제 시 .spinning 고착(무한 로딩) 방지 — locationError 로 전이.
        guard let mapViewModel else {
            state = .locationError("지도 데이터를 불러올 수 없어요.")
            return
        }

        // FR-24 정합성 단일 보장점: 추첨 전에 캐시가 stale(>5분)이면 최신 핀을 먼저 확보한다.
        // (MapView fire-and-forget 제거 — 여기서 await 해야 candidatePins() 가 최신 풀을 본다.)
        await mapViewModel.refreshPinsIfStale()

        // 권한 미허용이면 요청 후 one-shot 시도(거부면 nil 반환 → locationError).
        let status = locationService.authorizationStatus
        if status == .notDetermined {
            locationService.requestWhenInUsePermission()
        }

        guard let sample = await locationService.requestOneShot() else {
            state = .locationError("현재 위치를 확인할 수 없어요. 위치 권한을 허용해 주세요.")
            return
        }

        let center = Coordinate(latitude: sample.latitude, longitude: sample.longitude)
        let tagsAllowed = Roulette.computeTagsAllowed(
            visibleTags: mapViewModel.activeFilters,
            includeMemory: includeMemory
        )
        let pins = candidatePins()

        var rng = makeRNG()
        let outcome = Roulette.pickRandomWithExpansion(
            center: center,
            pins: pins,
            tagsAllowed: tagsAllowed,
            using: &rng
        )
        apply(outcome)
    }

    // MARK: - 다시(FR-22)

    /// 직전 결과 풀에서 직전 핀 제외 재추첨.
    /// reRoll 은 mapViewModel 을 참조하지 않고 직전 결과의 candidates/radiusKm 만 사용한다
    /// (apply 의 메타 보강만 mapViewModel?.pins 옵셔널 체이닝 — 해제돼도 안전).
    /// 따라서 spin 과 달리 mapViewModel guard 가 없으며, .result 가 아니면 직전 상태를 그대로 유지한다(의도).
    func reRoll() async {
        guard case let .result(prevPin, _, _, _, _, radiusKm, candidates) = state else { return }
        guard let sample = await locationService.requestOneShot() else {
            // 위치를 다시 못 얻으면 직전 결과 유지(보수적).
            return
        }
        let center = Coordinate(latitude: sample.latitude, longitude: sample.longitude)
        var rng = makeRNG()
        let outcome = Roulette.reRollFromSamePool(
            center: center,
            candidates: candidates,
            radiusKm: radiusKm,
            prevPinId: prevPin.pinId,
            using: &rng
        )
        apply(outcome)
    }

    // MARK: - 지도에서 보기(AC-11)

    /// 결과 핀으로 카메라 이동 + 정보창(selectedPinId) 오픈. 시트 닫기는 View 가 처리.
    func showOnMap() {
        guard case let .result(pin, _, _, _, _, _, _) = state else { return }
        guard let mapViewModel else { return }
        mapViewModel.flyTo(pinId: pin.pinId)
        mapViewModel.selectedPinId = pin.pinId
    }

    // MARK: - Private

    /// MapViewModel.pins → RoulettePin 사상(태그 보존, 필터는 pick 내부 tagsAllowed 가 담당).
    /// mapViewModel 해제 시 빈 배열 → 후보 0건(exhausted)으로 자연 처리.
    private func candidatePins() -> [RoulettePin] {
        guard let mapViewModel else { return [] }
        return mapViewModel.pins.map { pin in
            RoulettePin(
                pinId: pin.id,
                coordinate: Coordinate(latitude: pin.latitude, longitude: pin.longitude),
                tag: pin.tag
            )
        }
    }

    /// RouletteOutcome → State 매핑. picked 는 PinSummary 에서 표시 메타(장소명/주소/메모)를 보강.
    private func apply(_ outcome: RouletteOutcome) {
        switch outcome {
        case .exhausted:
            state = .exhausted
        case let .picked(pin, radiusKm, candidates, _, distanceKm):
            let summary = mapViewModel?.pins.first { $0.id == pin.pinId }
            state = .result(
                pin: pin,
                placeName: summary?.placeName ?? "",
                address: summary?.address,
                memo: summary?.memo,
                distanceKm: distanceKm,
                radiusKm: radiusKm,
                candidates: candidates
            )
        }
    }
}
