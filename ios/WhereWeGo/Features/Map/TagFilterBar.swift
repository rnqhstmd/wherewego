import SwiftUI

// 좌하단 지도 컨트롤(설계 §3, FR-6/AC-4 + 웹 정합).
// 구 TagFilterBar(상단 태그 칩)를 제거하고, 웹 map/_components 의 좌하단 2버튼을 1:1 이식한다:
//  - TagLegendButton  : [!] 마커 의미 안내 팝업(발견/위시/추억 색·설명).
//  - TagFilterButton  : [▽] 체크박스 드롭다운 필터(전체 + 추억/위시/발견).
// 글리프: 웹 markers.tsx 와 동일하게 태그별 '모양'을 다르게 그린다 —
//  REEL=하늘색 원 / WISH=노란 5각 별 / MEMORY=핑크 하트. (Core/Map/PinMarkerImage.swift 의 베지어 비율 재사용)
// 열림/닫힘은 부모(MapView)가 isOpen 바인딩으로 상호배타 제어(범례·필터 동시표시 금지 + 바깥 탭 닫힘 공유).

// MARK: - 공통 색 매핑

private func tagColor(_ tag: PinTag) -> Color {
    switch tag {
    case .REEL: return WGColor.pinReel
    case .WISH: return WGColor.pinWish
    case .MEMORY: return WGColor.pinMemory
    }
}

// MARK: - 태그 글리프 Shape(웹 markers.tsx 1:1, PinMarkerImage 비율 재사용)

/// 5각 별 — 웹 WishGlyph / PinMarkerImage.star 와 동일 비율(외곽/내부 0.39, 위쪽 꼭짓점 시작).
private struct StarShape: Shape {
    func path(in rect: CGRect) -> Path {
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let outer = min(rect.width, rect.height) / 2
        let inner = outer * 0.39
        var path = Path()
        for i in 0..<10 {
            let radius = (i % 2 == 0) ? outer : inner
            let angle = -CGFloat.pi / 2 + CGFloat(i) * (CGFloat.pi / 5)
            let point = CGPoint(
                x: center.x + radius * cos(angle),
                y: center.y + radius * sin(angle)
            )
            if i == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        path.closeSubpath()
        return path
    }
}

/// 하트 — 웹 MemoryGlyph / PinMarkerImage.heart 의 24x24 베지어 path 를 정규화 이식.
private struct HeartShape: Shape {
    func path(in rect: CGRect) -> Path {
        let scale = rect.width / 24.0
        let tx = rect.minX
        let ty = rect.minY
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: tx + x * scale, y: ty + y * scale)
        }
        var path = Path()
        path.move(to: p(12, 21.35))
        path.addCurve(to: p(2, 8.5), control1: p(7, 17), control2: p(2, 12.28))
        path.addCurve(to: p(7.5, 3), control1: p(2, 5.42), control2: p(4.42, 3))
        path.addCurve(to: p(12, 5.09), control1: p(9.24, 3), control2: p(10.91, 3.81))
        path.addCurve(to: p(16.5, 3), control1: p(13.09, 3.81), control2: p(14.76, 3))
        path.addCurve(to: p(22, 8.5), control1: p(19.58, 3), control2: p(22, 5.42))
        path.addCurve(to: p(12, 21.35), control1: p(22, 12.28), control2: p(17, 17))
        path.closeSubpath()
        return path
    }
}

/// 태그별 글리프(원/별/하트)를 옅은 후광 원 안에 채워 그린다. 범례/필터 공통.
/// 웹: 28pt(범례) / 22pt(필터) 원 배경(태그색 10%) + 14pt 글리프.
private struct TagGlyph: View {
    let tag: PinTag
    var halo: CGFloat = 28
    var glyph: CGFloat = 14

    var body: some View {
        ZStack {
            Circle()
                .fill(tagColor(tag).opacity(0.1))
                .frame(width: halo, height: halo)
            shape
                .frame(width: glyphSize.width, height: glyphSize.height)
        }
    }

    /// 태그별 글리프 모양(REEL=원 / WISH=별 / MEMORY=하트).
    @ViewBuilder
    private var shape: some View {
        switch tag {
        case .REEL:
            Circle().fill(tagColor(.REEL))
        case .WISH:
            StarShape().fill(tagColor(.WISH))
        case .MEMORY:
            HeartShape().fill(tagColor(.MEMORY))
        }
    }

    /// 웹 markers.tsx 비율: REEL 원/하트는 정사각, WISH 별은 1.2배(getMarkerVariant size).
    private var glyphSize: CGSize {
        switch tag {
        case .WISH:
            return CGSize(width: glyph * 1.2, height: glyph * 1.2)
        case .REEL, .MEMORY:
            return CGSize(width: glyph, height: glyph)
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
                    TagGlyph(tag: stage.tag)
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
        .frame(width: 256, alignment: .leading)
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
                // 필터 적용 중(전체 아님) 표식 — 우상단 주황 점(웹 active dot 동치, panel 1.5pt 보더).
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

            // 전체 토글(강조). 색 글리프 없음.
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
                    TagGlyph(tag: glyphTag, halo: 22, glyph: 14)
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
