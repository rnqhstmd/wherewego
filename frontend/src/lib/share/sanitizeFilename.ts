// Phase 9 BR-5: 파일명 sanitize.
// 파일 시스템 금지 문자/제어문자를 제거하고 공백을 "_"로 치환한다.
// 결과가 빈 문자열이면 "pin" 폴백.

export function sanitizeFilename(placeName: string): string {
  if (placeName === null || placeName === undefined || placeName === "") {
    return "pin";
  }
  // 1. 제어문자 제거 (DEL 및 \x00-\x1F 중 공백 종류 제외: \t=0x09, \n=0x0A, \r=0x0D)
  //    탭·개행은 공백류로 취급하여 4단계에서 "_"로 치환되도록 보존한다.
  // 2. 파일 시스템 금지 문자 제거 (/ \ : * ? " < > |)
  // 3. 좌우 공백 trim — "공백만" 입력이 "pin"으로 떨어지도록 치환 전에 수행
  // 4. 연속 공백류를 "_"로 치환
  const stripped = placeName
    .replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, "")
    .replace(/[/\\:*?"<>|]/g, "")
    .trim()
    .replace(/\s+/g, "_");
  if (stripped.length === 0) {
    return "pin";
  }
  return stripped;
}
