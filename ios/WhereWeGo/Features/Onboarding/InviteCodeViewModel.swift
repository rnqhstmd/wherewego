import Foundation

// 초대 코드 합류 ViewModel(설계 §4). slug 입력 → preview(token 획득) → confirm → accept 2단계 상태머신.
// 사용자는 slug 입력, token 은 previewBySlug 에서만 획득(BR-1). prefill 제거(FR-9).
@MainActor
final class InviteCodeViewModel: ObservableObject {
    @Published var code: String = ""
    @Published private(set) var step: InviteCodeStep = .input
    @Published var errorMessage: String?          // 입력 화면(preview 실패)
    @Published var confirmErrorMessage: String?   // 확인 화면(accept 실패)

    private let groupAPI: GroupAPIProtocol

    init(groupAPI: GroupAPIProtocol) {
        self.groupAPI = groupAPI
    }

    /// 입력 화면에서 코드가 비어있지 않을 때만 제출 가능(QE-1: previewing/accepting 중 false).
    var canSubmit: Bool {
        step == .input && !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var isPreviewing: Bool { step == .previewing }

    var isAccepting: Bool {
        if case .accepting = step { return true }
        return false
    }

    /// 입력 편집 시 입력화면 에러 클리어(FR-13/AC-12).
    func clearErrorOnEdit() {
        if errorMessage != nil { errorMessage = nil }
    }

    /// slug 프리뷰 조회 → 성공 시 확인화면(.confirm), 실패 시 입력화면 유지 + errorMessage(M1/M2).
    func submitPreview() async {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, step == .input else { return }
        step = .previewing
        errorMessage = nil
        confirmErrorMessage = nil   // 방어: 이전 accept 에러 잔재 제거(현 흐름상 불가하나 상태머신 확장 대비)
        do {
            let preview = try await groupAPI.previewBySlug(slug: trimmed)
            step = .confirm(preview)
        } catch {
            // M1/M2: NOT_FOUND·CAPACITY 등 preview 단계 에러 → 입력화면 표시.
            step = .input
            errorMessage = InviteCodeError.message(for: error)
        }
    }

    /// 확인화면에서 token 으로 합류(accept). GROUP_ALREADY_MEMBER 는 .alreadyMember 로 가로챔(FR-12).
    func confirmJoin(onJoined: @escaping () -> Void) async {
        guard case .confirm(let preview) = step else { return }
        step = .accepting(preview)
        confirmErrorMessage = nil
        do {
            _ = try await groupAPI.acceptInvite(token: preview.token)
            onJoined()
        } catch let error as APIError where error.code == "GROUP_ALREADY_MEMBER" {
            // FR-12: 이미 멤버 → 에러 아님, 이미멤버 화면으로 전이(BR-4).
            step = .alreadyMember(preview)
        } catch {
            // AC-8~11: capacity/self/rejoin/rate/expired(410) → 확인화면 복귀 + confirmErrorMessage.
            step = .confirm(preview)
            confirmErrorMessage = InviteCodeError.message(for: error)
        }
    }

    /// 이미 멤버 확인 → 합류 완료로 간주(BR-4).
    func acknowledgeAlreadyMember(onJoined: @escaping () -> Void) {
        onJoined()
    }

    /// 확인화면 → 입력화면. token(InvitePreview) 자연 폐기(QE-2).
    func cancelToInput() {
        step = .input
        confirmErrorMessage = nil
    }
}
