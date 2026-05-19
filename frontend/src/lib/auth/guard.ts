import "server-only";

import { redirect } from "next/navigation";

import type { UserResponse } from "../api/auth";
import { getMyActiveGroup } from "../api/group";
import { ApiError } from "../api/http-client";
import type { ActiveGroupResponse } from "../api/types";
import { getCurrentUserServer } from "../api/user-server";

function buildLoginRedirect(returnUrl?: string): never {
  // EC-004: returnUrl이 있으면 보호 페이지에서 튕긴 케이스(세션 만료 가능성) →
  // `error=session_expired`를 함께 전달하여 LoginClient가 안내를 띄울 수 있게 함.
  // returnUrl이 없으면 첫 진입(미인증) 흐름이므로 에러 파라미터 생략.
  if (returnUrl) {
    redirect(
      `/login?error=session_expired&returnUrl=${encodeURIComponent(returnUrl)}`,
    );
  }
  redirect("/login");
}

/**
 * 로그인 필수 페이지의 server component에서 호출.
 * 미인증(401) 시 `/login`으로 리다이렉트한다.
 */
export async function requireAuth(returnUrl?: string): Promise<UserResponse> {
  try {
    return await getCurrentUserServer();
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      buildLoginRedirect(returnUrl);
    }
    throw e;
  }
}

/**
 * 로그인 + 활성 그룹 필수 페이지에서 호출.
 * 미인증 시 `/login`, 그룹 미가입 시 `/onboarding/group-start`로 리다이렉트한다.
 */
export async function requireAuthAndGroup(
  returnUrl?: string,
): Promise<ActiveGroupResponse> {
  await requireAuth(returnUrl);
  try {
    const group = await getMyActiveGroup();
    // null 또는 undefined(parseResponse 잔여 케이스) 모두 잡아 그룹 미가입으로 처리.
    if (group == null) {
      redirect("/onboarding/group-start");
    }
    return group;
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      buildLoginRedirect(returnUrl);
    }
    throw e;
  }
}

/**
 * 인증된 사용자(활성 그룹 보유)는 지정 경로로 리다이렉트한다.
 * 로그인 / 온보딩 진입 페이지에서 사용.
 */
export async function redirectIfAuthed(target: string = "/map"): Promise<void> {
  try {
    const group = await getMyActiveGroup();
    // 그룹 보유 사용자만 redirect. 미가입(null/undefined)은 진입 허용.
    if (group != null) {
      redirect(target);
    }
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      return; // 미인증 사용자는 진입 허용
    }
    throw e;
  }
}
