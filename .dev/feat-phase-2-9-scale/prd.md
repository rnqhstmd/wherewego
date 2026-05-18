# PRD: Phase 2.9 — 규모 대응

## 1. 배경 (Why)

### 현재 제품 상태

**지도(map) 마커 렌더링**
현재 모든 핀은 DOM Marker 인스턴스로 지도에 표시된다. 핀이 추가될 때마다 `new mapboxgl.Marker()`가 DOM 요소를 생성하고 지도에 부착한다. viewport 이동/줌 시 화면 밖 마커는 제거되고 화면 안 마커는 재사용(인스턴스 캐시)된다. supercluster 기반 클러스터링이 적용되어 있어 인접 핀이 숫자 원으로 묶인다. `PinPopup`은 `map.project([lng, lat])`로 마커 화면 좌표를 직접 계산해 말풍선을 배치한다 (MapboxView.tsx, PinPopup.tsx에서 확인).

**핀 목록 조회**
`GET /api/v1/groups/{groupId}/pins?tag=` API는 페이지네이션 없이 그룹의 모든 활성 핀을 한 번에 반환한다. 응답 구조는 `{ items: PinSummaryResponse[] }`이다 (PinV1Dto.java, types.ts에서 확인). 서버는 `created_at DESC` 정렬, `deleted_at IS NULL` 필터를 적용한다. `/pins` 라우트와 `/map` 라우트(룰렛 5분 캐시 재조회 포함) 모두 이 응답의 `items` 필드를 직접 참조한다 (MapClient.tsx에서 확인).

**현재 사용자 규모**: MVP 단계, 2인 커플 서비스. 운영 데이터 상 핀 수량은 임계치(그룹 핀 500+, 전체 핀 1k+)에 도달하지 않은 것으로 판단된다.

### 변경이 필요한 이유

Phase 2.9는 "조건부" 작업이다. 임계치 도달 시 대응이 늦으면 다음 문제가 발생한다:
- DOM Marker 500개 이상: 지도 스크롤/줌 시 버벅임 (브라우저 DOM 부하)
- 전체 핀 1,000개 이상: 초기 로딩 지연, 클라이언트 메모리 증가

현재 시점에서 두 항목 모두 임계치 미도달 상태다. 아래 ROI 평가에 따라 **페이지네이션은 백엔드 API 계약을 이번 Phase에서 준비하되 UI 재설계는 보류하고, GL 마이그레이션은 사전 분석 문서화까지만** 수행한다.

### ROI 평가

| 항목 | 이번 Phase 범위 | 근거 |
|------|----------------|------|
| 핀 페이지네이션 백엔드 API | 포함 | 변경 비용 낮음. 임계치 도달 전 배포해야 운영 중 장애 없이 전환 가능 |
| `/pins` UI 페이지네이션 컨트롤 | 제외 | 지도에 핀이 이미 전부 표시됨. MVP 단계에서 별도 목록 페이징 UI는 ROI 낮음. 필요 시점에 별도 작업 |
| GL symbol layer 실제 마이그레이션 | 제외 | 현재 임계치(그룹 핀 500+) 미도달. 구현 비용 높음 |
| GL symbol layer 사전 분석 문서화 | 포함 | 실제 코드 변경 없이 문서만 작성. 이후 긴급 마이그레이션 리스크 경감 |

---

## 2. 요구사항 (What)

### 2.1 핵심 요구사항 [Must]

**[Must] FR-1: 핀 목록 API 페이지네이션 파라미터 지원**
`GET /api/v1/groups/{groupId}/pins` 엔드포인트가 `page`(0-based, 기본값 0) 및 `size`(기본값 20, 최대 100) 쿼리 파라미터를 수신한다. 파라미터 전달 시 응답에 `totalCount`(해당 필터 조건 기준 전체 핀 수), `hasNext`(다음 페이지 존재 여부) 필드가 추가된다. 기존 `items: PinSummaryResponse[]` 필드는 모든 경우에 유지된다.

**[Must] FR-2: 기존 API 하위 호환성 유지**
`page`/`size` 파라미터 미전달 시 기존과 동일하게 `{ items: [...] }` 구조로 전체 목록을 반환하며, `totalCount`/`hasNext`는 포함하지 않는다. 현재 `/map` 룰렛 재조회(`apiFetch<PinListResponse>('/groups/${groupId}/pins')`) 및 `/pins` 초기 로딩이 코드 변경 없이 동작한다.

**[Must] FR-3: Phase 2.8까지의 기능 회귀 없음**
GL symbol layer 마이그레이션을 이번 Phase에서 진행하지 않는다. DOM Marker 인스턴스 캐시 패턴, `useOptimistic` reducer(`patch|remove`), supercluster 클러스터링, `PinPopup`의 `map.project()` 좌표 계산, `PinDot`/`PinTag`/`SpeechBubblePopup` 디자인 토큰, Phase 2.8 AC 17건 — 모두 그대로 유지된다.

### 2.2 권장 요구사항 [Should]

**[Should] FR-4: `size` 파라미터 상한 서버 검증**
요청 `size`가 100을 초과하면 400 에러를 반환한다. 클라이언트가 의도치 않게 대량 조회를 요청하는 것을 방지한다. 에러 코드는 `PIN_PAGE_SIZE_EXCEEDED`로 정의한다.

**[Should] FR-5: GL symbol layer 마이그레이션 사전 분석 완료**
실제 코딩 없이, 다음 항목이 `context/map/` 문서에 기록된다:
① `PinPopup` 좌표 계산 방식 변경: 현재 `map.project([lng, lat])`(마커 DOM 위치 기반) → GL layer 전환 시 `map.queryRenderedFeatures` + `map.project` 조합으로 feature 픽셀 좌표 산출
② `useOptimistic` `patch|remove` 흐름 변경: 현재 마커 DOM element의 `innerHTML`/style 직접 갱신 → GL layer의 경우 `map.setFeatureState` 또는 source 데이터 교체로 feature 속성 갱신
③ supercluster + GL layer 클러스터 클릭 핸들러: 현재 DOM 이벤트 리스너(`el.addEventListener('click')`) → GL layer의 `map.on('click', layerId)` 이벤트로 전환 시 `getClusterExpansionZoom` 연동 변경 범위

### 2.3 선택 요구사항 [Could]

**[Could] FR-6: 지도 화면 핀 수량 기반 배너 안내**
그룹 핀이 400개 이상이 되면 지도 하단에 "핀이 많아졌어요. 탐색이 느려질 수 있어요" 안내를 1회 노출한다. 기존 `ClusterBanner`의 localStorage 1회 패턴을 재사용할 수 있다.

**[Could] FR-7: 프론트엔드 API 클라이언트 시그니처 선택적 확장**
`frontend/src/lib/api/pin.ts`의 `listPins(groupId, tag?)` 함수에 `page`/`size`를 선택적 파라미터로 추가한다. 기존 호출부는 영향 없고, 미래에 `/pins` UI 페이지네이션을 구현할 때 활용 가능하다.

---

## 3. 범위 (Scope)

### 3.1 포함

| 항목 | 근거 |
|------|------|
| 백엔드 핀 목록 API 페이지네이션 지원 — `page`/`size` 파라미터 + `totalCount`/`hasNext` 선택적 응답 (FR-1, FR-2, FR-4) | 백엔드 변경 비용 낮음. 임계치 도달 전 API 계약 준비 완료 필요 |
| 기존 `items` 필드 유지 (FR-2) | 현재 운영 중인 지도 화면, 룰렛, `/pins` 라우트 무중단 |
| GL symbol layer 마이그레이션 사전 분석 문서화 (FR-5) | 실제 코드 변경 없음. 문서 작성만. 이후 긴급 마이그레이션 리스크 경감 |
| main 브랜치 머지 (develop → main) | Phase 2.9 완료 후 일괄 |

### 3.2 제외

| 항목 | 이유 |
|------|------|
| `/pins` UI 페이지네이션 컨트롤 | MVP 단계 ROI 낮음. 지도에 핀이 전부 표시되므로 별도 목록 UI 페이징 필요성 낮음. 필요 시점에 별도 작업 |
| DOM Marker → GL symbol layer 실제 마이그레이션 | 현재 임계치(그룹 핀 500+) 미도달. 구현 비용 높음. FR-5 사전 분석으로 대체 |
| 지도 화면 핀 초기 로딩 방식 변경 (`revalidatePath` 패턴 포함) | DOM Marker 방식 유지 중이므로 변경 불필요 |
| 핀 수량 임계치 모니터링 자동화 | 운영 대시보드/알림 설정은 Phase 3.0 이후 |
| 삭제 핀 복원 기능 | Phase 2.8에서 별도 Phase 예정으로 분리된 사항 |
| 핀 좌표 수정 (지도 picker) | Phase 2.8에서 별도 Phase 예정으로 분리된 사항 |

---

## 4. 수용 기준 (Acceptance Criteria)

### 페이지네이션 API [Must]

**AC-1**: `GET /api/v1/groups/{groupId}/pins?page=0&size=20` 호출 시 응답이 `{ items: [...], totalCount: N, hasNext: true|false }` 구조를 반환하고, `items`의 건수는 최대 20건이다 → [FR-1]

**AC-2**: `page`/`size` 파라미터 없이 `GET /api/v1/groups/{groupId}/pins` 호출 시 기존과 동일한 `{ items: [...] }` 구조로 전체 목록이 반환되고, `totalCount`/`hasNext` 필드는 응답에 포함되지 않는다 → [FR-2]

**AC-3**: `size=101` 요청 시 400 응답이 반환된다 → [FR-4]

**AC-4**: `page=-1` 또는 `size=0` 요청 시 400 응답이 반환된다 → [FR-1]

**AC-5**: `tag=PLACE&page=0&size=10` 요청 시 PLACE 태그 핀만 최대 10건 반환되고, `totalCount`는 해당 그룹의 PLACE 태그 전체 수를 반영한다 → [FR-1]

**AC-6**: `page=0&size=20`으로 조회 후 `hasNext: true`이면, `page=1&size=20` 요청이 다음 20건을 반환하고 결과가 중복되지 않는다 → [FR-1]

### 하위 호환성 [Must]

**AC-7**: `MapClient`의 룰렛 stale 재조회 코드(`apiFetch<PinListResponse>('/groups/${groupId}/pins')`)가 파라미터 변경 없이 기존과 동일한 `{ items: [...] }` 응답을 받고, `pool = res.items` 참조가 정상 동작한다 → [FR-2]

**AC-8**: 기존 `listPins(groupId, tag?)` 호출부 및 `/pins` 초기 서버 fetch가 코드 변경 없이 동작하고, 반환 데이터가 이전과 동일하다 → [FR-2]

### Phase 2.8 회귀 방지 [Must]

**AC-9**: `PinDot`(PLACE 파란 동그라미 `#7BB3E8`, MEMORY 핑크 하트 `#F4A8B0`), `PinTag` 칩, `SpeechBubblePopup`의 시각적 표현이 Phase 2.8 완료 시점과 동일하다 → [FR-3]

**AC-10**: Phase 2.8 AC 1~17 항목이 이번 변경 후에도 모두 충족된다 → [FR-3]

**AC-11**: `useOptimistic` `patch|remove` reducer, supercluster 클러스터링, `PinPopup` 말풍선 좌표 계산이 정상 동작한다 → [FR-3]

### GL 마이그레이션 사전 분석 [Should]

**AC-12**: `context/map/` 문서에 GL symbol layer 전환 시 변경되는 3개 항목(① PinPopup 좌표 계산 방식, ② useOptimistic 패치 방법, ③ supercluster + GL layer 클러스터 클릭 핸들러)이 각각 현재 방식과 전환 후 방식의 대비와 함께 기록된다 → [FR-5]

---

## 5. 비기능 요구사항 (NFR)

**[Should] NFR-1: 페이지네이션 쿼리 성능**
`size=20` 기준 페이지네이션 응답 시간이 기존 전체 조회 대비 동일 건수 기준으로 크게 증가하지 않는다. 기존 인덱스 `INDEX(group_id, deleted_at)`, `INDEX(group_id, tag) WHERE deleted_at IS NULL`이 페이지네이션 쿼리에 그대로 활용됨을 전제로 한다.

**[Must] NFR-2: 하위 호환 배포**
페이지네이션 API는 기존 클라이언트와 동시에 운영되는 배포를 지원해야 한다. 동일 배포에서 파라미터 없는 기존 요청과 파라미터 있는 신규 요청이 모두 정상 처리된다.

**[Should] NFR-3: 에러 응답 일관성**
`size` 상한 초과, `page` 음수 등 잘못된 파라미터에 대한 에러 응답이 기존 `CoreException` / `ApiResponse` 오류 포맷을 따른다.

---

## 6. 위험 및 가정 (Risks & Assumptions)

### 위험

| 위험 | 가능성 | 대응 |
|------|--------|------|
| `PinListResponse`에 `totalCount`/`hasNext` 선택적 추가 시 프론트엔드 타입 파싱 오류 | 낮음 | `types.ts`의 `PinListResponse` 인터페이스에 `totalCount?: number`, `hasNext?: boolean`으로 선택적 필드 추가. 기존 `items` 접근부는 영향 없음 |
| 룰렛 `apiFetch<PinListResponse>` 호출부가 응답 구조 변경에 영향받음 | 낮음 | AC-7 검증 필수. `page`/`size` 미전달 시 응답에 `totalCount`/`hasNext` 미포함 보장 (FR-2) |
| 핀 수량이 예상보다 빠르게 증가하여 Phase 2.9 완료 전 500건 초과 | 낮음 | MVP 2인 서비스 특성상 가능성 낮음. 발생 시 GL 마이그레이션을 즉시 별도 긴급 Phase로 분리 |

### 가정

- 현재 운영 중인 그룹의 핀 수는 500건 미만이다.
- GL symbol layer 마이그레이션은 이번 Phase에서 실제 구현하지 않는다.
- `/pins` 라우트는 편집/삭제 관리 기능 제공 목적으로 유지하되, 페이지네이션 컨트롤 UI는 추가하지 않는다.
- main 브랜치 머지는 이 Phase의 모든 [Must] 항목 완료 후 일괄 수행한다.
- `/map` 화면의 핀 로딩 방식(초기 전체 fetch, `revalidatePath` 미호출)은 이번 Phase에서 변경하지 않는다.

---

## 7. 영향 도메인 (Impact)

| 도메인 | 영향 | 비고 |
|--------|------|------|
| **pin (백엔드)** | `PinService.listGroupPins`, `PinJpaRepository`, `PinRepositoryImpl`, `PinV1Controller`, `PinV1Dto.PinListResponse` 변경 | Pageable 시그니처 + 선택적 응답 필드 추가 |
| **pin (프론트엔드 `/pins`)** | 변경 없음. 기존 `listPins(groupId, tag?)` 호출 및 `PinListClient` 그대로 유지 | [Could] FR-7 선택 시 API 클라이언트 시그니처 선택적 확장만 허용 |
| **map (프론트엔드 `/map`)** | 변경 없음. DOM Marker 패턴, `useOptimistic`, `PinPopup` 모두 유지 | FR-2 하위 호환으로 보호 |
| **roulette** | 변경 없음. `MapClient` 룰렛 5분 캐시 재조회 경로 그대로 | `apiFetch('/groups/${groupId}/pins')` 파라미터 없이 기존 동작 유지 |
| **context/map/ 문서** | GL symbol layer 마이그레이션 사전 분석 추가 | 코드 변경 없음. 문서 작성만 |
| **types.ts** | `PinListResponse`에 `totalCount?: number`, `hasNext?: boolean` 선택적 필드 추가 | 기존 `items` 접근부 영향 없음 |
