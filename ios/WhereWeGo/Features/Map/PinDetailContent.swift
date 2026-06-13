import SwiftUI

// 핀 상세 공통 콘텐츠(설계 §6, D-5). 풀 모달 시트(PinDetailSheet)에서 콘텐츠만 분리·이관한 뷰.
// NavigationStack/ScrollView 풀모달 래퍼·툴바·dismiss 는 제거하고, 말풍선(PinBubbleView)이 래핑·앵커·닫기를 담당한다.
// frontend/src/app/map/_components/PinPopup.tsx + SpeechBubblePopup.tsx 의 보기/편집/삭제 흐름 이식.
//
// 보기 모드(기본, 웹 SpeechBubblePopup 보기 동치):
//  - 헤더: 태그 배지 + 장소명 + 공유 버튼 + ⋯ 메뉴(수정/삭제).
//  - 주소(있을 때) · 메모↔추억사진 제자리 펼침(C영역) · Instagram 바로가기(https 가드 AC-17).
// 편집 모드(⋯→수정 시에만): 태그 변경(낙관 PATCH)·장소명(≤200)·메모(≤500)·사진 관리(MEMORY)·삭제 + 완료 행.
//
// 태그/메모/장소명/삭제는 MapViewModel 의 낙관적 메서드에 위임(pins 단일 출처).
// 사진은 PinDetailViewModel(detailVM, PinBubbleView 가 @StateObject 로 소유·주입)이 직접 호출 후 replacePin 으로 반영.
struct PinDetailContent: View {
    let pin: PinSummary
    @ObservedObject var mapViewModel: MapViewModel
    /// 사진 상태(업로드/삭제) 소유 VM. PinBubbleView 가 @StateObject 로 보유하고 주입한다(말풍선 닫힘 가드와 공유).
    @ObservedObject var detailVM: PinDetailViewModel
    /// 닫기 요청(삭제 완료/다른 사용자 삭제). PinBubbleView 가 selectedPinId·화면좌표 해제로 연결.
    var onRequestClose: () -> Void

    // 보기/편집 모드 분기(B영역, 웹 PinPopup mode 동치). 진입 시 항상 보기 모드(말풍선은 핀마다 재생성).
    @State private var isEditing = false
    // 추억 사진 제자리 펼침 상태·전환 네임스페이스(C영역, 웹 PinPhotoInline + FLIP 동치).
    @State private var isPhotoExpanded = false
    @Namespace private var photoNS

    // 편집 입력 버퍼.
    @State private var memoText: String
    @State private var placeNameText: String
    @State private var isEditingMemo = false
    @State private var isEditingPlaceName = false

    // 작업 진행/에러. isMutating 은 detailVM(공유)으로 이동 — 배경탭 닫기 가드(BR-3, N2)와 일관 관찰.
    @State private var inlineError: String?

    // 다이얼로그/시트 상태.
    @State private var showDeleteConfirm = false
    @State private var showPhotoDeleteConfirm = false
    @State private var showPhotoPicker = false
    /// 피커에서 고른 이미지(크롭 단계로 넘김). 설정되면 SquareCropView 표시.
    @State private var pickedImage: PickedImage?
    /// 공유 카드 시트 표시 여부(웹 PinPopup shareOpen 동치).
    @State private var showShareCard = false
    /// 핀 답장 시트(PIN_REPLY → 그룹 채팅방) 표시 여부. 답장 버튼이 true 로 트리거.
    @State private var showReplySheet = false

    private let memoLimit = 100
    private let placeNameLimit = 200

    init(
        pin: PinSummary,
        mapViewModel: MapViewModel,
        detailVM: PinDetailViewModel,
        onRequestClose: @escaping () -> Void
    ) {
        self.pin = pin
        self.mapViewModel = mapViewModel
        self.detailVM = detailVM
        self.onRequestClose = onRequestClose
        _memoText = State(initialValue: pin.memo ?? "")
        _placeNameText = State(initialValue: pin.placeName)
    }

    /// pins 단일 출처에서 최신 핀을 읽는다(낙관/사진 갱신 즉시 반영). 삭제되면 닫기 트리거.
    private var currentPin: PinSummary? {
        mapViewModel.pins.first { $0.id == pin.id }
    }

    var body: some View {
        let live = currentPin ?? pin
        Group {
            if isEditing {
                editContent(live)
            } else {
                viewContent(live)
            }
        }
        .padding(20)
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
        // 공유 카드 시트(웹 PinShareSheet 동치). 그룹 핀은 pins 단일 출처에서 전달(자기 핀은 렌더러가 제외).
        .sheet(isPresented: $showShareCard) {
            PinShareCardSheet(
                pin: live,
                groupPins: mapViewModel.pins,
                onClose: { showShareCard = false }
            )
        }
        // 핀 답장 시트(PIN_REPLY → 그룹 채팅방). 경량 시트(220pt) — 미니 핀 카드 + 한마디 입력 + 전송.
        .sheet(isPresented: $showReplySheet) {
            PinReplySheet(
                pin: live,
                mapViewModel: mapViewModel,
                onClose: { showReplySheet = false }
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

    // MARK: - 보기 모드(웹 SpeechBubblePopup/PinPopup 보기 동치, B영역)

    /// 기본 표시 화면 — 웹과 동일한 세로 순서:
    /// ① 메모↔사진 펼침(맨 위 — 사진이 장소 "위로" 열린다) ② 글리프+장소명 ③ 주소 ④ 인스타
    /// ⑤ 하단 행(hairline 구분: 날짜·written by 작성자 | 공유·⋯ 메뉴).
    /// 편집용 입력 버퍼/상태(isEditingMemo 등)는 보기 모드에서 사용하지 않는다.
    @ViewBuilder
    private func viewContent(_ live: PinSummary) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            memoOrPhotoRow(live)
            placeRow(live)
            if let address = live.address, !address.isEmpty {
                addressRow(address)
            }
            if let url = instagramLink(live) {
                instagramRow(url)
            }
            if let error = inlineError ?? detailVM.photoError {
                errorBanner(error)
            }
            bottomRow(live)
        }
    }

    /// 장소 행(웹 Place row 동치) — 마커 미니 글리프(원/별/하트, PinMarkerGlyphs 재사용) + 장소명.
    /// 글리프 이미지는 그림자 여백(shadowPad)을 포함하므로 프레임을 약간 키워 실모양 크기를 웹(8/11px)에 맞춘다.
    private func placeRow(_ live: PinSummary) -> some View {
        HStack(alignment: .center, spacing: 7) {
            Image(uiImage: PinMarkerGlyphs.image(for: live.tag))
                .resizable()
                .scaledToFit()
                .frame(
                    width: live.tag == .MEMORY ? 16 : 12,
                    height: live.tag == .MEMORY ? 16 : 12
                )
            Text(live.placeName)
                .font(WGFont.emo(16))
                .foregroundStyle(WGColor.ink)
                .lineLimit(2)
            Spacer(minLength: 0)
        }
    }

    /// 방문자 아바타 스택(정책 v2 FR-B4, AC-5) — 하단 행 "written by {작성자}" 우측에 아이콘만 편입(별도 행·텍스트 없음).
    /// 아바타 16pt·-5 오버랩, 한 줄 최대 4개 × 2줄(그룹 정원 8명이라 +N 불필요). visitors 가 nil/빈이면 생략한다(AC-5).
    /// 가입 순(서버 순서) 그대로 노출.
    @ViewBuilder
    private func visitorsStack(_ live: PinSummary) -> some View {
        if let visitors = live.visitors, !visitors.isEmpty {
            let rows = stride(from: 0, to: min(visitors.count, 8), by: 4).map {
                Array(visitors[$0..<min($0 + 4, min(visitors.count, 8))])
            }
            VStack(alignment: .leading, spacing: 2) {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    HStack(spacing: -5) {
                        ForEach(row) { visitor in
                            AvatarView(
                                imageUrl: visitor.profileImageUrl,
                                name: visitor.nickname ?? "?",
                                size: 16
                            )
                            .overlay(Circle().stroke(WGColor.panel, lineWidth: 1.5))
                        }
                    }
                }
            }
            .padding(.leading, 6)
            .accessibilityLabel("\(visitors.count)명이 다녀감")
        }
    }

    /// 하단 행(웹 Bottom row 동치) — hairline 윗줄 + 날짜·written by 작성자(좌) / 공유·⋯ 메뉴(우).
    private func bottomRow(_ live: PinSummary) -> some View {
        VStack(spacing: 8) {
            Divider().overlay(WGColor.hairline)
            HStack(alignment: .center, spacing: 4) {
                Text(VisitDateFormatter.formatDate(live.createdAt))
                    .font(WGFont.mono(11))
                    .italic()
                    .foregroundStyle(WGColor.inkSoft)
                if let nickname = live.createdByNickname, !nickname.isEmpty {
                    Text("written by")
                        .font(WGFont.sans(10))
                        .italic()
                        .foregroundStyle(WGColor.inkSoft)
                        .padding(.leading, 4)
                    Text(nickname)
                        .font(WGFont.sansSemiBold(11))
                        .foregroundStyle(WGColor.ink)
                        .lineLimit(1)
                }
                visitorsStack(live)
                Spacer(minLength: 8)
                replyButton
                shareButton
                Menu {
                    Button("수정") { withAnimation(.easeOut(duration: 0.25)) { isEditing = true } }
                    Button("삭제", role: .destructive) { showDeleteConfirm = true }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(WGColor.inkSoft)
                        .frame(width: 28, height: 28)
                }
                .accessibilityLabel("더 보기")
            }
        }
    }

    // MARK: - 메모 ↔ 추억 사진 제자리 펼침(웹 PinPhotoInline + FLIP 동치, C영역)

    /// 보기 모드의 메모 행. MEMORY + 사진이 있으면 우측 36pt 썸네일 → 탭 시 풀폭 1:1 사진으로 제자리 펼침.
    /// 펼침/접힘은 withAnimation(easeOut 0.3)으로 말풍선 높이가 자연 애니메이트되게 한다(웹 height FLIP 동치).
    @ViewBuilder
    private func memoOrPhotoRow(_ live: PinSummary) -> some View {
        let hasPhoto = live.tag == .MEMORY && live.photoThumbnailUrl != nil && live.photoUrl != nil
        if isPhotoExpanded && hasPhoto,
           let thumb = live.photoThumbnailUrl, let full = live.photoUrl,
           let thumbURL = URL(string: thumb), let fullURL = URL(string: full) {
            // 확대 사진 노드(풀폭 1:1, blur-up 크로스페이드). 탭 → 메모로 복귀.
            ExpandedPinPhoto(thumbnailURL: thumbURL, photoURL: fullURL)
                .matchedGeometryEffect(id: "pinPhoto", in: photoNS)
                .onTapGesture {
                    withAnimation(.easeOut(duration: 0.3)) { isPhotoExpanded = false }
                }
        } else {
            HStack(alignment: .top, spacing: 10) {
                if let memo = live.memo, !memo.isEmpty {
                    Text(memo)
                        .font(WGFont.sans(14))
                        .foregroundStyle(WGColor.ink)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    Text("메모가 없어요")
                        .font(WGFont.sans(13))
                        .foregroundStyle(WGColor.inkFaint)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if hasPhoto, let thumb = live.photoThumbnailUrl, let thumbURL = URL(string: thumb) {
                    AsyncImage(url: thumbURL) { phase in
                        if case let .success(image) = phase {
                            image.resizable().scaledToFill()
                        } else {
                            WGColor.mapBlock
                        }
                    }
                    .frame(width: 36, height: 36)
                    .clipShape(RoundedRectangle(cornerRadius: 9))
                    .matchedGeometryEffect(id: "pinPhoto", in: photoNS)
                    .onTapGesture {
                        withAnimation(.easeOut(duration: 0.3)) { isPhotoExpanded = true }
                    }
                }
            }
        }
    }

    // MARK: - 편집 모드(⋯→수정 시에만, 기존 폼 + 완료 행)

    /// 편집 화면: 완료 행 + 기존 편집 폼(태그/장소명/메모/사진/인스타/에러/삭제). 기존 로직 무변경.
    private func editContent(_ live: PinSummary) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Text("편집")
                    .font(WGFont.sansBold(15))
                    .foregroundStyle(WGColor.ink)
                Spacer()
                Button("완료") { withAnimation(.easeOut(duration: 0.25)) { isEditing = false } }
                    .font(WGFont.sansSemiBold(15))
                    .foregroundStyle(WGColor.cta)
            }
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
    }

    /// 공유 카드 진입 버튼(웹 PinPopup 공유 아이콘 동치). 탭 시 공유 카드 시트를 띄운다.
    private var shareButton: some View {
        Button {
            showShareCard = true
        } label: {
            Image(systemName: "square.and.arrow.up")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(WGColor.inkSoft)
        }
        .accessibilityLabel("공유")
    }

    /// 핀 답장 버튼(PIN_REPLY → 그룹 채팅방). 공유 버튼 왼쪽. 탭 시 답장 시트를 띄운다.
    private var replyButton: some View {
        Button {
            showReplySheet = true
        } label: {
            Image(systemName: "arrowshape.turn.up.left")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(WGColor.inkSoft)
        }
        .accessibilityLabel("채팅방에 답장")
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
            guard !isOn, !detailVM.isMutating else { return }
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
        .disabled(detailVM.isMutating)
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
        .disabled(detailVM.isMutating)
        .padding(.top, 4)
    }

    // MARK: - 공통 작은 뷰

    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(WGFont.sans(12))
            .foregroundStyle(WGColor.inkSoft)
    }

    private func editToggle(isEditing: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(isEditing ? "저장" : "편집")
                .font(WGFont.sans(12))
                .foregroundStyle(WGColor.cta)
        }
        .disabled(detailVM.isMutating)
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
        detailVM.isMutating = true
        defer { detailVM.isMutating = false }
        do {
            try await mapViewModel.applyTagOptimistic(pinId: pin.id, tag: tag)
        } catch {
            inlineError = (error as? LocalizedError)?.errorDescription ?? "태그를 변경하지 못했어요."
        }
    }

    private func saveMemo() async {
        inlineError = nil
        detailVM.isMutating = true
        defer { detailVM.isMutating = false }
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
        detailVM.isMutating = true
        defer { detailVM.isMutating = false }
        do {
            try await mapViewModel.updatePlaceNameOptimistic(pinId: pin.id, placeName: trimmed)
            isEditingPlaceName = false
        } catch {
            inlineError = (error as? LocalizedError)?.errorDescription ?? "장소 이름을 저장하지 못했어요."
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
        case .REEL: return "릴스"
        case .WISH: return "위시"
        case .MEMORY: return "추억"
        }
    }
}

// MARK: - 확대 추억 사진(제자리 펼침, 웹 PinPhotoInline 동치)

/// 말풍선 메모 영역을 대체해 펼쳐지는 풀폭 1:1 사진. 캐시 썸네일을 blur-up placeholder 로 즉시 깔고
/// 원본 로드 성공 시 0→1 페이드로 드러낸다(스피너 없음). 탭→복귀는 호출처가 onTapGesture 로 처리한다.
/// internal(파일 외 GroupMessageRow 의 PIN_REPLY 썸네일 펼침에서도 재사용 — 설계 §3 승격).
struct ExpandedPinPhoto: View {
    let thumbnailURL: URL
    let photoURL: URL

    var body: some View {
        ZStack {
            // 아래: 썸네일 blur-up placeholder(즉시). 웹 filter blur(12px) scale(1.08) 동치.
            AsyncImage(url: thumbnailURL) { phase in
                if case let .success(image) = phase {
                    image.resizable().scaledToFill()
                        .blur(radius: 12)
                        .scaleEffect(1.08)
                } else {
                    WGColor.mapBlock
                }
            }
            // 위: 원본. 성공 시 0→1 크로스페이드(0.4s). 스피너 없음(웹 onLoad opacity 전환 동치).
            AsyncImage(url: photoURL) { phase in
                if case let .success(image) = phase {
                    image.resizable().scaledToFill()
                        .transition(.opacity.animation(.easeIn(duration: 0.4)))
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .contentShape(Rectangle())
    }
}

// fullScreenCover(item:) 바인딩용 선택 이미지 래퍼(UIImage 는 Identifiable 이 아니므로 래핑).
private struct PickedImage: Identifiable {
    let id = UUID()
    let image: UIImage
}
