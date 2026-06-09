import Foundation
import SwiftUI

// 공유 익스텐션 상태머신(설계 §4·§5). 그룹 로드 → 멀티선택(기본 빈, D2) → 전송 완료까지 대기(D1).
@MainActor
final class ShareViewModel: ObservableObject {
    enum State: Equatable {
        case loading
        case loaded([ShareGroup])
        case empty                              // 속한 그룹 0개
        case loginRequired                      // 토큰 없음/갱신 실패
        case sending
        case result(success: Int, failed: [String])  // failed = 전송 실패한 그룹명
        case error(String)
    }

    @Published private(set) var state: State = .loading
    /// 선택된 groupId(기본 빈 선택 — D2).
    @Published private(set) var selected: Set<Int> = []

    private let api: ShareAPIClientProtocol
    private let sharedURL: String
    private var groups: [ShareGroup] = []

    init(api: ShareAPIClientProtocol, sharedURL: String) {
        self.api = api
        self.sharedURL = sharedURL
    }

    var canSend: Bool {
        if case .sending = state { return false }
        return !selected.isEmpty
    }

    func load() async {
        state = .loading
        do {
            let rooms = try await api.botRooms()
            groups = rooms
            state = rooms.isEmpty ? .empty : .loaded(rooms)
        } catch let error as ShareAPIError where Self.isAuthError(error) {
            state = .loginRequired
        } catch {
            state = .error("그룹을 불러오지 못했어요. 잠시 후 다시 시도해주세요")
        }
    }

    func toggle(_ groupId: Int) {
        if selected.contains(groupId) {
            selected.remove(groupId)
        } else {
            selected.insert(groupId)
        }
    }

    /// 선택 그룹마다 동시 전송. 모두 끝날 때까지 대기 후 결과(D1). 부분 실패는 실패 그룹명 집계.
    func send() async {
        guard !selected.isEmpty, !isSending else { return }
        let targets = groups.filter { selected.contains($0.groupId) }
        state = .sending

        var failed: [String] = []
        await withTaskGroup(of: (String, Bool).self) { group in
            for target in targets {
                let api = self.api
                let url = self.sharedURL
                group.addTask {
                    do {
                        try await api.sendBotMessage(groupId: target.groupId, text: url)
                        return (target.groupName, true)
                    } catch {
                        return (target.groupName, false)
                    }
                }
            }
            for await (name, ok) in group where !ok {
                failed.append(name)
            }
        }
        state = .result(success: targets.count - failed.count, failed: failed)
    }

    private var isSending: Bool {
        if case .sending = state { return true }
        return false
    }

    private static func isAuthError(_ error: ShareAPIError) -> Bool {
        error.status == 401 || error.code == "NO_TOKEN" || error.code == "NO_REFRESH"
    }
}
