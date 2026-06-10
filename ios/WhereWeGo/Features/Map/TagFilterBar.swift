import SwiftUI

// 지도 상단 필터 컨트롤(설계 §3, FR-6/AC-4 + 웹 정합).
//  - TagFilterButton : 체크박스 드롭다운 필터(전체 + 추억/위시/발견). 구 TagLegendButton([!] 마커 안내)은
//    이 팝업에 통합 — 태그 행에 설명 병기 + 하단 안내 캡션(상단 버튼 2개 혼잡 해소).
// 글리프: 웹은 별/하트 모양을 쓰지만 iOS 지도 마커는 태그별 '색 원'(MapboxMapView CircleLayer)이라,
//  필터 글리프도 마커와 동일한 '색 원'으로 맞춘다(자기정합 — 실제 지도와 시각 일치).
// 열림/닫힘은 부모(MapView)가 isOpen 바인딩으로 제어(바깥 탭 닫힘 공유).

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

// MARK: - 필터 버튼

/// 지도 핀 필터 버튼(웹 TagFilterButton.tsx 이식 + 범례 통합). 탭 시 아래로 체크박스 드롭다운.
/// activeFilters(Set<PinTag>) 바인딩. 전체 체크 시 모두 표시(기본), 일부라도 해제되면 우상단 주황 점.
/// 각 태그 행에 마커 의미 설명을 병기하고 하단에 위시→추억 안내 캡션을 둬 구 범례([!]) 역할을 흡수한다.
struct TagFilterButton: View {
    @Binding var activeFilters: Set<PinTag>
    @Binding var isOpen: Bool

    // 웹 OPTIONS 순서: 추억 → 위시 → 발견. desc = 구 범례(TagLegendButton)의 마커 의미 설명.
    private let options: [(tag: PinTag, label: String, desc: String)] = [
        (.MEMORY, "추억", "다녀온 곳"),
        (.WISH, "위시", "가고 싶다고 표시한 곳"),
        (.REEL, "발견", "둘러본 곳"),
    ]

    private var allChecked: Bool { activeFilters.count == PinTag.allCases.count }
    private var isFiltering: Bool { !allChecked }

    var body: some View {
        Button {
            isOpen.toggle()
        } label: {
            // slider.horizontal.3: ≡(메뉴)로 오독되던 line.3.horizontal.decrease 대신 조절 슬라이더 — 필터로 명확히 읽힘.
            // 36pt: 상단 그룹 행(뒤로/⋯ 버튼)과 같은 행에 배치되므로 크기 통일.
            Image(systemName: "slider.horizontal.3")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(WGColor.inkSoft)
                .frame(width: 36, height: 36)
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
        // 팝업은 버튼 아래로(top +44) + trailing 앵커로 좌측 펼침(248 화면 내 유지).
        .overlay(alignment: .topTrailing) {
            if isOpen {
                popup.offset(y: 36 + 8)
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
                    desc: option.desc,
                    checked: activeFilters.contains(option.tag),
                    emphasize: false,
                    accent: tagColor(option.tag),
                    glyphTag: option.tag
                ) {
                    toggle(option.tag)
                }
            }

            // 구 범례([!]) 안내 문장 흡수 — 위시→추억 전이 설명.
            Rectangle()
                .fill(WGColor.hairline)
                .frame(height: 1)
                .padding(.vertical, 6)
                .padding(.horizontal, -14)
            (
                Text("가고 싶은 곳은 ")
                + Text("위시").fontWeight(.bold).foregroundColor(WGColor.ink)
                + Text("로, 다녀오면 ")
                + Text("추억").fontWeight(.bold).foregroundColor(WGColor.ink)
                + Text("이 돼요.")
            )
            .font(WGFont.sans(11))
            .foregroundStyle(WGColor.inkSoft)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 4)
            .padding(.bottom, 4)
        }
        .padding(EdgeInsets(top: 12, leading: 14, bottom: 8, trailing: 14))
        .frame(width: 248, alignment: .leading)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(WGColor.hairline, lineWidth: 1))
        .shadow(color: WGColor.shadowMd, radius: 14, y: 8)
    }

    // MARK: - 체크박스 행

    @ViewBuilder
    private func checkboxRow(
        label: String,
        desc: String? = nil,
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
                VStack(alignment: .leading, spacing: 1) {
                    Text(label)
                        .font(WGFont.sans(13))
                        .fontWeight(emphasize ? .bold : .semibold)
                        .foregroundStyle(WGColor.ink)
                    // 구 범례의 마커 의미 설명 병기(통합).
                    if let desc {
                        Text(desc)
                            .font(WGFont.sans(10.5))
                            .foregroundStyle(WGColor.inkSoft)
                    }
                }
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
