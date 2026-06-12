import SwiftUI

// 1:1 자작 크롭 화면(설계 §6, FR-17). 이미지 위 정사각 크롭 프레임 고정 +
// 이미지 드래그(offset)·핀치 줌(scale) 제스처. 확인 시 표시영역→원본 픽셀 crop rect 계산 →
// ImageCropper.crop(image, rect) → 콜백. 취소 시 콜백 없이 닫힘.
//
// 좌표 모델: 정사각 크롭 프레임(한 변 = cropSide)을 화면 중앙에 고정한다.
// 이미지는 "aspect fill" 기준 base 스케일로 프레임을 가득 채운 뒤, 사용자 scale/offset 으로 추가 변형한다.
// 확인 시 프레임이 덮는 이미지 표시 좌표 → base 스케일 역산으로 원본 픽셀 rect 를 구한다.
//
// maskShape(GP-1 BR-1): 가이드 마스크·테두리 모양만 바꾼다(.square 핀 / .circle 프사·그룹). 크롭 rect 계산
// (makeCroppedImage)은 양쪽 모두 정사각 프레임 기준이므로 결과물은 항상 1:1 정사각 UIImage 다(서버 webp 썸네일이 원형 클립).

/// 크롭 가이드 마스크 모양(GP-1 §2.1). 결과물 크롭 영역은 동일하며 가이드/테두리 시각만 분기.
enum CropMask {
    case square
    case circle
}

struct SquareCropView: View {
    /// 크롭 대상 원본 이미지.
    let image: UIImage
    /// 가이드 마스크 모양. 기본 .square(기존 핀 호출부 무변경). .circle 은 프사·그룹 이미지용.
    var maskShape: CropMask = .square
    /// 크롭 완료 콜백(1:1 결과 UIImage).
    let onCropped: (UIImage) -> Void
    /// 취소 콜백.
    let onCancel: () -> Void

    // 확정(commit) 제스처 누적값. 크롭 rect 계산은 항상 이 값만 사용한다(취소 안전).
    @State private var scale: CGFloat = 1
    @State private var offset: CGSize = .zero
    // 진행 중 제스처 임시 누적값(@GestureState). .updating 으로만 반영되고,
    // 제스처 취소/인터럽트 시 시스템이 자동으로 초기값(.zero / 1)으로 리셋한다(다음 제스처 점프 방지).
    // 임시값은 표시 transform 에만 합산하고, 크롭 rect 계산(makeCroppedImage)은 commit 값(scale/offset)만 사용한다(취소 안전).
    @GestureState private var dragTranslation: CGSize = .zero
    @GestureState private var pinchScale: CGFloat = 1

    var body: some View {
        GeometryReader { geo in
            let cropSide = min(geo.size.width, geo.size.height) * 0.82
            let effectiveScale = scale * pinchScale
            let effectiveOffset = CGSize(
                width: offset.width + dragTranslation.width,
                height: offset.height + dragTranslation.height
            )

            ZStack {
                Color.black.ignoresSafeArea()

                imageLayer(cropSide: cropSide, effectiveScale: effectiveScale, effectiveOffset: effectiveOffset)
                    .gesture(magnification)
                    .gesture(drag)

                cropMask(in: geo.size, cropSide: cropSide)

                controls(geo: geo, cropSide: cropSide)
            }
        }
        .background(Color.black)
    }

    // MARK: - 이미지 레이어

    private func imageLayer(cropSide: CGFloat, effectiveScale: CGFloat, effectiveOffset: CGSize) -> some View {
        // base: 이미지를 크롭 프레임에 aspect fill 로 맞춘 표시 크기(짧은 변이 cropSide 와 일치).
        let base = baseDisplaySize(cropSide: cropSide)
        return Image(uiImage: image)
            .resizable()
            .frame(width: base.width, height: base.height)
            .scaleEffect(effectiveScale)
            .offset(effectiveOffset)
    }

    // MARK: - 크롭 마스크(정사각 프레임 + 외곽 딤)

    private func cropMask(in size: CGSize, cropSide: CGFloat) -> some View {
        ZStack {
            Color.black.opacity(0.55)
                .mask(
                    ZStack {
                        Rectangle()
                        // 가이드 구멍 — .circle 이면 원, .square 면 라운드 사각(크롭 영역 자체는 동일).
                        guideShape
                            .frame(width: cropSide, height: cropSide)
                            .blendMode(.destinationOut)
                    }
                    .compositingGroup()
                )
                .ignoresSafeArea()
                .allowsHitTesting(false)

            guideShape
                .stroke(Color.white.opacity(0.9), lineWidth: 2)
                .frame(width: cropSide, height: cropSide)
                .allowsHitTesting(false)
        }
    }

    /// 가이드 마스크·테두리 모양(maskShape 분기). 둘 다 InsettableShape 라 .stroke 가능하도록 AnyShape 로 통일.
    private var guideShape: AnyShape {
        switch maskShape {
        case .square: return AnyShape(RoundedRectangle(cornerRadius: 8))
        case .circle: return AnyShape(Circle())
        }
    }

    // MARK: - 상하단 컨트롤

    private func controls(geo: GeometryProxy, cropSide: CGFloat) -> some View {
        VStack {
            HStack {
                Button(action: onCancel) {
                    Text("취소")
                        .font(WGFont.sans(15))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                }
                Spacer()
                Button {
                    onCropped(makeCroppedImage(cropSide: cropSide))
                } label: {
                    Text("확인")
                        .font(WGFont.sans(15))
                        .foregroundStyle(WGColor.cta)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                }
            }
            .padding(.horizontal, 8)
            .padding(.top, 4)

            Spacer()

            Text("드래그·핀치로 영역을 맞춰 주세요")
                .font(WGFont.sans(13))
                .foregroundStyle(.white.opacity(0.85))
                .padding(.bottom, 28)
        }
    }

    // MARK: - 제스처

    // .updating 으로 진행 중 배율을 @GestureState(pinchScale)에 반영하고(제스처 종료/취소 시 자동 1 리셋),
    // onEnded 에서만 commit(scale)에 곱해 누적한다. 취소로 onEnded 가 누락돼도 commit 값은 그대로 보존된다(크롭 rect 안전).
    private var magnification: some Gesture {
        MagnificationGesture()
            .updating($pinchScale) { value, state, _ in
                state = value
            }
            .onEnded { value in
                scale = max(1, scale * value)
            }
    }

    // .updating 으로 진행 중 이동량을 @GestureState(dragTranslation)에 반영하고(제스처 종료/취소 시 자동 .zero 리셋),
    // onEnded 에서만 commit(offset)에 더해 누적한다.
    private var drag: some Gesture {
        DragGesture()
            .updating($dragTranslation) { value, state, _ in
                state = value.translation
            }
            .onEnded { value in
                offset = CGSize(
                    width: offset.width + value.translation.width,
                    height: offset.height + value.translation.height
                )
            }
    }

    // MARK: - 좌표 계산

    /// 이미지를 cropSide 정사각 프레임에 aspect fill 시킨 표시 크기(scale=1 기준).
    /// 짧은 변이 cropSide 와 같아지도록 비율 유지.
    private func baseDisplaySize(cropSide: CGFloat) -> CGSize {
        let w = image.size.width
        let h = image.size.height
        guard w > 0, h > 0 else { return CGSize(width: cropSide, height: cropSide) }
        let fillScale = cropSide / min(w, h)
        return CGSize(width: w * fillScale, height: h * fillScale)
    }

    /// 현재 scale/offset 으로 크롭 프레임이 덮는 원본 픽셀 rect 를 계산해 크롭.
    /// 표시 좌표계: 이미지 중심이 프레임 중심 + offset 에 위치, 표시 크기 = base * scale.
    /// 프레임(중앙, 한 변 cropSide)의 좌상단을 표시 이미지 좌상단 기준 상대좌표로 환산 후
    /// 표시→원본 픽셀 비율(displayPerPixel)로 나눠 원본 rect 를 얻는다.
    private func makeCroppedImage(cropSide: CGFloat) -> UIImage {
        let base = baseDisplaySize(cropSide: cropSide)
        let displayW = base.width * scale
        let displayH = base.height * scale

        // 표시 이미지 좌상단(프레임 중심 기준 좌표). 프레임 중심 = (0,0).
        let imageOriginX = -displayW / 2 + offset.width
        let imageOriginY = -displayH / 2 + offset.height
        // 크롭 프레임 좌상단(프레임 중심 기준).
        let frameOriginX = -cropSide / 2
        let frameOriginY = -cropSide / 2

        // 프레임 좌상단을 표시 이미지 좌상단 기준 상대 표시좌표로 환산.
        let relX = frameOriginX - imageOriginX
        let relY = frameOriginY - imageOriginY

        // 표시 좌표 → 원본 픽셀 변환 비율(원본 px 당 표시 pt).
        let displayPerPixelX = displayW / image.size.width
        let displayPerPixelY = displayH / image.size.height
        guard displayPerPixelX > 0, displayPerPixelY > 0 else { return image }

        var pxRect = CGRect(
            x: relX / displayPerPixelX,
            y: relY / displayPerPixelY,
            width: cropSide / displayPerPixelX,
            height: cropSide / displayPerPixelY
        )
        // 원본 경계로 클램프(프레임이 이미지 밖을 덮은 경우 안전 보정).
        pxRect = clamp(pxRect, to: image.size)

        return ImageCropper.crop(image, to: pxRect) ?? image
    }

    /// rect 를 (0,0,size) 경계 안으로 클램프.
    private func clamp(_ rect: CGRect, to size: CGSize) -> CGRect {
        let x = max(0, min(rect.origin.x, size.width))
        let y = max(0, min(rect.origin.y, size.height))
        let w = min(rect.size.width, size.width - x)
        let h = min(rect.size.height, size.height - y)
        return CGRect(x: x, y: y, width: max(0, w), height: max(0, h))
    }
}
