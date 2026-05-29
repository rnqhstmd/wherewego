import imageCompression from "browser-image-compression";

/**
 * Phase 13 (FR-PIN-9j/k, AC-16): 추억핀 사진 클라이언트 압축 유틸.
 *
 * `browser-image-compression@2.0.2` 로 업로드 전 원본을 다운스케일/재인코딩한다.
 * - 장변 1600px 로 제한, JPEG 품질 ~0.8 로 재인코딩 (`fileType: "image/jpeg"` 으로 MIME 강제, AC-16).
 * - EXIF 방향은 라이브러리가 파일 자체 orientation 으로 자동 보정 (옵션 미지정 시 기본 동작).
 * - 웹 워커로 메인 스레드 블로킹 회피.
 * - `maxSizeMB: 1` 로 백엔드 2MB 한도 미만을 보장 (멀티파트 오버헤드 여유 포함).
 *
 * 압축 실패(디코딩 불가 등) 시 라이브러리 예외를 그대로 전파한다 — 호출처가 안내한다.
 */
export async function compressPinPhoto(file: File): Promise<File> {
  return imageCompression(file, {
    maxSizeMB: 1,
    maxWidthOrHeight: 1600,
    initialQuality: 0.8,
    fileType: "image/jpeg",
    useWebWorker: true,
  });
}
