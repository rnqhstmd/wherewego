import { apiFetch } from "./http-client";
import type { PinListResponse } from "./types";

/**
 * 클라이언트 컴포넌트에서 호출 가능한 그룹 핀 목록 조회.
 *
 * <p>{@code lib/api/pin.ts} 의 {@code listPins} 는 {@code next/headers} {@code cookies()}
 * 에 의존하는 server-only 함수이므로 브라우저 런타임에서 호출할 수 없다.
 * 이 함수는 {@link apiFetch} 기반으로 {@code /api/v1/...} same-origin 호출 →
 * Next.js BFF route ({@code app/api/[...path]/route.ts}) 가 백엔드로 프록시하면서
 * 쿠키를 자동 부착한다. {@code searchPlaces} 가 이미 동일 패턴으로 운영 중.</p>
 *
 * <p>polling 등 주기적 fetch 에서 직전 in-flight 요청을 취소할 수 있도록
 * {@link AbortSignal} 을 받는다.</p>
 */
export async function listPinsClient(
  groupId: number,
  signal?: AbortSignal,
): Promise<PinListResponse> {
  return apiFetch<PinListResponse>(`/groups/${groupId}/pins`, { signal });
}
