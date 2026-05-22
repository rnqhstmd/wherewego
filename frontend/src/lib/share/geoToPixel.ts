// Web Mercator (EPSG:3857) 투영 유틸.
// renderPinCard Step 4.5에서 그룹 핀·자기 핀의 위경도를 Mapbox Static API
// 응답 이미지(apiW×apiH) 기준 픽셀 좌표로 변환하기 위해 사용한다.

/** 위도를 Mercator 발산 방지를 위해 ±84.9°로 clamp */
function clampLat(lat: number): number {
  return Math.max(-84.9, Math.min(84.9, lat));
}

function mercatorX(lng: number): number {
  return (lng + 180) / 360;
}

function mercatorY(lat: number): number {
  const phi = clampLat(lat) * (Math.PI / 180);
  return 0.5 - Math.log((1 + Math.sin(phi)) / (1 - Math.sin(phi))) / (4 * Math.PI);
}

/**
 * 핀의 위경도를 Mapbox Static API 응답 이미지(apiW×apiH) 기준 픽셀 좌표로 변환.
 *
 * 반환값은 API 원본 이미지 좌표계 기준이다.
 * 카드 캔버스(CARD_WIDTH×CARD_HEIGHT) 좌표로 변환하려면 호출처에서
 *   cardX = x * (CARD_WIDTH / apiW)
 *   cardY = y * (CARD_HEIGHT / apiH)
 * 를 적용한다.
 *
 * antimeridian(±180° 경도 경계) 통과 감지 책임은 호출처에서 담당한다.
 * (Math.abs(pinLng - centerLng) > 180 이면 skip)
 */
export function geoToApiPixel(
  pinLat: number,
  pinLng: number,
  centerLat: number,
  centerLng: number,
  zoom: number,
  apiW: number,
  apiH: number,
): { x: number; y: number } {
  const scale = 256 * Math.pow(2, zoom);
  const dx = (mercatorX(pinLng) - mercatorX(centerLng)) * scale;
  const dy = (mercatorY(pinLat) - mercatorY(centerLat)) * scale;
  return {
    x: apiW / 2 + dx,
    y: apiH / 2 + dy,
  };
}
