import { getMyActiveGroup } from "@/lib/api/group";
import { listPins } from "@/lib/api/pin";
import { NoGroupGuide } from "@/app/pins/_components/NoGroupGuide";
import MapClient from "./MapClient";
import MapLoadError from "./_components/MapLoadError";

export const dynamic = "force-dynamic";

/**
 * /map 라우트 진입점 (Server Component).
 *
 * - NEXT_PUBLIC_MAPBOX_TOKEN 미설정 시 빌드 fail-fast 대신 MapLoadError 표시 (설계 §1).
 * - 활성 그룹이 없으면 기존 NoGroupGuide 재사용.
 * - 활성 그룹이 있으면 초기 핀 목록을 fetch 하여 MapClient에 주입.
 */
export default async function MapPage() {
  const token = process.env.NEXT_PUBLIC_MAPBOX_TOKEN;
  const styleUrl = process.env.NEXT_PUBLIC_MAPBOX_STYLE_URL ?? null;

  if (!token) {
    return <MapLoadError reason="TOKEN_MISSING" />;
  }

  const group = await getMyActiveGroup();
  if (!group) {
    return <NoGroupGuide />;
  }

  const pinList = await listPins(group.groupId);

  return (
    <MapClient
      initialPins={pinList.items}
      groupId={group.groupId}
      groupName={group.name}
      mapboxToken={token}
      mapboxStyleUrl={styleUrl}
    />
  );
}
