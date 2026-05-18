import { requireAuth } from "@/lib/auth/guard";
import { LocationPermClient } from "./LocationPermClient";

/**
 * `/onboarding/location` — 카카오 로그인 직후 위치 권한 요청 화면.
 * 응답(허용/나중에) 후 콜백 흐름의 다음 단계(닉네임/그룹 시작/지도)로 진행.
 */
export default async function LocationPermPage() {
  await requireAuth();
  return <LocationPermClient />;
}
