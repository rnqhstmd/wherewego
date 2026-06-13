import SwiftUI

// 방문 동행 선택 시트(정책 v2 §2-1, FR-B2/B3). VisitToastView(현행 "함께 방문하셨나요?" 토스트)를 대체한다.
// "○○에 도착! 누구와 함께인가요?" — 다음 3선택:
//  ① 🙙 혼자예요 — 즉시 제출(companionIds=[]). 그룹 ≥2명 = 체크인, 1인 그룹 = 전환(서버 FR-I6 판정).
//  ② 멤버 다중 선택(본인 제외, AvatarView+닉네임+체크) + "함께 다녀왔어요" 확인 버튼 — 선택 명단 제출(전환).
//  ③ 나중에요 — 닫기(세션 Set 유지 — MapViewModel.dismissVisitToast 의미 보존, 같은 핀 재노출 차단).
//
// 멤버 목록: 시트 표시 시 GroupAPI.listMembers(groupId) 로드(본인 제외). 로드 실패 폴백(critic 반영):
//  "혼자예요"만 노출 + 멤버 목록 영역에 재시도 버튼.
// 표시 트리거: MapViewModel.visitToastPinId(@Published) — MapView 가 sheet(item:) 로 띄운다.
struct VisitCompanionSheet: View {
    let pin: PinSummary
    /// 활성 그룹 id(멤버 로드용). nil 이면 멤버 로드 불가 → 혼자예요만.
    let groupId: Int?
    /// 본인 user id(멤버 목록에서 제외). nil 이면 제외 없이 전체 노출(보수적).
    let currentUserId: Int?
    let groupAPI: GroupAPIProtocol
    /// 제출 콜백(companionUserIds) — 본인 제외 동행 명단. 혼자예요 = 빈 배열.
    let onSubmit: (_ companionUserIds: [Int]) -> Void
    /// "나중에요"/닫기 콜백(세션 Set 유지 — dismissVisitToast 의미).
    let onSkip: () -> Void

    /// 멤버 로드 상태(idle/loading/loaded/error). 본인 제외한 동행 후보만 loaded 에 담긴다.
    @State private var loadState: MemberLoadState = .idle
    /// 선택한 동행 user id 집합(다중 선택 토글).
    @State private var selectedIds: Set<Int> = []

    private enum MemberLoadState: Equatable {
        case idle
        case loading
        case loaded([GroupMemberItem])
        case error
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    soloButton
                    memberSection
                }
                .padding(.horizontal, 20)
                .padding(.top, 4)
                .padding(.bottom, 16)
            }
            footer
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(WGColor.bg)
        .task { await loadMembers() }
    }

    // MARK: - 헤더("○○에 도착! 누구와 함께인가요?")

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(pin.placeName)에 도착!")
                .font(WGFont.sansBold(18))
                .foregroundStyle(WGColor.ink)
            Text("누구와 함께인가요?")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 24)
        .padding(.bottom, 16)
    }

    // MARK: - 🙙 혼자예요(즉시 제출)

    private var soloButton: some View {
        Button {
            onSubmit([])
        } label: {
            HStack(spacing: 12) {
                Text("🙙")
                    .font(.system(size: 22))
                Text("혼자예요")
                    .font(WGFont.sans(15))
                    .fontWeight(.semibold)
                    .foregroundStyle(WGColor.ink)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(WGColor.panel)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(WGColor.hairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    // MARK: - 멤버 다중 선택(본인 제외)

    @ViewBuilder
    private var memberSection: some View {
        switch loadState {
        case .idle, .loading:
            HStack {
                Spacer()
                ProgressView().tint(WGColor.cta)
                Spacer()
            }
            .padding(.vertical, 20)
        case let .loaded(members):
            if members.isEmpty {
                // 동행 후보 없음(1인 그룹 등) — 혼자예요만 의미 있으므로 멤버 행 생략.
                EmptyView()
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    Text("함께 간 멤버를 선택해주세요")
                        .font(WGFont.sans(12))
                        .foregroundStyle(WGColor.inkSoft)
                    ForEach(members) { member in
                        memberRow(member)
                    }
                }
            }
        case .error:
            // 로드 실패 폴백(critic 반영): 재시도 버튼. 혼자예요는 위에 항상 노출되므로 진행 가능.
            VStack(alignment: .leading, spacing: 10) {
                Text("멤버 목록을 불러오지 못했어요.")
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkSoft)
                Button {
                    Task { await loadMembers() }
                } label: {
                    Text("다시 시도")
                        .font(WGFont.sans(13))
                        .padding(.horizontal, 18)
                        .padding(.vertical, 9)
                        .foregroundStyle(WGColor.cta)
                        .overlay(Capsule().stroke(WGColor.cta, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
            .padding(.vertical, 6)
        }
    }

    /// 동행 후보 1명 행 — AvatarView(28pt) + 닉네임 + 체크(선택 시 cta 원). 탭 = 선택 토글.
    private func memberRow(_ member: GroupMemberItem) -> some View {
        let isSelected = selectedIds.contains(member.userId)
        return Button {
            if isSelected {
                selectedIds.remove(member.userId)
            } else {
                selectedIds.insert(member.userId)
            }
        } label: {
            HStack(spacing: 12) {
                AvatarView(imageUrl: member.profileImageUrl, name: member.nickname, size: 36)
                Text(member.nickname)
                    .font(WGFont.sans(15))
                    .foregroundStyle(WGColor.ink)
                Spacer(minLength: 0)
                ZStack {
                    Circle()
                        .stroke(isSelected ? Color.clear : WGColor.hairline, lineWidth: 1.5)
                        .frame(width: 22, height: 22)
                    if isSelected {
                        Circle()
                            .fill(WGColor.cta)
                            .frame(width: 22, height: 22)
                        Image(systemName: "checkmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(WGColor.panel)
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(isSelected ? WGColor.cta.opacity(0.06) : WGColor.panel)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    // MARK: - 하단(확인/나중에요)

    @ViewBuilder
    private var footer: some View {
        VStack(spacing: 0) {
            Divider().overlay(WGColor.hairline)
            HStack(spacing: 10) {
                Button(action: onSkip) {
                    Text("나중에요")
                        .font(WGFont.sans(14))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .foregroundStyle(WGColor.ctaSub)
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
                }
                .buttonStyle(.plain)
                // 멤버를 1명 이상 선택했을 때만 확인 버튼 활성(동행 전환).
                if !selectedIds.isEmpty {
                    Button {
                        onSubmit(Array(selectedIds))
                    } label: {
                        Text("함께 다녀왔어요")
                            .font(WGFont.sans(14))
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 13)
                            .background(WGColor.cta)
                            .foregroundStyle(WGColor.panel)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 8)
        }
        .background(WGColor.bg)
    }

    // MARK: - 멤버 로드(본인 제외)

    /// 활성 그룹 멤버 로드 후 본인 제외(currentUserId). 실패 시 .error(폴백 — 혼자예요만).
    /// groupId nil 이면 로드 불가 → .error 로 폴백(재시도 시 다시 nil 이라 의미 없으나 혼자예요는 항상 가능).
    private func loadMembers() async {
        guard let groupId else {
            loadState = .error
            return
        }
        loadState = .loading
        do {
            let members = try await groupAPI.listMembers(groupId: groupId)
            // 본인 제외(currentUserId nil 이면 제외 없이 전체 — 보수적이나 서버가 본인 자동 제거하므로 안전).
            let candidates = members.filter { currentUserId == nil || $0.userId != currentUserId }
            loadState = .loaded(candidates)
        } catch {
            loadState = .error
        }
    }
}

// MARK: - 방문 날짜 포맷(PinDetailContent/PinShareCard 공용)

/// ISO8601 파싱 + 점 구분 날짜 포맷(YYYY.MM.DD). 웹 formatDate / dateLabel 동치.
/// (정책 v2 — VisitToastView 삭제 시 PinDetailContent/PinShareCard 가 의존하던 formatDate/VisitDateFormatter 를
///  본 파일로 이관해 참조 정합을 유지한다.)
enum VisitDateFormatter {
    // 포매터는 생성 후 변경하지 않고 read(date(from:)/string(from:))만 호출한다 — 이 메서드들은
    // thread-safe 이므로 OnboardingFlags 와 동일하게 nonisolated(unsafe) 로 Swift 6 동시성 검사를 우회한다.
    /// fractional seconds 포함 ISO8601 포매터(재사용).
    nonisolated(unsafe) private static let isoWithFraction: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    /// fractional seconds 미포함 ISO8601 포매터(재사용).
    nonisolated(unsafe) private static let isoPlain: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    /// "YYYY.MM.DD" 출력 포매터(재사용).
    nonisolated(unsafe) private static let dottedFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy.MM.dd"
        return formatter
    }()

    /// ISO8601 문자열 → Date. fractional seconds 유무 양쪽 시도.
    static func parse(_ iso: String) -> Date? {
        if let date = isoWithFraction.date(from: iso) { return date }
        return isoPlain.date(from: iso)
    }

    /// Date → "YYYY.MM.DD".
    static func dotted(_ date: Date) -> String {
        dottedFormatter.string(from: date)
    }

    /// ISO8601 createdAt → "YYYY.MM.DD"(웹 formatDate 동치). 파싱 실패 시 빈 문자열.
    /// (구 VisitToastView.formatDate 이관 — PinDetailContent/PinShareCard 참조 정합.)
    static func formatDate(_ iso: String) -> String {
        guard let date = parse(iso) else { return "" }
        return dotted(date)
    }
}
