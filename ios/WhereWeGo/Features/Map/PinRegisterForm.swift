import SwiftUI
import UIKit

// 핀 등록 폼(인라인 위치 카드의 '등록' 후 표시). 웹 MemoTagPanelContent.tsx 정합 — 위치는 이미 정해진 상태에서
// 주소(표시) → 장소 이름 → 태그 → 메모 →(추억이면 사진)→ 릴스 링크를 한 번에 입력하고, 폼 제출 시 핀을 생성한다.
//  - 태그 칩: 글리프(●발견/★위시/♥추억) + 선택 시 색 채움(흰 글리프), 미선택은 옅은 색 배경 + 색 테두리(웹 PinTag 정합).
//  - 추억(MEMORY) 선택 시에만 "사진 (선택)" 섹션 노출 → 선택·크롭한 사진은 생성 성공 후 2단계 업로드(웹 BR-6).
//  - 등록: viewModel.submitRegisterForm() → 생성 성공 시 exitAddPin 으로 전체 종료. 취소: onClose()(위치 카드로 복귀).
struct PinRegisterForm: View {
    @ObservedObject var viewModel: AddPlaceViewModel
    /// 취소(시트만 닫고 위치 카드로 복귀; 추가 모드는 유지).
    let onClose: () -> Void

    /// 메모 상한(백엔드 정합 — 핀 메모 100자).
    private let memoLimit = 100

    /// 릴스 링크 형식 에러(https:// 미시작). nil = 정상/빈값.
    @State private var urlError: String?
    /// 사진 피커/크롭 트리거(추억 핀 첨부 사진).
    @State private var showPhotoPicker = false
    @State private var pickedImage: PickedFormImage?

    /// 등록 가능: 생성 중이 아니고 링크 형식 에러가 없을 때(장소명은 비어도 주소로 폴백, 태그는 기본값 보유).
    private var canSubmit: Bool {
        !viewModel.isCreating && urlError == nil
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    addressSection
                    Rectangle().fill(WGColor.hairline).frame(height: 1)
                    placeNameSection
                    tagSection
                    memoSection
                    if viewModel.draftTag == .MEMORY {
                        photoSection
                    }
                    instagramSection

                    if let error = viewModel.errorMessage {
                        Text(error)
                            .font(WGFont.sans(13))
                            .foregroundStyle(WGColor.pinNew)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .background(WGColor.pinNew.opacity(0.1))
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                }
                .padding(20)
            }
            .background(WGColor.bg)
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("핀 등록")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("취소") { onClose() }
                        .foregroundStyle(WGColor.inkSoft)
                        .disabled(viewModel.isCreating)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        viewModel.submitRegisterForm()
                    } label: {
                        Text(viewModel.isCreating ? "등록 중..." : "등록")
                            .font(WGFont.sansSemiBold(15))
                            .foregroundStyle(canSubmit ? WGColor.cta : WGColor.inkFaint)
                    }
                    .disabled(!canSubmit)
                }
            }
            // 추억 첨부 사진: 피커 → 정사각 크롭 → draftPhoto. 생성 성공 후 업로드(2단계).
            .sheet(isPresented: $showPhotoPicker) {
                PhotoPickerView(
                    onPicked: { pickedImage = PickedFormImage(image: $0) },
                    onDismiss: { showPhotoPicker = false }
                )
                .ignoresSafeArea()
            }
            .fullScreenCover(item: $pickedImage) { picked in
                SquareCropView(
                    image: picked.image,
                    onCropped: { cropped in
                        pickedImage = nil
                        viewModel.draftPhoto = cropped
                    },
                    onCancel: { pickedImage = nil }
                )
            }
        }
    }

    // MARK: - 주소(상단 표시 — 웹 정합)

    private var addressSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionLabel("주소")
            HStack(alignment: .top, spacing: 6) {
                Image(systemName: "mappin.and.ellipse")
                    .font(.system(size: 12))
                    .foregroundStyle(WGColor.inkSoft)
                Text(viewModel.formAddressLine)
                    .font(viewModel.formAddressIsCoordinate ? WGFont.mono(13) : WGFont.sans(13))
                    .foregroundStyle(WGColor.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: - 장소 이름

    private var placeNameSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionLabel("장소 이름")
            TextField("예: 우리집", text: $viewModel.draftPlaceName)
                .font(WGFont.sans(16))
                .foregroundStyle(WGColor.ink)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
        }
    }

    // MARK: - 태그(발견/위시/추억 — 글리프 + 선택 시 채움, 웹 PinTag 정합)

    private var tagSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionLabel("태그")
            HStack(spacing: 10) {
                ForEach([PinTag.MEMORY, .WISH, .REEL], id: \.self) { tag in
                    tagToggle(tag, isOn: viewModel.draftTag == tag)
                }
            }
        }
    }

    private func tagToggle(_ tag: PinTag, isOn: Bool) -> some View {
        let color = Self.tagColor(tag)
        return Button {
            viewModel.draftTag = tag
            // 비-추억 태그로 바꾸면 첨부 사진을 폐기(웹 정합 — 비-MEMORY 업로드 시도 방지).
            if tag != .MEMORY { viewModel.draftPhoto = nil }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: Self.tagIcon(tag))
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(isOn ? .white : color)
                Text(Self.tagLabel(tag))
                    .font(WGFont.sansSemiBold(13))
                    .foregroundStyle(isOn ? .white : color)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(isOn ? color : color.opacity(0.08))
            .clipShape(Capsule())
            .overlay(Capsule().stroke(color, lineWidth: 1.5))
        }
        .disabled(viewModel.isCreating)
    }

    // MARK: - 메모(선택)

    private var memoSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                sectionLabel("메모 (선택)")
                Spacer()
                Text("\(viewModel.draftMemo.count)/\(memoLimit)")
                    .font(WGFont.sans(11))
                    .foregroundStyle(WGColor.inkFaint)
            }
            TextField("메모를 입력해 보세요...", text: $viewModel.draftMemo, axis: .vertical)
                .font(WGFont.sans(15))
                .foregroundStyle(WGColor.ink)
                .lineLimit(3...5)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(WGColor.hairline, lineWidth: 1))
                // 100자 상한(백엔드 정합) — 초과 입력 시 잘라낸다.
                .onChange(of: viewModel.draftMemo) { _, newValue in
                    if newValue.count > memoLimit {
                        viewModel.draftMemo = String(newValue.prefix(memoLimit))
                    }
                }
        }
    }

    // MARK: - 사진(추억 핀 전용, 선택)

    private var photoSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionLabel("사진 (선택)")
            if let photo = viewModel.draftPhoto {
                ZStack(alignment: .topTrailing) {
                    Image(uiImage: photo)
                        .resizable()
                        .scaledToFill()
                        .frame(maxWidth: .infinity)
                        .frame(height: 180)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    Button { viewModel.draftPhoto = nil } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 22))
                            .foregroundStyle(.white, .black.opacity(0.45))
                            .padding(8)
                    }
                    .disabled(viewModel.isCreating)
                }
            } else {
                Button { showPhotoPicker = true } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "plus")
                        Text("사진 추가")
                    }
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.inkSoft)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 18)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .strokeBorder(WGColor.hairline, style: StrokeStyle(lineWidth: 1.5, dash: [5]))
                    )
                }
                .disabled(viewModel.isCreating)
            }
        }
    }

    // MARK: - 릴스 링크(선택, https:// 검증 — 웹 정합)

    private var instagramSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionLabel("릴스 링크 (선택)")
            TextField("https://instagram.com/...", text: $viewModel.draftInstagram)
                .font(WGFont.sans(14))
                .foregroundStyle(WGColor.ink)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(WGColor.panel)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12)
                    .stroke(urlError == nil ? WGColor.hairline : WGColor.pinNew, lineWidth: 1))
                .onChange(of: viewModel.draftInstagram) { _, newValue in
                    urlError = Self.validateUrl(newValue)
                }
            if let urlError {
                Text(urlError)
                    .font(WGFont.sans(12))
                    .foregroundStyle(WGColor.pinNew)
            }
        }
    }

    // MARK: - 공통

    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(WGFont.sansSemiBold(13))
            .foregroundStyle(WGColor.inkSoft)
    }

    /// 릴스 링크 형식 검증(웹 정합) — 빈값은 통과(선택), 값이 있으면 https:// 로 시작해야 한다.
    private static func validateUrl(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return nil }
        if !trimmed.hasPrefix("https://") { return "올바른 URL 형식이 아니에요 (https:// 로 시작)" }
        return nil
    }

    private static func tagColor(_ tag: PinTag) -> Color {
        switch tag {
        case .REEL: return WGColor.pinReel
        case .WISH: return WGColor.pinWish
        case .MEMORY: return WGColor.pinMemory
        }
    }

    /// 태그 글리프(웹 markers 정합): 발견=점, 위시=별, 추억=하트.
    private static func tagIcon(_ tag: PinTag) -> String {
        switch tag {
        case .REEL: return "circle.fill"
        case .WISH: return "star.fill"
        case .MEMORY: return "heart.fill"
        }
    }

    /// 태그 라벨(웹 PinTag 정합): REEL=발견, WISH=위시, MEMORY=추억.
    private static func tagLabel(_ tag: PinTag) -> String {
        switch tag {
        case .REEL: return "발견"
        case .WISH: return "위시"
        case .MEMORY: return "추억"
        }
    }
}

/// .fullScreenCover(item:) 용 Identifiable 래퍼(UIImage 는 Identifiable 아님).
private struct PickedFormImage: Identifiable {
    let id = UUID()
    let image: UIImage
}
