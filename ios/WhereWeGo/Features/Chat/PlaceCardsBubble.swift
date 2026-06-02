import SwiftUI

// PLACE_CARDS 메시지 버블(설계 §5·§7, FR-5/AC-3). 봇이 릴스에서 추출한 장소 후보 카드 묶음.
// 카드별: 장소명(세리프, chat1.md 톤)·주소(Mono)·선택 토글(다중). 하단 "저장" 버튼.
// 좌표(latitude/longitude) 없는 카드는 선택 비활성 + 안내(핀 저장 불가, 설계 §5).
//
// 순수 프레젠테이션 + 콜백. ViewModel 비참조 — 선택 상태는 내부 @State, 저장은 onSave 클로저로 상위(ViewModel)에 위임.
// 상위(C7/C8)는 onSave 에서 좌표 있는 선택 카드만 PinAPI.create 로 저장한다(409 흡수는 ViewModel 책임).
struct PlaceCardsBubble: View {
    let cards: [PlaceCard]
    /// 선택된 카드 저장 콜백. 좌표 있는 카드만 선택 가능하므로 selected 는 항상 저장 가능 카드.
    let onSave: ([PlaceCard]) -> Void

    /// 선택된 카드 id(PlaceCard.id) 집합. 좌표 있는 카드만 토글된다.
    @State private var selectedIDs: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("이런 장소를 찾았어요")
                .font(WGFont.sans(12))
                .foregroundStyle(WGColor.inkSoft)

            VStack(spacing: 8) {
                ForEach(cards) { card in
                    cardRow(card)
                }
            }

            saveButton
        }
        .padding(EdgeInsets(top: 14, leading: 14, bottom: 12, trailing: 14))
        .frame(maxWidth: 320, alignment: .leading)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(WGColor.hairline, lineWidth: 1))
    }

    // MARK: - 카드 1건

    private func cardRow(_ card: PlaceCard) -> some View {
        let savable = isSavable(card)
        let isSelected = selectedIDs.contains(card.id)
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
            .background(isSelected ? WGColor.cta.opacity(0.08) : WGColor.bg)
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

    // MARK: - 저장 버튼

    private var saveButton: some View {
        let count = selectedIDs.count
        return Button {
            let selected = cards.filter { selectedIDs.contains($0.id) }
            guard !selected.isEmpty else { return }
            onSave(selected)
            // 저장 완료 후 선택 초기화 — 체크박스 잔존·중복 저장 방지(Gemini).
            selectedIDs = []
        } label: {
            Text(count > 0 ? "\(count)곳 저장" : "저장")
                .font(WGFont.sans(13))
                .fontWeight(.semibold)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(count > 0 ? WGColor.cta : WGColor.inkFaint.opacity(0.3))
                .foregroundStyle(count > 0 ? WGColor.panel : WGColor.inkSoft)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .disabled(count == 0)
    }

    // MARK: - 선택 로직

    /// 좌표(lat/lng)가 모두 있어야 핀 저장 가능(설계 §5).
    private func isSavable(_ card: PlaceCard) -> Bool {
        card.latitude != nil && card.longitude != nil
    }

    private func toggle(_ card: PlaceCard) {
        if selectedIDs.contains(card.id) {
            selectedIDs.remove(card.id)
        } else {
            selectedIDs.insert(card.id)
        }
    }
}
