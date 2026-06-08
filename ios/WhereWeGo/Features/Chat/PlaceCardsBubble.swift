import SwiftUI

// PLACE_CARDS 메시지 버블(FR-I2, AC-3). 봇이 릴스에서 추출한 장소 후보 요약 + 위저드 진입점.
//  - 요약 본문: "릴스에서 N곳을 찾았어요" + [장소 정리하기] 버튼.
//  - [장소 정리하기] 탭 → .sheet 로 ReelSaveWizard(2스텝: 위시 고르기 → 메모) 표시.
//  - N 은 좌표 있는(저장 가능) 카드 수. 0개면 위저드가 "저장 가능한 장소가 없어요" 안내(BR-1).
//
// 순수 프레젠테이션 + 콜백. ViewModel 비참조 — 위저드 제출은 onSave(wishCardIDs, memo) 로 상위(ViewModel)에 위임.
// 전체 카드(cards)는 버블이 보유하므로 ViewModel 이 cards+wishIDs 로 WISH/REEL 을 분기한다(BR-2).
struct PlaceCardsBubble: View {
    let cards: [PlaceCard]
    /// 위저드 제출 콜백(체크된 카드 id 집합, 메모). 좌표 있는 카드 전부가 저장 대상이며 체크 여부는 tag 만 결정(BR-2).
    let onSave: (_ wishCardIDs: Set<String>, _ memo: String?) -> Void

    /// 위저드 바텀시트 표시 여부([장소 정리하기] 탭으로 true).
    @State private var showWizard = false

    /// 좌표가 있어 저장 가능한 카드 수(BR-1). 요약/버튼 라벨 기준.
    private var savableCount: Int {
        cards.filter { $0.latitude != nil && $0.longitude != nil }.count
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("릴스에서 \(savableCount)곳을 찾았어요")
                .font(WGFont.serif(16))
                .foregroundStyle(WGColor.ink)

            Text("가고 싶은 곳을 골라 위시로, 나머지는 발견으로 담아드려요")
                .font(WGFont.sans(12))
                .foregroundStyle(WGColor.inkSoft)
                .fixedSize(horizontal: false, vertical: true)

            organizeButton
        }
        .padding(EdgeInsets(top: 14, leading: 14, bottom: 12, trailing: 14))
        .frame(maxWidth: 320, alignment: .leading)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(WGColor.hairline, lineWidth: 1))
        .sheet(isPresented: $showWizard) {
            ReelSaveWizard(
                cards: cards,
                onSubmit: { wishIDs, memo in onSave(wishIDs, memo) },
                onClose: { showWizard = false }
            )
        }
    }

    // MARK: - [장소 정리하기]

    private var organizeButton: some View {
        Button {
            showWizard = true
        } label: {
            Text("장소 정리하기")
                .font(WGFont.sans(13))
                .fontWeight(.semibold)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(WGColor.cta)
                .foregroundStyle(WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
    }
}
