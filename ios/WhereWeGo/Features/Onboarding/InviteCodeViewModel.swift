import Foundation

// 초대 코드 합류 ViewModel(IC-2). 사용자에게 노출되는 코드는 slug 이고 accept 는 token 기반이므로,
// slug 입력 → previewBySlug(token·그룹명 확보) → 확인 → acceptInvite(token) 의 2단계로 합류한다.
// (구버전: 입력값을 token 으로 직접 accept → slug≠token 이라 실패하던 것을 교체.)
@MainActor
final class InviteCodeViewModel: ObservableObject {
    @Published var code: String = ""
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?
    /// 확인 다이얼로그에 표시할 그룹명(미리보기 성공 시 세팅). nil = 입력 단계.
    @Published var pendingGroupName: String?

    private let groupAPI: GroupAPIProtocol
    /// preview 로 확보한 합류 토큰(confirmJoin 의 accept 호출에 사용). dismiss 로는 비우지 않는다(아래 주석).
    private var pendingToken: String?

    init(groupAPI: GroupAPIProtocol, prefill: String = "") {
        self.groupAPI = groupAPI
        self.code = prefill
    }

    var canSubmit: Bool {
        !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isLoading
    }

    func clearErrorOnEdit() {
        if errorMessage != nil { errorMessage = nil }
    }

    /// 1단계: slug 미리보기로 token·그룹명 확보 → 확인 다이얼로그 노출(pendingGroupName 세팅).
    /// 실패(없음/만료 등)는 입력 화면에 에러 노출.
    func preview() async {
        let slug = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !slug.isEmpty, !isLoading else { return }
        isLoading = true
        errorMessage = nil
        do {
            let preview = try await groupAPI.previewBySlug(slug: slug)
            pendingToken = preview.token
            pendingGroupName = preview.groupName
            isLoading = false
        } catch {
            isLoading = false
            errorMessage = Self.message(for: error)
        }
    }

    /// 2단계: 확인 후 합류(accept). 성공 시 onJoined(groupId). 실패 시 입력 단계 복귀 + 에러.
    func confirmJoin(onJoined: @escaping (Int) -> Void) async {
        guard let token = pendingToken, !isLoading else { return }
        isLoading = true
        errorMessage = nil
        do {
            let result = try await groupAPI.acceptInvite(token: token)
            isLoading = false
            pendingGroupName = nil
            pendingToken = nil
            onJoined(result.groupId)
        } catch {
            isLoading = false
            pendingGroupName = nil
            pendingToken = nil
            errorMessage = Self.message(for: error)
        }
    }

    /// 확인 다이얼로그 닫힘(표시만 해제). pendingToken 은 유지한다 — confirmationDialog 가
    /// "합류하기" 탭 시 자동 닫히며 이 setter 를 먼저 호출하므로, 여기서 토큰을 비우면
    /// 뒤이어 실행되는 confirmJoin 이 토큰을 잃는다. 토큰은 confirmJoin 이 소비하거나 다음 preview 가 덮어쓴다.
    func dismissConfirm() {
        pendingGroupName = nil
    }

    /// APIError.code → 사용자 메시지(설계 §3).
    static func message(for error: Error) -> String {
        guard let api = error as? APIError else {
            return "합류하지 못했어요. 잠시 후 다시 시도해주세요"
        }
        switch api.code {
        case "GROUP_ALREADY_MEMBER": return "이미 이 그룹의 멤버예요"
        case "GROUP_CAPACITY_EXCEEDED": return "그룹 정원이 가득 찼어요"
        case "INVITE_LINK_NOT_FOUND", "INVITE_LINK_EXPIRED": return "잘못된 코드이거나 만료되었어요"
        default: return "합류하지 못했어요. 잠시 후 다시 시도해주세요"
        }
    }
}
