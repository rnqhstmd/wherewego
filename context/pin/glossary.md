# pin 용어 사전

| 용어 | 설명 |
|------|------|
| Pin | 핀 엔티티. 컬럼: `id`, `group_id`, `place_name`, `address`, `latitude`, `longitude`, `instagram_url`(nullable), `memo`, `memo_source`(AUTO/MANUAL), `tag`(PLACE/MEMORY), `created_at`, `created_by` |
| group_id | 핀이 속한 그룹의 외래키. [[group]] 도메인 참조 |
| place_name | API 응답에서 가져온 상호명 (한국어) |
| address | API 응답 주소 (한국어) |
| latitude / longitude | 위경도. Mapbox 핀 표시에 필수 |
| instagram_url | 원본 릴스 URL. 챗봇 등록 시에만 채워짐. 웹 직접 등록은 nullable |
| tag | PLACE 또는 MEMORY. [[tag]] 도메인 참조. NOT NULL |
| memo_source | AUTO(챗봇 2초 룰) 또는 MANUAL(웹 직접 입력). [[memo]] 우선순위 정책 참조 |
| 중복 방지 | `UNIQUE(group_id, instagram_url) WHERE instagram_url IS NOT NULL`. 웹 직접 등록 핀은 중복 가능 |
| ~~visited~~ | **PRD에서 제거됨**. 방문 인증 기능 없음. 시각적 표현은 [[tag]]로 일원화 |
| 방문 감지 (Phase 10) | WISH/REEL 핀 100m 이내에 30초 이상 머물면 토스트 노출 → MEMORY 자동 전환 제안. `useVisitDetection` 훅. GPS 정확도 50m 초과 이벤트는 평가 스킵 |
| 30초 머무름 임계 (Phase 10) | 첫 진입 시각 기록 → 이후 콜백마다 시간 차이 계산. 30초 경과 + 100m 이내면 토스트 발동. setInterval 미사용 |
| 100m 감지 반경 (Phase 10) | Haversine 공식(프론트엔드 `_lib/roulette.ts:haversineKm` 재사용). 백엔드 거리 계산 없음. PostGIS 미도입 |
| GPS 정확도 게이트 (Phase 10) | `position.coords.accuracy ≤ 50m` 인 이벤트만 평가 사용. 50m 초과는 머무름 타이머에도 영향 없음 (보존) |
| `PinUpdateResult` (Phase 10) | `PinService.updatePin` 반환 record. `(PinSummary summary, boolean wasWishOrReelToMemory)`. 컨트롤러가 태그 전이 감지하여 VISIT_DETECTED 알림 호출 분기 |
| 세션 Set `shownPinIds` (Phase 10) | 메모리 내 `Set<number>`. "다음에 올게요"/MEMORY 전환 후 추가. MapClient unmount 시 자동 소멸 (localStorage 미사용) |
| PinListResult | Phase 2.9 — `PinService.listGroupPinsPaged`의 페이지 모드 전용 결과 record. 필드: `List<PinSummary> items`, `long totalCount`, `boolean hasNext`. 항상 유의미한 값(null 없음). 인터페이스 레이어에서 `PinListResponse.fromPaged()`로 응답 매핑 |
| legacy 모드 | Phase 2.9 — 핀 목록 API가 `page`/`size` 파라미터 미전달일 때 동작. 기존 `{items}` 단일 응답 구조 유지. 룰렛 stale 재조회 + `/pins` 초기 fetch가 사용 |
| 페이지 모드 | Phase 2.9 — 핀 목록 API가 `page`(0-based)와 `size`(최대 100)를 둘 다 전달받았을 때 동작. 응답에 `totalCount`/`hasNext` 추가. `hasNext = (long)(page + 1) * size < totalCount` (long 캐스팅으로 오버플로 방지) |
| PIN_PAGE_PARAM_INVALID | Phase 2.9 — `page`/`size` 부분 전달, 음수/0, 비숫자 입력 시 400 응답 코드 |
| PIN_PAGE_SIZE_EXCEEDED | Phase 2.9 — `size>100` 시 400 응답 코드 (MAX_PAGE_SIZE 상한) |
