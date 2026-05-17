/**
 * Mapbox Geocoding API 역지오코딩 — 좌표 → 주소.
 *
 * 정책: 한국 영역 좌표는 한국어(`language=ko`), 그 외는 영어(`language=en`).
 * 디자인 번들의 정보창 사양 (`Seongsu-dong, Seongdong-gu, Seoul` 등 영어 주소)과 정렬한다.
 */

const ENDPOINT = "https://api.mapbox.com/geocoding/v5/mapbox.places";

/**
 * 대한민국 대략 bounding box (남단 33.0, 북단 38.7, 서단 124.5, 동단 132.0).
 * 휴전선 이북·해상 일부는 한국 좌표로 분류된다 — 단순 분기 목적.
 */
const KOREA_BBOX = { minLat: 33.0, maxLat: 38.7, minLng: 124.5, maxLng: 132.0 };

export function isKoreaCoord(lng: number, lat: number): boolean {
  return (
    lat >= KOREA_BBOX.minLat &&
    lat <= KOREA_BBOX.maxLat &&
    lng >= KOREA_BBOX.minLng &&
    lng <= KOREA_BBOX.maxLng
  );
}

export interface ReverseGeocodeResult {
  /** 사용자에게 보여줄 한 줄 주소. 없으면 null. */
  address: string | null;
  /** 지명/POI 후보 (있으면). 핀 placeName 기본값 후보. */
  placeName: string | null;
  /** 응답에 사용된 언어 코드. */
  language: "ko" | "en";
}

/**
 * Mapbox Geocoding API 역지오코딩 호출.
 *
 * @param lng 경도
 * @param lat 위도
 * @param token NEXT_PUBLIC_MAPBOX_TOKEN
 * @param signal AbortSignal — 좌표 변경 중 이전 요청 취소
 */
export async function reverseGeocode(
  lng: number,
  lat: number,
  token: string,
  signal?: AbortSignal,
): Promise<ReverseGeocodeResult> {
  const language: "ko" | "en" = isKoreaCoord(lng, lat) ? "ko" : "en";
  const url =
    `${ENDPOINT}/${lng},${lat}.json` +
    `?access_token=${encodeURIComponent(token)}` +
    `&language=${language}` +
    `&types=address,poi,place,neighborhood` +
    `&limit=1`;

  const res = await fetch(url, { signal, cache: "no-store" });
  if (!res.ok) {
    throw new Error(`Mapbox geocoding ${res.status}`);
  }
  const data: MapboxReverseResponse = await res.json();
  const feature = data.features?.[0];
  if (!feature) {
    return { address: null, placeName: null, language };
  }

  // place_name = 전체 주소(예: "Seongsu-ro 12, Seongdong-gu, Seoul, South Korea")
  // text       = 가장 가까운 단위 명칭(예: "Seongsu-ro 12")
  const address = feature.place_name?.trim() || null;
  const placeName =
    feature.properties?.address ||
    feature.text?.trim() ||
    null;
  return { address, placeName, language };
}

interface MapboxReverseResponse {
  features?: Array<{
    text?: string;
    place_name?: string;
    properties?: { address?: string };
  }>;
}
