import { apiFetch } from "./http-client";

/**
 * 클라이언트 전용 그룹 API.
 *
 * `lib/api/group.ts`(server)는 `next/headers`(cookies) 에 의존하므로
 * 클라이언트 번들에 섞이지 않도록 본 파일을 분리한다.
 */

/**
 * 그룹 생성 응답 — 백엔드 `GroupV1Dto.GroupCreatedResponse` 와 1:1.
 * 생성자는 자동으로 OWNER 멤버로 가입된다.
 */
export interface GroupCreatedResponse {
  groupId: number;
  name: string;
  createdAt: string;
}

/**
 * 신규 그룹 생성. 클라이언트 컴포넌트 전용.
 */
export async function createGroup(name: string): Promise<GroupCreatedResponse> {
  return apiFetch<GroupCreatedResponse>("/groups", {
    method: "POST",
    body: JSON.stringify({ name }),
  });
}

/**
 * 그룹 초대 링크 발급 응답.
 * 백엔드 `GroupV1Dto.InviteLinkResponse`와 1:1 대응.
 *
 * - `token`: 기존 UUID 토큰 (accept API 호출 키, BC).
 * - `slug`: base56 8자 단축 슬러그.
 * - `shareUrl`: `${app.invite.share-base-url}/invite/{slug}` 단축 공유 URL.
 * - `expiresAt`: 만료 시각 (UTC ISO). TTL 7일.
 */
export interface InviteLinkResponse {
  token: string;
  slug: string;
  expiresAt: string;
  shareUrl: string;
}

/**
 * 초대 링크 공개 미리보기 응답. 백엔드 `GroupV1Dto.InviteLinkPreviewResponse`.
 */
export interface InviteLinkPreviewResponse {
  token: string;
  groupName: string;
  inviterNickname: string;
  expiresAt: string;
}

/**
 * 그룹 초대 링크 합류 응답.
 * 백엔드 `GroupV1Dto.InviteLinkAcceptResponse`와 1:1 대응.
 */
export interface InviteLinkAcceptResponse {
  groupId: number;
  acceptedAt: string;
}

/**
 * 활성 그룹에 대한 초대 링크 발급. 클라이언트 컴포넌트 전용. (TTL 7일)
 */
export async function issueInviteLink(
  groupId: number,
): Promise<InviteLinkResponse> {
  return apiFetch<InviteLinkResponse>(
    `/groups/${groupId}/invite-links`,
    {
      method: "POST",
    },
  );
}

/**
 * 초대 링크 token으로 그룹 합류. 클라이언트 컴포넌트 전용.
 */
export async function acceptInviteLink(
  token: string,
): Promise<InviteLinkAcceptResponse> {
  return apiFetch<InviteLinkAcceptResponse>(
    `/groups/invite-links/${encodeURIComponent(token)}/accept`,
    {
      method: "POST",
    },
  );
}

/**
 * 현재 사용자가 해당 그룹에서 탈퇴. 클라이언트 컴포넌트 전용.
 * 백엔드는 204 No Content 응답.
 */
export async function leaveGroup(groupId: number): Promise<void> {
  await apiFetch<void>(`/groups/${groupId}/members/me`, {
    method: "DELETE",
  });
}

/**
 * 단축 슬러그로 초대 링크 미리보기. 인증 불필요(공개).
 * 만료/소진/없음 → ApiError(404 INVITE_LINK_NOT_FOUND), 레이트리밋 → 429 INVITE_LINK_RATE_LIMITED.
 */
export async function getInviteLinkPreview(
  slug: string,
): Promise<InviteLinkPreviewResponse> {
  return apiFetch<InviteLinkPreviewResponse>(
    `/groups/invite-links/by-slug/${encodeURIComponent(slug)}`,
    { method: "GET" },
  );
}
