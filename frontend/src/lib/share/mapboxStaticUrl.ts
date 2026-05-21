// Phase 9: Mapbox Static Images API URL 빌더.
// renderPinCard가 카드 배경(blur 처리 전 원본)을 다운받는 URL을 구성한다.

export interface MapboxStaticParams {
  lat: number;
  lng: number;
  width: number;
  height: number;
  zoom: number;
  token: string;
  /** null/undefined 면 mapbox/streets-v12 로 폴백 */
  styleId?: string | null;
}

const DEFAULT_STYLE_ID = "mapbox/streets-v12";

/**
 * `mapbox://styles/{user}/{styleId}` 형태에서 `{user}/{styleId}` 추출.
 * null/undefined/형식 불일치는 `mapbox/streets-v12` 폴백 (page.tsx:19 와 일치).
 */
export function extractStyleId(
  mapboxStyleUrl: string | null | undefined,
): string {
  if (!mapboxStyleUrl) {
    return DEFAULT_STYLE_ID;
  }
  const match = mapboxStyleUrl.match(/^mapbox:\/\/styles\/([^/]+)\/([^/]+)$/);
  if (!match) {
    return DEFAULT_STYLE_ID;
  }
  return `${match[1]}/${match[2]}`;
}

/**
 * Static Images API URL 생성.
 * 포맷:
 *   https://api.mapbox.com/styles/v1/{styleId}/static/{lng},{lat},{zoom},0/{width}x{height}?access_token={token}
 *
 * 좌표는 toFixed(6) 자릿수 안정화. token은 URL 인코딩.
 */
export function buildMapboxStaticUrl(params: MapboxStaticParams): string {
  const { lat, lng, width, height, zoom, token } = params;
  const styleId =
    params.styleId && params.styleId.length > 0
      ? params.styleId
      : DEFAULT_STYLE_ID;
  const lngFixed = lng.toFixed(6);
  const latFixed = lat.toFixed(6);
  const encodedToken = encodeURIComponent(token);
  return `https://api.mapbox.com/styles/v1/${styleId}/static/${lngFixed},${latFixed},${zoom},0/${width}x${height}?access_token=${encodedToken}`;
}
