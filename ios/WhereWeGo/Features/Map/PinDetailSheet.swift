import SwiftUI

// 핀 상세(정보창) 시트(설계 §3·§6, FR-8~12/16~19, AC-8/9/17).
// frontend/src/app/map/MapClient.tsx 정보창 + MemoTagPanelContent.tsx 의 표시/편집/삭제 흐름 이식.
//
// 표시: 장소명·주소(있을 때)·메모(있을 때)·Instagram 바로가기(https 가드 AC-17)·태그 배지·등록자.
// 편집: 태그 변경(REEL/WISH/MEMORY 세그먼트, 낙관 PATCH), 메모(≤500), 장소명(≤200 빈값 불가, Should).
// 삭제: confirmationDialog → 낙관 DELETE → 시트 닫기.
// 사진: MEMORY 태그일 때만 영역 노출(AC-9). 썸네일/추가·변경/삭제 + 업로드 로딩(QE-3).
//
// 태그/메모/장소명/삭제는 MapViewModel 의 낙관적 메서드에 위임(pins 단일 출처).
// 사진은 PinDetailViewModel 이 pinAPI 직접 호출 후 MapViewModel.replacePin 으로 반영.
struct PinDetailSheet: View {
    let pin: PinSummary
    @ObservedObject var mapViewModel: MapViewModel
    @StateObject private var detailVM: PinDetailViewModel

    @Environment(\.dismiss) private var dismiss

    // 편집 입력 버퍼.
    @State private var memoText: String
    @State private var placeNameText: String
    @State private var isEditingMemo = false
    @State private var isEditingPlaceName = false

    // 작업 진행/에러.
    @State private var isMutating = false
    @State private var inlineError: String?

    // 다이얼로그/시트 상태.
    @State private var showDeleteConfirm = false
    @State private var showPhotoDeleteConfirm = false
    @State private var showPhotoPicker = false
    /// 피커에서 고른 이미지(크롭 단계로 넘김). 설정되면 SquareCropView 표시.
    @State private var pickedImage: PickedImage?

    private let memoLimit = 500
    private let placeNameLimit = 200

    init(pin: PinSummary, mapViewModel: MapViewModel) {
        self.pin = pin
        self.mapViewModel = mapViewModel
        _detailVM = StateObject(wrappedValue: PinDetailViewModel(pinAPI: mapViewModel.pinAPI, mapViewModel: mapViewModel))
        _memoText = State(initialValue: pin.memo ?? "")
        _placeNameText = State(initialValue: pin.placeName)
    }

    /// pins 단일 출처에서 최신 핀을 읽는다(낙관/사진 갱신 즉시 반영). 삭제되면 시트 닫힘 트리거.
    private var currentPin: PinSummary? {
        mapViewModel.pins.first { $0.id == pin.id }
    }

    var body: some View {
        let live = currentPin ?? pin
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header(live)
                    if let address = live.address, !address.isEmpty {
                        addressRow(address)
                    }
                    Divider().overlay(WGColor.hairline)
                    tagSection(live)
                    placeNameSection(live)
                    memoSection(live)
                    if PinDetailViewModel.shouldShowPhotoSection(tag: live.tag) {
                        photoSection(live)
                    }
                    if let url = instagramLink(live) {
                        instagramRow(url)
                    }
                    if let error = inlineError ?? detailVM.photoError {
                        errorBanner(error)
                    }
                    deleteButton
                }
                .padding(20)
            }
            .background(WGColor.bg)
            .navigationTitle("핀 상세")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기") { dismiss() }
                        .foregroundStyle(WGColor.cta)
                }
            }
        }
        // 사진 업로드/삭제 중 스와이프 닫힘 차단(VisitMemoSheet 일관성, QE-3 silent 실패 방지).
        // currentPin == nil 프로그래매틱 dismiss(타 사용자 삭제)는 스와이프와 무관하게 유지.
        .interactiveDismissDisabled(detailVM.isPhotoBusy)
        .confirmationDialog("이 핀을 삭제할까요?", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button("삭제", role: .destructive) { Task { await deletePin() } }
            Button("취소", role: .cancel) {}
        } message: {
            Text("삭제하면 되돌릴 수 없어요.")
        }
        .confirmationDialog("사진을 삭제할까요?", isPresented: $showPhotoDeleteConfirm, titleVisibility: .visible) {
            Button("삭제", role: .destructive) { Task { await detailVM.deletePhoto(pinId: pin.id) } }
            Button("취소", role: .cancel) {}
        }
        .sheet(isPresented: $showPhotoPicker) {
            PhotoPickerView(
                onPicked: { pickedImage = PickedImage(image: $0) },
                onDismiss: { showPhotoPicker = false }
            )
            .ignoresSafeArea()
        }
        .fullScreenCover(item: $pickedImage) { picked in
            SquareCropView(
                image: picked.image,
                onCropped: { cropped in
                    pickedImage = nil
                    Task { await detailVM.uploadPhoto(pinId: pin.id, image: cropped) }
                },
                onCancel: { pickedImage = nil }
            )
        }
        // 다른 사용자/낙관 삭제로 pins 에서 사라지면 시트 닫기.
        .onChange(of: currentPin == nil) { _, gone in
            if gone { dismiss() }
        }
    }

    // MARK: - 헤더(장소명 + 태그 배지 + 등록자)

    private func header(_ live: PinSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(live.placeName)
                    .font(WGFont.serif(22))
                    .foregroundStyle(WGColor.ink)
                Spacer(minLength: 0)
                tagBadge(live.tag)
            }
            if let nickname = live.createdByNickname, !nickname.isEmpty {
                Text("\(nickname) 님이 추가")
                    .font(WGFont.sans(12))
                    .foregroundStyle(WGColor.inkSoft)
            }
        }
    }

    private func addressRow(_ address: String) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: "mappin.and.ellipse")
                .font(.system(size: 12))
                .foregroundStyle(WGColor.inkSoft)
            Text(address)
                .font(WGFont.sans(13))
                .foregroundStyle(WGColor.inkSoft)
        }
    }

    // MARK: - 태그 변경(낙관 PATCH, AC-6)

    private func tagSection(_ live: PinSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionLabel("태그")
            HStack(spacing: 8) {
                ForEach(PinTag.allCases, id: \.self) { tag in
                    tagOption(tag, isOn: live.tag == tag)
                }
            }
        }
    }

    private func tagOption(_ tag: PinTag, isOn: Bool) -> some View {
        Button {
            guard !isOn, !isMutating else { return }
            Task { await changeTag(tag) }
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
        .disabled(isMutating)
    }

    // MARK: - 장소명 편집(≤200 빈값 불가, Should)

    private func placeNameSection(_ live: PinSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                sectionLabel("장소 이름")
                Spacer()
                editToggle(isEditing: isEditingPlaceName) {
                    if isEditingPlaceName {
                        Task { await savePlaceName() }
                    } else {
                        placeNameText = live.placeName
                        isEditingPlaceName = true
                    }
                }
            }
            if isEditingPlaceName {
                TextField("장소 이름", text: $placeNameText)
                    .font(WGFont.sans(15))
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: placeNameText) { _, value in
                        if value.count > placeNameLimit {
                            placeNameText = String(value.prefix(placeNameLimit))
                        }
                    }
            } else {
                Text(live.placeName)
                    .font(WGFont.sans(15))
                    .foregroundStyle(WGColor.ink)
            }
        }
    }

    // MARK: - 메모 편집(≤500)

    private func memoSection(_ live: PinSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                sectionLabel("메모")
                Spacer()
                editToggle(isEditing: isEditingMemo) {
                    if isEditingMemo {
                        Task { await saveMemo() }
                    } else {
                        memoText = live.memo ?? ""
                        isEditingMemo = true
                    }
                }
            }
            if isEditingMemo {
                TextEditor(text: $memoText)
                    .font(WGFont.sans(14))
                    .frame(minHeight: 90)
                    .padding(6)
                    .background(WGColor.panel)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(WGColor.hairline, lineWidth: 1))
                    .onChange(of: memoText) { _, value in
                        if value.count > memoLimit {
                            memoText = String(value.prefix(memoLimit))
                        }
                    }
                Text("\(memoText.count)/\(memoLimit)")
                    .font(WGFont.sans(11))
                    .foregroundStyle(WGColor.inkFaint)
            } else if let memo = live.memo, !memo.isEmpty {
                Text(memo)
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.ink)
            } else {
                Text("메모가 없어요")
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkFaint)
            }
        }
    }

    // MARK: - 사진 영역(MEMORY 전용, AC-9)

    private func photoSection(_ live: PinSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionLabel("사진")
            ZStack {
                if let urlString = live.photoThumbnailUrl ?? live.photoUrl, let url = URL(string: urlString) {
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .success(let image):
                            image.resizable().scaledToFill()
                        case .failure:
                            placeholderTile(systemName: "photo")
                        default:
                            ProgressView().tint(WGColor.cta)
                        }
                    }
                    .frame(width: 140, height: 140)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                } else {
                    placeholderTile(systemName: "photo.badge.plus")
                        .frame(width: 140, height: 140)
                }
                if detailVM.isPhotoBusy {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color.black.opacity(0.35))
                        .frame(width: 140, height: 140)
                    ProgressView().tint(.white)
                }
            }
            HStack(spacing: 10) {
                Button {
                    detailVM.photoError = nil
                    showPhotoPicker = true
                } label: {
                    Text(live.photoUrl == nil ? "사진 추가" : "사진 변경")
                        .font(WGFont.sans(13))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 9)
                        .background(WGColor.cta)
                        .foregroundStyle(WGColor.panel)
                        .clipShape(Capsule())
                }
                .disabled(detailVM.isPhotoBusy)

                if live.photoUrl != nil {
                    Button {
                        showPhotoDeleteConfirm = true
                    } label: {
                        Text("삭제")
                            .font(WGFont.sans(13))
                            .padding(.horizontal, 16)
                            .padding(.vertical, 9)
                            .foregroundStyle(WGColor.pinNew)
                            .overlay(Capsule().stroke(WGColor.pinNew, lineWidth: 1))
                    }
                    .disabled(detailVM.isPhotoBusy)
                }
            }
        }
    }

    private func placeholderTile(systemName: String) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12).fill(WGColor.mapBlock)
            Image(systemName: systemName)
                .font(.system(size: 28))
                .foregroundStyle(WGColor.inkFaint)
        }
        .frame(width: 140, height: 140)
    }

    // MARK: - Instagram 바로가기(https 가드, AC-17)

    private func instagramRow(_ url: URL) -> some View {
        Link(destination: url) {
            HStack(spacing: 6) {
                Image(systemName: "link")
                Text("릴스 보러가기")
                    .font(WGFont.sans(14))
            }
            .foregroundStyle(WGColor.cta)
        }
    }

    // MARK: - 삭제 버튼

    private var deleteButton: some View {
        Button(role: .destructive) {
            showDeleteConfirm = true
        } label: {
            Text("핀 삭제")
                .font(WGFont.sans(14))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .foregroundStyle(WGColor.pinNew)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.pinNew, lineWidth: 1))
        }
        .disabled(isMutating)
        .padding(.top, 4)
    }

    // MARK: - 공통 작은 뷰

    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(WGFont.sans(12))
            .foregroundStyle(WGColor.inkSoft)
    }

    private func tagBadge(_ tag: PinTag) -> some View {
        HStack(spacing: 5) {
            Circle().fill(tagColor(tag)).frame(width: 7, height: 7)
            Text(tagLabel(tag))
                .font(WGFont.sans(12))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(tagColor(tag).opacity(0.16))
        .foregroundStyle(WGColor.ink)
        .clipShape(Capsule())
    }

    private func editToggle(isEditing: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(isEditing ? "저장" : "편집")
                .font(WGFont.sans(12))
                .foregroundStyle(WGColor.cta)
        }
        .disabled(isMutating)
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

    // MARK: - 액션(MapViewModel 낙관 메서드 위임)

    private func instagramLink(_ live: PinSummary) -> URL? {
        guard PinDetailViewModel.shouldShowInstagramLink(live.instagramUrl),
              let urlString = live.instagramUrl else { return nil }
        return URL(string: urlString)
    }

    private func changeTag(_ tag: PinTag) async {
        inlineError = nil
        isMutating = true
        defer { isMutating = false }
        do {
            try await mapViewModel.applyTagOptimistic(pinId: pin.id, tag: tag)
        } catch {
            inlineError = (error as? LocalizedError)?.errorDescription ?? "태그를 변경하지 못했어요."
        }
    }

    private func saveMemo() async {
        inlineError = nil
        isMutating = true
        defer { isMutating = false }
        do {
            try await mapViewModel.updateMemoOptimistic(pinId: pin.id, memo: memoText)
            isEditingMemo = false
        } catch {
            inlineError = (error as? LocalizedError)?.errorDescription ?? "메모를 저장하지 못했어요."
        }
    }

    private func savePlaceName() async {
        let trimmed = placeNameText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            inlineError = "장소 이름을 입력해 주세요."
            return
        }
        inlineError = nil
        isMutating = true
        defer { isMutating = false }
        do {
            try await mapViewModel.updatePlaceNameOptimistic(pinId: pin.id, placeName: trimmed)
            isEditingPlaceName = false
        } catch {
            inlineError = (error as? LocalizedError)?.errorDescription ?? "장소 이름을 저장하지 못했어요."
        }
    }

    private func deletePin() async {
        inlineError = nil
        isMutating = true
        defer { isMutating = false }
        do {
            try await mapViewModel.deletePinOptimistic(pinId: pin.id)
            dismiss()
        } catch {
            inlineError = (error as? LocalizedError)?.errorDescription ?? "핀을 삭제하지 못했어요."
        }
    }

    // MARK: - 태그 표현(TagFilterBar 와 동일)

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

// fullScreenCover(item:) 바인딩용 선택 이미지 래퍼(UIImage 는 Identifiable 이 아니므로 래핑).
private struct PickedImage: Identifiable {
    let id = UUID()
    let image: UIImage
}
