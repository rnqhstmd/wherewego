import "server-only";

import { apiFetchServer } from "./http";
import type { InviteLinkPreviewResponse } from "./group-client";

/**
 * Server Component 에서 단축 슬러그로 초대 링크 미리보기를 조회한다.
 *
 * 공개 엔드포인트지만 SSR 페이지의 generateMetadata + 페이지 분기에서 함께 쓰기 위해
 * 서버 fetch 헬퍼로 분리한다. 인증 쿠키가 없어도 호출 가능.
 *
 * 만료/소진/존재하지 않음은 ApiError(404 INVITE_LINK_NOT_FOUND) 로 던져진다.
 */
export async function getInviteLinkPreviewServer(
  slug: string,
): Promise<InviteLinkPreviewResponse> {
  return apiFetchServer<InviteLinkPreviewResponse>(
    `/groups/invite-links/by-slug/${encodeURIComponent(slug)}`,
  );
}
