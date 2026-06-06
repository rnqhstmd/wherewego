import SwiftUI

// 핀 상세 공통 콘텐츠(설계 §6, D-5). 풀 모달 시트(PinDetailSheet)에서 콘텐츠만 분리·이관한 뷰.
// NavigationStack/ScrollView 풀모달 래퍼·툴바·dismiss 는 제거하고, 말풍선(PinBubbleView)이 래핑·앵커·닫기를 담당한다.
// frontend/src/app/map/_components/PinPopup.tsx 의 표시/수정 흐름 이식.
//
// 보기(view): 장소명·주소(있을 때)·메모(있을 때)·Instagram 바로가기(https 가드 AC-17)·태그 글리프·등록자·사진 펼침.
// 수정(edit): 웹 PinPopup 의 edit 모드와 동일한 탭 위저드(장소 → 태그 → 메모). 세로 섹션 나열 방식은 폐기.
//  - 장소 탭: 장소명 입력(≤100) + "다음 →"(빈값 막기).
//  - 태그 탭: MEMORY/WISH/REEL 칩 + "← 이전"/"다음 →"(사진 있는 추억핀은 위시/발견 비활성 + 안내).
//  - 메모 탭: 메모 입력(≤500) + (MEMORY) 사진 추가/변경/삭제 + "취소"/"저장".
//  - 저장 시 변경된 장소/태그/메모를 한 번에 커밋(웹 handleSaveAll 동치 — MapViewModel 낙관 메서드 재사용).
//  - 수정 모드에선 본문(보기 내용)을 숨기고 위저드만 노출(웹 collapseBody 동치).
// 삭제: ⋮ → "삭제" → confirmationDialog → 낙관 DELETE → onRequestClose().
//
// 사진(MEMORY 전용, AC-9): detailVM(PinDetailViewModel)이 직접 업로드/삭제 후 replacePin 으로 반영(웹과 달리 즉시 커밋).
//  장소/태그/메모만 위저드 저장에서 일괄 커밋한다.
struct PinDetailContent: View {
    let pin: PinSummary
    @ObservedObject var mapViewModel: MapViewModel
    /// 사진 상태(업로드/삭제) 소유 VM. PinBubbleView 가 @StateObject 로 보유하고 주입한다(말풍선 닫힘 가드와 공유).
    @ObservedObject var detailVM: PinDetailViewModel
    /// 닫기 요청(삭제 완료/다른 사용자 삭제). PinBubbleView 가 selectedPinId·화면좌표 해제로 연결.
    var onRequestClose: () -> Void
    /// 공유 카드 모달 요청(보기 모드 공유 아이콘 탭). 모달은 말풍선 ScrollView clip 밖(전체화면 중앙)에서
    /// 떠야 하므로 PinBubbleView 가 ZStack 최상위에 띄운다(여기선 콜백만 발사).
    var onRequestShare: () -> Void

    // 표시 모드(C-b): 웹 PinPopup 처럼 보기(view)가 기본이고, ⋮ 메뉴의 "수정"으로만 편집(edit) 진입.
    private enum Mode { case view, edit }
    @State private var mode: Mode = .view

    // 수정 위저드 탭(웹 EditTab 동치). 진입 시 항상 장소 탭부터.
    private enum EditTab { case place, tag, memo }
    @State private var editTab: EditTab = .place

    // 위저드 draft 버퍼. 장소/태그는 draft 로만 바꾸고, 메모 탭의 "저장"이 변경분을 한 번에 커밋한다(웹 handleSaveAll 동치).
    @State private var placeDraft: String
    @State private var tagDraft: PinTag
    @State private var memoDraft: String
    /// 장소 탭 "다음" 시 빈 값 검증용 인라인 에러(웹 placeError 동치).
    @State private var placeError: String?

    // 저장 진행/에러. isMutating 은 detailVM(공유)으로 관찰 — 배경탭 닫기 가드(BR-3, N2)와 일관.
    @State private var saveError: String?
    @State private var inlineError: String?

    // 다이얼로그/시트 상태.
    @State private var showDeleteConfirm = false
    @State private var showPhotoDeleteConfirm = false
    @State private var showPhotoPicker = false
    /// 피커에서 고른 이미지(크롭 단계로 넘김). 설정되면 SquareCropView 표시.
    @State private var pickedImage: PickedImage?

    // 말풍선 안 사진 제자리 펼침(웹 photoExpanded 동치). MEMORY + 사진일 때만 토글.
    @State private var photoExpanded = false



    private let memoLimit = 500
    private let placeNameLimit = 100

    init(
        pin: PinSummary,
        mapViewModel: MapViewModel,
        detailVM: PinDetailViewModel,
        onRequestClose: @escaping () -> Void,
        onRequestShare: @escaping () -> Void
    ) {
        self.pin = pin
        self.mapViewModel = mapViewModel
        self.detailVM = detailVM
        self.onRequestClose = onRequestClose
        self.onRequestShare = onRequestShare
        _placeDraft = State(initialValue: pin.placeName)
        _tagDraft = State(initialValue: pin.tag)
        _memoDraft = State(initialValue: pin.memo ?? "")
    }

    /// pins 단일 출처에서 최신 핀을 읽는다(낙관/사진 갱신 즉시 반영). 삭제되면 닫기 트리거.
    private var currentPin: PinSummary? {
        mapViewModel.pins.first { $0.id == pin.id }
    }

    var body: some View {
        let live = currentPin ?? pin
        Group {
            if mode == .edit {
                // 수정 모드는 본문(보기 내용)을 숨기고 위저드만(웹 collapseBody 동치). 폼 입력 가독성 위해 패딩 유지.
                editBody(live)
                    .padding(EdgeInsets(top: 16, leading: 16, bottom: 14, trailing: 16))
            } else {
                // 보기 모드는 웹 SpeechBubblePopup 처럼 컴팩트한 패딩(16/18/14 동치).
                viewBody(live)
                    .padding(EdgeInsets(top: 16, leading: 16, bottom: 14, trailing: 16))
            }
        }
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
        // 사진 피커/크롭(아래 sheet·fullScreenCover)은 말풍선 내부 하위 모달로, MapViewModel.activeSheet(BR-2) 1패널
        // 규칙을 거치지 않는 의도된 예외다. 크롭 중 외부 activeSheet 변경 시 BubbleOverlay unmount 로 크롭이 폐기되는 것은
        // "편집 중 다른 시트 = 미저장 폐기" 정책과 일관된 허용 동작이다(N3).
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
        // 다른 사용자/낙관 삭제로 pins 에서 사라지면 말풍선 닫기.
        .onChange(of: currentPin == nil) { _, gone in
            if gone { onRequestClose() }
        }
        // 진입 시점에 이미 삭제된 핀(극단 케이스)이면 onChange 가 발동하지 않으므로 onAppear 에서 한 번 더 방어.
        .onAppear {
            if currentPin == nil { onRequestClose() }
        }
    }

    // MARK: - 보기 모드(기본, 웹 SpeechBubblePopup 1:1 컴팩트)
    //
    // 위→아래: 메모(+우상단 코너 36pt 썸네일/사진 펼침) → 장소 행(점+장소명) → 주소 행(mono)
    //  → 릴스 링크 → 하단 행(날짜·작성자 인라인 + 공유/⋮). 패딩·폰트를 웹처럼 작게.

    private func viewBody(_ live: PinSummary) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            memoOrPhotoArea(live)
            placeAndAddress(live)
            if let url = instagramLink(live) {
                instagramRow(url)
            }
            if let error = inlineError ?? detailVM.photoError {
                errorBanner(error)
            }
            bottomRow(live)
        }
    }

    // MARK: - 메모 ↔ 사진 펼침 영역(웹 swapWrap 동치)

    /// 사진 펼침 가능 여부 — MEMORY + 썸네일·원본 URL 모두 존재(웹 hasPhoto 동치).
    private func photoURLs(_ live: PinSummary) -> (thumb: URL, full: URL)? {
        guard PinDetailViewModel.shouldShowPhotoSection(tag: live.tag),
              let thumbString = live.photoThumbnailUrl, let thumb = URL(string: thumbString),
              let fullString = live.photoUrl, let full = URL(string: fullString) else {
            return nil
        }
        return (thumb, full)
    }

    /// 메모(+우상단 코너 썸네일) 또는 펼친 1:1 사진. 탭으로 메모↔사진 전환.
    /// 잔상 제거: 두 장 겹침 크로스페이드(ghost)도, matchedGeometry 모핑(아래 텍스트 뒤로 깔림)도 쓰지 않는다.
    ///  if/else 로 한쪽만 렌더 + .clipped() 로 영역에 가둔다. 높이 전환(탭 핸들러 withAnimation)이 영역을 키우면
    ///  사진이 그 안에서 드러나고(reveal), 컨테이너 높이는 PinBubbleView 의 TailAnchorLayout 이 부드럽게 보간한다.
    @ViewBuilder
    private func memoOrPhotoArea(_ live: PinSummary) -> some View {
        let photo = photoURLs(live)
        ZStack(alignment: .topTrailing) {
            if photoExpanded, let photo {
                expandedPhoto(thumb: photo.thumb, full: photo.full)
                    .transition(.identity)
            } else {
                memoText(live, hasThumb: photo != nil)
                    .transition(.identity)
                if let photo {
                    cornerThumb(photo.thumb)
                        .transition(.identity)
                }
            }
        }
        // 펼침/접힘 중 사진이 아래 텍스트(장소·주소) 위로 새지 않게 영역에 클립(잔상 제거).
        // 높이 전환은 탭 핸들러 withAnimation 이 구동 → 영역이 자라며 사진이 드러난다(reveal).
        .clipped()
    }

    /// 메모 본문. 사진 있으면 코너 썸네일(36pt)과 겹치지 않게 우측 패딩(웹 paddingRight 44) 확보.
    @ViewBuilder
    private func memoText(_ live: PinSummary, hasThumb: Bool) -> some View {
        let hasMemo = (live.memo?.isEmpty == false)
        Text(hasMemo ? (live.memo ?? "") : "메모가 없어요")
            .font(WGFont.sans(14))
            .foregroundStyle(hasMemo ? WGColor.ink : WGColor.inkFaint)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.trailing, hasThumb ? 44 : 0)
    }

    /// 우상단 코너 36pt 썸네일(탭 시 사진 펼침). 펼친 사진과 hero 모핑으로 연결된다.
    private func cornerThumb(_ thumb: URL) -> some View {
        Button {
            withAnimation(.easeInOut(duration: 0.38)) { photoExpanded = true }
            panMapForPhoto(expanded: true)
        } label: {
            AsyncImage(url: thumb) { phase in
                switch phase {
                case .success(let image):
                    image.resizable().scaledToFill()
                default:
                    // 로딩/실패는 단순 페이드(스피너 없이) — 빈 타일.
                    WGColor.mapBlock
                }
            }
            .frame(width: 36, height: 36)
            .clipShape(RoundedRectangle(cornerRadius: 9))
        }
        .buttonStyle(.plain)
    }

    /// 사진 펼침/접힘 시 지도를 팬해 핀을 보이게 한다.
    /// 펼침: 핀을 화면 아래(72%)로 내려 말풍선이 그 위로 자랄 공간을 확보(핀 가림 방지). 접힘: 다시 중앙.
    /// 줌은 현재 줌 유지(없으면 pinFocusZoom). 말풍선 앵커는 cameraMoved 추적으로 자동으로 따라온다.
    private func panMapForPhoto(expanded: Bool) {
        let p = currentPin ?? pin
        let zoom = mapViewModel.mapZoom ?? MapViewModel.pinFocusZoom
        mapViewModel.flyTo(lat: p.latitude, lng: p.longitude, zoom: zoom, focusYFraction: expanded ? 0.72 : 0.5)
    }

    /// 펼친 1:1 정사각 사진(탭 시 메모로 복귀). 웹 PinPhotoInline 의 blur-up 이식:
    /// 캐시된 썸네일을 흐리게(blur 12) 즉시 깔고(추가 GET 0) 원본을 그 위로 페이드인한다(스피너 없음, 어두운 ink 배경).
    /// 원본 GET 은 펼침 시(photoExpanded)에만 시작 — 비펼침 상태(높이 0)에서 불필요한 다운로드를 피한다.
    private func expandedPhoto(thumb: URL, full: URL) -> some View {
        Button {
            withAnimation(.easeInOut(duration: 0.38)) { photoExpanded = false }
            panMapForPhoto(expanded: false)
        } label: {
            ZStack {
                // blur-up placeholder — 캐시된 썸네일. scale 1.08 로 blur 가장자리 투명 노출 방지(웹 동치).
                AsyncImage(url: thumb) { image in
                    image.resizable().scaledToFill().blur(radius: 12).scaleEffect(1.08)
                } placeholder: {
                    WGColor.ink
                }
                // 원본 — 펼침 시에만 로드. 로드 완료 시 페이드인(0.4s). 로딩 중엔 투명이라 아래 흐린 썸네일이 비친다.
                if photoExpanded {
                    AsyncImage(url: full, transaction: Transaction(animation: .easeIn(duration: 0.4))) { phase in
                        if case .success(let image) = phase {
                            image.resizable().scaledToFill()
                        } else {
                            Color.clear
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .aspectRatio(1, contentMode: .fit)
            .background(WGColor.ink)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    // MARK: - 장소 행 + 주소 행(웹 place/address 동치)

    @ViewBuilder
    private func placeAndAddress(_ live: PinSummary) -> some View {
        let hasPlace = !live.placeName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        // 장소 행에 표시할 라벨 — 장소명이 있으면 장소명, 없으면 좌표(웹 "장소명 자리에 좌표" 동치).
        let placeLabel = hasPlace ? live.placeName : coordinate(live)
        // 주소 행은 실제 주소가 있을 때만(좌표만 있는 핀은 주소 행 생략 — 좌표는 이미 장소 행).
        let address = (live.address?.isEmpty == false) ? live.address : nil
        VStack(alignment: .leading, spacing: 3) {
            // 장소 행: 태그 글리프(발견=원/위시=별/추억=하트, 웹 PinDot 동치) + 장소명(또는 좌표) bold.
            HStack(spacing: 7) {
                PinTagGlyph(tag: live.tag, size: 9)
                Text(placeLabel)
                    .font(WGFont.sans(13.5))
                    .fontWeight(.bold)
                    .foregroundStyle(WGColor.ink)
                    .lineLimit(2)
            }
            // 주소 행(mono). 장소명/좌표 점 아래로 들여쓰기.
            if let address {
                Text(address)
                    .font(WGFont.mono(11.5))
                    .foregroundStyle(WGColor.inkSoft)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, 15)
            }
        }
    }

    /// "위도, 경도" 5자리 표기(좌표만 있는 핀의 장소명 폴백).
    private func coordinate(_ live: PinSummary) -> String {
        String(format: "%.5f, %.5f", live.latitude, live.longitude)
    }

    // MARK: - 하단 행(날짜·작성자 인라인 + 공유/⋮)

    /// 좌측: {날짜} written by {작성자} 한 줄 인라인. 우측: 공유 아이콘 + ⋮ 메뉴.
    private func bottomRow(_ live: PinSummary) -> some View {
        HStack(alignment: .center, spacing: 8) {
            // 날짜(italic mono) + "written by"(italic) + 작성자(bold) 인라인.
            HStack(spacing: 5) {
                if let date = displayDate(live) {
                    Text(date)
                        .font(WGFont.mono(12))
                        .italic()
                        .foregroundStyle(WGColor.inkSoft)
                        // 날짜는 절대 줄이지 않는다(웹은 "2026.05.20" 전체 표시 — iOS 가 "2026.0…"로 잘리던 문제).
                        .fixedSize(horizontal: true, vertical: false)
                }
                if let author = live.createdByNickname, !author.isEmpty {
                    Text("written by")
                        .font(WGFont.sans(11))
                        .italic()
                        .foregroundStyle(WGColor.inkSoft)
                        .fixedSize(horizontal: true, vertical: false)
                    Text(author)
                        .font(WGFont.sans(12))
                        .fontWeight(.semibold)
                        .foregroundStyle(WGColor.ink)
                        // 공간이 정 부족하면 날짜가 아니라 작성자만 말줄임.
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
            Spacer(minLength: 0)
            // 공유 아이콘 — 모달은 PinBubbleView 가 전체화면 중앙에 띄운다(콜백만 발사).
            Button {
                onRequestShare()
            } label: {
                Image(systemName: "square.and.arrow.up")
                    .font(.system(size: 15, weight: .regular))
                    .foregroundStyle(WGColor.inkSoft)
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            // ⋮ 메뉴(수정/삭제). 수정 → 위저드 진입.
            menuButton
        }
        .padding(.top, 10)
        .overlay(alignment: .top) {
            Rectangle().fill(WGColor.hairline).frame(height: 1)
        }
    }

    /// ⋮ 메뉴(수정/삭제). 웹 PinPopup 의 menu popover 와 동치. 수정 진입 시 항상 장소 탭부터.
    private var menuButton: some View {
        Menu {
            Button("수정") { enterEdit() }
            Button("삭제", role: .destructive) {
                showDeleteConfirm = true
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(WGColor.inkSoft)
                .frame(width: 28, height: 28)
                .contentShape(Rectangle())
        }
        .disabled(detailVM.isMutating)
    }

    // MARK: - 수정 위저드(웹 PinPopup edit 모드 동치: 장소 → 태그 → 메모 탭)
    //
    // #1 — 세로 섹션 나열(등록 입력폼 느낌) 대신 한 번에 한 탭만 보여 컴팩트. 저장은 메모 탭 "저장"이 일괄 커밋.
    // #2 — 위저드로 컴팩트해진 데다 폼 본문에 maxHeight + 내부 스크롤을 둬 말풍선이 화면 위로 과도하게 삐져나가지 않게 한다.

    private func editBody(_ live: PinSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            tabHeader
            // 폼 본문: maxHeight + 내부 스크롤(웹 footerContent maxHeight min(50vh,340) 동치).
            //  말풍선(PinBubbleView)이 마커 위로 앵커되므로 폼이 길면 화면 위로 넘친다 → 여기서 높이를 제한한다(#2).
            ScrollView {
                Group {
                    switch editTab {
                    case .place: placePanel(live)
                    case .tag: tagPanel(live)
                    case .memo: memoPanel(live)
                    }
                }
                .padding(.top, 12)
            }
            .frame(maxHeight: 320)
            // 보조 액션(닫기). 웹 footer 의 "좌표 수정/닫기" 중 iOS 엔 좌표 수정 경로가 없어 "닫기"만 둔다.
            editFooterAux
        }
    }

    /// 탭 헤더(장소/태그/메모, 밑줄 강조). 웹 renderTabButton 동치.
    private var tabHeader: some View {
        HStack(spacing: 0) {
            tabButton("장소", tab: .place)
            tabButton("태그", tab: .tag)
            tabButton("메모", tab: .memo)
            Spacer(minLength: 0)
        }
        .overlay(alignment: .bottom) {
            Rectangle().fill(WGColor.hairline).frame(height: 1)
        }
    }

    private func tabButton(_ label: String, tab: EditTab) -> some View {
        let active = editTab == tab
        return Button {
            editTab = tab
        } label: {
            Text(label)
                .font(WGFont.sans(13))
                .fontWeight(active ? .bold : .medium)
                .foregroundStyle(active ? WGColor.ink : WGColor.inkSoft)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .overlay(alignment: .bottom) {
                    Rectangle()
                        .fill(active ? WGColor.ink : Color.clear)
                        .frame(height: 2)
                }
        }
        .buttonStyle(.plain)
        .disabled(detailVM.isMutating)
    }

    // MARK: - 장소 탭(장소명 입력 ≤100 + "다음 →" 빈값 막기)

    private func placePanel(_ live: PinSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField("장소 이름", text: $placeDraft)
                .font(WGFont.sans(13))
                .textFieldStyle(.roundedBorder)
                .disabled(detailVM.isMutating)
                .onChange(of: placeDraft) { _, value in
                    if value.count > placeNameLimit {
                        placeDraft = String(value.prefix(placeNameLimit))
                    }
                    if placeError != nil { placeError = nil }
                }
            if let placeError {
                Text(placeError)
                    .font(WGFont.sans(12))
                    .foregroundStyle(WGColor.pinNew)
            }
            HStack {
                Spacer()
                Button("다음 →") { nextFromPlace() }
                    .font(WGFont.sans(12))
                    .fontWeight(.bold)
                    .foregroundStyle(WGColor.cta)
                    .disabled(detailVM.isMutating)
            }
            .padding(.top, 2)
        }
    }

    /// 장소 탭 "다음" — 빈 장소면 막고, 아니면 태그 탭으로 진행(웹 handleNextFromPlace 동치).
    private func nextFromPlace() {
        if placeDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            placeError = "장소 이름을 비울 수 없어요"
            return
        }
        placeError = nil
        editTab = .tag
    }

    // MARK: - 태그 탭(칩 선택 + "← 이전"/"다음 →", 사진 있는 추억핀은 위시/발견 비활성)

    /// 사진이 붙은 추억핀은 위시/발견으로 바꿀 수 없다(비-MEMORY 핀은 사진 불가). 웹 photoPresentForTag 동치.
    private func photoPresentForTag(_ live: PinSummary) -> Bool {
        live.tag == .MEMORY && live.photoUrl != nil
    }

    private func tagPanel(_ live: PinSummary) -> some View {
        let locked = photoPresentForTag(live)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                ForEach(PinTag.allCases, id: \.self) { tag in
                    // MEMORY 는 항상 선택 가능, 위시/발견은 사진 있는 추억핀이면 비활성.
                    let disabled = (tag != .MEMORY) && locked
                    tagChip(tag, isOn: tagDraft == tag, disabled: disabled)
                }
            }
            if locked {
                Text("사진이 있는 추억핀이에요. 위시·발견으로 바꾸려면 먼저 사진을 삭제해 주세요.")
                    .font(WGFont.sans(12))
                    .foregroundStyle(WGColor.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Button("← 이전") { editTab = .place }
                    .font(WGFont.sans(12))
                    .fontWeight(.semibold)
                    .foregroundStyle(WGColor.inkSoft)
                Spacer()
                Button("다음 →") { editTab = .memo }
                    .font(WGFont.sans(12))
                    .fontWeight(.bold)
                    .foregroundStyle(WGColor.cta)
            }
            .disabled(detailVM.isMutating)
            .padding(.top, 2)
        }
    }

    private func tagChip(_ tag: PinTag, isOn: Bool, disabled: Bool) -> some View {
        Button {
            guard !disabled, !detailVM.isMutating else { return }
            tagDraft = tag
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
            .opacity(disabled ? 0.45 : 1)
        }
        .buttonStyle(.plain)
        .disabled(disabled || detailVM.isMutating)
    }

    // MARK: - 메모 탭(메모 입력 ≤500 + (MEMORY) 사진 + "취소"/"저장")

    private func memoPanel(_ live: PinSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("메모 편집")
                    .font(WGFont.sans(13))
                    .fontWeight(.semibold)
                    .foregroundStyle(WGColor.ink)
                Spacer()
                Text("\(memoDraft.count)/\(memoLimit)")
                    .font(WGFont.sans(11))
                    .foregroundStyle(memoDraft.count >= memoLimit - 50 ? WGColor.cta : WGColor.inkFaint)
            }
            TextEditor(text: $memoDraft)
                .font(WGFont.sans(14))
                .frame(minHeight: 90)
                .padding(6)
                .background(WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(WGColor.hairline, lineWidth: 1))
                .disabled(detailVM.isMutating)
                .onChange(of: memoDraft) { _, value in
                    if value.count > memoLimit {
                        memoDraft = String(value.prefix(memoLimit))
                    }
                }
            // (MEMORY 일 때) 사진 추가/변경/삭제 — 웹은 staging 후 일괄 반영이나 iOS detailVM 은 즉시 커밋한다.
            if PinDetailViewModel.shouldShowPhotoSection(tag: live.tag) {
                photoEditor(live)
            }
            if let error = saveError ?? inlineError ?? detailVM.photoError {
                errorBanner(error)
            }
            // "취소" / "저장" — 저장은 변경된 장소/태그/메모를 한 번에 커밋(웹 handleSaveAll 동치).
            HStack(spacing: 8) {
                Spacer()
                Button("취소") { cancelEdit() }
                    .font(WGFont.sans(13))
                    .foregroundStyle(WGColor.inkSoft)
                    .disabled(detailVM.isMutating)
                Button {
                    Task { await saveAll(live) }
                } label: {
                    Text(detailVM.isMutating ? "저장 중..." : "저장")
                        .font(WGFont.sans(13))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(WGColor.cta)
                        .foregroundStyle(WGColor.panel)
                        .clipShape(Capsule())
                }
                .disabled(detailVM.isMutating)
            }
            .padding(.top, 2)
        }
    }

    /// 메모 탭 내 사진 편집(MEMORY 전용). 썸네일/추가·변경/삭제 + 업로드 로딩(QE-3). iOS 즉시 커밋(detailVM).
    private func photoEditor(_ live: PinSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
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
                    .frame(width: 120, height: 120)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                } else {
                    placeholderTile(systemName: "photo.badge.plus")
                        .frame(width: 120, height: 120)
                }
                if detailVM.isPhotoBusy {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color.black.opacity(0.35))
                        .frame(width: 120, height: 120)
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
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .overlay(Capsule().stroke(WGColor.hairline, lineWidth: 1))
                        .foregroundStyle(WGColor.ink)
                }
                .disabled(detailVM.isPhotoBusy)

                if live.photoUrl != nil {
                    Button {
                        showPhotoDeleteConfirm = true
                    } label: {
                        Text("삭제")
                            .font(WGFont.sans(13))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .foregroundStyle(WGColor.pinNew)
                            .overlay(Capsule().stroke(WGColor.pinNew, lineWidth: 1))
                    }
                    .disabled(detailVM.isPhotoBusy)
                }
            }
        }
    }

    /// 위저드 보조 푸터(닫기). 수정 중 draft 를 되돌리고 보기로(웹 footer 닫기 동치). 좌표 수정은 iOS 미지원이라 생략.
    private var editFooterAux: some View {
        HStack {
            Spacer()
            Button("닫기") { cancelEdit() }
                .font(WGFont.sans(12))
                .fontWeight(.semibold)
                .foregroundStyle(WGColor.inkSoft)
                .disabled(detailVM.isMutating)
        }
        .padding(.top, 12)
        .overlay(alignment: .top) {
            Rectangle().fill(WGColor.hairline).frame(height: 1)
        }
    }

    private func placeholderTile(systemName: String) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12).fill(WGColor.mapBlock)
            Image(systemName: systemName)
                .font(.system(size: 24))
                .foregroundStyle(WGColor.inkFaint)
        }
        .frame(width: 120, height: 120)
    }

    // MARK: - 표시 날짜(보기/편집 공통)

    /// 표시 날짜: MEMORY + visitedAt 이면 "다녀온 날", 그 외 createdAt. 웹 PinPopup dateSource 동치.
    private func displayDate(_ live: PinSummary) -> String? {
        let iso = (live.tag == .MEMORY ? live.visitedAt : nil) ?? live.createdAt
        guard let date = VisitDateFormatter.parse(iso) else { return nil }
        return VisitDateFormatter.dotted(date)
    }

    // MARK: - Instagram 바로가기(https 가드, AC-17)

    /// 릴스 링크(웹 instagramUrl 동치). 인스타 핑크 #C13584 + "📷 릴스 보기 ↗".
    private func instagramRow(_ url: URL) -> some View {
        Link(destination: url) {
            HStack(spacing: 4) {
                Text("📷")
                Text("릴스 보기")
                    .font(WGFont.sans(12))
                    .fontWeight(.semibold)
                Text("↗")
                    .font(WGFont.sans(10))
            }
            .foregroundStyle(Color(hex: "#C13584"))
        }
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

    // MARK: - 위저드 진입/취소

    /// ⋮ → "수정" 진입(웹 menu→edit 동치). draft 를 현재 값으로 초기화하고 장소 탭부터 노출.
    private func enterEdit() {
        inlineError = nil
        saveError = nil
        placeError = nil
        detailVM.photoError = nil
        photoExpanded = false
        placeDraft = (currentPin ?? pin).placeName
        tagDraft = (currentPin ?? pin).tag
        memoDraft = (currentPin ?? pin).memo ?? ""
        editTab = .place
        mode = .edit
    }

    /// 수정 취소/닫기 — draft 를 원래 값으로 되돌리고 보기로(웹 handleCancelEdit 동치).
    private func cancelEdit() {
        let live = currentPin ?? pin
        placeDraft = live.placeName
        tagDraft = live.tag
        memoDraft = live.memo ?? ""
        placeError = nil
        saveError = nil
        inlineError = nil
        editTab = .place
        mode = .view
    }

    // MARK: - 액션(MapViewModel 낙관 메서드 위임)

    private func instagramLink(_ live: PinSummary) -> URL? {
        guard PinDetailViewModel.shouldShowInstagramLink(live.instagramUrl),
              let urlString = live.instagramUrl else { return nil }
        return URL(string: urlString)
    }

    /// 위저드 최종 저장(웹 handleSaveAll 동치) — 변경된 장소/태그/메모를 순서대로 커밋한다.
    /// 백엔드가 필드별 API 라 변경분만 호출하고, 일부 실패 시 메시지를 합쳐 보여준다(이미 성공한 건 유지).
    /// 사진은 메모 탭 사진 편집에서 detailVM 이 즉시 커밋하므로 여기선 다루지 않는다.
    private func saveAll(_ live: PinSummary) async {
        guard !detailVM.isMutating else { return }
        let trimmedPlace = placeDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedPlace.isEmpty else {
            saveError = "장소 이름을 비울 수 없어요"
            placeError = "장소 이름을 비울 수 없어요"
            editTab = .place
            return
        }
        let placeChanged = trimmedPlace != live.placeName
        let tagChanged = tagDraft != live.tag
        let memoChanged = memoDraft != (live.memo ?? "")
        guard placeChanged || tagChanged || memoChanged else {
            // 변경 없음 — 그냥 보기로 복귀(웹 동치).
            mode = .view
            return
        }
        saveError = nil
        detailVM.isMutating = true
        defer { detailVM.isMutating = false }

        var errors: [String] = []
        if placeChanged {
            do {
                try await mapViewModel.updatePlaceNameOptimistic(pinId: pin.id, placeName: trimmedPlace)
            } catch {
                errors.append((error as? LocalizedError)?.errorDescription ?? "장소 저장에 실패했어요")
            }
        }
        if tagChanged {
            do {
                try await mapViewModel.applyTagOptimistic(pinId: pin.id, tag: tagDraft)
            } catch {
                errors.append((error as? LocalizedError)?.errorDescription ?? "태그 저장에 실패했어요")
            }
        }
        if memoChanged {
            do {
                try await mapViewModel.updateMemoOptimistic(pinId: pin.id, memo: memoDraft)
            } catch {
                errors.append((error as? LocalizedError)?.errorDescription ?? "메모 저장에 실패했어요")
            }
        }
        if errors.isEmpty {
            mode = .view
        } else {
            saveError = errors.joined(separator: " / ")
        }
    }

    private func deletePin() async {
        inlineError = nil
        detailVM.isMutating = true
        defer { detailVM.isMutating = false }
        do {
            try await mapViewModel.deletePinOptimistic(pinId: pin.id)
            onRequestClose()
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
        case .REEL: return "발견"
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
