import SwiftUI

// 태그 필터 바(설계 §3, FR-6/AC-4). REEL/WISH/MEMORY 독립 토글.
// frontend/src/app/map/MapClient.tsx 의 태그 필터 칩 UI 를 SwiftUI 로 이식. 기본 전체 ON.
struct TagFilterBar: View {
    /// 활성 태그 집합. MapViewModel.activeFilters 바인딩.
    @Binding var activeFilters: Set<PinTag>

    var body: some View {
        HStack(spacing: 8) {
            ForEach(PinTag.allCases, id: \.self) { tag in
                chip(for: tag)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private func chip(for tag: PinTag) -> some View {
        let isOn = activeFilters.contains(tag)
        return Button {
            toggle(tag)
        } label: {
            HStack(spacing: 6) {
                Circle()
                    .fill(color(for: tag))
                    .frame(width: 8, height: 8)
                Text(label(for: tag))
                    .font(WGFont.sans(13))
                    .foregroundStyle(isOn ? WGColor.ink : WGColor.inkFaint)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(isOn ? WGColor.panel : WGColor.bg)
            .overlay(
                Capsule()
                    .stroke(isOn ? color(for: tag) : WGColor.hairline, lineWidth: 1)
            )
            .clipShape(Capsule())
            .opacity(isOn ? 1 : 0.6)
        }
    }

    /// 독립 토글: 켜져 있으면 끄고, 꺼져 있으면 켠다.
    private func toggle(_ tag: PinTag) {
        if activeFilters.contains(tag) {
            activeFilters.remove(tag)
        } else {
            activeFilters.insert(tag)
        }
    }

    private func color(for tag: PinTag) -> Color {
        switch tag {
        case .REEL: return WGColor.pinReel
        case .WISH: return WGColor.pinWish
        case .MEMORY: return WGColor.pinMemory
        }
    }

    private func label(for tag: PinTag) -> String {
        switch tag {
        case .REEL: return "릴스"
        case .WISH: return "위시"
        case .MEMORY: return "추억"
        }
    }
}
