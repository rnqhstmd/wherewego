// Phase 9: Mapbox Static Images API URL 빌더.
// renderPinCard가 카드 배경(blur 처리 전 원본)을 다운받는 URL을 구성한다.

export interface MapboxStaticMarker {
  lng: number;
  lat: number;
  /** 6자리 hex (앞 #는 자동 제거). 미지정 시 기본 회색. */
  color?: string;
  /** 'small' | 'large'. 기본 small. */
  size?: "small" | "large";
}

export interface MapboxStaticParams {
  lat: number;
  lng: number;
  width: number;
  height: number;
  zoom: number;
  token: string;
  /** null/undefined 면 mapbox/streets-v12 로 폴백 */
  styleId?: string | null;
  /** 지도 위에 그릴 마커 목록. URL 길이 한계로 최대 30개 권장. */
  markers?: MapboxStaticMarker[];
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
 * 포맷 (markers 있을 때):
 *   https://api.mapbox.com/styles/v1/{styleId}/static/{markers}/{lng},{lat},{zoom},0/{width}x{height}?access_token={token}
 * markers 없으면 markers 부분 생략.
 *
 * 좌표는 toFixed(6) 자릿수 안정화. token은 URL 인코딩.
 */
export function buildMapboxStaticUrl(params: MapboxStaticParams): string {
  const { lat, lng, width, height, zoom, token, markers } = params;
  const styleId =
    params.styleId && params.styleId.length > 0
      ? params.styleId
      : DEFAULT_STYLE_ID;
  const lngFixed = lng.toFixed(6);
  const latFixed = lat.toFixed(6);
  const encodedToken = encodeURIComponent(token);

  let markerSegment = "";
  if (markers && markers.length > 0) {
    markerSegment =
      markers
        .map((m) => {
          const hex = (m.color ?? "8B8B9E").replace(/^#/, "").toLowerCase();
          const sizeChar = m.size === "large" ? "l" : "s";
          return `pin-${sizeChar}+${hex}(${m.lng.toFixed(6)},${m.lat.toFixed(6)})`;
        })
        .join(",") + "/";
  }

  return `https://api.mapbox.com/styles/v1/${styleId}/static/${markerSegment}${lngFixed},${latFixed},${zoom},0/${width}x${height}?access_token=${encodedToken}`;
}
