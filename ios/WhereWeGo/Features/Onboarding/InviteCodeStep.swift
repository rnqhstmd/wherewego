import Foundation

// 초대 코드 가입 2단계 상태머신 + 에러 매핑 + 만료일 포맷(설계 §3, 순수 타입 — 테스트 격리).

/// 초대 코드 가입 흐름 상태(설계 §3, 5상태).
/// previewing/accepting 별도 case 유지: 로딩 중 버튼 비활성(QE-1)이라 취소는 .confirm/.alreadyMember 에서만.
enum InviteCodeStep: Equatable {
    case input
    case previewing
    case confirm(InvitePreview)
    case accepting(InvitePreview)
    case alreadyMember(InvitePreview)
}

/// errorCode → 화면 문구 매핑(설계 §3, M1/M2). 단계 무관 통합: preview·accept catch 양쪽이 공유.
/// 백엔드 meta.errorCode 기준(APIError.code). GROUP_ALREADY_MEMBER 는 nil(호출부가 .alreadyMember 로 가로챔).
enum InviteCodeError {
    static func message(for error: Error) -> String? {
        guard let apiError = error as? APIError else {
            // 비 APIError(네트워크 등) → 공통 문구.
            return "오류가 발생했어요. 잠시 후 다시 시도해 주세요."
        }
        switch apiError.code {
        case "INVITE_LINK_NOT_FOUND":
            // M1: preview 가 만료·없음·그룹삭제를 모두 404 로 통합 → 정직하게 "존재하지 않거나 만료된".
            return "존재하지 않거나 만료된 코드예요. 다시 확인해 주세요."
        case "INVITE_LINK_EXPIRED":
            return "만료된 초대 코드예요. 새 코드를 받아 주세요."
        case "GROUP_CAPACITY_EXCEEDED":
            return "그룹 정원(10명)이 꽉 찼어요."
        case "INVITE_LINK_SELF_ACCEPT":
            return "내가 만든 초대 코드는 사용할 수 없어요."
        case "GROUP_REJOIN_FORBIDDEN":
            return "한번 나간 그룹에는 다시 합류할 수 없어요."
        case "INVITE_LINK_RATE_LIMITED":
            return "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."
        case "GROUP_ALREADY_MEMBER":
            // FR-12/BR-4: 에러 아님 — confirmJoin 이 .alreadyMember 로 가로챔.
            return nil
        default:
            // HTTP_* · 그 외 서버 에러 → 공통 문구.
            return "오류가 발생했어요. 잠시 후 다시 시도해 주세요."
        }
    }
}

/// 초대 코드 만료일 포맷(설계 §3, M3). ISO8601 → "M월 d일까지"(KST).
enum InviteDateFormatter {
    /// ISO8601 문자열을 "M월 d일까지"로 변환. 파싱 실패 시 nil(FR-18 graceful).
    static func untilMonthDay(_ iso: String) -> String? {
        guard let date = isoWithFraction.date(from: iso) ?? isoPlain.date(from: iso) else {
            return nil
        }
        return monthDayFormatter.string(from: date)
    }

    // thread-safe 이므로 nonisolated(unsafe) 로 Swift 6 동시성 검사 우회(VisitDateFormatter 패턴).

    nonisolated(unsafe) private static let isoWithFraction: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    nonisolated(unsafe) private static let isoPlain: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    // timeZone = Asia/Seoul 필수 — UTC 경계일 오차 방지(M3).
    nonisolated(unsafe) private static let monthDayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.timeZone = TimeZone(identifier: "Asia/Seoul")
        formatter.dateFormat = "M월 d일까지"
        return formatter
    }()
}
