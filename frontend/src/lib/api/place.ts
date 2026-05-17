import { apiFetch } from "./http-client";
import type { PlaceSearchResponse } from "./types";

/**
 * 키워드로 장소를 검색한다. Client Component에서 호출되며,
 * BFF 프록시(`app/api/[...path]/route.ts`)를 통해 백엔드로 전달된다.
 *
 * @param signal 입력 도중 재호출 시 이전 in-flight 요청을 취소할 수 있는 AbortSignal.
 */
export async function searchPlaces(
  keyword: string,
  signal?: AbortSignal,
): Promise<PlaceSearchResponse> {
  const query = encodeURIComponent(keyword);
  return apiFetch<PlaceSearchResponse>(`/places/search?q=${query}`, { signal });
}
