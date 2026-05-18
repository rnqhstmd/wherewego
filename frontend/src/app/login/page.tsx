import { redirectIfAuthed } from "@/lib/auth/guard";
import { LoginClient } from "./LoginClient";

export const dynamic = "force-dynamic";

/**
 * /login 라우트 진입점 (Server Component).
 *
 * - 이미 활성 그룹을 가진 사용자는 `/map`으로 즉시 redirect (redirectIfAuthed).
 * - 미인증/그룹 미가입 사용자는 LoginClient를 렌더링.
 */
export default async function LoginPage() {
  await redirectIfAuthed("/map");
  return <LoginClient />;
}
