import { requireAuthAndGroup } from "@/lib/auth/guard";
import { BotConnectClient } from "./BotConnectClient";

export const dynamic = "force-dynamic";

/**
 * `/bot/connect` — 카카오톡 챗봇(MayGo 채널) 연동 6자리 코드 발급 화면.
 *
 * - 인증 + 활성 그룹 필수.
 * - 진입 시 자동으로 6자리 코드 발급 (10분 TTL).
 */
export default async function BotConnectPage() {
  await requireAuthAndGroup("/bot/connect");
  return <BotConnectClient />;
}
