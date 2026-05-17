import { apiFetch } from "./http-client";
import type { PlaceSearchResponse } from "./types";

/**
 * 키워드로 장소를 검색한다. Client Component에서 호출되며,
 * BFF 프록시(`app/api/[...path]/route.ts`)를 통해 백엔드로 전달된다.
 */
export async function searchPlaces(
  keyword: string,
): Promise<PlaceSearchResponse> {
  const query = encodeURIComponent(keyword);
  return apiFetch<PlaceSearchResponse>(`/places/search?q=${query}`);
}
