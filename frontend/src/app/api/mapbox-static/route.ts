import type { NextRequest } from "next/server";

const MAPBOX_BASE = "https://api.mapbox.com/styles/v1";
// styleId 형식: {user}/{styleId} — 영숫자·언더스코어·하이픈만 허용
const STYLE_ID_RE = /^[a-zA-Z0-9_-]+\/[a-zA-Z0-9_-]+$/;

/**
 * Mapbox Static Images API 서버사이드 프록시.
 * 브라우저가 api.mapbox.com에 직접 접근하면 CORS + 토큰 노출 문제가 생기므로
 * Next.js 서버에서 요청을 중계한다.
 *
 * Query: lat, lng, zoom, w(idth), h(eight), styleId
 */
export async function GET(request: NextRequest): Promise<Response> {
  const sp = request.nextUrl.searchParams;
  const lat = sp.get("lat");
  const lng = sp.get("lng");
  const zoom = sp.get("zoom") ?? "14";
  const width = sp.get("w") ?? "1024";
  const height = sp.get("h") ?? "1280";
  const styleId = sp.get("styleId") ?? "mapbox/light-v11";

  const token = process.env.NEXT_PUBLIC_MAPBOX_TOKEN;
  if (!token || !lat || !lng) {
    return new Response(null, { status: 400 });
  }
  if (!STYLE_ID_RE.test(styleId)) {
    return new Response(null, { status: 400 });
  }

  const mapboxUrl = `${MAPBOX_BASE}/${styleId}/static/${lng},${lat},${zoom},0/${width}x${height}?access_token=${token}`;

  try {
    const upstream = await fetch(mapboxUrl, {
      signal: AbortSignal.timeout(8000),
    });
    if (!upstream.ok) {
      return new Response(null, { status: upstream.status });
    }
    const buffer = await upstream.arrayBuffer();
    return new Response(buffer, {
      headers: {
        "Content-Type": "image/png",
        "Cache-Control": "public, max-age=3600",
      },
    });
  } catch {
    return new Response(null, { status: 502 });
  }
}
