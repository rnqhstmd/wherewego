import SwiftUI

// 방문 전환 후속 메모 입력 시트(설계 §4, FR-29/30). 웹 VisitMemoSheet.tsx 이식.
// frontend/src/app/map/_components/VisitMemoSheet.tsx — 헤더(장소명) + 다녀온 날짜 + 메모(≤500) + 저장/건너뛰기.
//
// 동작:
//  - 헤더 "다녀온 흔적", 날짜 "다녀온 날 · YYYY.MM.DD"(visitedAt ISO8601 파싱→포맷).
//  - 메모(≤500) → mapViewModel.updateMemoOptimistic → 성공 시 닫고 정보창(selectedPinId) 오픈.
//  - 저장 실패 시 인라인 에러, 시트 유지, 입력값 보존(웹 FR-VD-22).
//  - 건너뛰기: 2차 PATCH 미발사 — 닫고 정보창 오픈.
struct VisitMemoSheet: View {
    let pin: PinSummary
    @ObservedObject var mapViewModel: MapViewModel

    @Environment(\.dismiss) private var dismiss

    @State private var memoText: String = ""
    @State private var isSaving = false
    @State private var inlineError: String?

    private let memoLimit = 500

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header
                    Text(dateLabel)
                        .font(WGFont.mono(12))
                        .foregroundStyle(WGColor.inkSoft)

                    TextEditor(text: $memoText)
                        .font(WGFont.sans(14))
                        .frame(minHeight: 96)
                        .padding(8)
                        .background(WGColor.bg)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(WGColor.hairline, lineWidth: 1.5))
                        .onChange(of: memoText) { _, value in
                            if value.count > memoLimit {
                                memoText = String(value.prefix(memoLimit))
                            }
                        }
                    Text("\(memoText.count)/\(memoLimit)")
                        .font(WGFont.sans(11))
                        .foregroundStyle(WGColor.inkFaint)

                    if let inlineError {
                        errorBanner(inlineError)
                    }

                    buttons
                }
                .padding(20)
            }
            // 글래스 통일: 불투명 WGColor.bg 배경 제거 — 시트의 .presentationBackground(.regularMaterial)이 비치도록.
            .scrollContentBackground(.hidden)
            .navigationTitle("다녀온 흔적")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .interactiveDismissDisabled(isSaving)
        }
    }

    // MARK: - 헤더/날짜

    private var header: some View {
        Text("\(pin.placeName), 다녀온 흔적을 남겨볼까요?")
            .font(WGFont.serif(18))
            .foregroundStyle(WGColor.ink)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// "다녀온 날 · YYYY.MM.DD"(visitedAt 우선, 없으면 createdAt). 파싱 실패 시 날짜 생략.
    private var dateLabel: String {
        let iso = pin.visitedAt ?? pin.createdAt
        guard let date = VisitDateFormatter.parse(iso) else { return "다녀온 날" }
        return "다녀온 날 · \(VisitDateFormatter.dotted(date))"
    }

    // MARK: - 버튼

    private var buttons: some View {
        HStack(spacing: 8) {
            Button {
                skip()
            } label: {
                Text("건너뛰기")
                    .font(WGFont.sans(14))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .foregroundStyle(WGColor.ctaSub)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
            }
            .disabled(isSaving)

            Button {
                Task { await save() }
            } label: {
                Text(isSaving ? "저장 중..." : "저장")
                    .font(WGFont.sans(14))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(WGColor.cta)
                    .foregroundStyle(WGColor.panel)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            .disabled(isSaving)
        }
    }

    private func errorBanner(_ message: String) -> some View {
        Text(message)
            .font(WGFont.sans(13))
            .foregroundStyle(WGColor.pinNew)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(WGColor.pinNew.opacity(0.1))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    // MARK: - 액션

    /// 메모 저장(2차 PATCH). 성공 시 닫고 정보창(selectedPinId) 오픈. 실패 시 인라인 에러·시트 유지.
    private func save() async {
        let trimmed = memoText.trimmingCharacters(in: .whitespacesAndNewlines)
        // 빈 메모는 PATCH 없이 건너뛰기와 동일하게 처리(웹은 maxLength 만 가드, 빈값 허용).
        guard !trimmed.isEmpty else {
            skip()
            return
        }
        inlineError = nil
        isSaving = true
        defer { isSaving = false }
        do {
            try await mapViewModel.updateMemoOptimistic(pinId: pin.id, memo: trimmed)
            finish()
        } catch {
            inlineError = (error as? LocalizedError)?.errorDescription ?? "메모를 저장하지 못했어요. 다시 시도해 주세요."
        }
    }

    /// 건너뛰기 — 2차 PATCH 미발사. 닫고 정보창 오픈.
    private func skip() {
        finish()
    }

    /// 시트 닫고 핀 상세(정보창) 자동 오픈(웹 Phase 10 UX).
    /// 단일 사이클에 visitMemo 시트를 닫으면서 selectedPinId 까지 세팅하면
    /// 두 .sheet(item:) 전환이 경쟁해 PinDetail 이 안 열릴 수 있다(시트 전환 경쟁).
    /// → visitMemo 시트만 먼저 닫고(activeSheet=.none), 열어야 할 핀은 pendingDetailPinId 로 보류한다.
    ///   selectedPinId 설정은 MapView 의 visitMemoSheet onDismiss 에서 소비(시트 dismiss 이후 시퀀싱).
    private func finish() {
        mapViewModel.pendingDetailPinId = pin.id
        mapViewModel.activeSheet = .none
        dismiss()
    }
}
