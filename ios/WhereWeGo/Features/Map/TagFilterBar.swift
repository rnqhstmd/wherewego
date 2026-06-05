import SwiftUI

// 좌하단 지도 컨트롤(설계 §3, FR-6/AC-4 + 웹 정합).
// 구 TagFilterBar(상단 태그 칩)를 제거하고, 웹 map/_components 의 좌하단 2버튼을 1:1 이식한다:
//  - TagLegendButton  : [!] 마커 의미 안내 팝업(발견/위시/추억 색·설명).
//  - TagFilterButton  : [▽] 체크박스 드롭다운 필터(전체 + 추억/위시/발견).
// 글리프: 웹은 별/하트 모양을 쓰지만 iOS 지도 마커는 태그별 '색 원'(MapboxMapView CircleLayer)이라,
//  범례/필터 글리프도 마커와 동일한 '색 원'으로 맞춘다(자기정합 — 실제 지도와 시각 일치).
// 열림/닫힘은 부모(MapView)가 isOpen 바인딩으로 상호배타 제어(범례·필터 동시표시 금지 + 바깥 탭 닫힘 공유).

// MARK: - 공통 색 매핑

private func tagColor(_ tag: PinTag) -> Color {
    switch tag {
    case .REEL: return WGColor.pinReel
    case .WISH: return WGColor.pinWish
    case .MEMORY: return WGColor.pinMemory
    }
}

/// 마커 정합 색 원 글리프(바깥 옅은 후광 + 안쪽 채운 원 + 흰 테두리). 범례/필터 공통.
private struct TagDotGlyph: View {
    let tag: PinTag
    var halo: CGFloat = 28
    var dot: CGFloat = 14

    var body: some View {
        ZStack {
            Circle().fill(tagColor(tag).opacity(0.1)).frame(width: halo, height: halo)
            Circle()
                .fill(tagColor(tag))
                .frame(width: dot, height: dot)
                .overlay(Circle().stroke(Color.white, lineWidth: 1.4))
        }
    }
}

// MARK: - 범례 버튼([!])

/// 좌하단 ! 마커 범례 버튼(웹 TagLegendButton.tsx 이식). 탭 → 위로 안내 팝업.
struct TagLegendButton: View {
    @Binding var isOpen: Bool

    private struct Stage {
        let tag: PinTag
        let label: String
        let desc: String
    }

    // 웹 STAGES 순서: 발견 → 위시 → 추억.
    private let stages: [Stage] = [
        Stage(tag: .REEL, label: "발견", desc: "둘러본 곳"),
        Stage(tag: .WISH, label: "위시", desc: "가고 싶다고 표시한 곳"),
        Stage(tag: .MEMORY, label: "추억", desc: "다녀온 곳"),
    ]

    var body: some View {
        Button {
            isOpen.toggle()
        } label: {
            Image(systemName: "exclamationmark.circle")
                .font(.system(size: 18, weight: .regular))
                .foregroundStyle(WGColor.inkSoft)
                .frame(width: 44, height: 44)
                .background(Circle().fill(WGColor.panel))
                .overlay(Circle().stroke(WGColor.hairline, lineWidth: 1))
                .shadow(color: WGColor.shadow, radius: 8, y: 3)
        }
        .accessibilityLabel("아이콘 및 단계 안내")
        // 팝업은 버튼 위(leading)로 띄운다(웹 bottom:52, left:0 동치). 버튼 프레임 밖으로 렌더되며 상호배타.
        .overlay(alignment: .bottomLeading) {
            if isOpen {
                popup.offset(y: -(44 + 8))
            }
        }
    }

    private var popup: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("지도 마커 안내")
                .font(WGFont.sans(11))
                .fontWeight(.bold)
                .foregroundStyle(WGColor.inkFaint)
                .padding(.bottom, 6)

            (
                Text("가고 싶은 곳은 ")
                + Text("위시").fontWeight(.bold).foregroundColor(WGColor.ink)
                + Text("로, 다녀오면 ")
                + Text("추억").fontWeight(.bold).foregroundColor(WGColor.ink)
                + Text("이 돼요.")
            )
            .font(WGFont.sans(11.5))
            .foregroundStyle(WGColor.inkSoft)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.bottom, 12)

            ForEach(Array(stages.enumerated()), id: \.offset) { index, stage in
                HStack(spacing: 10) {
                    TagDotGlyph(tag: stage.tag)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(stage.label)
                            .font(WGFont.sans(12.5))
                            .fontWeight(.bold)
                            .foregroundStyle(WGColor.ink)
                        Text(stage.desc)
                            .font(WGFont.sans(11))
                            .foregroundStyle(WGColor.inkSoft)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.bottom, index == stages.count - 1 ? 0 : 8)
            }
        }
        .padding(EdgeInsets(top: 14, leading: 16, bottom: 12, trailing: 16))
        .frame(width: 248, alignment: .leading)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(WGColor.hairline, lineWidth: 1))
        .shadow(color: WGColor.shadowMd, radius: 14, y: 8)
    }
}

// MARK: - 필터 버튼([▽])

/// 좌하단 핀 필터 버튼(웹 TagFilterButton.tsx 이식). 깔때기 아이콘 → 탭 시 위로 체크박스 드롭다운.
/// activeFilters(Set<PinTag>) 바인딩. 전체 체크 시 모두 표시(기본), 일부라도 해제되면 우상단 주황 점.
struct TagFilterButton: View {
    @Binding var activeFilters: Set<PinTag>
    @Binding var isOpen: Bool

    // 웹 OPTIONS 순서: 추억 → 위시 → 발견.
    private let options: [(tag: PinTag, label: String)] = [
        (.MEMORY, "추억"),
        (.WISH, "위시"),
        (.REEL, "발견"),
    ]

    private var allChecked: Bool { activeFilters.count == PinTag.allCases.count }
    private var isFiltering: Bool { !allChecked }

    var body: some View {
        Button {
            isOpen.toggle()
        } label: {
            Image(systemName: "line.3.horizontal.decrease")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(WGColor.inkSoft)
                .frame(width: 44, height: 44)
                .background(Circle().fill(WGColor.panel))
                .overlay(Circle().stroke(WGColor.hairline, lineWidth: 1))
                // 필터 적용 중(전체 아님) 표식 — 우상단 주황 점(웹 active dot 동치).
                .overlay(alignment: .topTrailing) {
                    if isFiltering {
                        Circle()
                            .fill(WGColor.cta)
                            .frame(width: 8, height: 8)
                            .overlay(Circle().stroke(WGColor.panel, lineWidth: 1.5))
                            .offset(x: -7, y: 7)
                    }
                }
                .shadow(color: WGColor.shadow, radius: 8, y: 3)
        }
        .accessibilityLabel("핀 필터")
        .overlay(alignment: .bottomLeading) {
            if isOpen {
                popup.offset(y: -(44 + 8))
            }
        }
    }

    private var popup: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("보고 싶은 핀을 골라요")
                .font(WGFont.sans(11))
                .fontWeight(.bold)
                .foregroundStyle(WGColor.inkFaint)
                .padding(.bottom, 8)

            // 전체 토글(강조). 색 원 없음.
            checkboxRow(label: "전체", checked: allChecked, emphasize: true, accent: WGColor.cta, glyphTag: nil) {
                toggleAll()
            }

            Rectangle()
                .fill(WGColor.hairline)
                .frame(height: 1)
                .padding(.vertical, 6)
                .padding(.horizontal, -14)   // 카드 좌우 끝까지(웹 margin:-14)

            ForEach(Array(options.enumerated()), id: \.offset) { _, option in
                checkboxRow(
                    label: option.label,
                    checked: activeFilters.contains(option.tag),
                    emphasize: false,
                    accent: tagColor(option.tag),
                    glyphTag: option.tag
                ) {
                    toggle(option.tag)
                }
            }
        }
        .padding(EdgeInsets(top: 12, leading: 14, bottom: 8, trailing: 14))
        .frame(width: 220, alignment: .leading)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(WGColor.hairline, lineWidth: 1))
        .shadow(color: WGColor.shadowMd, radius: 14, y: 8)
    }

    // MARK: - 체크박스 행

    @ViewBuilder
    private func checkboxRow(
        label: String,
        checked: Bool,
        emphasize: Bool,
        accent: Color,
        glyphTag: PinTag?,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 5)
                        .fill(checked ? accent : Color.clear)
                        .frame(width: 18, height: 18)
                        .overlay(
                            RoundedRectangle(cornerRadius: 5)
                                .stroke(checked ? accent : WGColor.hairline, lineWidth: 1.5)
                        )
                    if checked {
                        Image(systemName: "checkmark")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(Color.white)
                    }
                }
                if let glyphTag {
                    TagDotGlyph(tag: glyphTag, halo: 22, dot: 11)
                }
                Text(label)
                    .font(WGFont.sans(13))
                    .fontWeight(emphasize ? .bold : .semibold)
                    .foregroundStyle(WGColor.ink)
                Spacer(minLength: 0)
            }
            .padding(.vertical, 5)
            .padding(.horizontal, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - 토글 로직(웹 동치)

    /// 전체: 모두 켜져 있으면 전부 해제(빈 Set → 마커 0), 아니면 전체 선택.
    private func toggleAll() {
        if allChecked {
            activeFilters = []
        } else {
            activeFilters = Set(PinTag.allCases)
        }
    }

    /// 개별 독립 토글.
    private func toggle(_ tag: PinTag) {
        if activeFilters.contains(tag) {
            activeFilters.remove(tag)
        } else {
            activeFilters.insert(tag)
        }
    }
}
