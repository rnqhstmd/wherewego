import { requireAuthAndGroup } from "@/lib/auth/guard";
import { listPins } from "@/lib/api/pin";
import { getCurrentUserServer } from "@/lib/api/user-server";
import { getOnboardingStatusServer } from "@/lib/api/me-server";
import type { OnboardingStatusResponse } from "@/lib/api/me-client";
import MapClient from "./MapClient";
import MapLoadError from "./_components/MapLoadError";

export const dynamic = "force-dynamic";

/**
 * /map 라우트 진입점 (Server Component).
 *
 * - NEXT_PUBLIC_MAPBOX_TOKEN 미설정 시 빌드 fail-fast 대신 MapLoadError 표시 (설계 §1).
 * - 인증/활성 그룹 가드는 requireAuthAndGroup이 처리한다.
 *   미인증 → /login?returnUrl=/map, 그룹 미가입 → /onboarding/group-start.
 * - 활성 그룹이 있으면 초기 핀 목록 + 온보딩 상태를 fetch 하여 MapClient에 주입.
 * - 온보딩 상태 fetch 실패 시(예: PR-B 머지 전) safe default 로 폴백하여 카드 미노출.
 */
export default async function MapPage() {
  const token = process.env.NEXT_PUBLIC_MAPBOX_TOKEN;
  const styleUrl = process.env.NEXT_PUBLIC_MAPBOX_STYLE_URL ?? null;

  if (!token) {
    return <MapLoadError reason="TOKEN_MISSING" />;
  }

  const group = await requireAuthAndGroup("/map");

  const [pinList, me, onboardingStatus] = await Promise.all([
    listPins(group.groupId),
    getCurrentUserServer(),
    getOnboardingStatusServer().catch((): OnboardingStatusResponse => ({
      // PR-B 의 API 가 develop 에 머지되기 전이거나 일시적 오류 시 카드 미노출로 폴백.
      hasActiveGroup: true,
      activeGroupMemberCount: 2,
      hasBotMapping: true,
    })),
  ]);

  return (
    <MapClient
      initialPins={pinList.items}
      groupId={group.groupId}
      groupName={group.name}
      mapboxToken={token}
      mapboxStyleUrl={styleUrl}
      myNickname={me.nickname}
      myId={me.id}
      onboardingStatus={onboardingStatus}
    />
  );
}
