import SwiftUI

// 릴스 장소 저장 2스텝 위저드(FR-I2~I5, BR-1/2/3). PlaceCardsBubble 의 [장소 정리하기] → .sheet 로 표시.
//  - Step 1/2(위시 고르기): 카드 체크박스. 체크=WISH, 미체크(좌표 있는 것)=REEL 저장. 좌표 없는 카드는 비활성.
//  - Step 2/2(메모): 모든 핀에 공통 적용될 메모 입력(선택). [← 이전]/[건너뛰기]/[저장].
//
// 순수 프레젠테이션 + 콜백. ViewModel 비참조 — 제출은 onSubmit(wishCardIDs, memo) 로 상위(버블/ViewModel)에 위임.
// 전체 카드는 버블이 cards 로 보유하므로 위저드는 "체크된 카드 id 집합 + 메모"만 올려보낸다(ViewModel 이 cards+wishIds 분기).
struct ReelSaveWizard: View {
    let cards: [PlaceCard]
    /// 제출 콜백(체크된 카드 id 집합, 메모). 메모는 빈 문자열이면 nil 로 정규화해 전달.
    let onSubmit: (_ wishCardIDs: Set<String>, _ memo: String?) -> Void
    /// 시트 닫기(취소/제출 후 공통).
    let onClose: () -> Void

    /// 현재 단계(1: 위시 고르기, 2: 메모).
    @State private var step: Int = 1
    /// 체크된(=WISH) 카드 id 집합. 좌표 있는 카드만 토글된다(BR-1/BR-2).
    @State private var wishIDs: Set<String> = []
    /// 메모 초안(Step 2). 모든 저장 핀에 공통 적용(BR-3).
    @State private var memoDraft: String = ""

    /// 좌표가 있어 저장 가능한 카드(BR-1). 위저드 진입 가드(0개면 안내).
    private var savableCards: [PlaceCard] {
        cards.filter { $0.latitude != nil && $0.longitude != nil }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header

            if savableCards.isEmpty {
                noSavableState
            } else if step == 1 {
                stepWish
            } else {
                stepMemo
            }
        }
        .background(WGColor.bg)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    // MARK: - 헤더(FR-I7 단계 표시)

    private var header: some View {
        HStack(spacing: 8) {
            Text(headerTitle)
                .font(WGFont.serif(18))
                .foregroundStyle(WGColor.ink)
            Spacer(minLength: 0)
            Button {
                onClose()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(WGColor.inkSoft)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .padding(.bottom, 12)
    }

    private var headerTitle: String {
        if savableCards.isEmpty {
            return "릴스 장소 정리"
        }
        return step == 1
            ? "릴스에서 \(savableCards.count)곳을 찾았어요 · 1/2"
            : "메모를 남겨볼까요? · 2/2"
    }

    // MARK: - 저장 가능 0개(BR-1)

    private var noSavableState: some View {
        VStack(spacing: 12) {
            Spacer(minLength: 24)
            Image(systemName: "mappin.slash")
                .font(.system(size: 32))
                .foregroundStyle(WGColor.inkFaint)
            Text("저장 가능한 장소가 없어요")
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.inkSoft)
            Spacer(minLength: 0)
            Button {
                onClose()
            } label: {
                Text("닫기")
                    .font(WGFont.sans(14))
                    .fontWeight(.semibold)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(WGColor.panel)
                    .foregroundStyle(WGColor.ink)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Step 1/2 (위시 고르기)

    private var stepWish: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("가고 싶은 곳을 골라주세요 · 나머지는 발견으로 담겨요")
                .font(WGFont.sans(12.5))
                .foregroundStyle(WGColor.inkSoft)
                .padding(.horizontal, 20)
                .padding(.bottom, 12)

            ScrollView {
                VStack(spacing: 8) {
                    ForEach(cards) { card in
                        cardRow(card)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 8)
            }

            nextButton
        }
    }

    private func cardRow(_ card: PlaceCard) -> some View {
        let savable = isSavable(card)
        let isSelected = wishIDs.contains(card.id)
        return Button {
            guard savable else { return }
            toggle(card)
        } label: {
            HStack(alignment: .top, spacing: 10) {
                checkbox(isSelected: isSelected, enabled: savable)
                VStack(alignment: .leading, spacing: 3) {
                    Text(card.name)
                        .font(WGFont.serif(16))
                        .foregroundStyle(savable ? WGColor.ink : WGColor.inkFaint)
                        .multilineTextAlignment(.leading)
                    if let address = card.address, !address.isEmpty {
                        Text(address)
                            .font(WGFont.mono(11.5))
                            .foregroundStyle(WGColor.inkSoft)
                            .multilineTextAlignment(.leading)
                    }
                    if !savable {
                        Text("좌표 정보가 없어 저장할 수 없어요")
                            .font(WGFont.sans(11))
                            .foregroundStyle(WGColor.inkFaint)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(isSelected ? WGColor.cta.opacity(0.08) : WGColor.panel)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isSelected ? WGColor.cta : WGColor.hairline, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .disabled(!savable)
    }

    private func checkbox(isSelected: Bool, enabled: Bool) -> some View {
        Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
            .font(.system(size: 18))
            .foregroundStyle(isSelected ? WGColor.cta : (enabled ? WGColor.inkFaint : WGColor.inkFaint.opacity(0.5)))
    }

    private var nextButton: some View {
        Button {
            step = 2
        } label: {
            Text("다음")
                .font(WGFont.sans(15))
                .fontWeight(.semibold)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(WGColor.cta)
                .foregroundStyle(WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 20)
    }

    // MARK: - Step 2/2 (메모)

    private var stepMemo: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("고른 장소들에 함께 붙어요(선택)")
                .font(WGFont.sans(12.5))
                .foregroundStyle(WGColor.inkSoft)
                .padding(.horizontal, 20)
                .padding(.bottom, 12)

            TextField("메모를 남겨보세요", text: $memoDraft, axis: .vertical)
                .font(WGFont.sans(15))
                .foregroundStyle(WGColor.ink)
                .lineLimit(2...5)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
                .padding(.horizontal, 20)

            Spacer(minLength: 0)

            memoButtons
        }
    }

    private var memoButtons: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Button {
                    step = 1
                } label: {
                    Text("← 이전")
                        .font(WGFont.sans(14))
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(WGColor.panel)
                        .foregroundStyle(WGColor.ink)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
                }

                Button {
                    submit(memo: nil)   // 건너뛰기 = 메모 없이 저장(FR-I4).
                } label: {
                    Text("건너뛰기")
                        .font(WGFont.sans(14))
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(WGColor.panel)
                        .foregroundStyle(WGColor.inkSoft)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
                }
            }

            Button {
                submit(memo: memoDraft)
            } label: {
                Text("저장")
                    .font(WGFont.sans(15))
                    .fontWeight(.semibold)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(WGColor.cta)
                    .foregroundStyle(WGColor.panel)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 20)
    }

    // MARK: - 로직

    /// 좌표(lat/lng)가 모두 있어야 핀 저장 가능(BR-1).
    private func isSavable(_ card: PlaceCard) -> Bool {
        card.latitude != nil && card.longitude != nil
    }

    private func toggle(_ card: PlaceCard) {
        if wishIDs.contains(card.id) {
            wishIDs.remove(card.id)
        } else {
            wishIDs.insert(card.id)
        }
    }

    /// 제출(체크된 카드 id + 메모). 빈 메모는 nil 로 정규화. 제출 후 시트 닫기.
    private func submit(memo: String?) {
        let trimmed = memo?.trimmingCharacters(in: .whitespacesAndNewlines)
        onSubmit(wishIDs, (trimmed?.isEmpty == false) ? trimmed : nil)
        onClose()
    }
}
