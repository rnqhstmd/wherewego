"use client";

import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
} from "react";
import mapboxgl from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
import type { PinSummaryResponse, PinTag } from "@/lib/api/types";
import { colors } from "@/lib/design/tokens";
import {
  getReelSvgString,
  getWishSvgString,
  getMemorySvgString,
  getMarkerVariant,
} from "@/lib/pin/markers";
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
  /** 지도 빈 공간(핀/클러스터 외) 클릭 시 호출. 핀 팝업 닫기 등에 사용. */
  onMapBackgroundClick?: () => void;
  onMapReady?: (map: mapboxgl.Map) => void;
  /** viewport 갱신마다 현재 클러스터 존재 여부를 전달 (FR-MAP-5 안내 배너용). */
  onClustersChange?: (info: { hasCluster: boolean }) => void;
  /**
   * 지도 로드/스타일 에러 발생 시 사유 분류와 함께 호출. 호출 측은
   * MapLoadError 오버레이를 노출하여 사용자에게 안내한다.
   */
  onMapError?: (reason: MapLoadErrorReason) => void;
  /**
   * 검색 결과 선택 / 메모 입력 단계에서 표시할 임시 미리보기 마커 좌표.
   * null 이면 마커 비표시. cta(rust) 색상으로 일반 핀과 시각적으로 구분된다.
   */
  previewMarker?: { lat: number; lng: number } | null;
  /**
   * true 이면 초기 지도 로드 시 현재 위치로 flyTo 하지 않는다.
   * 딥링크(?pinId=X) 진입 시 핀 위치 줌인이 geolocation에 의해 덮어쓰이는 것을 방지.
   */
  skipInitialGeoFly?: boolean;
  /**
   * Phase 10: GeolocateControl `geolocate` 이벤트마다 호출되는 위치 콜백.
   * MapClient 가 `useVisitDetection.evaluate(...)` 트리거에 사용한다.
   * 자체 사용자 마커 갱신과 무관하게 항상 호출된다.
   */
  onGeolocate?: (position: GeolocationPosition) => void;
  /**
   * Phase 12 (§9.7, AC-12-36 / D-14): reel_bundle 모드에서 비번들 핀을 시각적으로 dim 시키기 위한
   * 핀 ID 집합. 본 집합에 포함된 핀의 마커 element 는 opacity 0.3 으로 렌더되며, 그 외는 1.0.
   * 비어있거나 미전달이면 모든 마커가 정상 opacity 로 렌더된다.
   *
   * <p>Mapbox GL JS Marker 자체는 opacity prop 을 노출하지 않으나, {@code Marker({ element })}
   * 로 부착된 DOM element 에 직접 {@code style.opacity} 를 설정하면 의도한 시각 효과를 얻을 수 있다.</p>
   */
  dimmedPinIds?: Set<number>;
  /**
   * Phase 12 (FR-PIN-12-11, AC-12-18, §9.2): REEL → WISH 자동 전환 시 0.5초 동안
   * 마커 DOM 에 `.pin-pulse-once` 클래스를 부착하여 펄스 keyframe(globals.css)을 1회 재생한다.
   * 부모(MapClient) 가 wishConverted=true 시점에 pinId 를 set 하고 0.5초 뒤 null 로 되돌리므로
   * 본 컴포넌트는 prop 변화에 맞춰 클래스만 토글한다.
   */
  pulsingPinId?: number | null;
}

/**
 * Phase 10: 부모(MapClient)가 imperative API 로 마커 bounce + confetti 를 트리거하기 위한 핸들.
 */
export interface MapboxViewHandle {
  /**
   * 지정 핀의 마커에 bounce + 하트 confetti 를 1회 발사한다.
   * 클러스터에 포함되어 markerCacheRef 에 없는 핀이면 no-op (설계 §9 허용 케이스).
   */
  triggerVisitCelebration: (pinId: number) => void;
}

/**
 * 마커 element 자식 노드로 bounce + 하트 confetti 를 주입하고 600ms 후 자연 소멸시킨다 (설계 §5.3).
 *
 *  - 기존 자식(SVG 글리프) 을 `data-bounce-inner` div 로 감싸 bounce animation 적용.
 *  - confetti div 안에 하트 3개를 절대 위치로 추가. 각도 -120°/-90°/-60°, 거리 36~44px 의
 *    오프셋을 CSS 변수(--dx, --dy) 로 주입한다.
 *  - 600ms 후 confetti div 와 inner wrapper 를 제거하고 원본 자식을 복원. 다음 renderClusters 에서
 *    별도 처리가 필요하지 않다.
 */
function runMarkerBounceAndConfetti(markerEl: HTMLDivElement): void {
  // 같은 마커에 중복 트리거 방지 — 진행 중이면 무시.
  if (markerEl.dataset.celebrating === "1") return;
  markerEl.dataset.celebrating = "1";

  // 기존 자식들을 inner div 로 이동시켜 bounce 적용.
  const inner = document.createElement("div");
  inner.dataset.bounceInner = "1";
  inner.style.cssText =
    "width:100%;height:100%;display:block;animation:maygo-marker-bounce 600ms ease-in-out both;transform-origin:50% 50%;";
  const originalChildren: ChildNode[] = [];
  while (markerEl.firstChild) {
    originalChildren.push(markerEl.firstChild);
    inner.appendChild(markerEl.firstChild);
  }
  markerEl.appendChild(inner);

  // confetti 레이어 — 마커 중심 기준 절대 위치 하트 3개.
  const confetti = document.createElement("div");
  confetti.dataset.confetti = "1";
  confetti.style.cssText =
    "position:absolute;top:50%;left:50%;width:0;height:0;pointer-events:none;";

  // 각도 -120° / -90° / -60° (상단으로 부채꼴), 거리 36~44px.
  const offsets: Array<{ dx: number; dy: number }> = [
    { dx: -38 * Math.cos(Math.PI / 6), dy: -38 * Math.sin(Math.PI / 6) - 28 }, // -120°
    { dx: 0, dy: -44 }, // -90°
    { dx: 38 * Math.cos(Math.PI / 6), dy: -38 * Math.sin(Math.PI / 6) - 28 }, // -60°
  ];
  for (let i = 0; i < offsets.length; i++) {
    const { dx, dy } = offsets[i];
    const heart = document.createElement("span");
    heart.textContent = "♡";
    heart.style.cssText = `position:absolute;top:0;left:0;font-size:16px;color:${colors.pinMemory};animation:maygo-confetti-heart-${i} 600ms ease-out both;--dx:${dx}px;--dy:${dy}px;`;
    confetti.appendChild(heart);
  }
  markerEl.appendChild(confetti);

  window.setTimeout(() => {
    // confetti 제거.
    if (confetti.parentNode === markerEl) {
      markerEl.removeChild(confetti);
    }
    // inner 제거 + 원본 자식 복원.
    if (inner.parentNode === markerEl) {
      markerEl.removeChild(inner);
      for (const child of originalChildren) {
        markerEl.appendChild(child);
      }
    }
    delete markerEl.dataset.celebrating;
  }, 600);
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

/**
 * 지도 자체는 정상 동작하는 비치명적 에러(개별 layer/tile/mesh 로딩 실패 등)는
 * UI 전환을 일으키지 않는다. 콘솔 로깅으로만 처리.
 */
function isFatalMapError(err: unknown): boolean {
  if (!err || typeof err !== "object") return true;
  const e = err as { status?: number; message?: string };
  if (typeof e.status === "number" && (e.status === 429 || e.status === 402 || e.status === 401)) {
    return true;
  }
  const message = typeof e.message === "string" ? e.message.toLowerCase() : "";
  const nonFatalSignals = [
    "does not exist in the map's style",
    "is not iterable",
    "meshes",
    "tile",
  ];
  if (nonFatalSignals.some((sig) => message.includes(sig))) {
    return false;
  }
  return true;
}

/** 운영에서는 NEXT_PUBLIC_MAPBOX_STYLE_URL 사용, 미설정 시 개발용 fallback (설계 §9). */
const FALLBACK_STYLE = "mapbox://styles/mapbox/standard";

/** 기본 중심 좌표 (서울 시청 부근). 핀이 없을 때만 사용. */
const DEFAULT_CENTER: [number, number] = [127.0, 37.5];

/**
 * Marker DOM element에 PinDot UI를 그린다.
 *
 * `PinDot` React 컴포넌트와 동일한 모양을 vanilla DOM으로 표현하여
 * mapboxgl.Marker { element } 에 직접 부착할 수 있게 한다.
 *
 * 태그 변경 시에도 element 인스턴스를 재사용하기 위해 innerHTML/style만 갱신.
 *
 * Phase 12 (AC-12-16/17, D-13) + 후속(UX 재반영3, 하트 뱃지 방식):
 *  - REEL → 하늘색 원 1.0배
 *  - WISH → 노랑 별 1.2배
 *  - MEMORY → 핑크 하트 1.0배
 *  - REEL && wantCount >= 1 → 위 REEL 마커 우상단에 빨간 하트 뱃지 오버레이 추가
 *
 * 베이스 사이즈: REEL/MEMORY = 22px, WISH = 18px. variant.size 계수를 곱해 최종 사이즈 결정.
 * 뱃지는 12px, position absolute (top=-3, right=-4) — 컨테이너 중심은 그대로라 mapbox
 * anchor("center") 가 가리키는 지리 좌표는 변하지 않는다.
 */
const HEART_BADGE_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" aria-hidden="true" style="position:absolute;top:-3px;right:-4px;pointer-events:none;"><path d="M12 21s-7.5-4.6-9.5-9.1C1 7.7 3.6 4 7.3 4c2 0 3.5 1.1 4.7 2.7C13.2 5.1 14.7 4 16.7 4c3.7 0 6.3 3.7 4.8 7.9C19.5 16.4 12 21 12 21z" fill="#FF2D55" stroke="#fff" stroke-width="1.8" stroke-linejoin="round"/></svg>`;

function renderPinDotInto(
  el: HTMLDivElement,
  tag: PinTag,
  wantCount: number,
): void {
  el.innerHTML = "";
  el.style.cursor = "pointer";
  el.style.display = "block";
  el.style.padding = "0";
  el.style.margin = "0";
  el.style.background = "transparent";
  el.style.border = "none";
  el.style.borderRadius = "0";
  el.style.boxShadow = "none";
  // 뱃지가 우상단으로 살짝 비집고 나오므로 stacking/잘림 방지.
  el.style.position = "relative";
  el.style.overflow = "visible";

  const variant = getMarkerVariant(tag, wantCount);

  switch (variant.kind) {
    case "reel": {
      const size = Math.round(22 * variant.size);
      el.style.width = `${size}px`;
      el.style.height = `${size}px`;
      // REEL 핀에만 want_count>=1 시 빨간 하트 뱃지 오버레이를 합성.
      el.innerHTML =
        getReelSvgString(size) + (wantCount >= 1 ? HEART_BADGE_SVG : "");
      break;
    }
    case "wish": {
      const size = Math.round(18 * variant.size);
      el.style.width = `${size}px`;
      el.style.height = `${size}px`;
      el.innerHTML = getWishSvgString(size);
      break;
    }
    case "memory": {
      const size = Math.round(22 * variant.size);
      el.style.width = `${size}px`;
      el.style.height = `${size}px`;
      // material standard 하트 viewBox 0 0 24 24 정사각이라 size x size 동일 비율로 호출.
      el.innerHTML = getMemorySvgString(size, size);
      break;
    }
    default: {
      // M1 fallback: 알 수 없는 enum → WISH 글리프.
      // Phase 7 사용자 확인된 안전장치 — 운영 관찰 목적
      console.warn(
        "[MapboxView] Unknown tag in marker, falling back to wish:",
        tag,
      );
      el.style.width = "18px";
      el.style.height = "18px";
      el.innerHTML = getWishSvgString(18);
      break;
    }
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
 *
 * Phase 10: forwardRef 로 변환되어 부모(MapClient)가 `triggerVisitCelebration` 을 호출할 수 있다.
 * `next/dynamic` 의 default export 컴포넌트 ref 전달은 React.lazy 동작과 동일하게 지원된다.
 */
const MapboxView = forwardRef<MapboxViewHandle, MapboxViewProps>(function MapboxView(
  {
    pins,
    token,
    styleUrl,
    onMarkerClick,
    onMapBackgroundClick,
    onMapReady,
    onClustersChange,
    onMapError,
    previewMarker,
    skipInitialGeoFly = false,
    onGeolocate,
    dimmedPinIds,
    pulsingPinId,
  },
  ref,
) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<mapboxgl.Map | null>(null);
  const markerCacheRef = useRef<Map<number, mapboxgl.Marker>>(new Map());
  const clusterMarkerCacheRef = useRef<Map<number, mapboxgl.Marker>>(new Map());
  const clustererRef = useRef<SuperclusterInstance | null>(null);
  const pinsRef = useRef<PinSummaryResponse[]>(pins);
  const onMarkerClickRef = useRef(onMarkerClick);
  const onClustersChangeRef = useRef(onClustersChange);
  const onMapErrorRef = useRef(onMapError);
  const onMapBackgroundClickRef = useRef(onMapBackgroundClick);
  const onGeolocateRef = useRef(onGeolocate);
  // Phase 12 (§9.7): dimmed 핀 ID 집합. 마커 element.style.opacity 를 분기 갱신하기 위해 ref 로 유지한다.
  const dimmedPinIdsRef = useRef<Set<number> | null>(dimmedPinIds ?? null);
  // 마지막으로 부모에 알린 hasCluster 값. 변화가 있을 때만 콜백 호출 → setState 폭주 차단.
  const lastHasClusterRef = useRef<boolean | null>(null);
  // 자체 사용자 위치 마커 — geolocate 이벤트마다 좌표만 갱신.
  const userMarkerRef = useRef<mapboxgl.Marker | null>(null);
  const userMarkerAddedRef = useRef(false);
  // 검색/메모 단계의 임시 미리보기 마커 — pins와 별도 캐시로 관리.
  const previewMarkerRef = useRef<mapboxgl.Marker | null>(null);

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
    onMapBackgroundClickRef.current = onMapBackgroundClick;
  }, [onMapBackgroundClick]);
  useEffect(() => {
    onGeolocateRef.current = onGeolocate;
  }, [onGeolocate]);
  useEffect(() => {
    pinsRef.current = pins;
  }, [pins]);

  // Phase 12 (§9.7, AC-12-36): dimmedPinIds 변경 시 ref 갱신 + 캐시된 모든 핀 마커의 opacity 즉시 반영.
  // 비번들 핀은 0.3, 번들 핀(또는 dim 비활성) 은 1.0 으로 element.style.opacity 를 분기 적용한다.
  useEffect(() => {
    dimmedPinIdsRef.current = dimmedPinIds && dimmedPinIds.size > 0 ? dimmedPinIds : null;
    const dim = dimmedPinIdsRef.current;
    for (const [pinId, marker] of markerCacheRef.current) {
      const el = marker.getElement() as HTMLDivElement;
      el.style.opacity = dim && dim.has(pinId) ? "0.3" : "1";
    }
  }, [dimmedPinIds]);

  // Phase 12 (FR-PIN-12-11, AC-12-18, §9.2): pulsingPinId 변경에 맞춰 마커 DOM 에 `.pin-pulse-once`
  // 클래스를 부착/해제한다. 부모(MapClient)가 wishConverted=true 시 0.5초 동안 pinId 를 set 했다가
  // null 로 되돌리므로, 본 effect 는 prop 변화만 좇아 클래스 토글만 수행한다. cleanup 에서 명시 제거하여
  // 동일 핀에 재트리거가 들어와도 keyframe 이 1회 재생되도록 보장.
  useEffect(() => {
    if (pulsingPinId === null || pulsingPinId === undefined) return;
    const marker = markerCacheRef.current.get(pulsingPinId);
    if (!marker) return;
    const el = marker.getElement() as HTMLDivElement;
    // PR #76 Copilot #5: outer container 는 Mapbox 가 인라인 transform(translate3d) 으로 좌표를
    // 주입하므로, keyframe 의 scale transform 이 그것을 덮어써 마커 위치 글리치가 발생한다.
    // SVG 내부 노드에만 펄스 클래스를 부착하여 좌표 transform 과 분리한다.
    const svg = el.querySelector("svg");
    if (!svg) return;
    // 동일 노드 재트리거 대비 — 클래스 제거 후 reflow 강제로 keyframe 을 다시 1회 재생.
    svg.classList.remove("pin-pulse-once");
    // reflow trigger (offsetWidth 접근). SVGElement 는 HTMLElement 가 아니므로 캐스팅 필요.
    void (svg as unknown as HTMLElement).offsetWidth;
    svg.classList.add("pin-pulse-once");
    return () => {
      svg.classList.remove("pin-pulse-once");
    };
  }, [pulsingPinId]);

  // Phase 10: imperative API — visit 검출 시 마커 bounce + confetti 트리거.
  useImperativeHandle(
    ref,
    () => ({
      triggerVisitCelebration: (pinId: number) => {
        const marker = markerCacheRef.current.get(pinId);
        if (!marker) return;
        const el = marker.getElement() as HTMLDivElement;
        runMarkerBounceAndConfetti(el);
      },
    }),
    [],
  );

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
        const dim = dimmedPinIdsRef.current;
        const shouldDim = dim != null && dim.has(pinId);
        // Phase 12 (AC-12-16/17, D-13): wantCount 기반 INTEREST variant 분기를 위해
        // pinsList 에서 최신 wantCount 를 조회. supercluster feature props 에는 wantCount 가
        // 포함되지 않아 매 렌더 시 pinsList 로 룩업한다 (props 확장은 클러스터 재생성을 요구).
        const pinData = pinsList.find((p) => p.id === pinId);
        const wantCount = pinData?.wantCount ?? 0;
        const variantKey = `${tag}|${wantCount >= 1 ? "wc1" : "wc0"}`;
        const existing = markerCacheRef.current.get(pinId);
        if (existing) {
          const cur = existing.getLngLat();
          if (cur.lng !== lng || cur.lat !== lat) {
            existing.setLngLat([lng, lat]);
          }
          // 태그 또는 INTEREST 임계 변경 시 element 내부만 다시 그림 (DOM 인스턴스 재사용)
          const el = existing.getElement() as HTMLDivElement;
          if (el.dataset.variant !== variantKey) {
            renderPinDotInto(el, tag, wantCount);
            el.dataset.tag = tag;
            el.dataset.variant = variantKey;
          }
          // Phase 12 (§9.7): viewport 재렌더 경로에서도 dim 상태가 일관되게 유지되도록 매번 분기 적용.
          const nextOpacity = shouldDim ? "0.3" : "1";
          if (el.style.opacity !== nextOpacity) {
            el.style.opacity = nextOpacity;
          }
        } else {
          // pinsList에서 최신 핀 데이터 확인 (없으면 skip — race 방지)
          if (!pinData) continue;
          const el = document.createElement("div");
          el.dataset.tag = tag;
          el.dataset.variant = variantKey;
          el.dataset.pinId = String(pinId);
          renderPinDotInto(el, tag, wantCount);
          // Phase 12 (§9.7): 신규 마커 생성 시점에 dim 분기 적용. dim 상태에서 새로 등장한
          // 비번들 핀도 즉시 opacity 0.3 으로 그려진다.
          el.style.opacity = shouldDim ? "0.3" : "1";
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

    const nextHasCluster = clusterCount > 0;
    if (lastHasClusterRef.current !== nextHasCluster) {
      lastHasClusterRef.current = nextHasCluster;
      onClustersChangeRef.current?.({ hasCluster: nextHasCluster });
    }
  }, []);

  // 1) 지도 인스턴스 생성/정리 (token/styleUrl 변경 시에만 재생성)
  useEffect(() => {
    if (!containerRef.current) return;

    mapboxgl.accessToken = token;

    const initialCenter: [number, number] =
      pins.length > 0
        ? [Number(pins[0].longitude), Number(pins[0].latitude)]
        : DEFAULT_CENTER;
    // 초기 진입 시 globe(3D) 시점 유지: 핀이 있어도 줌을 낮춰서 시작한다.
    // 사용자가 줌인하면 Mapbox가 자연스럽게 2D 머카토르로 전환된다.
    const initialZoom = pins.length > 0 ? 3 : 2;

    const map = new mapboxgl.Map({
      container: containerRef.current,
      style: styleUrl ?? FALLBACK_STYLE,
      center: initialCenter,
      zoom: initialZoom,
      projection: { name: "globe" },
      attributionControl: false,
    });

    map.on("style.load", () => {
      // 3D 지구본 fog (설계 §9)
      map.setFog({});
      // styleUrl 미설정 fallback (Mapbox Standard) 사용 시 파스텔 페이디드 분위기 적용.
      // 운영에서는 NEXT_PUBLIC_MAPBOX_STYLE_URL 로 커스텀 스타일을 권장 (설계 §9).
      if (!styleUrl) {
        // Standard 스타일 config: faded(채도 낮춘 파스텔) + day.
        // monochrome은 물/숲까지 회색으로 만들어 가독성이 떨어짐 → faded 유지.
        try {
          map.setConfigProperty("basemap", "theme", "faded");
        } catch {
          /* 구버전 스타일/SDK — 무시 */
        }
        try {
          map.setConfigProperty("basemap", "lightPreset", "day");
        } catch {
          /* 구버전 스타일/SDK — 무시 */
        }
        try {
          map.setConfigProperty("basemap", "show3dObjects", true);
        } catch {
          /* 구버전 스타일/SDK — 무시 */
        }
        try {
          map.setConfigProperty("basemap", "showPointOfInterestLabels", false);
        } catch {
          /* 구버전 스타일/SDK — 무시 */
        }
      }
      // 커스텀 스타일을 쓰는 경우의 디자인 토큰 보정 (layer id가 다를 수 있어 사전 검사).
      if (!styleUrl) {
        // Mapbox는 미존재 layer에 setPaintProperty 시 비동기 'error' 이벤트를 발생시키므로
        // try/catch로 잡히지 않는다. getLayer로 사전 확인 후 호출한다.
        if (map.getLayer("background")) {
          map.setPaintProperty("background", "background-color", colors.mapBg);
        }
        if (map.getLayer("water")) {
          map.setPaintProperty("water", "fill-color", colors.mapWater);
        }
        if (map.getLayer("landuse-park")) {
          map.setPaintProperty("landuse-park", "fill-color", colors.mapPark);
        }
      }
    });

    // 지도 빈 공간 클릭 시 부모에 알림. marker click은 자체적으로 stopPropagation 처리되어
    // 여기로 전파되지 않으므로 항상 "빈 공간 클릭"으로 안전하게 해석할 수 있다.
    map.on("click", () => {
      onMapBackgroundClickRef.current?.();
    });

    map.on("load", () => {
      mapRef.current = map;
      // 현재 위치 이동 컨트롤 (우측 하단). 기본 위치 도트/정확도 원은 모두 숨기고
      // 자체 마커(user-location-marker)로 그린다 — 색/위치/크기 정확히 매칭.
      const geo = new mapboxgl.GeolocateControl({
        positionOptions: { enableHighAccuracy: true },
        trackUserLocation: true,
        showUserHeading: false,
        showUserLocation: false,
        showAccuracyCircle: false,
      });
      map.addControl(geo, "bottom-right");

      const userEl = document.createElement("div");
      userEl.className = "user-location-marker";
      userEl.innerHTML =
        '<div class="user-location-marker__pulse"></div>' +
        '<div class="user-location-marker__dot"></div>';
      const userMarker = new mapboxgl.Marker({
        element: userEl,
        anchor: "center",
      });
      userMarkerRef.current = userMarker;
      geo.on("geolocate", (e: GeolocationPosition) => {
        const lng = e.coords.longitude;
        const lat = e.coords.latitude;
        userMarker.setLngLat([lng, lat]);
        if (!userMarkerAddedRef.current) {
          userMarker.addTo(map);
          userMarkerAddedRef.current = true;
        }
        // Phase 10: 부모(MapClient)의 useVisitDetection.evaluate 트리거.
        onGeolocateRef.current?.(e);
      });
      onMapReady?.(map);
      // 초기 렌더: clusterer가 이미 준비되어 있다면 즉시 표시.
      renderClusters();

      // 초기 진입 시 globe 시점 → 현재 위치로 부드러운 비행.
      // skipInitialGeoFly(딥링크 진입)일 때는 flyTo 생략, 마커 표시만 수행.
      // 권한 거부/실패 시는 그대로 globe 유지.
      //
      // iOS에서 자동 getCurrentPosition 호출은 blocking modal을 띄우고,
      // 사용자가 거부하면 Mapbox GeolocateControl 버튼이 비활성화된다.
      // (Mapbox 내부에서 navigator.permissions.query 결과가 'denied'가 되기 때문)
      // → 이미 'granted'인 경우에만 자동 호출, 그 외에는 버튼 클릭으로 유도.
      if (typeof navigator !== "undefined" && navigator.geolocation) {
        const tryInitialFly = () => {
          navigator.geolocation.getCurrentPosition(
            (pos) => {
              // unmount 후 비동기 콜백이 도착하면 이미 제거된 map에 접근하므로 조기 반환.
              if (mapRef.current !== map) return;
              const lng = pos.coords.longitude;
              const lat = pos.coords.latitude;
              if (!skipInitialGeoFly) {
                map.flyTo({
                  center: [lng, lat],
                  zoom: 15,
                  pitch: 0,
                  bearing: 0,
                  speed: 0.9,
                  curve: 1.5,
                  essential: true,
                });
              }
              // GeolocateControl trigger 없이 진입한 경우에도 자체 마커 표시.
              if (userMarkerRef.current && !userMarkerAddedRef.current) {
                userMarkerRef.current.setLngLat([lng, lat]).addTo(map);
                userMarkerAddedRef.current = true;
              }
            },
            () => {
              /* 권한 거부/실패 — globe 유지 */
            },
            { enableHighAccuracy: true, timeout: 5000 },
          );
        };

        // Permissions API 미지원 환경(구형 브라우저)은 자동 요청 없이 버튼 클릭으로 유도.
        // granted일 때만 자동 flyTo — prompt 상태에서 자동 요청 시 iOS blocking modal 발생,
        // 거부 선택 시 Mapbox GeolocateControl 버튼이 비활성화되는 버그 방지.
        if (navigator.permissions) {
          navigator.permissions
            .query({ name: "geolocation" as PermissionName })
            .then((status) => {
              if (status.state === "granted") tryInitialFly();
            })
            .catch(() => { /* Permissions API 오류 — 버튼 클릭으로 유도 */ });
        }
      }
    });

    const handleViewportChange = () => {
      renderClusters();
    };
    map.on("moveend", handleViewportChange);
    map.on("zoomend", handleViewportChange);

    map.on("error", (e) => {
      const cause = e?.error ?? e;
      console.error("Mapbox error:", cause);
      if (!isFatalMapError(cause)) return;
      const reason = inferMapErrorReason(cause);
      onMapErrorRef.current?.(reason);
    });

    // cleanup에서 안전하게 참조하기 위해 effect 본문에서 캐시를 로컬로 캡처.
    const pinCacheAtMount = markerCacheRef.current;
    const clusterCacheAtMount = clusterMarkerCacheRef.current;

    return () => {
      map.off("moveend", handleViewportChange);
      map.off("zoomend", handleViewportChange);
      if (userMarkerRef.current) {
        userMarkerRef.current.remove();
        userMarkerRef.current = null;
        userMarkerAddedRef.current = false;
      }
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

  // 3) 검색/메모 단계 미리보기 마커 — previewMarker prop 변화에 따라 추가/이동/제거.
  //    cta 색상 드롭핀 + 살짝 떨어지는 애니메이션으로 사용자 시선을 유도한다.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    if (!previewMarker) {
      if (previewMarkerRef.current) {
        previewMarkerRef.current.remove();
        previewMarkerRef.current = null;
      }
      return;
    }

    const lngLat: [number, number] = [previewMarker.lng, previewMarker.lat];
    if (previewMarkerRef.current) {
      previewMarkerRef.current.setLngLat(lngLat);
      return;
    }
    // 외부 div: Mapbox가 transform 으로 위치 제어 → animation/transform 사용 금지.
    // 내부 div: 우리 애니메이션. translate/scale 은 이 안에서만 안전하다.
    const el = document.createElement("div");
    el.style.cssText = "width:28px;height:36px;pointer-events:none;";
    el.innerHTML =
      `<div style="width:100%;height:100%;animation:maygo-preview-pin-drop 360ms cubic-bezier(0.2,0.8,0.2,1) both;transform-origin:50% 100%;">` +
      `<svg width="28" height="36" viewBox="0 0 28 36" style="display:block;filter:drop-shadow(0 3px 6px ${colors.cta}66);" aria-hidden="true">` +
      `<path d="M14 0C6.27 0 0 6.27 0 14c0 9.4 14 22 14 22s14-12.6 14-22C28 6.27 21.73 0 14 0z" fill="${colors.cta}"/>` +
      `<circle cx="14" cy="14" r="5" fill="#FFFFFF"/></svg></div>`;
    const marker = new mapboxgl.Marker({ element: el, anchor: "bottom" })
      .setLngLat(lngLat)
      .addTo(map);
    previewMarkerRef.current = marker;
  }, [previewMarker]);

  // 미리보기 마커 cleanup — 컴포넌트 unmount 또는 map 인스턴스 재생성 시.
  useEffect(
    () => () => {
      if (previewMarkerRef.current) {
        previewMarkerRef.current.remove();
        previewMarkerRef.current = null;
      }
    },
    [],
  );

  return (
    <div
      ref={containerRef}
      style={{ position: "absolute", inset: 0 }}
      aria-label="지도"
    />
  );
});

export default MapboxView;
