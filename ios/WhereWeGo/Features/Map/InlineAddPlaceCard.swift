import SwiftUI

// 인라인 핀 추가 하단 카드(웹 SearchPanelContent + AddPinPickerContent + MemoTagPanelContent 1:1 이식).
// 2단계 흐름:
//  - locate(위치 확정): 검색 모드=검색바+결과 목록 / 콕찍기 모드=주소 표시 + [취소]/[완료].
//  - form(메모·태그): 주소·장소명·태그(추억/위시/발견)·메모·사진(MEMORY)·릴스 링크 입력 후 [취소]/[저장].
//
// VM(AddPlaceViewModel) 은 MapViewModel 이 소유하고 여기선 @ObservedObject 로 관찰만 한다.
// 검색 결과 선택·취소는 카드가 직접 카메라/모드를 만지지 않고 콜백으로 MapView 에 위임한다(B2 계약).
struct InlineAddPlaceCard: View {
    @ObservedObject var viewModel: AddPlaceViewModel
    /// 검색 결과 선택 → MapView 에서 selectResult + 메인 cameraCommand flyTo(AC-9).
    let onSelectResult: (PlaceItem) -> Void
    /// 취소(전체 종료) → MapView 에서 MapViewModel.exitAddPin().
    let onCancel: () -> Void

    /// 사진 선택 시퀀스(PHPicker → 1:1 크롭) 표시 상태.
    @State private var isPickerPresented = false
    /// PHPicker 가 넘긴 원본 이미지(크롭 화면 입력). nil 이면 크롭 화면 닫힘.
    @State private var photoToCrop: UIImage?

    var body: some View {
        VStack(spacing: 0) {
            switch viewModel.step {
            case .locate:
                locateCard
            case .form:
                formCard
            }
        }
        // 사진 1:1 크롭 화면(MEMORY 폼 — 기존 SquareCropView 재사용). 핀치 줌 + pan + aspect 1:1.
        .fullScreenCover(isPresented: cropBinding) {
            if let image = photoToCrop {
                SquareCropView(
                    image: image,
                    onCropped: { cropped in
                        viewModel.pendingPhoto = cropped
                        photoToCrop = nil
                    },
                    onCancel: { photoToCrop = nil }
                )
            }
        }
        // PHPicker(이미지 1장) 시트 — 선택 결과를 크롭 화면으로 넘긴다.
        .sheet(isPresented: $isPickerPresented) {
            PhotoPickerView(
                onPicked: { image in photoToCrop = image },
                onDismiss: { isPickerPresented = false }
            )
        }
    }

    /// 크롭 화면 표시 바인딩(photoToCrop 존재 여부에 종속).
    private var cropBinding: Binding<Bool> {
        Binding(
            get: { photoToCrop != nil },
            set: { if !$0 { photoToCrop = nil } }
        )
    }

    // MARK: - ① 위치 확정 단계(locate)

    @ViewBuilder
    private var locateCard: some View {
        if viewModel.inputMode == .search {
            searchCard
        } else {
            pinpointCard
        }
    }

    // MARK: - 검색 흐름(웹 SearchPanelContent)

    private var searchCard: some View {
        VStack(spacing: 0) {
            // 인라인 검색은 드래그 시트가 아니라 인라인 글래스 카드 — 시트용 드래그 핸들 없이 우상단 ✕ 로만 닫는다(웹 Sheet 닫기 동치).
            HStack {
                Spacer()
                Button(action: onCancel) {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(WGColor.inkSoft)
                        .frame(width: 28, height: 28)
                }
                .accessibilityLabel("닫기")
            }
            searchBar
            searchBody
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard(cornerRadius: 18)
        .padding(.horizontal, 12)
        .padding(.top, 10)
    }

    /// 검색바(placeholder "장소 검색", 우측 28pt 돋보기 버튼). 입력 중 호출 X — Enter/돋보기 탭 시에만 검색.
    private var searchBar: some View {
        HStack(spacing: 8) {
            TextField("장소 검색", text: $viewModel.query)
                .font(WGFont.sans(15))
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit { Task { await viewModel.search() } }
            Button {
                Task { await viewModel.search() }
            } label: {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 18, weight: .semibold))
                    // 키워드 있을 때 cta, 없으면 inkFaint(웹 정합).
                    .foregroundStyle(hasKeyword ? WGColor.cta : WGColor.inkFaint)
                    .frame(width: 28, height: 28)
            }
            .disabled(!hasKeyword || viewModel.isSearching)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(WGColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
        .padding(.top, 6)
    }

    private var hasKeyword: Bool {
        !viewModel.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// 검색 상태문구 + 결과 목록(웹 정합 — 검색 중/무결과/에러/결과).
    @ViewBuilder
    private var searchBody: some View {
        if viewModel.isSearching {
            statusText("검색 중...", color: WGColor.inkSoft)
        } else if let error = viewModel.errorMessage {
            statusText(error, color: WGColor.cta)
        } else if viewModel.didSearch, viewModel.results.isEmpty {
            statusText("검색 결과가 없어요", color: WGColor.inkSoft)
        } else if !viewModel.results.isEmpty {
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(viewModel.results) { place in
                        Button {
                            select(place)
                        } label: {
                            resultRow(place)
                        }
                    }
                }
            }
            .frame(maxHeight: 260)
            .padding(.top, 4)
        }
    }

    private func statusText(_ text: String, color: Color) -> some View {
        Text(text)
            .font(WGFont.sans(13))
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 12)
    }

    /// 결과 행: 전체폭, 세로패딩 11pt, 하단 hairline. 📍 + 장소명(14/600/ink) / 주소(12/inkSoft/mono).
    private func resultRow(_ place: PlaceItem) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text("📍")
                .font(WGFont.sans(14))
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 2) {
                Text(place.placeName)
                    .font(WGFont.sans(14))
                    .fontWeight(.semibold)
                    .foregroundStyle(WGColor.ink)
                    .multilineTextAlignment(.leading)
                if let address = place.address, !address.isEmpty {
                    Text(address)
                        .font(WGFont.mono(12))
                        .foregroundStyle(WGColor.inkSoft)
                        .multilineTextAlignment(.leading)
                }
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
        .padding(.vertical, 11)
        .overlay(alignment: .bottom) {
            Rectangle().fill(WGColor.hairline).frame(height: 1)
        }
    }

    /// 검색 결과 선택 → 카메라/VM 갱신은 MapView 에 위임(B2 계약). 키보드만 내린다.
    private func select(_ place: PlaceItem) {
        onSelectResult(place)
        hideKeyboard()
    }

    // MARK: - 콕찍기 흐름(웹 AddPinPickerContent)

    /// 콕찍기 카드: 📍 주소(mono, 역지오 진행 시 "주소를 찾는 중...", 실패 시 좌표) + [취소]/[완료] 동일폭.
    private var pinpointCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("📍 \(pinpointAddressLine)")
                .font(WGFont.mono(13))
                .foregroundStyle(WGColor.inkSoft)
                .frame(maxWidth: .infinity, minHeight: 20, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 8) {
                secondaryButton("취소", action: onCancel)
                primaryButton("완료", enabled: viewModel.canProceed) {
                    viewModel.proceedToForm()
                }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard(cornerRadius: 18)
        .padding(.horizontal, 12)
        .padding(.top, 10)
    }

    /// 콕찍기 주소 표시 라인(로딩/주소/좌표 폴백). 웹 AddPinPickerContent.displayLine 정합.
    private var pinpointAddressLine: String {
        if viewModel.isResolvingAddress { return "주소를 찾는 중..." }
        if let address = viewModel.resolvedAddress, !address.isEmpty { return address }
        return "지도를 이동해 위치를 선택해주세요"
    }

    // MARK: - ② 메모·태그 폼 단계(form, 웹 MemoTagPanelContent)

    private var formCard: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // 1. 주소(읽기 전용).
                fieldLabel("주소")
                Text("📍 \(formAddressLine)")
                    .font(viewModel.inputMode == .search ? WGFont.mono(13) : WGFont.sans(13))
                    .foregroundStyle(WGColor.inkSoft)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 14)
                Rectangle().fill(WGColor.hairline).frame(height: 1)
                    .padding(.bottom, 14)

                // 2. 장소 이름.
                fieldLabel("장소 이름")
                formTextField("예: 우리집", text: $viewModel.formPlaceName)
                    .padding(.bottom, 16)

                // 3. 태그(추억 → 위시 → 발견 순서).
                fieldLabel("태그")
                HStack(spacing: 8) {
                    tagChip(.MEMORY)
                    tagChip(.WISH)
                    tagChip(.REEL)
                }
                .padding(.bottom, 16)

                // 4. 메모(선택).
                fieldLabel("메모 (선택)")
                memoEditor
                    .padding(.bottom, 16)

                // 5. 사진(선택, MEMORY 일 때만).
                if viewModel.selectedTag == .MEMORY {
                    fieldLabel("사진 (선택)")
                    photoUploader
                        .padding(.bottom, 16)
                }

                // 6. 릴스 링크(선택).
                fieldLabel("릴스 링크 (선택)")
                formTextField("https://instagram.com/...", text: $viewModel.instagramUrl)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .padding(.bottom, viewModel.urlError == nil ? 16 : 6)
                if let urlError = viewModel.urlError {
                    Text(urlError)
                        .font(WGFont.sans(12))
                        .foregroundStyle(WGColor.pinNew)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.bottom, 16)
                }

                // 7. 에러박스 + [취소]/[저장].
                if let error = viewModel.errorMessage {
                    errorBox(error)
                        .padding(.bottom, 12)
                }
                if viewModel.isCreating {
                    HStack(spacing: 8) {
                        ProgressView().tint(WGColor.cta)
                        Text("저장 중...")
                            .font(WGFont.sans(13))
                            .foregroundStyle(WGColor.inkSoft)
                    }
                    .padding(.bottom, 12)
                }
                HStack(spacing: 8) {
                    secondaryButton("취소", enabled: !viewModel.isCreating) {
                        viewModel.backToLocate()
                    }
                    primaryButton(viewModel.isCreating ? "저장 중..." : "저장", enabled: viewModel.canSubmit) {
                        hideKeyboard()
                        viewModel.submit()
                    }
                }
            }
            .padding(20)
        }
        .frame(maxHeight: 520)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard(cornerRadius: 18)
        .padding(.horizontal, 12)
        .padding(.top, 10)
    }

    /// 폼 주소 표시 라인. 검색=장소명·주소 결합, 콕찍기=역지오 주소(없으면 좌표).
    private var formAddressLine: String {
        switch viewModel.inputMode {
        case .search:
            guard let place = viewModel.selectedPlace else { return "" }
            if let address = place.address, !address.isEmpty {
                return "\(place.placeName) · \(address)"
            }
            return place.placeName
        case .pinpoint:
            if let address = viewModel.resolvedAddress, !address.isEmpty { return address }
            return viewModel.confirmTitle ?? ""
        }
    }

    // MARK: - 태그 칩(웹 ui/PinTag 정합)

    /// 태그 칩: cornerRadius 999, padding 7x16, 1.5pt 태그색 보더, 13/600.
    /// active=태그색 채움+흰 글자+흰 글리프, inactive=태그색 8% 배경+태그색 글자.
    private func tagChip(_ tag: PinTag) -> some View {
        let active = viewModel.selectedTag == tag
        let color = tagColor(tag)
        return Button {
            viewModel.selectedTag = tag
        } label: {
            HStack(spacing: 6) {
                Image(systemName: tagGlyph(tag))
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(active ? Color.white : color)
                Text(tagLabel(tag))
                    .font(WGFont.sans(13))
                    .fontWeight(.semibold)
                    .foregroundStyle(active ? Color.white : color)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 7)
            .background(active ? color : color.opacity(0.08))
            .overlay(
                RoundedRectangle(cornerRadius: 999).stroke(color, lineWidth: 1.5)
            )
            .clipShape(RoundedRectangle(cornerRadius: 999))
        }
        .disabled(viewModel.isCreating)
    }

    // MARK: - 메모 에디터(웹 textarea)

    /// 메모 TextEditor: maxLength 500, minHeight 72, hairline 보더, 라운드 10, 배경 bg.
    private var memoEditor: some View {
        ZStack(alignment: .topLeading) {
            if viewModel.memo.isEmpty {
                Text("메모를 입력해 보세요...")
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.inkSoft)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .allowsHitTesting(false)
            }
            TextEditor(text: $viewModel.memo)
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.ink)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 72)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .onChange(of: viewModel.memo) { _, newValue in
                    if newValue.count > 500 {
                        viewModel.memo = String(newValue.prefix(500))
                    }
                }
        }
        .background(WGColor.bg)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(WGColor.hairline, lineWidth: 1))
    }

    // MARK: - 사진 업로더(웹 PinPhotoUploader)

    /// 빈 상태=점선 "사진 추가" 버튼 / 선택 후=미리보기(maxHeight 200) + 우상단 삭제 버튼.
    @ViewBuilder
    private var photoUploader: some View {
        if let photo = viewModel.pendingPhoto {
            ZStack(alignment: .topTrailing) {
                Image(uiImage: photo)
                    .resizable()
                    .scaledToFill()
                    .frame(maxWidth: .infinity, maxHeight: 200)
                    .frame(height: 200)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))

                Button {
                    viewModel.pendingPhoto = nil
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(WGColor.pinNew)
                        .frame(width: 30, height: 30)
                        .background(Circle().fill(WGColor.panel))
                        .shadow(color: WGColor.shadowMd, radius: 4, y: 2)
                }
                .padding(8)
                .accessibilityLabel("사진 삭제")
            }
        } else {
            Button {
                isPickerPresented = true
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "plus")
                        .font(.system(size: 18, weight: .semibold))
                    Text("사진 추가")
                        .font(WGFont.sans(13))
                        .fontWeight(.semibold)
                }
                .foregroundStyle(WGColor.inkSoft)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(WGColor.bg)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(
                            WGColor.hairline,
                            style: StrokeStyle(lineWidth: 1.5, dash: [5, 4])
                        )
                )
            }
            .disabled(viewModel.isCreating)
        }
    }

    // MARK: - 공통 작은 뷰

    private func fieldLabel(_ text: String) -> some View {
        Text(text)
            .font(WGFont.sans(12))
            .fontWeight(.semibold)
            .foregroundStyle(WGColor.inkSoft)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, 6)
    }

    private func formTextField(_ placeholder: String, text: Binding<String>) -> some View {
        TextField(placeholder, text: text)
            .font(WGFont.sans(14))
            .foregroundStyle(WGColor.ink)
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .background(WGColor.panel)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(WGColor.hairline, lineWidth: 1))
    }

    private func errorBox(_ message: String) -> some View {
        Text(message)
            .font(WGFont.sans(13))
            .foregroundStyle(WGColor.pinNew)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(WGColor.pinNew.opacity(0.15))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func primaryButton(_ title: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(WGFont.sans(14))
                .fontWeight(.semibold)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(enabled ? WGColor.cta : WGColor.cta.opacity(0.4))
                .foregroundStyle(WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .disabled(!enabled)
    }

    private func secondaryButton(_ title: String, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(WGFont.sans(14))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(WGColor.bg)
                .foregroundStyle(WGColor.ink)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .disabled(!enabled)
    }

    // MARK: - 태그 메타(색/라벨/글리프)

    private func tagColor(_ tag: PinTag) -> Color {
        switch tag {
        case .REEL: return WGColor.pinReel
        case .WISH: return WGColor.pinWish
        case .MEMORY: return WGColor.pinMemory
        }
    }

    private func tagLabel(_ tag: PinTag) -> String {
        switch tag {
        case .REEL: return "발견"
        case .WISH: return "위시"
        case .MEMORY: return "추억"
        }
    }

    /// 태그 글리프(웹 ReelGlyph/WishGlyph/MemoryGlyph 대응 SF Symbol).
    private func tagGlyph(_ tag: PinTag) -> String {
        switch tag {
        case .REEL: return "play.circle.fill"
        case .WISH: return "star.fill"
        case .MEMORY: return "heart.fill"
        }
    }

    private func hideKeyboard() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil, from: nil, for: nil
        )
    }
}
