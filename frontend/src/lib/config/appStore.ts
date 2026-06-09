// iOS App Store URL 헬퍼 — 초대 랜딩(InvitePreviewClient / InviteExpiredState) 공용.
//
// NEXT_PUBLIC_* 환경변수는 빌드타임에 인라인된다(번들에 박힘).
// 따라서 Vercel 환경변수를 변경했다면 재배포해야 반영된다(런타임 변경 X).
//
// 미설정(빈 문자열)이어도 배지는 항상 노출한다.
export const IOS_APP_URL = process.env.NEXT_PUBLIC_IOS_APP_URL ?? "";
