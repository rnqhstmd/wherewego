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
 * 백엔드 `GroupV1Dto.InviteLinkResponse`와 1:1 대응. 24h TTL.
 */
export interface InviteLinkResponse {
  token: string;
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
 * 활성 그룹에 대한 초대 링크 발급. 클라이언트 컴포넌트 전용. (24h TTL)
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
