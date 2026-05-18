import { requireAuth } from "@/lib/auth/guard";
import { NotificationClient } from "./NotificationClient";

/**
 * /onboarding/notification — 알림 권한 요청 화면 (Server wrapper).
 *
 * 미인증 시 `/login`으로 리다이렉트. 인증된 사용자만 NotificationClient 렌더.
 */
export default async function NotificationPage() {
  await requireAuth("/onboarding/notification");
  return <NotificationClient />;
}
