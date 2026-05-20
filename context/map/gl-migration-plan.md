# GL Symbol Layer 마이그레이션 사전 분석

> 작성일: 2026-05-18
> 트리거: 그룹 핀 500개 이상 도달 시 (status.md 참조)

> **작성 시점 스냅샷**: 본 문서는 2026-05-18 기준 코드 상태를 분석한 것이다.
> 이후 `PinPopup.tsx`, `MapboxView.tsx`, `clusterer.ts` 변경 시 분석 결과가 무효화될 수 있다.
> 실제 GL 마이그레이션 시점에 본 문서를 재검증한 뒤 사용하라.

## 1. 배경

- 현재 DOM Marker 인스턴스 캐시 패턴(`MapboxView.tsx`)이 500+ 핀 규모에서 성능 한계를 가짐
- Phase 2.9 PRD §1 ROI 평가에 따라 본 Phase에서는 사전 분석 문서만 작성 (FR-5)
- 실제 마이그레이션은 임계치 도달 시 별도 Phase로 진행

## 2. 변경 지점 분석

### 2.1 PinPopup 좌표 계산 방식

**현재** (`frontend/src/app/map/_components/PinPopup.tsx`):
- `map.project([lng, lat])`로 마커 DOM 화면 픽셀 좌표를 계산하여 SpeechBubblePopup을 배치한다.
- 좌표 산출 대상은 DOM Marker 인스턴스이므로 마커가 존재하는 한 동기적으로 즉시 픽셀 좌표를 얻을 수 있다.

**전환 후**:
- GL symbol layer로 옮기면 마커가 DOM이 아닌 GPU 렌더 feature가 된다.
- 좌표 산출은 `map.queryRenderedFeatures({ layers: ['pin-layer'], filter: ['==', ['id'], pinId] })`로 feature를 찾은 뒤 `feature.geometry.coordinates`를 `map.project()`로 픽셀 변환한다.
- zoom/pitch 변경 시 좌표 추적은 `map.on('move', ...)`에서 동일 변환을 반복하여 SpeechBubblePopup 위치를 동기화한다.

### 2.2 useOptimistic patch|remove 흐름

**현재** (`frontend/src/app/map/MapClient.tsx:138` reducer + `MapboxView.tsx` 마커 인스턴스 캐시):
- reducer가 `{kind:"patch", patch}|{kind:"remove"}` 액션을 받아 배열을 재구성한다.
- 재구성된 핀 배열을 `MapboxView`에 `pins` prop으로 전달하면, 내부 마커 인스턴스 캐시가 `pinId`별로 diff 비교하여 DOM Marker `el.innerHTML`/style을 갱신하거나 제거한다.

**전환 후**:
- GL layer는 마커 인스턴스가 아닌 GeoJSON source 데이터로 그려진다.
- patch는 `source.setData(newGeoJson)` 또는 개별 feature 시각 속성을 `map.setFeatureState(featureRef, { tag: 'MEMORY' })`로 반영하고, layer의 `paint` 표현식이 `['feature-state', 'tag']`로 색상/아이콘을 분기한다.
- remove는 GeoJSON features 배열에서 해당 feature를 제외한 뒤 `setData`로 갱신한다.

### 2.3 supercluster + GL layer 클러스터 클릭 핸들러

**현재** (`frontend/src/app/map/_lib/clusterer.ts` + DOM Marker 클릭):
- `clusterer.ts`가 supercluster 인스턴스를 보유하고 `getClusters(bbox, zoom)`를 호출하여 DOM Marker로 클러스터/포인트를 직접 렌더한다.
- 클릭은 각 DOM 엘리먼트에 `el.addEventListener('click', ...)`로 부착한다.

**전환 후**:
- GL 내장 클러스터링(`cluster: true, clusterRadius: 50`) 사용 시 supercluster를 GL이 내부 보유한다.
- 클러스터 클릭은 `map.on('click', 'cluster-layer', e => { const f = e.features[0]; source.getClusterExpansionZoom(f.properties.cluster_id, (err, z) => map.easeTo({ center: f.geometry.coordinates, zoom: z })); })`.
- 단일 포인트 클릭은 `map.on('click', 'pin-layer', ...)`로 분리한다.
- 외부 supercluster 인스턴스는 폐기하거나 클러스터 메타 통계용으로만 잔존시킨다.

## 3. 트레이드오프 요약

| 항목 | 현재 (DOM Marker) | 전환 후 (GL symbol layer) |
|------|------------------|---------------------------|
| 렌더 성능 | 500핀 이상에서 저하 | 1만+ 핀까지 안정 |
| 디버깅 용이성 | DOM 인스펙터로 확인 | feature 상태는 `map.queryRenderedFeatures`로만 확인 |
| useOptimistic 결합 | 배열 → 마커 인스턴스 diff | `source.setData` / `setFeatureState` |
| 클러스터 통합 | 외부 supercluster + DOM 렌더 | GL 내장 클러스터링 |

## 4. 후속 작업 시 체크리스트

1. PinPopup 좌표 계산 로직을 `queryRenderedFeatures` 기반으로 재작성
2. `MapClient.tsx`의 useOptimistic reducer를 `source.setData` / `setFeatureState`로 재작성
3. `clusterer.ts` 제거 및 GL 내장 클러스터링 옵션 적용
4. Phase 2.8 AC 17건 및 useOptimistic 회귀 테스트 재실행
5. PinDot/PinTag 디자인 토큰 유지 확인 (sprite/icon image로 변환)

## 5. 보안 노트

**GL 전환 후에도 핀 조작(수정/삭제) 권한 검증은 백엔드 `requireActiveMembership`이 담당하며, 클라이언트 측 feature property 위조(콘솔에서 source 조작 등)는 서버 인증을 우회하지 못한다. 클라이언트 측 pinId만으로 인가를 판단하는 코드를 추가하지 말 것.**

## 관련 문서

- [status.md](./status.md) — 핀 도메인 구현 상태 추적
- [architecture.md](./architecture.md) — map 도메인 아키텍처 요약
