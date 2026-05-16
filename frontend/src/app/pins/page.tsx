import { getMyActiveGroup } from "@/lib/api/group";
import { listPins } from "@/lib/api/pin";

import { PinListClient } from "./PinListClient";
import { NoGroupGuide } from "./_components/NoGroupGuide";

export const dynamic = "force-dynamic";

export default async function PinsPage() {
  const group = await getMyActiveGroup();
  if (!group) {
    return <NoGroupGuide />;
  }

  const list = await listPins(group.groupId);
  return (
    <PinListClient
      initialPins={list.items}
      groupId={group.groupId}
      groupName={group.name}
    />
  );
}
