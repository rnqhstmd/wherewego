import SwiftUI

// 크로스헤어 임의 좌표 핀 추가 시트(설계 §3 CrosshairAddView, FR-15 Should).
// frontend/src/app/map/_components/CrosshairOverlay.tsx + MapClient handleConfirmCoordinateEdit(:781~782) 이식.
//
// 흐름:
//  1) 지도 중앙 크로스헤어 안내 + 현재 중심 좌표(mapCenter) 표시.
//  2) 장소명 입력 + 태그 선택(REEL/WISH/MEMORY).
//  3) "여기에 추가" → MapViewModel.addPinAtCenter(7자리 반올림 → create → appendPin + flyTo).
//
// 중심 좌표(mapCenter)는 Mapbox cameraIdle(token 후)에서만 들어온다.
// 플레이스홀더(token 미설정)에서는 mapCenter 가 nil 이므로 추가 버튼을 비활성화하고 안내한다.
struct CrosshairAddView: View {
    @ObservedObject var mapViewModel: MapViewModel

    @Environment(\.dismiss) private var dismiss

    @State private var placeNameText: String = ""
    @State private var selectedTag: PinTag = .WISH
    @State private var isCreating = false
    @State private var errorMessage: String?

    private let placeNameLimit = 200

    /// 중심 좌표 확보 여부(플레이스홀더면 false → 추가 비활성).
    private var hasCenter: Bool { mapViewModel.mapCenter != nil }

    private var canSubmit: Bool {
        hasCenter
            && !placeNameText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !isCreating
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    centerSection
                    placeNameSection
                    tagSection
                    if let errorMessage {
                        errorBanner(errorMessage)
                    }
                    if isCreating {
                        creatingRow
                    }
                    submitButton
                }
                .padding(20)
            }
            .background(WGColor.bg)
            .navigationTitle("이 위치에 핀 추가")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기") { dismiss() }
                        .foregroundStyle(WGColor.cta)
                }
            }
        }
        .onChange(of: mapViewModel.activeSheet) { _, sheet in
            // 생성 성공 등으로 activeSheet 가 .crosshair 가 아니게 되면 닫는다.
            if sheet != .crosshair { dismiss() }
        }
    }

    // MARK: - 중심 좌표 안내(크로스헤어)

    private var centerSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "plus.viewfinder")
                    .font(.system(size: 18))
                    .foregroundStyle(WGColor.cta)
                Text("지도 중앙 위치")
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkSoft)
            }
            if let center = mapViewModel.mapCenter {
                Text(coordinateLabel(center))
                    .font(WGFont.mono(13))
                    .foregroundStyle(WGColor.ink)
            } else {
                Text("지도를 움직여 추가할 위치를 정해 주세요.")
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkFaint)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
    }

    // MARK: - 장소명 입력(≤200)

    private var placeNameSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionLabel("장소 이름")
            TextField("장소 이름을 입력해 주세요", text: $placeNameText)
                .font(WGFont.sans(15))
                .textFieldStyle(.roundedBorder)
                .onChange(of: placeNameText) { _, value in
                    if value.count > placeNameLimit {
                        placeNameText = String(value.prefix(placeNameLimit))
                    }
                }
        }
    }

    // MARK: - 태그 선택

    private var tagSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionLabel("태그")
            HStack(spacing: 8) {
                ForEach(PinTag.allCases, id: \.self) { tag in
                    tagOption(tag, isOn: selectedTag == tag)
                }
            }
        }
    }

    private func tagOption(_ tag: PinTag, isOn: Bool) -> some View {
        Button {
            selectedTag = tag
        } label: {
            HStack(spacing: 6) {
                Circle().fill(tagColor(tag)).frame(width: 8, height: 8)
                Text(tagLabel(tag))
                    .font(WGFont.sans(13))
                    .foregroundStyle(isOn ? WGColor.ink : WGColor.inkSoft)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(isOn ? WGColor.panel : WGColor.bg)
            .overlay(Capsule().stroke(isOn ? tagColor(tag) : WGColor.hairline, lineWidth: 1))
            .clipShape(Capsule())
        }
        .disabled(isCreating)
    }

    // MARK: - 추가 버튼

    private var submitButton: some View {
        Button {
            Task { await submit() }
        } label: {
            Text("여기에 핀 추가")
                .font(WGFont.sans(14))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(canSubmit ? WGColor.cta : WGColor.cta.opacity(0.4))
                .foregroundStyle(WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .disabled(!canSubmit)
        .padding(.top, 4)
    }

    private var creatingRow: some View {
        HStack(spacing: 8) {
            ProgressView().tint(WGColor.cta)
            Text("추가하는 중...")
                .font(WGFont.sans(13))
                .foregroundStyle(WGColor.inkSoft)
        }
    }

    // MARK: - 액션

    private func submit() async {
        let trimmed = placeNameText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            errorMessage = "장소 이름을 입력해 주세요."
            return
        }
        errorMessage = nil
        isCreating = true
        defer { isCreating = false }
        do {
            try await mapViewModel.addPinAtCenter(placeName: trimmed, tag: selectedTag)
            mapViewModel.activeSheet = .none
        } catch let error as APIError {
            errorMessage = error.message
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "핀을 추가하지 못했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    // MARK: - 공통 작은 뷰

    private func coordinateLabel(_ center: Coordinate) -> String {
        let lat = MapViewModel.roundCoordinate(center.latitude)
        let lng = MapViewModel.roundCoordinate(center.longitude)
        return String(format: "%.7f, %.7f", lat, lng)
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(WGFont.sans(12))
            .foregroundStyle(WGColor.inkSoft)
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

    private func tagColor(_ tag: PinTag) -> Color {
        switch tag {
        case .REEL: return WGColor.pinReel
        case .WISH: return WGColor.pinWish
        case .MEMORY: return WGColor.pinMemory
        }
    }

    private func tagLabel(_ tag: PinTag) -> String {
        switch tag {
        case .REEL: return "릴스"
        case .WISH: return "위시"
        case .MEMORY: return "추억"
        }
    }
}
