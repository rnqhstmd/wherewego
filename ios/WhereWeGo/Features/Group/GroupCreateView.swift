import SwiftUI

// 그룹 생성 화면(GP-1 FR-1, 웹 NewGroupClient 이식). 그룹명 입력 + 대표 이미지(선택) 지정.
// 생성 = 2단계: ① POST /groups(그룹 생성) ② 크롭 이미지가 있으면 uploadGroupImage(신규 groupId).
//  ② 실패해도 그룹 진입은 정상 진행 + "그룹 관리에서 다시 올릴 수 있어요" 안내(그룹은 이미 만들어졌으므로 막지 않는다).
// 이미지 자리(120pt): 미선택 시 카메라 아이콘 placeholder 원, 선택 시 크롭 결과 원형 프리뷰. 탭 = 피커 → 원형 크롭(BR-1).
// 완료(onCreated)는 호출측(MainTabView/OnboardingRouter)이 목록 갱신/진입으로 위임한다(VM 은 화면 결합 회피).
struct GroupCreateView: View {
    private let groupAPI: GroupAPIProtocol
    /// 생성 성공 콜백(groupId). 호출측이 그룹 목록 갱신/진입·시트 닫기로 위임. 이미지 업로드 성패와 무관하게 호출(그룹은 생성됨).
    private let onCreated: (Int) -> Void

    @State private var name = ""
    @State private var errorMessage: String?
    @State private var submitting = false
    /// 크롭 완료된 대표 이미지(선택). nil = 미지정 → 콜라주 폴백.
    @State private var croppedImage: UIImage?
    /// 사진 피커 시트 트리거.
    @State private var showPhotoPicker = false
    /// 피커에서 고른 원본(크롭 fullScreenCover 트리거).
    @State private var pickedImage: PickedGroupImage?
    /// 이미지 업로드만 실패한 그룹 id(PR#123 리뷰). alert 확인 후 onCreated 호출 — 즉시 닫으면 안내를 못 본다.
    @State private var imageFailedGroupId: Int?

    init(groupAPI: GroupAPIProtocol, onCreated: @escaping (Int) -> Void) {
        self.groupAPI = groupAPI
        self.onCreated = onCreated
    }

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var isValid: Bool { trimmed.count >= 1 && trimmed.count <= 20 }
    private var canSubmit: Bool { isValid && !submitting }

    var body: some View {
        ZStack {
            WGColor.bg.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 0) {
                Text("새 지도를 만들어요\n어떤 이름이 좋을까요?")
                    .font(WGFont.emo(28))
                    .tracking(-1)   // 웹 NewGroupClient letterSpacing:-1 (AC-7)
                    .foregroundStyle(WGColor.ink)
                    .fixedSize(horizontal: false, vertical: true)

                Text("함께하는 사람들과 공유할 지도의 이름이에요.")
                    .font(WGFont.sans(14))
                    .foregroundStyle(WGColor.inkSoft)
                    .padding(.top, 12)

                // 대표 이미지 자리(120pt 원형). 미선택 = 카메라 placeholder, 선택 = 크롭 프리뷰. 탭 = 피커→원형 크롭.
                imagePicker
                    .padding(.top, 32)
                    .frame(maxWidth: .infinity, alignment: .center)

                // 그룹명 입력(웹: cta 하단 보더 + Gowun Batang).
                VStack(alignment: .leading, spacing: 8) {
                    TextField("예: 우리집 데이트 지도", text: $name)
                        .font(WGFont.emo(22))
                        .foregroundStyle(WGColor.ink)
                        .submitLabel(.done)
                        .onSubmit { Task { await submit() } }
                        .onChange(of: name) { _, newValue in
                            // 20자 제한(웹 slice(0,20) 동치).
                            if newValue.count > 20 { name = String(newValue.prefix(20)) }
                        }
                    Rectangle()
                        .fill(WGColor.cta)
                        .frame(height: 2)
                    Text("1~20자")
                        .font(WGFont.sans(12))
                        .foregroundStyle(isValid || name.isEmpty ? WGColor.inkSoft : WGColor.cta)
                }
                .padding(.top, 32)

                if let errorMessage {
                    Text(errorMessage)
                        .font(WGFont.sans(13))
                        .foregroundStyle(WGColor.cta)
                        .padding(.top, 12)
                }

                Spacer()

                Button {
                    Task { await submit() }
                } label: {
                    Text(submitting ? "만드는 중..." : "만들기")
                        .font(WGFont.sans(15))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(canSubmit ? WGColor.cta : WGColor.inkFaint)
                        .foregroundStyle(WGColor.panel)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .disabled(!canSubmit)
            }
            .padding(EdgeInsets(top: 70, leading: 28, bottom: 32, trailing: 28))
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        // 사진 피커 → 원형 크롭(PinDetailContent 선례). 크롭 완료 시 croppedImage 보관(생성 시 업로드).
        .sheet(isPresented: $showPhotoPicker) {
            PhotoPickerView(
                onPicked: { pickedImage = PickedGroupImage(image: $0) },
                onDismiss: { showPhotoPicker = false }
            )
            .ignoresSafeArea()
        }
        .fullScreenCover(item: $pickedImage) { picked in
            SquareCropView(
                image: picked.image,
                maskShape: .circle,   // 프사·그룹 이미지는 원형 가이드(BR-1, 결과물은 1:1 정사각).
                onCropped: { cropped in
                    pickedImage = nil
                    croppedImage = cropped
                },
                onCancel: { pickedImage = nil }
            )
        }
        // 이미지 업로드만 실패(그룹은 생성됨, PR#123 리뷰): 즉시 닫지 않고 alert 로 안내 → 확인 시 진입 진행.
        //  (기존엔 errorMessage 설정 직후 onCreated 로 dismiss 되어 사용자가 안내를 못 봤다.)
        //  presenting 패턴(cross-review): dismiss 가 버튼 액션보다 먼저 state 를 nil 로 만들어도
        //  groupId 가 액션 클로저 파라미터로 캡처되어 onCreated 가 유실되지 않는다.
        .alert(
            "그룹은 만들어졌어요",
            isPresented: Binding(
                get: { imageFailedGroupId != nil },
                set: { if !$0 { imageFailedGroupId = nil } }
            ),
            presenting: imageFailedGroupId
        ) { groupId in
            Button("확인") {
                onCreated(groupId)
            }
        } message: { _ in
            Text("대표 이미지는 올리지 못했어요.\n그룹 관리에서 다시 올릴 수 있어요.")
        }
    }

    // MARK: - 대표 이미지 자리(120pt)

    @ViewBuilder
    private var imagePicker: some View {
        Button {
            showPhotoPicker = true
        } label: {
            Group {
                if let croppedImage {
                    Image(uiImage: croppedImage)
                        .resizable()
                        .scaledToFill()
                } else {
                    // 미선택 placeholder — 카메라 아이콘 원(panel 배경 + hairline 테두리).
                    ZStack {
                        WGColor.panel
                        Image(systemName: "camera.fill")
                            .font(.system(size: 28))
                            .foregroundStyle(WGColor.inkFaint)
                    }
                }
            }
            .frame(width: 120, height: 120)
            .clipShape(Circle())
            .overlay(Circle().stroke(WGColor.hairline, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
    }

    // MARK: - 생성(2단계)

    /// ① 그룹 생성(POST) ② 크롭 이미지 있으면 업로드. ② 실패는 alert 안내 후 진입(PR#123 리뷰 — 즉시 dismiss 금지).
    private func submit() async {
        guard canSubmit else { return }
        submitting = true
        errorMessage = nil
        do {
            let created = try await groupAPI.createGroup(name: trimmed)
            // ② 대표 이미지 업로드(선택). 실패해도 그룹은 만들어졌으므로 진입은 진행하되,
            //    바로 onCreated(dismiss)하면 안내를 못 보므로 alert 확인 후 진입(설계 §2.3 + PR#123 리뷰).
            if let croppedImage, let jpeg = ImageCropper.resizeAndCompress(croppedImage) {
                do {
                    _ = try await groupAPI.uploadGroupImage(groupId: created.groupId, jpegData: jpeg)
                } catch {
                    submitting = false   // alert 대기 동안 버튼 재활성 방지 해제는 불필요하나 상태 정합 유지.
                    imageFailedGroupId = created.groupId
                    return
                }
            }
            onCreated(created.groupId)
        } catch {
            errorMessage = "그룹을 만들지 못했어요. 잠시 후 다시 시도해 주세요."
            submitting = false   // 그룹 생성 자체 실패 — 재시도 가능하도록 해제(성공 경로는 onCreated 가 화면 전환).
        }
    }
}

/// .fullScreenCover(item:) 용 Identifiable 래퍼(UIImage 자체는 Identifiable 아님). PinDetailContent.PickedImage 동치.
private struct PickedGroupImage: Identifiable {
    let id = UUID()
    let image: UIImage
}
