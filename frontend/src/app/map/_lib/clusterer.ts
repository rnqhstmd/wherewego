/**
 * supercluster 인스턴스 라이프사이클 래퍼 (설계 §9, FR-MAP-4).
 *
 * MapboxView의 useEffect에서 pins 변경 시 createClusterer로 새 인스턴스 생성,
 * moveend/zoomend 시 getClustersForView로 현재 viewport의 클러스터/포인트 계산.
 *
 * supercluster.load()는 immutable index를 만들므로 pins가 바뀌면 새 인스턴스 필요.
 */

import Supercluster from "supercluster";
import type { PinSummaryResponse, PinTag } from "@/lib/api/types";

/** 개별 핀 feature에 부착되는 properties. */
export interface PinFeatureProps {
  pinId: number;
  tag: PinTag;
}

/**
 * `Supercluster.getClusters()`의 반환 타입.
 * 클러스터(`cluster: true`)와 개별 포인트(properties=PinFeatureProps)가 섞여 있다.
 */
export type ClusterOrPointFeature =
  | Supercluster.ClusterFeature<Supercluster.AnyProps>
  | Supercluster.PointFeature<PinFeatureProps>;

export type SuperclusterInstance = Supercluster<PinFeatureProps>;

/**
 * 핀 목록으로부터 supercluster 인스턴스 생성.
 * radius=60(px), maxZoom=16, minPoints=2 (설계 §9).
 */
export function createClusterer(
  pins: PinSummaryResponse[],
): SuperclusterInstance {
  const cluster = new Supercluster<PinFeatureProps>({
    radius: 60,
    maxZoom: 16,
    minPoints: 2,
  });
  cluster.load(
    pins.map((p) => ({
      type: "Feature" as const,
      properties: { pinId: p.id, tag: p.tag },
      geometry: {
        type: "Point" as const,
        coordinates: [Number(p.longitude), Number(p.latitude)],
      },
    })),
  );
  return cluster;
}

/**
 * 현재 viewport(bbox)와 zoom 기준으로 클러스터/포인트 feature 배열을 반환.
 * @param bounds [west, south, east, north]
 */
export function getClustersForView(
  cluster: SuperclusterInstance,
  bounds: [number, number, number, number],
  zoom: number,
): ClusterOrPointFeature[] {
  return cluster.getClusters(bounds, Math.floor(zoom));
}

/** feature가 클러스터인지 판별 (type narrowing). */
export function isClusterFeature(
  f: ClusterOrPointFeature,
): f is Supercluster.ClusterFeature<Supercluster.AnyProps> {
  return (f.properties as { cluster?: boolean }).cluster === true;
}
