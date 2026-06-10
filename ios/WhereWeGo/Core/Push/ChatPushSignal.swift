import Foundation

// 포그라운드 그룹 메시지 수신 신호(GC-2 FR-GC2-6). willPresent(AppNotificationDelegate)와 GroupChatViewModel 의 약결합 채널.
//  - currentRoomId: 현재 열려 있는 그룹 방 roomId(GroupChatViewModel.appear 등록 / disappear 해제).
//    willPresent 가 GROUP_MESSAGE roomId 와 비교해 "현재 방"이면 배너를 억제하고 재조회를 트리거한다.
//  - tick: 현재 방 대상 수신 신호 카운터. GroupChatView 가 onChange 로 관찰해 reconcileLatest 한다.
// AppDependencies 가 단일 인스턴스로 조립해 notificationDelegate·MainTabView 에 주입한다(전역 싱글톤 지양).
@MainActor
final class ChatPushSignal: ObservableObject {

    /// 현재 화면에 열려 있는 그룹 방 roomId. 없으면 nil(목록/다른 화면).
    private(set) var currentRoomId: Int?
    /// 현재 방 대상 GROUP_MESSAGE 수신 신호(증가마다 재조회). View 가 onChange 관찰.
    @Published private(set) var tick: Int = 0

    /// 방 진입 시 현재 방 등록(GroupChatViewModel.appear). roomId nil(가상 방)이면 등록 보류 — 첫 프레임 확보 후 재등록.
    func register(roomId: Int?) {
        currentRoomId = roomId
    }

    /// 방 이탈 시 해제(현재 roomId 와 일치할 때만 — 빠른 방 전환 경합 시 새 방 등록을 덮지 않음).
    func clear(roomId: Int?) {
        if currentRoomId == roomId { currentRoomId = nil }
    }

    /// willPresent 가 호출. 주어진 roomId 가 현재 방이면 tick 증가(재조회 신호) + true(배너 억제) 반환.
    /// 현재 방이 아니거나 roomId 없으면 false(배너 표시 유지).
    @discardableResult
    func notifyIfCurrent(roomId: Int?) -> Bool {
        guard let roomId, currentRoomId == roomId else { return false }
        tick &+= 1
        return true
    }
}
