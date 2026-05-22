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
  /** null/undefined 면 mapbox/light-v11 로 폴백 */
  styleId?: string | null;
  /** 지도 위에 그릴 마커 목록. URL 길이 한계로 최대 30개 권장. */
  markers?: MapboxStaticMarker[];
}

const DEFAULT_STYLE_ID = "mapbox/light-v11";

// mapbox/standard는 GL JS v3 전용 렌더링 파이프라인이라 Static Images API와
// 호환되지 않으므로 light-v11 폴백으로 교체한다.
const STANDARD_STYLE_ID = "mapbox/standard";

/**
 * `mapbox://styles/{user}/{styleId}` 형태에서 `{user}/{styleId}` 추출.
 * null/undefined/형식 불일치는 `mapbox/light-v11` 폴백.
 * `mapbox/standard`는 Static Images API 미지원이므로 폴백으로 교체.
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
  const extracted = `${match[1]}/${match[2]}`;
  if (extracted === STANDARD_STYLE_ID) {
    return DEFAULT_STYLE_ID;
  }
  return extracted;
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
