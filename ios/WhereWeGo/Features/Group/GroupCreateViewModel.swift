import Foundation

// 그룹 생성 ViewModel(FR-18). 이름 입력 → 검증(trim 1~30자) → createGroup → 성공 콜백.
// InviteCodeViewModel 의 @MainActor ObservableObject 패턴을 따른다.
@MainActor
final class GroupCreateViewModel: ObservableObject {
    /// 그룹명 검증 길이 한도(백엔드 GroupMemberService.createGroup 의 1~30자 규칙과 일치).
    static let maxNameLength = 30

    @Published var name: String = ""
    @Published private(set) var isCreating = false
    @Published var errorMessage: String?

    private let groupAPI: GroupAPIProtocol

    init(groupAPI: GroupAPIProtocol) {
        self.groupAPI = groupAPI
    }

    /// trim 후 그룹명. 검증/제출에 사용하는 정규화 값.
    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 생성 가능 여부: 생성 중이 아니고 trim 후 1~30자.
    var canSubmit: Bool {
        guard !isCreating else { return false }
        let count = trimmedName.count
        return count >= 1 && count <= Self.maxNameLength
    }

    /// 입력 편집 시 에러 클리어(InviteCode clearErrorOnEdit 패턴).
    func clearErrorOnEdit() {
        if errorMessage != nil { errorMessage = nil }
    }

    /// 그룹 생성 → 성공 시 onCreated 호출, 실패 시 errorMessage 표시.
    func submit(onCreated: @escaping () -> Void) async {
        let trimmed = trimmedName
        guard canSubmit else { return }
        isCreating = true
        errorMessage = nil
        do {
            _ = try await groupAPI.createGroup(name: trimmed)
            onCreated()
        } catch {
            isCreating = false
            errorMessage = GroupCreateError.message(for: error)
        }
    }
}

/// 그룹 생성 에러 → 화면 문구 매핑(InviteCodeError 패턴). 백엔드 meta.errorCode 기준.
enum GroupCreateError {
    static func message(for error: Error) -> String {
        guard let apiError = error as? APIError else {
            return "오류가 발생했어요. 잠시 후 다시 시도해 주세요."
        }
        switch apiError.code {
        case "GROUP_NAME_INVALID":
            return "그룹 이름은 1~30자로 입력해 주세요."
        default:
            return "오류가 발생했어요. 잠시 후 다시 시도해 주세요."
        }
    }
}
