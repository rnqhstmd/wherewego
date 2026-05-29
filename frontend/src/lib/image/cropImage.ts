import type { Area } from "react-easy-crop";

/**
 * 추억핀 사진 1:1 크롭 유틸 (작업 1).
 *
 * `react-easy-crop` 의 `onCropComplete` 가 화면 표시 기준으로 계산한 `croppedAreaPixels`
 * (원본 픽셀 좌표계의 정사각 영역)를 받아 canvas 로 잘라 정사각 JPEG `File` 을 만든다.
 * - 결과는 항상 정사각(width === height)이므로 이후 표시에서 잘림이 없다.
 * - `image/jpeg`, 품질 0.92 로 재인코딩한다. 추가 다운스케일/방향보정은 호출처가
 *   `compressPinPhoto` 로 이어서 수행한다(크롭 → compress 순서).
 *
 * 디코딩/캔버스 실패 시 예외를 throw 한다 — 호출처(PinPhotoUploader)가 안내한다.
 */
export async function getCroppedSquareFile(
  file: File,
  croppedAreaPixels: Area,
): Promise<File> {
  const image = await loadImage(file);
  const objectUrl = image.dataset.objectUrl;

  try {
    const size = Math.round(
      Math.min(croppedAreaPixels.width, croppedAreaPixels.height),
    );
    const canvas = document.createElement("canvas");
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      throw new Error("canvas 2d context 를 얻지 못했어요.");
    }

    ctx.drawImage(
      image,
      croppedAreaPixels.x,
      croppedAreaPixels.y,
      size,
      size,
      0,
      0,
      size,
      size,
    );

    const blob = await new Promise<Blob | null>((resolve) => {
      canvas.toBlob((b) => resolve(b), "image/jpeg", 0.92);
    });
    if (!blob) {
      throw new Error("이미지를 인코딩하지 못했어요.");
    }

    return new File([blob], file.name, { type: "image/jpeg" });
  } finally {
    if (objectUrl) URL.revokeObjectURL(objectUrl);
  }
}

/** File → HTMLImageElement 디코딩 (object URL 사용, 정리는 호출처가 dataset 으로 처리). */
function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      img.dataset.objectUrl = url;
      resolve(img);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("이미지를 불러오지 못했어요."));
    };
    img.src = url;
  });
}
