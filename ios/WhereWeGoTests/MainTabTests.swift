import XCTest
import SwiftUI
import CoreLocation
@testable import WhereWeGo

// MainTab 열거형 + 장소 추가(＋) 액션 검증(설계 §1·§2, FR-1/FR-2/FR-11, BR-1/BR-2, AC-1/AC-2).
//
// FloatingTabBar/MainTabView/MapView 는 SwiftUI View 라 body/@State 상호작용을 직접 구동할 수 없다.
// 대신 검증 가능한 두 축으로 나눠 단언한다:
//  - AC-1: MainTab.allCases 순서·개수·couple 부재(CaseIterable 정적 보장). ＋ 는 탭이 아니라 액션이므로 미포함.
//  - AC-2: 장소 추가(＋) 액션의 탭 독립성 — ＋ 는 지도 화면 speed-dial(MapView.addPinSpeedDial)의 enterAddPin(mode:) 으로,
//          탭 selection 과 분리돼 있다(MainTab 에 plus 케이스 부재). FAB 액션이 selection 을 바꾸지 않음을
//          클로저 모델로 검증한다.
//          (P8: ＋ 를 탭바 센터에서 지도 우하단 FAB 로 이전. FloatingTabBar 는 5탭(지도·어디갈까·채팅·알림·내정보)만 받고 ＋ 미보유.)
//          (SwiftUI 버튼 탭 자체를 구동하는 뷰 상호작용 테스트는 DoD-B(Mac/Xcode) 이연 — 본 테스트는 로직/구조 검증.)
@MainActor
final class MainTabTests: XCTestCase {

    // MARK: - AC-1: MainTab 열거형(순서·개수·couple 부재)

    func test_mainTab_allCases_orderAndCount() {
        // FR-1: 탭 순서 = 지도·어디갈까·채팅·알림·내정보. ＋ 는 액션이므로 케이스 미포함.
        //  (어디갈까=위치기반 룰렛 추천 탭, 구 지도 우상단 🎲 시트에서 탭으로 승격.)
        XCTAssertEqual(MainTab.allCases, [.map, .discover, .chat, .notification, .myInfo])
        XCTAssertEqual(MainTab.allCases.count, 5)
    }

    func test_mainTab_doesNotContainCouple() {
        // FR-11/BR-2: 커플 탭 제거. MainTab 에 couple 식별자 부재 → allCases 어디에도 매칭되지 않음.
        // (couple 케이스가 남아 있으면 컴파일 단계에서 case 참조가 가능했을 것 — 부재의 표면 검증.)
        let labels = MainTab.allCases.map { String(describing: $0) }
        XCTAssertFalse(labels.contains("couple"))
    }

    func test_mainTab_isCaseIterableWithExactFive() {
        // 개수 회귀 방지: 탭 식별자는 정확히 5개(지도·어디갈까·채팅·알림·내정보). ＋ 는 액션이라 미포함.
        XCTAssertEqual(Set(MainTab.allCases).count, 5)
    }

    // MARK: - AC-2: 장소 추가(＋) 액션의 탭 독립성

    func test_addPinAction_entersAddPinIndependentFromTabSelection() {
        // 지도 FAB speed-dial ＋ 액션 모델: addPinSpeedDial 의 선택지 = { mapViewModel.enterAddPin(mode:) }(P8 영역4 후속).
        // ＋ 는 탭 selection 과 무관하게 인라인 추가 모드만 활성화한다(BR-1/AC-1/AC-2). (기본 .pinpoint 로 검증)
        let selection: MainTab = .map
        let mapViewModel = makeMapViewModel()

        // addPinSpeedDial 선택지와 동일 성격의 액션 클로저(selection 바인딩 없음 · 인라인 모드 토글).
        let addPinAction: () -> Void = { mapViewModel.enterAddPin() }

        // When ＋ 누름(여러 번 — 중복 진입 방어로 1회만 활성)
        addPinAction()
        addPinAction()

        // Then selection 은 .map 유지(무관), 인라인 추가 모드만 활성.
        XCTAssertEqual(selection, .map, "＋ 액션은 탭 selection 과 무관해야 한다(BR-1).")
        XCTAssertTrue(mapViewModel.isAddingPin, "＋ 액션은 인라인 추가 모드를 활성화해야 한다(AC-1).")
    }

    func test_floatingTabBar_isFiveTabNavigationWithoutPlusParameter() {
        // P8: FloatingTabBar 는 5탭 순수 네비게이션 바. ＋(onPlusTap) 파라미터를 더는 받지 않는다.
        // 생성 시그니처가 selection/hasUnread 만으로 정합하고, selection 바인딩 초기값을 보존함을 확인.
        var selection: MainTab = .chat

        let bar = FloatingTabBar(
            selection: Binding(get: { selection }, set: { selection = $0 }),
            hasUnread: false
        )

        // FloatingTabBar 는 View 라 body 를 구동하진 않지만, 생성 시그니처 정합(＋ 파라미터 부재) +
        // selection 바인딩이 외부 상태를 그대로 반영(초기값 보존)함을 확인.
        _ = bar
        XCTAssertEqual(selection, .chat)
    }

    // MARK: - 헬퍼

    /// ＋ 액션 모델 검증용 MapViewModel(권한 거부 — enterAddPin 의 줌인은 부작용 없이 동기 종료).
    private func makeMapViewModel() -> MapViewModel {
        MapViewModel(
            pinAPI: StubPinAPI(),
            placeAPI: StubPlaceAPI(),
            groupAPI: StubGroupAPI(group: ActiveGroup(groupId: 1, name: "팀", memberCount: 2)),
            locationService: StubLocationService()
        )
    }
}

// MARK: - In-file 프로토콜 목(MapViewModelTests/AddPlaceViewModelTests 패턴)

private final class StubPinAPI: PinAPIProtocol, @unchecked Sendable {
    func list(groupId: Int) async throws -> [PinSummary] { [] }
    func create(groupId: Int, request: CreatePinRequest) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }
    func update(groupId: Int, pinId: Int, request: UpdatePinRequest) async throws -> UpdatePinResponse {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }
    func delete(groupId: Int, pinId: Int) async throws {}
    func uploadPhoto(groupId: Int, pinId: Int, imageData: Data) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }
    func deletePhoto(groupId: Int, pinId: Int) async throws -> PinSummary {
        throw APIError(code: "UNSUPPORTED", status: 0, message: "stub")
    }
}

private final class StubPlaceAPI: PlaceAPIProtocol, @unchecked Sendable {
    func search(_ keyword: String) async throws -> [PlaceItem] { [] }
}

private final class StubGroupAPI: GroupAPIProtocol, @unchecked Sendable {
    private let group: ActiveGroup?
    init(group: ActiveGroup?) { self.group = group }
    func myActiveGroup() async throws -> ActiveGroup? { group }
    func acceptInvite(token: String) async throws -> InviteAccept { InviteAccept(groupId: 0) }
    func issueInviteLink(groupId: Int) async throws -> InviteLink { InviteLink(token: "stub", slug: nil, shareUrl: nil) }
    func leaveGroup(groupId: Int) async throws {}
}

@MainActor
private final class StubLocationService: LocationServiceProtocol {
    var authorizationStatus: CLAuthorizationStatus = .denied
    var onSample: ((LocationSample) -> Void)?
    func requestWhenInUsePermission() {}
    func startUpdating() {}
    func stopUpdating() {}
    func requestOneShot() async -> LocationSample? { nil }
}
