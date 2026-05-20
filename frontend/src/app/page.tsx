import { ApiError } from "@/lib/api/http-client";
import { getMyActiveGroup } from "@/lib/api/group";
import { SplashScreen } from "@/components/ui/SplashScreen";

// 인증 분기는 server-side에서 수행한다. 그 후 클라이언트 SplashScreen이
// 1.5초 타이머 + router.replace 로 실제 이동을 처리한다.
// - JWT 없음/만료(401) → /login
// - 활성 그룹 있음 → /map
// - 활성 그룹 없음 → /onboarding/group-start
export const dynamic = "force-dynamic"; // 매 요청 fresh 분기 필요

export default async function RootPage() {
  let redirectTo = "/login";
  try {
    const group = await getMyActiveGroup();
    redirectTo = group !== null ? "/map" : "/onboarding/group-start";
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      redirectTo = "/login";
    } else {
      // 그 외 오류는 일단 /login으로 폴백
      redirectTo = "/login";
    }
  }
  return <SplashScreen redirectTo={redirectTo} />;
}
