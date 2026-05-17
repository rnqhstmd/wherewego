"use client";

import { useCallback, useEffect, useRef } from "react";
import mapboxgl from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
import type { PinSummaryResponse, PinTag } from "@/lib/api/types";
import { colors } from "@/lib/design/tokens";
import {
  createClusterer,
  getClustersForView,
  isClusterFeature,
  type SuperclusterInstance,
} from "../_lib/clusterer";
import type { MapLoadErrorReason } from "./MapLoadError";

interface MapboxViewProps {
  pins: PinSummaryResponse[];
  token: string;
  styleUrl: string | null;
  onMarkerClick: (pinId: number) => void;
  onMapReady?: (map: mapboxgl.Map) => void;
  /** viewport 갱신마다 현재 클러스터 존재 여부를 전달 (FR-MAP-5 안내 배너용). */
  onClustersChange?: (info: { hasCluster: boolean }) => void;
  /**
   * 지도 로드/스타일 에러 발생 시 사유 분류와 함께 호출. 호출 측은
   * MapLoadError 오버레이를 노출하여 사용자에게 안내한다.
   */
  onMapError?: (reason: MapLoadErrorReason) => void;
}

/**
 * mapbox `map.on("error")` 의 ErrorEvent.error 는 표준 Error 또는
 * fetch 실패 시 status/url 필드를 동반한 객체일 수 있다 (mapbox-gl 내부 AJAXError).
 * 사용자 안내 UI 분기를 위해 사유를 3종으로 좁힌다.
 */
function inferMapErrorReason(err: unknown): MapLoadErrorReason {
  if (err && typeof err === "object") {
    const e = err as { status?: number; message?: string; url?: string };
    if (typeof e.status === "number" && (e.status === 429 || e.status === 402)) {
      return "QUOTA";
    }
    const url = typeof e.url === "string" ? e.url.toLowerCase() : "";
    const message = typeof e.message === "string" ? e.message.toLowerCase() : "";
    if (url.includes("style") || message.includes("style")) {
      return "STYLE";
    }
  }
  return "GENERIC";
}

/** 운영에서는 NEXT_PUBLIC_MAPBOX_STYLE_URL 사용, 미설정 시 개발용 fallback (설계 §9). */
const FALLBACK_STYLE = "mapbox://styles/mapbox/light-v11";

/** 기본 중심 좌표 (서울 시청 부근). 핀이 없을 때만 사용. */
const DEFAULT_CENTER: [number, number] = [127.0, 37.5];

/**
 * Marker DOM element에 PinDot UI를 그린다.
 *
 * `PinDot` React 컴포넌트와 동일한 모양을 vanilla DOM으로 표현하여
 * mapboxgl.Marker { element } 에 직접 부착할 수 있게 한다.
 *
 * 태그 변경 시에도 element 인스턴스를 재사용하기 위해 innerHTML/style만 갱신.
 */
function renderPinDotInto(el: HTMLDivElement, tag: PinTag): void {
  el.innerHTML = "";
  el.style.cursor = "pointer";
  el.style.display = "block";
  el.style.padding = "0";
  el.style.margin = "0";
  el.style.background = "transparent";
  el.style.border = "none";

  if (tag === "PLACE") {
    el.style.width = "10px";
    el.style.height = "10px";
    el.style.borderRadius = "50%";
    el.style.background = colors.pinPlace;
    el.style.boxShadow = `0 1px 4px ${colors.pinPlace}80`;
  } else {
    // MEMORY: SVG heart, PinDot의 viewBox와 동일
    el.style.width = "15px";
    el.style.height = "13px";
    el.style.borderRadius = "0";
    el.style.background = "transparent";
    el.style.boxShadow = "none";
    el.innerHTML = `<svg width="15" height="13" viewBox="-8 -6 16 12" style="display:block;filter:drop-shadow(0 1px 3px ${colors.pinMemory}80);" aria-hidden="true"><path d="M 0 4.5 C -7 0 -8 -5 -3.5 -5 C -1.5 -5 0 -3 0 -3 C 0 -3 1.5 -5 3.5 -5 C 8 -5 7 0 0 4.5 Z" fill="${colors.pinMemory}"/></svg>`;
  }
}

/**
 * 클러스터 마커 DOM element를 생성. rust 32px 원 + 흰 숫자 (설계 §9).
 * 숫자만 갱신 가능하도록 dataset.count로 캐시 키 유지.
 */
function createClusterElement(count: string): HTMLDivElement {
  const el = document.createElement("div");
  el.dataset.count = count;
  el.innerText = count;
  el.style.cssText = `
    width: 32px;
    height: 32px;
    border-radius: 50%;
    background: ${colors.cta};
    color: #fff;
    display: flex;
    align-items: center;
    justify-content: center;
    font-family: sans-serif;
    font-size: 13px;
    font-weight: 700;
    box-shadow: 0 2px 8px ${colors.shadow};
    cursor: pointer;
    user-select: none;
  `;
  return el;
}

/**
 * Mapbox GL JS 컨테이너 컴포넌트.
 *
 * 책임:
 *  - mapbox 인스턴스 생성/정리 (token, styleUrl 변경 시 재생성)
 *  - 3D 지구본 + fog 적용
 *  - supercluster 기반 viewport-aware 마커 렌더 (FR-MAP-4)
 *    · 줌 아웃: 인접 핀들이 rust 32px 원 + 숫자로 묶임
 *    · 줌 인: 개별 PinDot 마커로 분리
 *    · 클러스터 클릭: getClusterExpansionZoom으로 자동 flyTo
 *  - 핀/클러스터 마커 인스턴스 캐시 패턴 (MUST-1, 설계 §4)
 *
 * 비책임:
 *  - 정보창/팝업 렌더링 (PinPopup에서 처리)
 *  - 클러스터 안내 배너 (ClusterBanner — hasCluster 콜백만 제공)
 *  - 데이터 fetch (page.tsx 서버 컴포넌트)
 *
 * SSR 불가: dynamic ssr:false 로만 로드되어야 한다 (mapbox-gl이 window 의존).
 */
export default function MapboxView({
  pins,
  token,
  styleUrl,
  onMarkerClick,
  onMapReady,
  onClustersChange,
  onMapError,
}: MapboxViewProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<mapboxgl.Map | null>(null);
  const markerCacheRef = useRef<Map<number, mapboxgl.Marker>>(new Map());
  const clusterMarkerCacheRef = useRef<Map<number, mapboxgl.Marker>>(new Map());
  const clustererRef = useRef<SuperclusterInstance | null>(null);
  const pinsRef = useRef<PinSummaryResponse[]>(pins);
  const onMarkerClickRef = useRef(onMarkerClick);
  const onClustersChangeRef = useRef(onClustersChange);
  const onMapErrorRef = useRef(onMapError);

  // 최신 prop을 ref로 유지 → marker element 이벤트 리스너 재바인딩 회피.
  useEffect(() => {
    onMarkerClickRef.current = onMarkerClick;
  }, [onMarkerClick]);
  useEffect(() => {
    onClustersChangeRef.current = onClustersChange;
  }, [onClustersChange]);
  useEffect(() => {
    onMapErrorRef.current = onMapError;
  }, [onMapError]);
  useEffect(() => {
    pinsRef.current = pins;
  }, [pins]);

  /**
   * 현재 viewport 기준으로 클러스터/핀 마커를 diff 렌더링.
   * supercluster.getClusters() 결과를 두 캐시(markerCacheRef, clusterMarkerCacheRef)로 분배.
   */
  const renderClusters = useCallback(() => {
    const map = mapRef.current;
    const cluster = clustererRef.current;
    if (!map || !cluster) return;

    const b = map.getBounds();
    if (!b) return;
    const bounds: [number, number, number, number] = [
      b.getWest(),
      b.getSouth(),
      b.getEast(),
      b.getNorth(),
    ];
    const zoom = map.getZoom();
    const features = getClustersForView(cluster, bounds, zoom);

    const visiblePinIds = new Set<number>();
    const visibleClusterIds = new Set<number>();
    let clusterCount = 0;
    const pinsList = pinsRef.current;

    for (const f of features) {
      const [lng, lat] = f.geometry.coordinates;

      if (isClusterFeature(f)) {
        clusterCount++;
        const cid = f.properties.cluster_id;
        visibleClusterIds.add(cid);
        const text = String(f.properties.point_count_abbreviated);
        const existing = clusterMarkerCacheRef.current.get(cid);
        if (existing) {
          const cur = existing.getLngLat();
          if (cur.lng !== lng || cur.lat !== lat) {
            existing.setLngLat([lng, lat]);
          }
          const el = existing.getElement() as HTMLDivElement;
          if (el.dataset.count !== text) {
            el.dataset.count = text;
            el.innerText = text;
          }
        } else {
          const el = createClusterElement(text);
          el.addEventListener("click", (e) => {
            e.stopPropagation();
            const c = clustererRef.current;
            const m = mapRef.current;
            if (!c || !m) return;
            const expandZoom = c.getClusterExpansionZoom(cid);
            m.flyTo({ center: [lng, lat], zoom: expandZoom });
          });
          const marker = new mapboxgl.Marker({ element: el })
            .setLngLat([lng, lat])
            .addTo(map);
          clusterMarkerCacheRef.current.set(cid, marker);
        }
      } else {
        const pinId = f.properties.pinId;
        const tag = f.properties.tag;
        visiblePinIds.add(pinId);
        const existing = markerCacheRef.current.get(pinId);
        if (existing) {
          const cur = existing.getLngLat();
          if (cur.lng !== lng || cur.lat !== lat) {
            existing.setLngLat([lng, lat]);
          }
          // 태그 변경 시 element 내부만 다시 그림 (DOM 인스턴스 재사용)
          const el = existing.getElement() as HTMLDivElement;
          if (el.dataset.tag !== tag) {
            renderPinDotInto(el, tag);
            el.dataset.tag = tag;
          }
        } else {
          // pinsList에서 최신 핀 데이터 확인 (없으면 skip — race 방지)
          const pinData = pinsList.find((p) => p.id === pinId);
          if (!pinData) continue;
          const el = document.createElement("div");
          el.dataset.tag = tag;
          el.dataset.pinId = String(pinId);
          renderPinDotInto(el, tag);
          el.addEventListener("click", (e) => {
            e.stopPropagation();
            onMarkerClickRef.current(pinId);
          });
          const marker = new mapboxgl.Marker({ element: el })
            .setLngLat([lng, lat])
            .addTo(map);
          markerCacheRef.current.set(pinId, marker);
        }
      }
    }

    // 화면 밖 핀 마커 제거 (캐시도 정리하여 GC 가능하게)
    for (const [pinId, marker] of markerCacheRef.current) {
      if (!visiblePinIds.has(pinId)) {
        marker.remove();
        markerCacheRef.current.delete(pinId);
      }
    }
    // 사라진 클러스터 마커 제거
    for (const [cid, marker] of clusterMarkerCacheRef.current) {
      if (!visibleClusterIds.has(cid)) {
        marker.remove();
        clusterMarkerCacheRef.current.delete(cid);
      }
    }

    onClustersChangeRef.current?.({ hasCluster: clusterCount > 0 });
  }, []);

  // 1) 지도 인스턴스 생성/정리 (token/styleUrl 변경 시에만 재생성)
  useEffect(() => {
    if (!containerRef.current) return;

    mapboxgl.accessToken = token;

    const initialCenter: [number, number] =
      pins.length > 0
        ? [Number(pins[0].longitude), Number(pins[0].latitude)]
        : DEFAULT_CENTER;
    const initialZoom = pins.length > 0 ? 12 : 2;

    const map = new mapboxgl.Map({
      container: containerRef.current,
      style: styleUrl ?? FALLBACK_STYLE,
      center: initialCenter,
      zoom: initialZoom,
      projection: { name: "globe" },
    });

    map.on("style.load", () => {
      // 3D 지구본 fog (설계 §9)
      map.setFog({});
    });

    map.on("load", () => {
      mapRef.current = map;
      onMapReady?.(map);
      // 초기 렌더: clusterer가 이미 준비되어 있다면 즉시 표시.
      renderClusters();
    });

    const handleViewportChange = () => {
      renderClusters();
    };
    map.on("moveend", handleViewportChange);
    map.on("zoomend", handleViewportChange);

    map.on("error", (e) => {
      const cause = e?.error ?? e;
      console.error("Mapbox error:", cause);
      const reason = inferMapErrorReason(cause);
      onMapErrorRef.current?.(reason);
    });

    // cleanup에서 안전하게 참조하기 위해 effect 본문에서 캐시를 로컬로 캡처.
    const pinCacheAtMount = markerCacheRef.current;
    const clusterCacheAtMount = clusterMarkerCacheRef.current;

    return () => {
      map.off("moveend", handleViewportChange);
      map.off("zoomend", handleViewportChange);
      for (const m of pinCacheAtMount.values()) {
        m.remove();
      }
      pinCacheAtMount.clear();
      for (const m of clusterCacheAtMount.values()) {
        m.remove();
      }
      clusterCacheAtMount.clear();
      map.remove();
      mapRef.current = null;
    };
    // pins는 의도적으로 의존성 제외 (초기 center/zoom 계산에만 사용).
    // 마커 갱신은 별도 effect에서 supercluster 기반 diff.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, styleUrl, renderClusters]);

  // 2) pins 변경 시 supercluster 재생성 + 즉시 렌더 (immutable index 특성).
  useEffect(() => {
    clustererRef.current = createClusterer(pins);
    if (mapRef.current) {
      renderClusters();
    }
  }, [pins, renderClusters]);

  return (
    <div
      ref={containerRef}
      style={{ position: "absolute", inset: 0 }}
      aria-label="지도"
    />
  );
}
